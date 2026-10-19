package com.slz.crm.platform.security;

import com.slz.crm.platform.contract.PlatformErrorCode;

/** 内容命中高风险拦截策略时抛出。 */
public class ContentRiskBlockedException extends RuntimeException {

  /** 平台契约错误码（contracts-frozen.md §11 的 96004）：拦截统一走该码，不得由上游降级为 code=0。 */
  private final Integer code = PlatformErrorCode.CONTENT_RISK.getCode();

  private final ContentSecurityResult result;

  /**
   * 构造拦截异常。
   *
   * @param result 安全检查结果
   */
  public ContentRiskBlockedException(ContentSecurityResult result) {
    super(result.reason());
    this.result = result;
  }

  /**
   * @return 安全检查结果
   */
  public ContentSecurityResult result() {
    return result;
  }

  /**
   * @return 平台内容风险错误码（96004）
   */
  public Integer getCode() {
    return code;
  }
}
