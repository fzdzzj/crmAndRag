package com.slz.crm.knowledge.retrieval;

import com.slz.crm.platform.config.ConfigKeyDefinition;
import com.slz.crm.platform.config.ConfigValueType;
import com.slz.crm.platform.config.DynamicConfigKeyRegistry;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * per-KB 检索策略覆盖键白名单（add-per-kb-retrieval-strategy-override 任务 2.1）。
 *
 * <p><b>恰 12 键封闭集</b>（proposal §1，v1 第一档运营调参键）：白名单是封闭集，扩充需 owner 拍板并同步 {@code
 * docs/dynamic-config-keys.md}。成本类（rerank.mode=llm、compressor.mode=llm、multi-query.*、hyde.*、
 * derived-questions.*、vision-pdf.*、replay-*）与结构类（chunking.*、chunkSize/chunkOverlap）永远不得进入 per-KB
 * 覆盖——它们按分层定夺仍归超管全局或变更管理。
 *
 * <p>校验语义复用 {@link DynamicConfigKeyRegistry}（动态配置键注册表）的类型/范围/枚举护栏： 每个白名单键都已在注册表登记（12 键逐一核验），写入直接委托
 * {@link #validate(String, String)}， 与超管全局配置中心的合法口径一致，杜绝 per-KB 与全局口径分叉。
 *
 * <p>线程安全：白名单启动期一次性构建、之后只读，天然线程安全。
 */
@Component
public class KbRetrievalStrategyWhitelist {

  /** 白名单键全集（恰好 12 键，封闭集；顺序即管理端展示顺序）。 */
  public static final List<String> KEYS =
      List.of(
          "rag.retrieval.topK",
          "rag.retrieval.minScore",
          "rag.retrieval.fusion.mode",
          "rag.retrieval.fusion.rrf-k",
          "rag.retrieval.rerank.vector-weight",
          "rag.retrieval.rerank.bm25-weight",
          "rag.retrieval.rerank.candidate-multiplier",
          "rag.retrieval.image-text-route-weight",
          "rag.retrieval.image-vector-route-weight",
          "rag.context.neighbors",
          "rag.context.parent-expand",
          "rag.retrieval.query-rewrite.enabled");

  private static final Set<String> KEYS_SET =
      java.util.Collections.unmodifiableSet(new java.util.HashSet<>(KEYS));

  private final DynamicConfigKeyRegistry registry;

  public KbRetrievalStrategyWhitelist(DynamicConfigKeyRegistry registry) {
    this.registry = registry;
    // 封闭集自检：12 白名单键必须全在注册表登记过（否则校验口径会失败开）。启动时一次性把关，防 whitelist 与注册表漂移。
    for (String key : KEYS) {
      if (registry.definitionOf(key).isEmpty()) {
        throw new IllegalStateException(
            "per-KB 白名单键未在 DynamicConfigKeyRegistry 登记，无法复用类型/范围校验：" + key);
      }
    }
  }

  /**
   * @param key 覆盖键
   * @return 是否在 12 键白名单封闭集内
   */
  public static boolean isWhitelisted(String key) {
    return KEYS_SET.contains(key);
  }

  /**
   * @return 白名单全键（保序，封闭集）
   */
  public static List<String> keys() {
    return KEYS;
  }

  /**
   * 复用注册表的类型/范围/枚举校验；白名单外键直接失败。
   *
   * @param key 覆盖键
   * @param raw 原始输入
   * @return 校验结果（{@link ConfigValueType.Parsed}）
   */
  public ConfigValueType.Parsed validate(String key, String raw) {
    ConfigValueType.Parsed result;
    if (!isWhitelisted(key)) {
      result = ConfigValueType.Parsed.fail("键不在 per-KB 覆盖白名单（12 键封闭集）：" + key);
    } else {
      result = registry.validate(key, raw);
    }
    return result;
  }

  /** 读取某键的注册表定义（类型/范围/默认展示）；白名单外返回空。 */
  public Optional<ConfigKeyDefinition> definitionOf(String key) {
    return isWhitelisted(key) ? registry.definitionOf(key) : Optional.empty();
  }

  /**
   * 存储值还原（缓存加载用）：按注册表类型把规范化文本还原为类型化对象；失败返回 {@code null}（读取方回落全局）。
   *
   * @param key 覆盖键（须在 12 键白名单内）
   * @param canonical DB 中的规范化文本
   */
  public Object parseStored(String key, String canonical) {
    ConfigKeyDefinition def = definitionOf(key).orElse(null);
    Object result = null;
    if (def != null) {
      result = def.type().parseStored(canonical, def);
    }
    return result;
  }

  /** 兼容构造（单测注入 mock 注册表时用）。 */
  KbRetrievalStrategyWhitelist() {
    this.registry = null;
  }

  /** 测试专用：暴露注册表引用（仅本包测试可调用）。 */
  DynamicConfigKeyRegistry registry() {
    return registry;
  }
}
