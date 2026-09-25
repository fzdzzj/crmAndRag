package com.slz.crm.platform.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 校验护栏测试：类型/范围/枚举/长度/未知键，非法值必须被拒绝并给出可读原因。 */
class DynamicConfigKeyRegistryTest {

  private final DynamicConfigKeyRegistry registry =
      new DynamicConfigKeyRegistry(new ObjectMapper());

  @Test
  @DisplayName("合法值通过校验并规范化为存储文本")
  void validValuesAccepted() {
    ConfigValueType.Parsed temp = registry.validate("ai.model.temperature", "0.3");
    assertThat(temp.valid()).isTrue();
    assertThat(temp.typed()).isEqualTo(0.3d);
    assertThat(temp.canonical()).isEqualTo("0.3");

    // 边界值（含上下界）必须放行
    assertThat(registry.validate("ai.model.temperature", "1.0").valid()).isTrue();
    assertThat(registry.validate("ai.model.temperature", "0.0").valid()).isTrue();
    assertThat(registry.validate("rag.retrieval.topK", "8").typed()).isEqualTo(8);
    assertThat(registry.validate("rag.retrieval.topK", "1").valid()).isTrue();
    assertThat(registry.validate("rag.retrieval.topK", "100").valid()).isTrue();

    // 枚举白名单
    assertThat(registry.validate("ai.model.provider", "openai-compatible").valid()).isTrue();
    assertThat(registry.validate("ai.model.provider", "vllm").valid()).isTrue();

    // 布尔
    assertThat(registry.validate("rag.retrieval.strictKb", "true").typed()).isEqualTo(true);
    assertThat(registry.validate("rag.retrieval.admin-vector.enabled", "false").typed())
        .isEqualTo(false);
    assertThat(registry.validate("rag.retrieval.strictKb", "FALSE").typed()).isEqualTo(false);

    // STRING_LIST：解析为 List<String>，规范化为紧凑 JSON
    ConfigValueType.Parsed categories =
        registry.validate("rag.intent.categories", "[\"财务报销\", \"人事制度\"]");
    assertThat(categories.valid()).isTrue();
    assertThat(categories.typed()).isEqualTo(List.of("财务报销", "人事制度"));
    assertThat(categories.canonical()).isEqualTo("[\"财务报销\",\"人事制度\"]");

    // LONG（大数预算）
    assertThat(registry.validate("business.quota.maxTokensPerSession", "100000").typed())
        .isEqualTo(100000L);
  }

  @Test
  @DisplayName("类型不符/越界/枚举外/空值一律拒绝且不产生规范值")
  void invalidValuesRejected() {
    // 范围越界：温度 > 1.0 或 < 0.0
    assertRejected("ai.model.temperature", "1.5");
    assertRejected("ai.model.temperature", "-0.1");
    assertRejected("ai.model.temperature", "abc");
    // topK 越界（1~100）与类型不符
    assertRejected("rag.retrieval.topK", "0");
    assertRejected("rag.retrieval.topK", "-5");
    assertRejected("rag.retrieval.topK", "1.5");
    assertRejected("rag.retrieval.topK", "abc");
    assertRejected("rag.retrieval.topK", "101");
    // maxTokens 越界
    assertRejected("ai.model.maxTokens", "999999");
    // 枚举外 Provider（未知模型/Provider 名）
    assertRejected("ai.model.provider", "openai");
    // 布尔只收 true/false
    assertRejected("rag.retrieval.strictKb", "yes");
    // STRING_LIST：非 JSON / 空列表 / 含空条目
    assertRejected("rag.intent.categories", "not-json");
    assertRejected("rag.intent.categories", "[]");
    assertRejected("rag.intent.categories", "[\"财务报销\",\"\"]");
    // 空值一律拒绝
    assertRejected("rag.retrieval.topK", "");
    assertRejected("rag.retrieval.topK", "   ");
    // 未知键（未注册）拒绝
    ConfigValueType.Parsed unknown = registry.validate("foo.bar", "1");
    assertThat(unknown.valid()).isFalse();
    assertThat(unknown.errorMessage()).contains("未知配置键");
  }

