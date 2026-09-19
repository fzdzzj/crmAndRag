package com.slz.crm.unit.knowledge.retrieval;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.retrieval.LlmContextCompressor;
import com.slz.crm.knowledge.retrieval.RuleContextCompressor;
import com.slz.crm.knowledge.retrieval.TokenEstimator;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
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
 * LLM 上下文压缩器单测（add-context-compression-and-enrichment 任务 2.2/2.3）： 成功路径采用 LLM
 * 输出并计量；失败/空输出/编号不完整/超时四类情形回退规则链； 失败调用也计量（补计量盲点）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LlmContextCompressorTest {

  private static final String OVER_BUDGET_CONTEXT =
      """
            [1] 首句含数字金额15万元。填充内容一句。填充内容二句。填充内容三句。填充内容四句。末句收尾。
            [2] 第二段首句。第二段填充一句。第二段填充二句。第二段填充三句。第二段填充四句。第二段末句。""";
  private static final String COMPRESSED_OUTPUT = "[1] 压缩要点：金额15万元。\n[2] 压缩要点：第二段口径。";
  private static final int BUDGET = TokenEstimator.estimate(COMPRESSED_OUTPUT);

  @Mock private ModelProvider modelProvider;

  @Mock private ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  @Mock private ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider;

  @Mock private TokenUsageRecorder tokenUsageRecorder;

  private final RuleContextCompressor fallback = new RuleContextCompressor();
  private LlmContextCompressor compressor;

  @BeforeEach
  void setUp() {
    UserContextHolder.set(new UserContext(1L, 2L, 10L, DataScopeLevel.SELF, "user"));
    when(tokenUsageRecorderProvider.getIfAvailable()).thenReturn(tokenUsageRecorder);
    compressor =
        new LlmContextCompressor(
            modelProvider, fallback, dynamicConfigProvider, tokenUsageRecorderProvider) {
          @Override
          protected long resolveTimeoutMs() {
            return 3000;
          }
        };
  }

  @AfterEach
  void tearDown() {
    UserContextHolder.clear();
  }

  /** 未超预算不触发 LLM：原文逐字返回，无模型调用、无计量。 */
  @Test
  @DisplayName("未超预算：不调模型、不计量")
  void withinBudgetMustNotCallModel() {
    String context = "[1] 短上下文。";

    String result = compressor.compress(context, TokenEstimator.estimate(context) + 1);

    assertThat(result).isEqualTo(context);
    verifyNoInteractions(modelProvider);
    verifyNoInteractions(tokenUsageRecorder);
  }

  /** 成功路径：采用 LLM 压缩结果，并按 usage 计量（type=SUMMARY、success=true、user 挂账）。 */
  @Test
  @DisplayName("成功：采用输出并计量 usage")
  void successfulCompressionShouldBeUsedAndMetered() {
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText(COMPRESSED_OUTPUT, "qwen-max", 100L, 10L, 110L));

    String result = compressor.compress(OVER_BUDGET_CONTEXT, BUDGET);

    assertThat(result).isEqualTo(COMPRESSED_OUTPUT.strip());
    ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
    verify(tokenUsageRecorder).record(captor.capture());
    TokenUsageRecord record = captor.getValue();
    assertThat(record.type()).isEqualTo(TokenUsageType.SUMMARY);
    assertThat(record.success()).isTrue();
    assertThat(record.promptTokens()).isEqualTo(100L);
    assertThat(record.completionTokens()).isEqualTo(10L);
    assertThat(record.totalTokens()).isEqualTo(110L);
    assertThat(record.model()).isEqualTo("qwen-max");
    assertThat(record.userIdRef()).isEqualTo("user:1");
  }

  /** 回退路径1——调用失败（Provider 抛异常）：回退规则链，且失败调用也计量（success=false）。 */
  @Test
  @DisplayName("调用失败：回退规则链并计量失败")
  void providerFailureShouldFallBackToRuleAndRecordFailure() {
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenThrow(new IllegalStateException("网络不可用"));

    String result = compressor.compress(OVER_BUDGET_CONTEXT, BUDGET);

    assertThat(result).isEqualTo(fallback.compress(OVER_BUDGET_CONTEXT, BUDGET));
    ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
    verify(tokenUsageRecorder).record(captor.capture());
    assertThat(captor.getValue().success()).isFalse();
    assertThat(captor.getValue().totalTokens()).isNull();
  }

  /** 回退路径2——空输出：回退规则链（模型调用本身成功，照常计量 usage）。 */
  @Test
  @DisplayName("空输出：回退规则链，调用计量不丢")
  void blankOutputShouldFallBackToRuleChain() {
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText("  ", "qwen-max", 100L, 0L, 100L));

    String result = compressor.compress(OVER_BUDGET_CONTEXT, BUDGET);

    assertThat(result).isEqualTo(fallback.compress(OVER_BUDGET_CONTEXT, BUDGET));
    ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
    verify(tokenUsageRecorder).record(captor.capture());
    assertThat(captor.getValue().success()).isTrue();
    assertThat(captor.getValue().completionTokens()).isEqualTo(0L);
  }

  /** 回退路径2b——编号不完整（丢了 [2]）：引用编号完整性不达标，回退规则链。 */
  @Test
  @DisplayName("编号不完整：回退规则链")
  void incompleteNumberingShouldFallBackToRuleChain() {
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText("[1] 只压缩了第一段。", "qwen-max", 100L, 10L, 110L));

    String result = compressor.compress(OVER_BUDGET_CONTEXT, BUDGET);

    assertThat(result).isEqualTo(fallback.compress(OVER_BUDGET_CONTEXT, BUDGET));
  }

  /** 回退路径2c——输出仍超预算：预算契约不达标，回退规则链。 */
  @Test
  @DisplayName("输出仍超预算：回退规则链")
  void overBudgetOutputShouldFallBackToRuleChain() {
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText(OVER_BUDGET_CONTEXT, "qwen-max", 100L, 200L, 300L));

    String result = compressor.compress(OVER_BUDGET_CONTEXT, BUDGET);

    assertThat(result).isEqualTo(fallback.compress(OVER_BUDGET_CONTEXT, BUDGET));
  }

  /** 回退路径3——超时：等待超过 timeout-ms 即回退规则链。 */
  @Test
  @DisplayName("超时：回退规则链且计量失败")
  void timeoutShouldFallBackToRuleChain() {
    LlmContextCompressor shortTimeout =
        new LlmContextCompressor(
            modelProvider, fallback, dynamicConfigProvider, tokenUsageRecorderProvider) {
          @Override
          protected long resolveTimeoutMs() {
            return 50;
          }
        };
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenAnswer(
            invocation -> {
              Thread.sleep(300);
              return ModelCallResult.ofText(COMPRESSED_OUTPUT, "qwen-max", 100L, 10L, 110L);
            });

    long startedAt = System.currentTimeMillis();
    String result = shortTimeout.compress(OVER_BUDGET_CONTEXT, BUDGET);

    assertThat(result).isEqualTo(fallback.compress(OVER_BUDGET_CONTEXT, BUDGET));
    assertThat(System.currentTimeMillis() - startedAt).isLessThan(2000);
    ArgumentCaptor<TokenUsageRecord> captor = ArgumentCaptor.forClass(TokenUsageRecord.class);
    verify(tokenUsageRecorder).record(captor.capture());
    assertThat(captor.getValue().success()).isFalse();
  }

  /** 编号校验只认行首递增编号：正文里的编号文本不构成编号要求，跳号视为不完整。 */
  @Test
  @DisplayName("编号校验：行首递增编号口径")
  void numberingValidationShouldOnlyCountSequentialHeaders() {
    int budget = TokenEstimator.estimate("[1] 段一含[3]正文编号。\n[2] 段二。");
    // 输出行首编号 [1][2] 与输入一致（正文里的 [3] 不构成编号）→ 校验通过、采用输出
    String tricky = "[1] 段一含[3]正文编号。\n[2] 段二。";
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText(tricky, "qwen-max", 10L, 5L, 15L));
    assertThat(compressor.compress(OVER_BUDGET_CONTEXT, budget)).isEqualTo(tricky.strip());
    // 输出行首编号跳号 [1]→[3]，与输入 [1][2] 序列不一致 → 回退规则链
    String skipped = "[1] 段一。\n[3] 跳号。";
    when(modelProvider.chat(any(Prompt.class), any(ModelCallOptions.class)))
        .thenReturn(ModelCallResult.ofText(skipped, "qwen-max", 10L, 5L, 15L));
    assertThat(compressor.compress(OVER_BUDGET_CONTEXT, budget))
        .isEqualTo(fallback.compress(OVER_BUDGET_CONTEXT, budget));
  }
}
