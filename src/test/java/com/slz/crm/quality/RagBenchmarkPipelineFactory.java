package com.slz.crm.quality;

import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.ContextBuilder;
import com.slz.crm.knowledge.retrieval.DefaultWeightedReranker;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.RrfFusion;
import com.slz.crm.knowledge.retrieval.RuleContextCompressor;
import com.slz.crm.knowledge.retrieval.SparseRecallService;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 真检索基准的检索管线装配工厂（run-baseline-ladder 任务组 2）。
 *
 * <p>职责：按 {@link RagBenchmarkRun} profile 用 <b>生产 {@link KnowledgeRetrievalServiceImpl}</b>
 * 装配回退态（旧构造器）/混合（稀疏+R R F）/上下文（+ContextBuilder）三档管线。稀疏路用 {@link
 * SparseBenchmarkRecallService}（测试侧子类），上下文邻居用 {@code InMemoryDocumentVectorChunkMapper} 内存 double
 * 驱动真实 {@link ContextBuilder}——两条测试侧替换均不动 {@code src/main}。
 *
 * <p>装配等价（0.1/0.3 决策）：回退态 = 6 参兼容构造（sparse/rrf/context=null），与第一跑（baseline-v1）一致； 混合/上下文档 = 10
 * 参兼容构造（提案4 完成态，查询侧关闭），sparse/rrf 常开、context 按 profile 装配。 单测通过反射断言各档稀疏路/上下文是否装配，验证回退矩阵 = 旧构造器行为等价。
 */
final class RagBenchmarkPipelineFactory {

  private RagBenchmarkPipelineFactory() {}

  static KnowledgeRetrievalPort build(
      RagBenchmarkRun run,
      KnowledgeBaseAuthorizationService authorization,
      EmbeddingService embeddingService,
      CrmVectorStore store,
      DynamicConfigService dynamicConfig,
      ModelProvider provider,
      List<RagBenchmarkDataPreparer.BenchmarkChunk> chunks) {
    ObjectProvider<DynamicConfigService> providerCfg = provider(dynamicConfig);
    RetrievalQueryRewriteService rewrite = new RetrievalQueryRewriteService(provider, providerCfg);
    // 语义切分/矩阵由 dynamicConfig 驱动；稀疏/上下文按 profile 装配
    if (!run.sparseOn) {
      // 回退态：6 参兼容构造 = 纯向量单路 + plainNumbered（第一跑口径）
      return new KnowledgeRetrievalServiceImpl(
          authorization, embeddingService, store, rewrite, new Bm25Scorer(), providerCfg);
    }
    DefaultWeightedReranker defaultReranker =
        new DefaultWeightedReranker(new Bm25Scorer(), providerCfg);
    RrfFusion rrfFusion = new RrfFusion();
    SparseRecallService sparse = new SparseBenchmarkRecallService(chunks);
    ContextBuilder contextBuilder =
        run.contextOn
            ? new ContextBuilder(
                InMemoryDocumentVectorChunkMapper.createFromChunks(chunks),
                providerCfg,
                new RuleContextCompressor(),
                null)
            : null;
    return new KnowledgeRetrievalServiceImpl(
        authorization,
        embeddingService,
        store,
        rewrite,
        providerCfg,
        sparse,
        rrfFusion,
        defaultReranker,
        null,
        contextBuilder);
  }

  /** 只读 ObjectProvider 包装单个 DynamicConfigService。 */
  static ObjectProvider<DynamicConfigService> provider(DynamicConfigService dynamicConfig) {
    return new ObjectProvider<>() {
      @Override
      public DynamicConfigService getObject() {
        if (dynamicConfig == null) {
          throw new NoSuchBeanDefinitionException(DynamicConfigService.class);
        }
        return dynamicConfig;
      }

      @Override
      public DynamicConfigService getIfAvailable() {
        return dynamicConfig;
      }

      @Override
      public DynamicConfigService getIfUnique() {
        return dynamicConfig;
      }
    };
  }

  /** Map 背书的 DynamicConfigService 桩：缺失键返回默认值，命中键按原值类型转换。 */
  static DynamicConfigService dynamicConfig(Map<String, Object> matrix) {
    return new DynamicConfigService() {
      @Override
      public <T> T get(String key, Class<T> type, T defaultValue) {
        if (matrix == null || !matrix.containsKey(key)) {
          return defaultValue;
        }
        return convert(matrix.get(key), type, defaultValue);
      }
    };
  }

  @SuppressWarnings("unchecked")
  private static <T> T convert(Object value, Class<T> type, T defaultValue) {
    if (value == null) {
      return defaultValue;
    }
    if (type == String.class) {
      return (T) String.valueOf(value);
    }
    if (type == Integer.class) {
      if (value instanceof Number number) {
        return (T) Integer.valueOf(number.intValue());
      }
      if (value instanceof String text) {
        try {
          return (T) Integer.valueOf(text.strip());
        } catch (NumberFormatException ignored) {
          return defaultValue;
        }
      }
      return defaultValue;
    }
    if (type == Boolean.class) {
      if (value instanceof Boolean b) {
        return (T) b;
      }
      Object text = value;
      if (text instanceof String s) {
        return (T) Boolean.valueOf(s.strip());
      }
      return defaultValue;
    }
    if (type == Double.class) {
      if (value instanceof Number number) {
        return (T) Double.valueOf(number.doubleValue());
      }
      if (value instanceof String text) {
        try {
          return (T) Double.valueOf(text.strip());
        } catch (NumberFormatException ignored) {
          return defaultValue;
        }
      }
      return defaultValue;
    }
    return defaultValue;
  }
}