  @Test
  @DisplayName("五个官方命名空间齐全，键名与命名空间自洽")
  void namespacesComplete() {
    assertThat(DynamicConfigKeyRegistry.NAMESPACES)
        .containsExactlyInAnyOrder(
            "ai.prompt", "ai.model", "rag.retrieval", "rag.intent", "business");
    var defs = registry.definitions();
    assertThat(defs).isNotEmpty();
    // 键必须以命名空间开头；同一命名空间下键前缀一致（V6 脚本分组约定）
    for (ConfigKeyDefinition def : defs) {
      assertThat(def.key()).startsWith(def.namespace() + ".");
    }
    assertThat(registry.byNamespace("rag.intent")).allMatch(d -> d.key().startsWith("rag.intent."));
    assertThat(registry.byNamespace(null)).hasSameSizeAs(defs);
    // 覆盖 spec 优先项：提示词/模型/检索/strict-KB/意图类目/图片缓存上限
    assertThat(registry.definitionOf("ai.prompt.system")).isPresent();
    assertThat(registry.definitionOf("rag.retrieval.strictKb")).isPresent();
    assertThat(registry.definitionOf("rag.retrieval.admin-vector.enabled")).isPresent();
    assertThat(registry.definitionOf("rag.intent.categories")).isPresent();
    assertThat(registry.definitionOf("business.assistant.imageCacheMaxEntries")).isPresent();
  }

  @Test
  @DisplayName("register-rag-retrieval-dynamic-keys：11 个检索管线键已登记，默认值=消费点代码缺省")
  void retrievalPipelineKeysRegisteredWithCodeDefaults() {
    // （键, 登记默认值）；默认值与消费点内联缺省一致：
    // RetrievalQueryRewriteService(true)、RetrievalConfigResolver(rrf/60/4/0.70/0.30)、
    // KnowledgeRetrievalServiceImpl(default)、DefaultWeightedReranker(0.60/0.40)、LlmReranker(3000/20)。
    var expectedDefaults =
        Map.ofEntries(
            Map.entry("rag.retrieval.query-rewrite.enabled", "true"),
            Map.entry("rag.retrieval.fusion.mode", "rrf"),
            Map.entry("rag.retrieval.fusion.rrf-k", "60"),
            Map.entry("rag.retrieval.rerank.mode", "default"),
            Map.entry("rag.retrieval.rerank.vector-weight", "0.6"),
            Map.entry("rag.retrieval.rerank.bm25-weight", "0.4"),
            Map.entry("rag.retrieval.rerank.candidate-multiplier", "4"),
            Map.entry("rag.retrieval.rerank.llm.timeout-ms", "3000"),
            Map.entry("rag.retrieval.rerank.llm.max-candidates", "20"),
            Map.entry("rag.retrieval.image-text-route-weight", "0.7"),
            Map.entry("rag.retrieval.image-vector-route-weight", "0.3"));
    for (var entry : expectedDefaults.entrySet()) {
      var definition = registry.definitionOf(entry.getKey());
      assertThat(definition).as("键 %s 必须已登记", entry.getKey()).isPresent();
      assertThat(definition.orElseThrow().defaultValue())
          .as("键 %s 登记默认值须与消费点代码缺省一致", entry.getKey())
          .isEqualTo(entry.getValue());
    }
    // 合法值逐键放行（覆盖 Boolean/枚举/整数/长整数/浮点全部类型）
    assertThat(registry.validate("rag.retrieval.query-rewrite.enabled", "false").typed())
        .isEqualTo(false);
    assertThat(registry.validate("rag.retrieval.fusion.mode", "weighted").typed())
        .isEqualTo("weighted");
    assertThat(registry.validate("rag.retrieval.fusion.rrf-k", "60").typed()).isEqualTo(60);
    assertThat(registry.validate("rag.retrieval.rerank.mode", "llm").typed()).isEqualTo("llm");
    assertThat(registry.validate("rag.retrieval.rerank.vector-weight", "0.6").typed())
        .isEqualTo(0.6d);
    assertThat(registry.validate("rag.retrieval.rerank.bm25-weight", "0.4").typed())
        .isEqualTo(0.4d);
    assertThat(registry.validate("rag.retrieval.rerank.candidate-multiplier", "4").typed())
        .isEqualTo(4);
    assertThat(registry.validate("rag.retrieval.rerank.llm.timeout-ms", "3000").typed())
        .isEqualTo(3000L);
    assertThat(registry.validate("rag.retrieval.rerank.llm.max-candidates", "20").typed())
        .isEqualTo(20);
    assertThat(registry.validate("rag.retrieval.image-text-route-weight", "0.7").typed())
        .isEqualTo(0.7d);
    assertThat(registry.validate("rag.retrieval.image-vector-route-weight", "0.3").typed())
        .isEqualTo(0.3d);
  }

