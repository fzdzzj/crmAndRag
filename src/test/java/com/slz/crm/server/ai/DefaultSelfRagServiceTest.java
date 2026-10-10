package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.SourceReference;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** 统一 Self-RAG 门面路由服务单测（add-self-rag-reflection 任务 2.1/2.2）。 */
@ExtendWith(MockitoExtension.class)
class DefaultSelfRagServiceTest {

  @Mock private ObjectProvider<LlmSelfRagReflector> llmReflectorProvider;
  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  @Mock private DynamicConfigService dynamicConfigService;

  private final RuleSelfRagReflector ruleReflector = new RuleSelfRagReflector();

  private static SourceReference src(String excerpt) {
    return new SourceReference(
        "md", "vector", "doc.md", "doc-1", "chunk-1", 0, 1, null, excerpt, 0.9d);
  }

  @Test
  @DisplayName("off 模式：直通返回，不剥除引用，不加尾注")
  void offModePassesThrough() {
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    when(dynamicConfigService.get("rag.generation.selfrag.mode", String.class, "rule"))
        .thenReturn("off");

    DefaultSelfRagService service =
        new DefaultSelfRagService(ruleReflector, llmReflectorProvider, dynamicConfigProvider);

    // [99] 初始引用在 off 模式下原样直通保留，不加尾注
    List<SourceReference> sources = List.of(src("知识库内容"));
    String answer = "这是答案内容[99]。";

    SelfRagResult result = service.reflect(answer, sources, List.of(99));

    assertThat(result.citations()).containsExactly(99);
    assertThat(result.answer()).doesNotContain(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);
    assertThat(result.answer()).isEqualTo(answer);
  }

  @Test
  @DisplayName("rule 模式（默认）：调用规则反思链校验并剥除越界引用")
  void ruleModeRoutesToRuleReflector() {
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    when(dynamicConfigService.get("rag.generation.selfrag.mode", String.class, "rule"))
        .thenReturn("rule");

    DefaultSelfRagService service =
        new DefaultSelfRagService(ruleReflector, llmReflectorProvider, dynamicConfigProvider);

    List<SourceReference> sources = List.of(src("知识库内容"));
    String answer = "这是答案内容[99]。";

    SelfRagResult result = service.reflect(answer, sources);

    // 规则链剥除越界 99
    assertThat(result.citations()).isEmpty();
    assertThat(result.answer()).contains(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);
  }

  @Test
  @DisplayName("非法值模式：自动回落默认 rule 规则反思")
  void invalidModeFallsBackToRule() {
    when(dynamicConfigProvider.getIfAvailable()).thenReturn(dynamicConfigService);
    when(dynamicConfigService.get("rag.generation.selfrag.mode", String.class, "rule"))
        .thenReturn("invalid-mode-xyz");

    DefaultSelfRagService service =
        new DefaultSelfRagService(ruleReflector, llmReflectorProvider, dynamicConfigProvider);

    List<SourceReference> sources = List.of(src("知识库内容"));
    String answer = "这是答案内容[99]。";

    SelfRagResult result = service.reflect(answer, sources);

    assertThat(result.citations()).isEmpty();
    assertThat(result.answer()).contains(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);
  }
}
