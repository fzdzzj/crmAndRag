package com.slz.crm.server.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slz.crm.common.result.Result;
import com.slz.crm.platform.contract.PlatformErrorCode;
import com.slz.crm.platform.security.ContentRiskBlockedException;
import com.slz.crm.platform.security.ContentRiskLevel;
import com.slz.crm.platform.security.ContentSecurityAction;
import com.slz.crm.platform.security.ContentSecurityResult;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 摄取 BLOCK 错误码收口（fix-minor-debt-family 任务 2.1/2.2）。
 *
 * <p>改造前 {@link ContentRiskBlockedException} 是裸 {@code RuntimeException}（无 code），落到兜底处理器后客户端看到
 * {@code code=0}/500；收口后统一取平台契约码 {@link PlatformErrorCode#CONTENT_RISK}（96004），且响应只回通用文案，
 * 不透出命中规则与原始内容。
 */
@DisplayName("内容风险拦截 96004 统一出口")
class GlobalExceptionHandlerContentRiskTest {

  private static ContentRiskBlockedException blockedException() {
    return new ContentRiskBlockedException(
        new ContentSecurityResult(
            ContentRiskLevel.HIGH,
            ContentSecurityAction.BLOCK,
            "prompt-injection",
            "内容试图覆盖或提取系统指令"));
  }

  @Test
  @DisplayName("异常自带 96004：不再是无码 RuntimeException")
  void exceptionCarriesPlatformContentRiskCode() {
    assertThat(blockedException().getCode())
        .isEqualTo(PlatformErrorCode.CONTENT_RISK.getCode())
        .isEqualTo(96004);
  }

  @Test
  @DisplayName("摄取链穿透到 HTTP 出口时回 96004 + 通用文案（不回规则细节）")
  void handlerReturns96004WithGenericMessage() {
    HttpServletRequest request = mock(HttpServletRequest.class);
    when(request.getMethod()).thenReturn("POST");
    when(request.getRequestURI()).thenReturn("/knowledge/document/ingest");

    Result<String> result =
        new GlobalExceptionHandler().contentRiskBlocked(blockedException(), request);

    assertThat(result.getCode()).isEqualTo(PlatformErrorCode.CONTENT_RISK.getCode());
    assertThat(result.getMsg()).isEqualTo(PlatformErrorCode.CONTENT_RISK.getMessage());
    assertThat(result.getMsg()).doesNotContain("prompt-injection").doesNotContain("系统指令");
  }
}
