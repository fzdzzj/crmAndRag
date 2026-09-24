package com.slz.crm.platform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 动态配置键注册表：平台已知配置键的“schema 唯一来源”（校验护栏）。
 *
 * <p>覆盖 spec-delta（D10）优先纳入的动态配置范围：提示词、模型 Provider/名称/温度/最大 token、 限流与配额阈值、检索
 * topK/阈值/分块参数、strict-KB 空匹配兜底开关（D16）、 意图类目与关键词 / intent-filter-enabled（D17）、图片缓存上限（D13）、业务功能开关。
 *
 * <p>命名空间约定（任务 16）：{@code ai.prompt}/{@code ai.model}/{@code rag.retrieval}/ {@code
 * rag.intent}/{@code business}；每个键 = 命名空间 + '.' + 键名。
 *
 * <p>敏感值说明：密钥/凭据按 D10 边界仍走环境变量（静态），本域原则上不注册敏感键； {@code sensitive} 标记与掩码逻辑完整保留，供将来确需运行期调整的半敏感参数使用。
 *
 * <p>线程安全：注册表启动期一次性构建、之后只读，天然线程安全。
 */
@Component
public class DynamicConfigKeyRegistry {

  /** 键 → 定义，保序（管理端列表按注册顺序展示） */
  private final Map<String, ConfigKeyDefinition> definitions = new LinkedHashMap<>();

  /** 五个官方命名空间（任务 16 强制） */
  public static final Set<String> NAMESPACES =
      Set.of("ai.prompt", "ai.model", "rag.retrieval", "rag.intent", "business");

  public DynamicConfigKeyRegistry(ObjectMapper objectMapper) {
    register(catalog(objectMapper));
  }

  /**
   * 批量注册（启动期由构造器调用；测试可在构造后追加自定义键验证掩码等护栏）。
   *
   * @param defs 待注册定义；重复键直接抛错，避免静默覆盖造成歧义
   */
  public synchronized void register(Collection<ConfigKeyDefinition> defs) {
    for (ConfigKeyDefinition def : defs) {
      def.selfCheck();
      if (!NAMESPACES.contains(def.namespace())) {
        throw new IllegalArgumentException("非法命名空间：" + def.namespace() + "（允许：" + NAMESPACES + "）");
      }
      if (definitions.put(def.key(), def) != null) {
        throw new IllegalArgumentException("配置键重复注册：" + def.key());
      }
    }
  }

  /**
   * @return 指定键的定义；未知键返回 {@link Optional#empty()}（写入将被拒绝、读取回退默认）
   */
  public Optional<ConfigKeyDefinition> definitionOf(String key) {
    return Optional.ofNullable(definitions.get(key));
  }

  /**
   * @return 全部定义（保序）
   */
  public Collection<ConfigKeyDefinition> definitions() {
    return definitions.values();
  }

  /**
   * @param namespace 命名空间；null 表示全部
   * @return 该命名空间下的定义（保序）
   */
  public List<ConfigKeyDefinition> byNamespace(String namespace) {
    List<ConfigKeyDefinition> result;
    if (namespace == null || namespace.isBlank()) {
      result = List.copyOf(definitions.values());
    } else {
      result =
          definitions.values().stream().filter(def -> def.namespace().equals(namespace)).toList();
    }
    return result;
  }

  /**
   * 写入期校验：类型/范围/枚举/长度全护栏一次通过则返回规范化值， 否则返回可读错误（调用方拒绝保存并保持原值）。
   *
   * @param key 配置键（必须已注册）
   * @param raw 原始输入
   * @return 校验结果（{@link ConfigValueType.Parsed}）
   */
  public ConfigValueType.Parsed validate(String key, String raw) {
    ConfigKeyDefinition def = definitions.get(key);
    ConfigValueType.Parsed result;
    if (def == null) {
      result = ConfigValueType.Parsed.fail("未知配置键：" + key + "（键必须预先在注册表中声明）");
    } else {
      result = def.type().parse(raw, def);
    }
    return result;
  }

  /**
   * 存储值还原（缓存加载用）；失败返回 {@code null}，由读取方回退默认。
   *
   * @param key 配置键
   * @param canonical DB 中的规范化文本
   */
  public Object parseStored(String key, String canonical) {
    ConfigKeyDefinition def = definitions.get(key);
    Object result = null;
    if (def != null) {
      result = def.type().parseStored(canonical, def);
    }
    return result;
  }

