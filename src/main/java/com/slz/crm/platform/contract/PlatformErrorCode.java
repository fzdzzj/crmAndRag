package com.slz.crm.platform.contract;

/**
 * 融合平台新增的跨 lane 错误码（冻结契约）。
 *
 * <p>与既有 {@link com.slz.crm.common.enumeration.ErrorCode} 的关系：
 * CRM 既有错误码继续用于业务域（客户/商机/合同/权限…），
 * 本枚举只承载“平台能力域”错误（限流/配额/未登录/内容风险），避免把业务码表越撑越大。</p>
 *
 * <p>响应封装仍统一为 {@link com.slz.crm.common.result.Result}（任务 3），
 * 禁止另起一套响应结构；编码区间预留 96xxx，避免与业务码冲突。</p>
 */
public enum PlatformErrorCode {

    /** 请求频率超限（助手每用户每分钟上限、知识库入库并发上限等） */
    RATE_LIMITED(96001, "请求过于频繁，请稍后再试"),

    /** 配额耗尽（Token 预算、存储配额等，由 Lane D 聚合裁决） */
    QUOTA_EXCEEDED(96002, "配额已用尽，请联系管理员"),

    /** 未登录 / 身份缺失（D8：融合平台已移除匿名链路） */
    UNAUTHORIZED(96003, "请先登录"),

    /** 内容风险拦截（合规审核/敏感词/模型安全策略触发） */
    CONTENT_RISK(96004, "内容存在风险，已阻止本次处理");

    private final Integer code;
    private final String message;

    PlatformErrorCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }

    /** @return 稳定错误码（用于前端识别与监控告警） */
    public Integer getCode() {
        return code;
    }

    /** @return 用户可读提示（直接透出给前端） */
    public String getMessage() {
        return message;
    }
}
