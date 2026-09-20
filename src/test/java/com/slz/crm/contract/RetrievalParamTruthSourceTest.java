package com.slz.crm.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.platform.config.DynamicConfigKeyRegistry;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.server.ai.AiChatKnowledgeRetrievalService;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 检索参数单一真相源门禁（TASK-18 裁定，CI 阶段 1 常驻，纯 JVM 无外部依赖）。
 *
 * <p>真相源 = 文档 + Registry + 基准三方一致的 (topK=5, minScore=0.20)。本测试强制四方逐项一致：
 *
 * <ul>
 *   <li>{@link KnowledgeRetrievalServiceImpl} 的 {@code DEFAULT_TOP_K} / {@code DEFAULT_MIN_SCORE}
 *       常量（反射读取）；
 *   <li>{@link AiChatKnowledgeRetrievalService} 的动态配置未装配时的运行期默认 topK（mock 端口捕获实参）；
 *   <li>{@link DynamicConfigKeyRegistry} 注册的 {@code rag.retrieval.topK} / {@code
 *       rag.retrieval.minScore} 默认值（管理台展示口径）；
 *   <li>{@code docs/dynamic-config-keys.md} 表格中两键的默认值列。
 * </ul>
 *
 * <p>任一来源漂移（含基准口径的 5）本测试即红。历史教训：AiChat 编排曾孤例 topK=4（线上召回从未被测过）、 Registry 曾展示 minScore=0.0 而运行值为
 * 0.2。文档表格行被删除同样视为门禁失败（宁可报错不静默跳过）。
 */
class RetrievalParamTruthSourceTest {

  private static final String TOP_K_KEY = "rag.retrieval.topK";
  private static final String MIN_SCORE_KEY = "rag.retrieval.minScore";

  /**
   * 裁定真相源值（TASK-18 spec「裁定」节；基准侧 RagRealRetrievalBenchmarkIT / ChunkNgramRecallGateIT 均为 TOP_K=5）。
   */
  private static final int TRUTH_TOP_K = 5;

  private static final double TRUTH_MIN_SCORE = 0.20D;

  @Test
  @DisplayName("Registry 默认值必须等于真相源 (5 / 0.20)")
  void registryDefaultsMatchTruthSource() {
    assertEquals(
        TRUTH_TOP_K,
        Integer.parseInt(registryDefault(TOP_K_KEY)),
        "DynamicConfigKeyRegistry rag.retrieval.topK 默认值漂移（管理台展示口径）");
    assertEquals(
        TRUTH_MIN_SCORE,
        Double.parseDouble(registryDefault(MIN_SCORE_KEY)),
        "DynamicConfigKeyRegistry rag.retrieval.minScore 默认值漂移（管理台展示口径）");
  }

  @Test
  @DisplayName("docs/dynamic-config-keys.md 表格默认值必须等于真相源 (5 / 0.20)")
  void docTableMatchesTruthSource() {
    Map<String, String> docDefaults = docDefaults();
    assertEquals(
        TRUTH_TOP_K, Integer.parseInt(docDefaults.get(TOP_K_KEY)), "文档 rag.retrieval.topK 默认值漂移");
    assertEquals(
        TRUTH_MIN_SCORE,
        Double.parseDouble(docDefaults.get(MIN_SCORE_KEY)),
        "文档 rag.retrieval.minScore 默认值漂移");
  }

  @Test
  @DisplayName("KnowledgeRetrievalServiceImpl 常量必须等于真相源 (5 / 0.20)")
  void knowledgeServiceConstantsMatchTruthSource() {
    assertEquals(
        TRUTH_TOP_K,
        readIntConstant(KnowledgeRetrievalServiceImpl.class, "DEFAULT_TOP_K"),
        "KnowledgeRetrievalServiceImpl.DEFAULT_TOP_K 漂移");
    assertEquals(
        TRUTH_MIN_SCORE,
        readDoubleConstant(KnowledgeRetrievalServiceImpl.class, "DEFAULT_MIN_SCORE"),
        "KnowledgeRetrievalServiceImpl.DEFAULT_MIN_SCORE 漂移");
  }

  @Test
  @DisplayName("AiChatKnowledgeRetrievalService 运行期默认 topK 必须等于真相源（孤例 4 判漏配）")
  @SuppressWarnings("unchecked")
  void aiChatRuntimeDefaultMatchesTruthSource() {
    KnowledgeRetrievalPort port = mock(KnowledgeRetrievalPort.class);
    ObjectProvider<KnowledgeRetrievalPort> portProvider = mock(ObjectProvider.class);
    when(portProvider.getIfAvailable()).thenReturn(port);
    ObjectProvider<DynamicConfigService> configProvider = mock(ObjectProvider.class);
    when(configProvider.getIfAvailable()).thenReturn(null);
    when(port.retrieve(any())).thenReturn(KnowledgeRetrievalPort.RetrievalResult.empty());

    AiChatKnowledgeRetrievalService service =
        new AiChatKnowledgeRetrievalService(portProvider, configProvider);
    service.retrieve("真相源门禁问题", 42L, null, true);

    ArgumentCaptor<KnowledgeRetrievalPort.RetrievalQuery> captor =
        ArgumentCaptor.forClass(KnowledgeRetrievalPort.RetrievalQuery.class);
    verify(port).retrieve(captor.capture());
    assertEquals(
        TRUTH_TOP_K,
        captor.getValue().topK(),
        "AiChatKnowledgeRetrievalService 缺省 topK 与真相源不一致（曾孤例硬编码 4）");
  }

