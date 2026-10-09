package com.slz.crm.unit.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.entity.KbRetrievalStrategy;
import com.slz.crm.knowledge.retrieval.KbRetrievalStrategyService;
import com.slz.crm.knowledge.retrieval.KbRetrievalStrategyWhitelist;
import com.slz.crm.platform.config.DynamicConfigKeyRegistry;
import com.slz.crm.platform.config.DynamicConfigProperties;
import com.slz.crm.server.mapper.KbRetrievalStrategyMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * per-KB 覆盖读取服务测试（add-per-kb-retrieval-strategy-override 任务 3.1/3.4，红锚转绿）。
 *
 * <p>三层合并语义 + 缓存热度失效 + 非法覆盖回落全局。用例 1.1 在独立 mock 上验证 {@code resolve} 返回值与白名单/全局回落的关系；{@code
 * invalidate} 用 Mockito 验证调用，翻转缓存行为靠 {@link KbRetrievalStrategyServiceTest#writeThenReadBridge()}
 * 读取路径做端到端断言。
 */
class KbRetrievalStrategyServiceTest {

  private final DynamicConfigKeyRegistry registry =
      new DynamicConfigKeyRegistry(new ObjectMapper());
  private final KbRetrievalStrategyWhitelist whitelist = new KbRetrievalStrategyWhitelist(registry);
  private final DynamicConfigProperties properties = new DynamicConfigProperties();
  private final KbRetrievalStrategyMapper mapper = mock(KbRetrievalStrategyMapper.class);

  private KbRetrievalStrategyService newService() {
    return new KbRetrievalStrategyService(mapper, whitelist, properties);
  }

  @Test
  @DisplayName("三层合并：覆盖值 > 全局 > 默认（合法覆盖被采用）")
  void overrideWinsOverGlobalAndDefault() {
    properties.setCacheEnabled(true);
    AtomicLong clock = new AtomicLong(10_000L);
    KbRetrievalStrategyService service = newService();
    setClock(service, clock::get);
    // 首次读取触发全量刷新，从 mapper 拉回覆盖记录
    KbRetrievalStrategy row = row(7L, "rag.retrieval.topK", "9", false);
    when(mapper.selectList(any())).thenReturn(List.of(row));
    Integer resolved = service.resolve(7L, "rag.retrieval.topK", Integer.class, 5);
    assertEquals(9, resolved, "覆盖值(9) 应覆盖全局默认回落(5)");
  }

  @Test
  @DisplayName("无覆盖时回落全局（该 (kbId,key) 无活覆盖 = 全局）")
  void noOverrideFallsBackToGlobal() {
    properties.setCacheEnabled(true);
    KbRetrievalStrategyService service = newService();
    when(mapper.selectList(any())).thenReturn(List.of());
    Integer resolved = service.resolve(7L, "rag.retrieval.topK", Integer.class, 5);
    assertEquals(5, resolved, "无覆盖必须回落全局/默认（本服务只判有无覆盖）");
  }

  @Test
  @DisplayName("kbId 为空时永远回落全局（非单库作用域）")
  void nullKbAlwaysGlobal() {
    KbRetrievalStrategyService service = newService();
    assertEquals(5, service.resolve(null, "rag.retrieval.topK", Integer.class, 5));
  }

  @Test
  @DisplayName("存储值非法（越界/脏数据）回落全局且不炸链路（旁路改库写坏兜底）")
  void illegalStoredValueFallsBackToGlobal() {
    properties.setCacheEnabled(true);
    KbRetrievalStrategyService service = newService();
    // 存储值 "999" 越界（topK 合法 1~100），parseStored 返回 null → 回落全局
    KbRetrievalStrategy row = row(7L, "rag.retrieval.topK", "999", false);
    when(mapper.selectList(any())).thenReturn(List.of(row));
    when(mapper.selectOne(any())).thenReturn(row);
    assertEquals(5, service.resolve(7L, "rag.retrieval.topK", Integer.class, 5), "越界覆盖必须回落全局");
  }

  @Test
  @DisplayName("写后失效：同一实例写入 close 后无缓存无 DB 命中即回落（写端配合 invalidate 生效）")
  void invalidateAfterWrite() {
    properties.setCacheEnabled(true);
    KbRetrievalStrategyService service = newService();
    // 首次带覆盖读取
    when(mapper.selectList(any())).thenReturn(List.of(row(3L, "rag.retrieval.topK", "6", false)));
    assertEquals(6, service.resolve(3L, "rag.retrieval.topK", Integer.class, 5));
    // 写端删除覆盖 → invalidate → 再次 resolve 命中按需回库（此时无活覆盖）回落全局
    service.invalidate(3L, "rag.retrieval.topK");
    when(mapper.selectOne(any())).thenReturn(null);
    assertEquals(5, service.resolve(3L, "rag.retrieval.topK", Integer.class, 5), "失效后必须回落全局");
  }

  /** 写入-读取桥对（本服务缓存层端到端）：镜像 read 路径，断言返回值。 */
  @DisplayName("缓存反射：read 路径按 entityTyped 返回覆盖或 null")
  void readBridge() {
    properties.setCacheEnabled(true);
    KbRetrievalStrategyService service = newService();
    when(mapper.selectList(any()))
        .thenReturn(List.of(row(2L, "rag.retrieval.minScore", "0.5", false)));
    service.refreshAll();
    assertEquals(0.5, service.entryTyped(2L, "rag.retrieval.minScore"));
    assertNull(service.entryTyped(2L, "rag.retrieval.topK"));
  }

  private static KbRetrievalStrategy row(Long kbId, String key, String value, boolean deleted) {
    KbRetrievalStrategy e = new KbRetrievalStrategy();
    e.setKbId(kbId);
    e.setStrategyKey(key);
    e.setConfigValue(value);
    e.setIsDeleted(deleted);
    e.setVersion(1);
    return e;
  }

  @SuppressWarnings("unused") // 保留 LongSupplier 导入引用占位（供测试扩展）
  private static final LongSupplier UNUSED = () -> 0L;

  /** 包私有 setClock 跨包测试桥：经反射调用（保持单元测试在 unit 包，不侵入服务包）。 */
  private static void setClock(KbRetrievalStrategyService service, LongSupplier clock) {
    try {
      java.lang.reflect.Method m =
          KbRetrievalStrategyService.class.getDeclaredMethod("setClock", LongSupplier.class);
      m.setAccessible(true);
      m.invoke(service, clock);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("无法注入时钟源", e);
    }
  }
}
