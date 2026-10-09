package com.slz.crm.platform.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 动态配置键三档定级封闭表（add-dynamic-config-key-tier-acl 任务 1.3）。
 *
 * <p>与 {@link DynamicConfigKeyRegistry} 的 63 键全集一一对应（2026-10-09 实测普查）：OPERATIONAL 34 （608 持有者可写，含
 * P-ac per-KB 12 键白名单全集）/ COST 22（超管专写——LLM 重排/压缩、查询增强、VLM 转写、重放、限流配额、模型选择等费用红线族）/ STRUCTURAL
 * 7（超管专写——切分策略/分块尺寸、嵌入模型、 Provider、数据范围安全语义等变更管理键）。
 *
 * <p>解析顺序（tierOf）：sensitive=true 防御性映射 COST → 显式登记表 → 默认 OPERATIONAL。封闭性由 census 防呆测试 {@code
 * ConfigKeyTierPolicyTest} 锁死：注册表新增键未在此登记即红。改档需 owner 拍板并同步 docs/dynamic-config-keys.md（「权限档位」列与
 * tier ACL 专节）。
 *
 * <p>线程安全：启动期一次性构建、之后只读，天然线程安全。
 */
public final class ConfigKeyTierPolicy {

  private ConfigKeyTierPolicy() {}

  /** OPERATIONAL 档封闭集（34 键：运营调参，608 可写） */
  private static final Set<String> OPERATIONAL_KEYS =
      Set.of(
          "ai.prompt.system",
          "ai.model.temperature",
          "ai.model.maxTokens",
          "rag.retrieval.topK",
          "rag.retrieval.minScore",
          "rag.retrieval.strictKb",
          "rag.retrieval.query-rewrite.enabled",
          "rag.retrieval.fusion.mode",
          "rag.retrieval.fusion.rrf-k",
          "rag.retrieval.rerank.vector-weight",
          "rag.retrieval.rerank.bm25-weight",
          "rag.retrieval.rerank.candidate-multiplier",
          "rag.retrieval.image-text-route-weight",
          "rag.retrieval.image-vector-route-weight",
          "rag.context.neighbors",
          "rag.context.parent-expand",
          "rag.intent.filterEnabled",
          "rag.intent.categories",
          "rag.intent.keywords",
          "business.assist.reminderEnabled",
          "business.feature.aiAssistantEnabled",
          "business.assistant.imageCacheMaxEntries",
          "platform.resilience.failure-threshold",
          "platform.resilience.open-duration-ms",
          "platform.resilience.failure-threshold.model-chat",
          "platform.resilience.open-duration-ms.model-chat",
          "platform.resilience.failure-threshold.model-embed",
          "platform.resilience.open-duration-ms.model-embed",
          "platform.resilience.failure-threshold.model-vision",
          "platform.resilience.open-duration-ms.model-vision",
          "platform.resilience.failure-threshold.vector-qdrant",
          "platform.resilience.open-duration-ms.vector-qdrant",
          "platform.resilience.failure-threshold.storage-minio",
          "platform.resilience.open-duration-ms.storage-minio");

  /** COST 档封闭集（22 键：成本开关，超管专写） */
  private static final Set<String> COST_KEYS =
      Set.of(
          "ai.model.chatModel",
          "ai.model.visionModel",
          "business.rateLimit.perMinute",
          "business.quota.maxTokensPerSession",
          "rag.retrieval.admin-vector.enabled",
          "rag.retrieval.vision-pdf.enabled",
          "rag.retrieval.vision-pdf.min-text-chars",
          "rag.retrieval.vision-pdf.max-pages",
          "rag.retrieval.rerank.mode",
          "rag.retrieval.rerank.llm.timeout-ms",
          "rag.retrieval.rerank.llm.max-candidates",
          "rag.context.token-budget",
          "rag.context.compressor.mode",
          "rag.context.compressor.llm.timeout-ms",
          "rag.query.multi-query.enabled",
          "rag.query.multi-query.variants",
          "rag.query.hyde.enabled",
          "rag.query.hyde.timeout-ms",
          "rag.query.derived-questions.enabled",
          "rag.query.derived-questions.max-per-chunk",
          "rag.ingest.replay-enabled",
          "rag.ingest.replay-batch-size");

  /** STRUCTURAL 档封闭集（7 键：变更管理，超管专写） */
  private static final Set<String> STRUCTURAL_KEYS =
      Set.of(
          "ai.model.provider",
          "ai.model.embeddingModel",
          "rag.retrieval.chunkSize",
          "rag.retrieval.chunkOverlap",
          "rag.chunking.strategy",
          "rag.chunking.max-chunk-size",
          "business.dataScope.enabled");

  /** 键 → 档位（三封闭集拼装，启动期一次性构建） */
  private static final Map<String, ConfigKeyTier> TIERS = buildTiers();

  private static Map<String, ConfigKeyTier> buildTiers() {
    Map<String, ConfigKeyTier> tiers = new LinkedHashMap<>();
    for (String key : OPERATIONAL_KEYS) {
      tiers.put(key, ConfigKeyTier.OPERATIONAL);
    }
    for (String key : COST_KEYS) {
      if (tiers.put(key, ConfigKeyTier.COST) != null) {
        throw new IllegalStateException("定级表键跨档重复登记：" + key);
      }
    }
    for (String key : STRUCTURAL_KEYS) {
      if (tiers.put(key, ConfigKeyTier.STRUCTURAL) != null) {
        throw new IllegalStateException("定级表键跨档重复登记：" + key);
      }
    }
    return Collections.unmodifiableMap(tiers);
  }

  /**
   * 解析键的权限档位：sensitive=true 防御性映射 COST → 显式登记表 → 默认 OPERATIONAL。
   *
   * @param def 配置键定义（须来自注册表或测试自建）
   */
  public static ConfigKeyTier tierOf(ConfigKeyDefinition def) {
    ConfigKeyTier result;
    if (def.sensitive()) {
      result = ConfigKeyTier.COST;
    } else {
      result = TIERS.getOrDefault(def.key(), ConfigKeyTier.OPERATIONAL);
    }
    return result;
  }

  /**
   * @return OPERATIONAL 档封闭集（只读）
   */
  public static Set<String> operationalKeys() {
    return Collections.unmodifiableSet(OPERATIONAL_KEYS);
  }

  /**
   * @return COST 档封闭集（只读）
   */
  public static Set<String> costKeys() {
    return Collections.unmodifiableSet(COST_KEYS);
  }

  /**
   * @return STRUCTURAL 档封闭集（只读）
   */
  public static Set<String> structuralKeys() {
    return Collections.unmodifiableSet(STRUCTURAL_KEYS);
  }
}