  /** 平台内置配置键目录（注册顺序即管理端展示顺序）。 */
  private static List<ConfigKeyDefinition> catalog(ObjectMapper objectMapper) {
    return List.of(
        // ---------------- ai.prompt.*：提示词 ----------------
        def(
            objectMapper,
            "ai.prompt.system",
            "ai.prompt",
            ConfigValueType.STRING,
            "",
            "AI 助手系统提示词。为空表示不覆盖（消费方使用 classpath prompts/system-prompt.txt 静态默认）；"
                + "写入后热生效；删除该项 = 恢复静态默认。影响面：所有助手会话的上下文组装。",
            null,
            null,
            Set.of(),
            false,
            20000),

        // ---------------- ai.model.*：模型 Provider / 名称 / 参数 ----------------
        def(
            objectMapper,
            "ai.model.provider",
            "ai.model",
            ConfigValueType.STRING,
            "dashscope",
            "模型 Provider 类型，取值：dashscope | openai-compatible | vllm（对齐静态配置 platform.ai.model.provider）。"
                + "影响面：ModelProvider 抽象的路由选择（Lane B/C 消费）。",
            null,
            null,
            Set.of("dashscope", "openai-compatible", "vllm"),
            false,
            100),
        def(
            objectMapper,
            "ai.model.chatModel",
            "ai.model",
            ConfigValueType.STRING,
            "qwen-plus",
            "对话模型名（如 qwen-plus/qwen-turbo）。影响面：助手对话与工具调用（对齐 platform.ai.model.chat-model）。",
            null,
            null,
            Set.of(),
            false,
            200),
        def(
            objectMapper,
            "ai.model.visionModel",
            "ai.model",
            ConfigValueType.STRING,
            "qwen-vl-plus",
            "视觉理解模型名（如 qwen-vl-plus）。影响面：图片理解/OCR（对齐 platform.ai.model.vision-model）。",
            null,
            null,
            Set.of(),
            false,
            200),
        def(
            objectMapper,
            "ai.model.embeddingModel",
            "ai.model",
            ConfigValueType.STRING,
            "text-embedding-v3",
            "向量化模型名（须与 Qdrant 集合维度一致，改动需重建集合——破坏性，谨慎）。影响面：知识库嵌入（对齐 platform.ai.model.embedding-model）。",
            null,
            null,
            Set.of(),
            false,
            200),
        def(
            objectMapper,
            "ai.model.temperature",
            "ai.model",
            ConfigValueType.DOUBLE,
            "0.3",
            "对话采样温度，范围 0.0~1.0（业务场景偏低保证稳定）。影响面：所有助手生成。",
            "0.0",
            "1.0",
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "ai.model.maxTokens",
            "ai.model",
            ConfigValueType.INTEGER,
            "2048",
            "单次对话最大输出 token 数，范围 1~32000（对齐静态 max-tokens）。影响面：生成长度上限。",
            "1",
            "32000",
            Set.of(),
            false,
            100),

        // ---------------- business.*：限流 / 配额阈值（Lane D 消费） ----------------
        def(
            objectMapper,
            "business.rateLimit.perMinute",
            "business",
            ConfigValueType.INTEGER,
            "10",
            "每用户每分钟 AI 流式请求上限，范围 1~10000（对齐 crm.ai.rate-limit-per-minute；Lane C 滑动窗口限流消费）。",
            "1",
            "10000",
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "business.quota.maxTokensPerSession",
            "business",
            ConfigValueType.LONG,
            "100000",
            "单会话 Token 预算上限，范围 1~100000000（Lane D 预算控制消费；超限返回 TOKEN_BUDGET_EXCEEDED）。",
            "1",
            "100000000",
            Set.of(),
            false,
            100),

        // ---------------- rag.retrieval.*：检索 / 分块参数 + strict-KB（D16） ----------------
        def(
            objectMapper,
            "rag.retrieval.topK",
            "rag.retrieval",
            ConfigValueType.INTEGER,
            "5",
            "混合检索返回候选片段数，范围 1~100。影响面：上下文注入量与召回质量（Lane B 消费）。",
            "1",
            "100",
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "rag.retrieval.admin-vector.enabled",
            "rag.retrieval",
            ConfigValueType.BOOLEAN,
            "false",
            "管理端真向量检索总开关（默认关闭）；开启前仍需 owner 授权，缺失或读取失败按关闭处理。",
            null,
            null,
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "rag.retrieval.minScore",
            "rag.retrieval",
            ConfigValueType.DOUBLE,
            "0.2",
            "检索相关性最低分阈值，范围 0.0~1.0；低于阈值的片段不进入上下文。默认值对齐运行真相源 "
                + "RetrievalDefaults.MIN_SCORE=0.20（TASK-18，展示值≠运行值曾漂移已修正）。",
            "0.0",
            "1.0",
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "rag.retrieval.chunkSize",
            "rag.retrieval",
            ConfigValueType.INTEGER,
            "800",
            "文档分块大小（字符），范围 200~2000。影响面：分块粒度 → 检索精度/引用锚点（改后需重新入库生效）。",
            "200",
            "2000",
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "rag.retrieval.chunkOverlap",
            "rag.retrieval",
            ConfigValueType.INTEGER,
            "100",
            "分块重叠（字符），范围 0~500，须小于 chunkSize。影响面：跨块语义连续性。",
            "0",
            "500",
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "rag.retrieval.strictKb",
            "rag.retrieval",
            ConfigValueType.BOOLEAN,
            "false",
            "strict-KB 空匹配硬兜底开关（D16）：true 时 KB ON 零命中返回“未检索到”式硬兜底；"
                + "false 时软标记 + 诚实生成。影响面：助手空匹配行为。",
            null,
            null,
            Set.of(),
            false,
            100),
        // add-vision-pdf-ingest-pilot 任务 2.2：图像 PDF 视觉转写试点（默认关）
        def(
            objectMapper,
            "rag.retrieval.vision-pdf.enabled",
            "rag.retrieval",
            ConfigValueType.BOOLEAN,
            "false",
            "图像 PDF 视觉转写总开关（add-vision-pdf-ingest-pilot）：false=仅文本层（默认）；"
                + "true=文本层过短页可走 ModelProvider.vision。影响面：入库解析成本与图像页召回。",
            null,
            null,
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "rag.retrieval.vision-pdf.min-text-chars",
            "rag.retrieval",
            ConfigValueType.INTEGER,
            "80",
            "图像页判定阈值：normalize 后文本长度低于此值才尝试视觉转写，范围 1~2000（默认 80）。",
            "1",
            "2000",
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "rag.retrieval.vision-pdf.max-pages",
            "rag.retrieval",
            ConfigValueType.INTEGER,
            "3",
            "单文档最多视觉转写页数，范围 1~20（默认 3）；超出的过短页保留文本层。",
            "1",
            "20",
            Set.of(),
            false,
            100),

        // ---------------- rag.intent.*：意图类目与关键词（D17） ----------------
        def(
            objectMapper,
            "rag.intent.filterEnabled",
            "rag.intent",
            ConfigValueType.BOOLEAN,
            "false",
            "意图类目过滤开关（D17）：false 或无配置时跳过类目过滤；true 时按 categories/keywords 过滤检索候选。",
            null,
            null,
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "rag.intent.categories",
            "rag.intent",
            ConfigValueType.STRING_LIST,
            "[]",
            "CRM 域意图类目列表（JSON 字符串数组，至少 1 条），如 [\"财务报销\",\"人事制度\"]。"
                + "影响面：文档入库 metadata 打标与检索期类目过滤（Lane B 消费）。",
            null,
            null,
            Set.of(),
            false,
            10000,
            50,
            100),
        def(
            objectMapper,
            "rag.intent.keywords",
            "rag.intent",
            ConfigValueType.STRING_LIST,
            "[]",
            "意图关键词列表（JSON 字符串数组，至少 1 条），与类目配合做候选过滤；为空表示仅按类目过滤。",
            null,
            null,
            Set.of(),
            false,
            10000,
            100,
            100),

        // ---------------- business.*：业务运行参数与功能开关 ----------------
        def(
            objectMapper,
            "business.dataScope.enabled",
            "business",
            ConfigValueType.BOOLEAN,
            "true",
            "数据范围过滤总开关：false = 关闭行级过滤（全量可见，仅排障用，慎开）。影响面：DataScope 切面（Lane A）。",
            null,
            null,
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "business.assist.reminderEnabled",
            "business",
            ConfigValueType.BOOLEAN,
            "true",
            "业务提醒开关（生日/合同到期等定时提醒）。影响面：提醒任务是否派发。",
            null,
            null,
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "business.feature.aiAssistantEnabled",
            "business",
            ConfigValueType.BOOLEAN,
            "true",
            "AI 助手功能总开关：false = 助手入口不可用（降级兜底）。影响面：助手路由与前端入口。",
            null,
            null,
            Set.of(),
            false,
            100),
        def(
            objectMapper,
            "business.assistant.imageCacheMaxEntries",
            "business",
            ConfigValueType.INTEGER,
            "50",
            "每会话图片理解缓存（L1/L2）上限，范围 1~1000（D13）。影响面：图片场景内存占用与命中率。",
            "1",
            "1000",
            Set.of(),
            false,
            100));
  }

  private static ConfigKeyDefinition def(
      ObjectMapper om,
      String key,
      String ns,
      ConfigValueType type,
      String defaultValue,
      String description,
      String min,
      String max,
      Set<String> allowed,
      boolean sensitive,
      int maxLen) {
    return new ConfigKeyDefinition(
        key,
        ns,
        type,
        defaultValue,
        description,
        min,
        max,
        allowed,
        sensitive,
        maxLen,
        ConfigKeyDefinition.DEFAULT_MAX_VALUE_LENGTH,
        ConfigKeyDefinition.DEFAULT_MAX_VALUE_LENGTH,
        om);
  }

  private static ConfigKeyDefinition def(
      ObjectMapper om,
      String key,
      String ns,
      ConfigValueType type,
      String defaultValue,
      String description,
      String min,
      String max,
      Set<String> allowed,
      boolean sensitive,
      int maxLen,
      int maxListSize,
      int maxEntryLen) {
    return new ConfigKeyDefinition(
        key,
        ns,
        type,
        defaultValue,
        description,
        min,
        max,
        allowed,
        sensitive,
        maxLen,
        maxListSize,
        maxEntryLen,
        om);
  }
}