  @Test
  @DisplayName("register-rag-retrieval-dynamic-keys：布尔/枚举/0~1/整数下限非法值一律拒绝")
  void retrievalPipelineKeysRejectInvalidValues() {
    // Boolean 只收 true/false
    assertRejected("rag.retrieval.query-rewrite.enabled", "yes");
    // 枚举白名单外（含消费侧会按 rrf 兜底的写法，写入期直接拒绝）
    assertRejected("rag.retrieval.fusion.mode", "linear");
    assertRejected("rag.retrieval.rerank.mode", "bm25");
    // Double 0~1 越界
    assertRejected("rag.retrieval.rerank.vector-weight", "1.1");
    assertRejected("rag.retrieval.rerank.bm25-weight", "-0.1");
    assertRejected("rag.retrieval.image-text-route-weight", "1.5");
    assertRejected("rag.retrieval.image-vector-route-weight", "-0.2");
    // 整数/长整数下限拒绝（消费侧 <1 回落默认，写入期护栏直接拒）
    assertRejected("rag.retrieval.fusion.rrf-k", "0");
    assertRejected("rag.retrieval.rerank.candidate-multiplier", "0");
    assertRejected("rag.retrieval.rerank.llm.timeout-ms", "0");
    assertRejected("rag.retrieval.rerank.llm.max-candidates", "0");
  }

  @Test
  @DisplayName("register-rag-retrieval-dynamic-keys：未扩 NAMESPACES，context/chunking/query 仍未知")
  void nonWhitelistedNamespacesStayUnknown() {
    // 白名单不扩：五个官方命名空间不变
    assertThat(DynamicConfigKeyRegistry.NAMESPACES)
        .containsExactlyInAnyOrder(
            "ai.prompt", "ai.model", "rag.retrieval", "rag.intent", "business");
    // 未登记的检索链路键仍为未知键（写入拒绝）
    assertThat(registry.definitionOf("rag.query.multi-query.enabled")).isEmpty();
    assertThat(registry.definitionOf("rag.context.token-budget")).isEmpty();
    assertThat(registry.definitionOf("rag.chunking.strategy")).isEmpty();
    ConfigValueType.Parsed unknown = registry.validate("rag.context.token-budget", "4096");
    assertThat(unknown.valid()).isFalse();
    assertThat(unknown.errorMessage()).contains("未知配置键");
  }

  private void assertRejected(String key, String raw) {
    ConfigValueType.Parsed parsed = registry.validate(key, raw);
    assertThat(parsed.valid()).as("%s = [%s] 应被拒绝", key, raw).isFalse();
    assertThat(parsed.errorMessage()).as("%s 应给出可读原因", key).isNotBlank();
  }
}
