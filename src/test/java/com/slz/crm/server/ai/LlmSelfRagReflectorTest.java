package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;

/**
 * LLM 支持度自评（L 档）单测（add-self-rag-reflection 任务 2.3）。
 *
 * <p>测试四类 case：fake ModelProvider 支持 / 不支持 / 超时回退 / 空输出回退。 均为本地单测，零真实外呼。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmSelfRagReflectorTest {

  @Mock private ModelProvider modelProvider;
  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  @Mock private ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider;
  @Mock private TokenUsageRecorder tokenUsageRecorder;

  private final RuleSelfRagReflector ruleReflector = new RuleSelfRagReflector();
  private LlmSelfRagReflector reflector;

  private static SourceReference src(String excerpt) {
    return new SourceReference(
        "md", "vector", "doc.md", "doc-1", "chunk-1", 0, 1, null, excerpt, 0.9d);
  }

  @BeforeEach
  void setUp() {
    UserContextHolder.set(new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user"));
    when(tokenUsageRecorderProvider.getIfAvailable()).thenReturn(tokenUsageRecorder);
    reflector =
        new LlmSelfRagReflector(
            modelProvider,
            ruleReflector,
            dynamicConfigProvider,
            tokenUsageRecorderProvider,
            Executors.newSingleThreadExecutor());
  }

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
  }

  @Test
  @DisplayName("case 1 支持：LLM 自评全部引用均被支持时保留 citations 且不加尾注，记成功计量")
  void supportedClaimsRetainedWithoutNote() {
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(
            ModelCallResult.ofText(
                "{\"supported\":[1,2],\"unsupported\":[]}", "qwen-max", 20L, 10L, 30L));

    List<SourceReference> sources = List.of(src("知识库检索包含向量召回与重排机制"), src("双路混合检索融合支持 RRF 算法规则"));
    String answer = "知识库检索包含向量召回与重排机制[1]，双路混合检索融合支持 RRF 算法规则[2]。";

    SelfRagResult result = reflector.reflect(answer, sources);

    assertThat(result.citations()).containsExactly(1, 2);
    assertThat(result.answer()).doesNotContain(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);

    ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
    verify(tokenUsageRecorder).record(captor.capture());
    TokenUsageRecord record = captor.getValue();
    assertThat(record.success()).isTrue();
    assertThat(record.type()).isEqualTo(TokenUsageType.SUMMARY);
    assertThat(record.totalTokens()).isEqualTo(30L);
    assertThat(record.userIdRef()).isEqualTo("user:1");
  }

  @Test
  @DisplayName("case 2 不支持：LLM 自评指出未支持引用时剥除该引用并软降级尾注")
  void unsupportedClaimStrippedWithSoftFallback() {
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(
            ModelCallResult.ofText(
                "{\"supported\":[1],\"unsupported\":[2]}", "qwen-max", 20L, 10L, 30L));

    List<SourceReference> sources = List.of(src("知识库检索包含向量召回与重排机制"), src("双路混合检索融合支持 RRF 算法规则"));
    String answer = "知识库检索包含向量召回与重排机制[1]，双路混合检索融合支持 RRF 算法规则[2]。";

    SelfRagResult result = reflector.reflect(answer, sources);

    // 编号 2 未获支持被剥除
    assertThat(result.citations()).containsExactly(1);
    assertThat(result.answer()).contains(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);

    ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
    verify(tokenUsageRecorder).record(captor.capture());
    assertThat(captor.getValue().success()).isTrue();
  }

  @Test
  @DisplayName("case 3 超时回退：LLM 等待超时自动回退到规则反思链，记失败计量")
  void timeoutFallbackToRuleChain() {
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenAnswer(
            invocation -> {
              Thread.sleep(200);
              return ModelCallResult.ofText("{\"supported\":[1]}", "qwen-max", 10L, 10L, 20L);
            });

    LlmSelfRagReflector shortTimeoutReflector =
        new LlmSelfRagReflector(
            modelProvider,
            ruleReflector,
            dynamicConfigProvider,
            tokenUsageRecorderProvider,
            Executors.newSingleThreadExecutor()) {
          @Override
          protected long resolveTimeoutMs() {
            return 30; // 短超时
          }
        };

    List<SourceReference> sources = List.of(src("知识库检索包含向量召回与重排机制"), src(""));
    // [1] 在规则链下有效，[2] 在规则链下指向空块被剥除
    String answer = "知识库检索包含向量召回与重排机制[1]，空块引用[2]。";

    SelfRagResult result = shortTimeoutReflector.reflect(answer, sources);

    // 回退规则反思链：1 保留，2 剥除并追加软降级尾注
    assertThat(result.citations()).containsExactly(1);
    assertThat(result.answer()).contains(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);

    ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
    verify(tokenUsageRecorder).record(captor.capture());
    assertThat(captor.getValue().success()).isFalse();
  }

  @Test
  @DisplayName("case 4 空输出回退：LLM 输出空或非法格式时回退规则反思链，记失败计量")
  void emptyOutputFallbackToRuleChain() {
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText("", "qwen-max", 10L, 0L, 10L));

    List<SourceReference> sources = List.of(src("知识库检索包含向量召回与重排机制"));
    String answer = "知识库检索包含向量召回与重排机制[1]。";

    SelfRagResult result = reflector.reflect(answer, sources);

    // 回退规则链：1 得到支持保留，不加尾注
    assertThat(result.citations()).containsExactly(1);
    assertThat(result.answer()).doesNotContain(SelfRagReflector.UNSUPPORTED_CLAIM_NOTE);

    ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
    verify(tokenUsageRecorder).record(captor.capture());
    assertThat(captor.getValue().success()).isFalse();
  }
}
