package com.slz.crm.platform.security;

/**
 * 内容命中高风险拦截策略时抛出。
 */
public class ContentRiskBlockedException extends RuntimeException {

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
}