  @Test
  @DisplayName("两处 service 的默认 topK 必须同源（跨服务漂移即红）")
  void bothServicesShareSameTopK() {
    assertEquals(
        readIntConstant(KnowledgeRetrievalServiceImpl.class, "DEFAULT_TOP_K"),
        aiChatRuntimeTopKDefault(),
        "AiChat 编排与生产检索实现的缺省 topK 不同源");
  }

  @Test
  @DisplayName("真相源自身不得为 0/负等病值（防测试常量被误改成平凡值而空转）")
  void truthSourceIsSane() {
    assertTrue(TRUTH_TOP_K >= 1 && TRUTH_TOP_K <= 100, "TRUTH_TOP_K 必须落在 Registry 允许范围 1~100");
    assertTrue(TRUTH_MIN_SCORE >= 0.0D && TRUTH_MIN_SCORE <= 1.0D, "TRUTH_MIN_SCORE 必须落在 0.0~1.0");
    assertNotNull(docDefaults().get(TOP_K_KEY));
  }

  private static String registryDefault(String key) {
    DynamicConfigKeyRegistry registry = new DynamicConfigKeyRegistry(new ObjectMapper());
    return registry
        .definitionOf(key)
        .orElseThrow(() -> new IllegalStateException("Registry 缺失键 " + key + "，门禁拒绝静默跳过"))
        .defaultValue();
  }

  /** 解析 docs/dynamic-config-keys.md 的键表格：| `key` | 类型 | 默认值 | 语义 |。 */
  private static Map<String, String> docDefaults() {
    Path doc = ContractFiles.find("docs/dynamic-config-keys.md");
    Map<String, String> defaults = new HashMap<>();
    try {
      for (String line : Files.readAllLines(doc, StandardCharsets.UTF_8)) {
        if (!line.startsWith("|")) {
          continue;
        }
        String[] cells = line.split("\\|");
        if (cells.length < 4) {
          continue;
        }
        String key = cells[1].replace("`", "").strip();
        if (key.equals(TOP_K_KEY) || key.equals(MIN_SCORE_KEY)) {
          defaults.put(key, cells[3].strip());
        }
      }
    } catch (IOException exception) {
      throw new UncheckedIOException("无法读取文档真相源 " + doc, exception);
    }
    assertTrue(
        defaults.containsKey(TOP_K_KEY) && defaults.containsKey(MIN_SCORE_KEY),
        "文档表格中找不到 rag.retrieval.topK / minScore 默认值行——表格被改写，门禁拒绝静默跳过：" + defaults);
    return defaults;
  }

  @SuppressWarnings("unchecked")
  private static int aiChatRuntimeTopKDefault() {
    KnowledgeRetrievalPort port = mock(KnowledgeRetrievalPort.class);
    ObjectProvider<KnowledgeRetrievalPort> portProvider = mock(ObjectProvider.class);
    when(portProvider.getIfAvailable()).thenReturn(port);
    ObjectProvider<DynamicConfigService> configProvider = mock(ObjectProvider.class);
    when(configProvider.getIfAvailable()).thenReturn(null);
    when(port.retrieve(any())).thenReturn(KnowledgeRetrievalPort.RetrievalResult.empty());
    new AiChatKnowledgeRetrievalService(portProvider, configProvider)
        .retrieve("问题", 42L, null, true);
    ArgumentCaptor<KnowledgeRetrievalPort.RetrievalQuery> captor =
        ArgumentCaptor.forClass(KnowledgeRetrievalPort.RetrievalQuery.class);
    verify(port).retrieve(captor.capture());
    return captor.getValue().topK();
  }

  private static int readIntConstant(Class<?> type, String name) {
    return (int) readConstant(type, name);
  }

  private static double readDoubleConstant(Class<?> type, String name) {
    return (double) readConstant(type, name);
  }

  private static Object readConstant(Class<?> type, String name) {
    try {
      Field field = type.getDeclaredField(name);
      field.setAccessible(true);
      return field.get(null);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(
          "真相源常量 " + type.getSimpleName() + "." + name + " 不存在或不可读——若迁移了常量落点，请同步更新本门禁", exception);
    }
  }
}
