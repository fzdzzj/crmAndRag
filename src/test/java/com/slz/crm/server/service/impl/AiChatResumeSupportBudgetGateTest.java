package com.slz.crm.server.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.slz.crm.platform.token.TokenBudgetDecision;
import com.slz.crm.platform.token.TokenBudgetRequest;
import com.slz.crm.platform.token.TokenBudgetService;
import com.slz.crm.server.ai.AiChatSseEventWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Token 预算闸接线两向测试（wire-built-unfinished-infra 任务 1.2）：超限拒绝复用既有 SSE 错误事件语义、
 * 预算内放行不触碰响应通道、服务/用户缺席空跳过。mock 零外呼。
 */
@ExtendWith(MockitoExtension.class)
class AiChatResumeSupportBudgetGateTest {

  @Mock private TokenBudgetService tokenBudgetService;
  @Mock private AiChatSseEventWriter eventWriter;
  @Mock private SseEmitter emitter;

  @Test
  @DisplayName("超限拒绝：发 TOKEN_BUDGET_EXCEEDED 错误事件并 complete（与频次限流同构）")
  void overBudgetRejectedWithQuotaEventSemantics() {
    TokenBudgetDecision denied =
        new TokenBudgetDecision(false, 100, 100, 0, 0, 3600, "TOKEN_BUDGET_EXCEEDED");
    when(tokenBudgetService.check(any())).thenReturn(denied);

    boolean rejected =
        AiChatResumeSupport.rejectIfBudgetExceeded(tokenBudgetService, 7L, emitter, eventWriter);

    assertThat(rejected).isTrue();
    ArgumentCaptor<String> codeCaptor = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(eventWriter).sendError(eq(emitter), codeCaptor.capture(), messageCaptor.capture());
    assertThat(codeCaptor.getValue()).isEqualTo("TOKEN_BUDGET_EXCEEDED");
    assertThat(messageCaptor.getValue()).contains("Token 预算已用尽").contains("3600");
    verify(emitter).complete();
  }

  @Test
  @DisplayName("预算内放行：不发错误事件、不 complete，按 USER/CHAT 维度先查")
  void withinBudgetAllowedWithoutTouchingResponseChannel() {
    when(tokenBudgetService.check(any())).thenReturn(TokenBudgetDecision.allow(100, 50, 0, 50));

    boolean rejected =
        AiChatResumeSupport.rejectIfBudgetExceeded(tokenBudgetService, 7L, emitter, eventWriter);

    assertThat(rejected).isFalse();
    ArgumentCaptor<TokenBudgetRequest> requestCaptor =
        ArgumentCaptor.forClass(TokenBudgetRequest.class);
    verify(tokenBudgetService).check(requestCaptor.capture());
    assertThat(requestCaptor.getValue().scope().name()).isEqualTo("USER");
    assertThat(requestCaptor.getValue().scopeId()).isEqualTo("7");
    assertThat(requestCaptor.getValue().usageType().wireName()).isEqualTo("chat");
    assertThat(requestCaptor.getValue().estimatedTokens()).isZero();
    verifyNoInteractions(eventWriter, emitter);
  }

  @Test
  @DisplayName("闸缺席或 userId 缺席时空跳过：零检查、零响应通道交互")
  void absentGateOrUserSkipsCheck() {
    assertThat(AiChatResumeSupport.rejectIfBudgetExceeded(null, 7L, emitter, eventWriter))
        .isFalse();
    assertThat(
            AiChatResumeSupport.rejectIfBudgetExceeded(
                tokenBudgetService, null, emitter, eventWriter))
        .isFalse();
    verifyNoInteractions(tokenBudgetService, eventWriter, emitter);
  }

  @Test
  @DisplayName("sendError 返回值不影响拒绝判定：complete 幂等跟随调用")
  void sendErrorReturnValueDoesNotAffectRejection() {
    TokenBudgetDecision denied =
        new TokenBudgetDecision(false, 1000, 1000, 0, 0, 60, "TOKEN_BUDGET_EXCEEDED");
    when(tokenBudgetService.check(any())).thenReturn(denied);
    when(eventWriter.sendError(any(), anyString(), anyString())).thenReturn(false);

    boolean rejected =
        AiChatResumeSupport.rejectIfBudgetExceeded(tokenBudgetService, 7L, emitter, eventWriter);

    assertThat(rejected).isTrue();
    verify(emitter).complete();
  }
}
