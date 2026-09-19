package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.knowledge.retrieval.ContextBuilder;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 检索管线装配与开关矩阵单元测试（run-baseline-ladder 任务组 2.4）。
 *
 * <p>验证三档 profile（v1/hybrid/context）经 {@link RagBenchmarkPipelineFactory} 装配后的对象图等价：
 * 回退态（v1）sparse/context 均为 null（= 旧构造器升级前行为），混合后 sparse 装上、上下文跑装上 contextBuilder；同时验证 Map 背书
 * DynamicConfig 桩的键位转换。
 */
class RagBenchmarkAssemblyTest {

  @Test
  void fallbackMatrixAssemblesLikeLegacyConstructor() throws Exception {
    KnowledgeRetrievalPort port = build(RagBenchmarkRun.V1, Map.of());
    assertNull(sparse(port), "回退态（v1）不得装配稀疏路");
    assertNull(context(port), "回退态（v1）不得装配上下文组装器");
  }

  @Test
  void hybridMatrixEnablesSparseButNotContext() throws Exception {
    KnowledgeRetrievalPort port = build(RagBenchmarkRun.HYBRID, RagBenchmarkRun.HYBRID.matrix);
    assertTrue(sparse(port) != null, "混合跑（hybrid）必须装配稀疏路");
    assertNull(context(port), "混合跑（hybrid）其余回退：不得装配上下文组装器");
  }

  @Test
  void contextMatrixEnablesSparseAndContext() throws Exception {
    KnowledgeRetrievalPort port = build(RagBenchmarkRun.CONTEXT, RagBenchmarkRun.CONTEXT.matrix);
    assertNotNull(sparse(port), "上下文跑（context）承混稀，必须装配稀疏路");
    assertNotNull(context(port), "上下文跑（context）必须装配 ContextBuilder");
  }

  @Test
  void mapDynamicConfigConvertsAndFallsBack() {
    DynamicConfigService config =
        RagBenchmarkPipelineFactory.dynamicConfig(
            Map.of(
                "rag.context.neighbors",
                1,
                "rag.context.compressor.mode",
                "rule",
                "rag.retrieval.multi-query.enabled",
                true));
    assertEquals(1, config.get("rag.context.neighbors", Integer.class, 0));
    assertEquals("rule", config.get("rag.context.compressor.mode", String.class, "llm"));
    assertEquals(
        Boolean.TRUE, config.get("rag.retrieval.multi-query.enabled", Boolean.class, false));
    assertEquals(4096, config.get("rag.context.token-budget", Integer.class, 4096), "缺失键回默认值");
    assertEquals("fixed", config.get("rag.chunking.strategy", String.class, "fixed"));
  }

  @Test
  void runProfileDefinitionsCarryExpectedSemantics() {
    assertFalse(RagBenchmarkRun.V1.sparseOn);
    assertFalse(RagBenchmarkRun.V1.contextOn);
    assertTrue(RagBenchmarkRun.HYBRID.sparseOn);
    assertFalse(RagBenchmarkRun.HYBRID.contextOn);
    assertTrue(RagBenchmarkRun.CONTEXT.sparseOn);
    assertTrue(RagBenchmarkRun.CONTEXT.contextOn);
    assertEquals(
        "baseline-after-hybrid.json",
        RagBenchmarkRun.HYBRID.outFile.substring(
            RagBenchmarkRun.HYBRID.outFile.lastIndexOf('/') + 1));
  }

  private KnowledgeRetrievalPort build(RagBenchmarkRun run, Map<String, Object> matrix) {
    return RagBenchmarkPipelineFactory.build(
        run, null, null, null, RagBenchmarkPipelineFactory.dynamicConfig(matrix), null, List.of());
  }

  private static SparseRecallService sparse(KnowledgeRetrievalPort port) throws Exception {
    Field field = KnowledgeRetrievalServiceImpl.class.getDeclaredField("sparseRecallService");
    field.setAccessible(true);
    return (SparseRecallService) field.get(port);
  }

  private static ContextBuilder context(KnowledgeRetrievalPort port) throws Exception {
    Field field = KnowledgeRetrievalServiceImpl.class.getDeclaredField("contextBuilder");
    field.setAccessible(true);
    return (ContextBuilder) field.get(port);
  }
}
