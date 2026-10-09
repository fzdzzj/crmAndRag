package com.slz.crm.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.entity.KbRetrievalStrategy;
import com.slz.crm.platform.config.DynamicConfigKeyRegistry;
import com.slz.crm.platform.config.DynamicConfigProperties;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.server.mapper.KbRetrievalStrategyMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * per-KB 三层合并矩阵 + 零行为回归锚（add-per-kb-retrieval-strategy-override 任务 3.4，红锚转绿）。
 *
 * <p>断言：覆盖 &gt; 全局 &gt; 默认；非法覆盖回落全局；多库/全库/空（singleKbId=null）一律走全局； kbId 不可解析（null）走全局；
 * 零覆盖时新旧解析输出逐字节一致（单库/多库均与升级前一致，topK=5 / minScore=0.20 全局锚）。
 */
class RetrievalMergeResolverTest {

  private final DynamicConfigKeyRegistry registry =
      new DynamicConfigKeyRegistry(new ObjectMapper());
  private final DynamicConfigProperties properties = new DynamicConfigProperties();
  private final KbRetrievalStrategyMapper mapper = mock(KbRetrievalStrategyMapper.class);

  private KbRetrievalStrategyService newCache(List<KbRetrievalStrategy> rows) {
    properties.setCacheEnabled(true);
    AtomicLong clock = new AtomicLong(10_000L);
    when(mapper.selectList(any())).thenReturn(rows);
    KbRetrievalStrategyService cache =
        new KbRetrievalStrategyService(
            mapper, new KbRetrievalStrategyWhitelist(registry), properties);
    cache.setClock(clock::get);
    cache.refreshAll();
    return cache;
  }

  @SuppressWarnings("unchecked")
  private RetrievalConfigResolver newResolver(
      DynamicConfigService config, KbRetrievalStrategyService cache) {
    ObjectProvider<DynamicConfigService> configProvider = mock(ObjectProvider.class);
    when(configProvider.getIfAvailable()).thenReturn(config);
    return new RetrievalConfigResolver(configProvider, () -> cache);
  }

  private DynamicConfigService noGlobal() {
    // 未配置全局（get 返回 null 运行时回落默认），模拟「无全局覆盖」
    DynamicConfigService config = mock(DynamicConfigService.class);
    when(config.get(any(), any(), any())).thenAnswer(inv -> inv.getArgument(2));
    return config;
  }

  private DynamicConfigService globalTopK(int topK) {
    DynamicConfigService config = mock(DynamicConfigService.class);
    when(config.get("rag.retrieval.topK", Integer.class, 5)).thenReturn(topK);
    return config;
  }

  private static KbRetrievalStrategy row(Long kbId, String key, String value) {
    KbRetrievalStrategy e = new KbRetrievalStrategy();
    e.setKbId(kbId);
    e.setStrategyKey(key);
    e.setConfigValue(value);
    e.setIsDeleted(false);
    e.setVersion(1);
    return e;
  }

  @Test
  @DisplayName("覆盖 > 全局 > 默认：单库覆盖（topK=9）压过全局（topK=7）与默认（5）")
  void overrideBeatsGlobalAndDefault() {
    KbRetrievalStrategyService cache = newCache(List.of(row(7L, "rag.retrieval.topK", "9")));
    RetrievalConfigResolver resolver = newResolver(globalTopK(7), cache);
    assertEquals(9, resolver.resolveTopK(null, 7L));
  }

  @Test
  @DisplayName("无覆盖但全局到位：全局（topK=7）生效，默认 5 被覆盖")
  void globalUsedWhenNoOverride() {
    KbRetrievalStrategyService cache = newCache(List.of());
    RetrievalConfigResolver resolver = newResolver(globalTopK(7), cache);
    assertEquals(7, resolver.resolveTopK(null, 7L));
  }

  @Test
  @DisplayName("无覆盖且无全局：回落注册表默认（topK=5 / minScore=0.20）")
  void defaultUsedWhenNothingPresent() {
    KbRetrievalStrategyService cache = newCache(List.of());
    RetrievalConfigResolver resolver = newResolver(noGlobal(), cache);
    assertEquals(5, resolver.resolveTopK(null, 7L));
    assertEquals(0.20, resolver.resolveMinScore(7L), 1e-9);
  }

  @Test
  @DisplayName("非法覆盖（越界/脏数据）回落全局与默认（契约 2 非法覆盖回落）")
  void illegalOverrideFallsBack() {
    // topK=999 越界（合法 1~100）→ 覆盖解析失败 → 回落全局 7
    KbRetrievalStrategyService cache = newCache(List.of(row(7L, "rag.retrieval.topK", "999")));
    RetrievalConfigResolver resolver = newResolver(globalTopK(7), cache);
    assertEquals(7, resolver.resolveTopK(null, 7L));
  }

  @Test
  @DisplayName("多库/全库/空/kbId 不可解析（singleKbId=null）一律走全局（契约 3 单库作用域）")
  void nonSingleKbGoesGlobal() {
    KbRetrievalStrategyService cache = newCache(List.of(row(7L, "rag.retrieval.topK", "9")));
    RetrievalConfigResolver resolver = newResolver(noGlobal(), cache);
    // 即便库里存了 topK=9 覆盖，单库作用域外（null）也必须走全局/默认
    assertEquals(5, resolver.resolveTopK(null, null));
  }

  @Test
  @DisplayName("零行为回归锚：无任何覆盖配置时，单库/多库解析与升级前逐字节一致（topK=5 / minScore=0.20）")
  void zeroOverrideByteIdenticalToLegacy() {
    DynamicConfigService config = noGlobal();
    KbRetrievalStrategyService cache = newCache(List.of());
    RetrievalConfigResolver resolver = newResolver(config, cache);
    // 单库（singleKbId 非空但无覆盖）与多库（null）都必须与旧方法（resolverLegacy）逐字节一致
    // 以 topK=5、minScore=0.20、fusion 默认 rrf、candidate-multiplier=4 为锚。
    assertEquals(resolver.resolveTopK(null, null), resolver.resolveTopK(null, 7L));
    assertEquals(5, resolver.resolveTopK(null, 7L));
    assertEquals(resolver.resolveMinScore(), resolver.resolveMinScore(7L));
    assertEquals(0.20, resolver.resolveMinScore(7L), 1e-9);
    assertEquals(resolver.useRrfFusion(), resolver.useRrfFusion(7L));
    assertEquals(resolver.resolveRrfK(), resolver.resolveRrfK(7L));
    assertEquals(60, resolver.resolveRrfK(7L));
    assertEquals(resolver.resolveCandidateMultiplier(), resolver.resolveCandidateMultiplier(7L));
    assertEquals(4, resolver.resolveCandidateMultiplier(7L));
    assertEquals(resolver.resolveTextRouteWeight(), resolver.resolveTextRouteWeight(7L), 1e-9);
    assertEquals(resolver.resolveImageRouteWeight(), resolver.resolveImageRouteWeight(7L), 1e-9);
  }

  @Test
  @DisplayName("显式传参最优先：topK 显式 3 压过覆盖 9（契约 3 维持既有显式传参语义）")
  void explicitParamBeatsOverride() {
    KbRetrievalStrategyService cache = newCache(List.of(row(7L, "rag.retrieval.topK", "9")));
    RetrievalConfigResolver resolver = newResolver(noGlobal(), cache);
    assertEquals(3, resolver.resolveTopK(3, 7L));
  }
}
