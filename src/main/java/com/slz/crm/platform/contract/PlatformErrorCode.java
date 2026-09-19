package com.slz.crm.platform.contract;

/**
 * 融合平台跨 lane 错误码（冻结契约，contracts-frozen.md §11 全集）。
 *
 * <p>与既有 {@link com.slz.crm.common.enumeration.ErrorCode} 的关系： CRM 既有错误码继续用于业务域（客户/商机/合同/权限…），
 * 本枚举只承载“平台能力域”错误；编码区间 96xxx 已核对与 CRM 现有码（最大 93004）不冲突。
 *
 * <p>响应封装仍统一为 {@link com.slz.crm.common.result.Result}（任务 3）， 禁止另起一套响应结构；SSE 的 {@code error} 事件
 * payload{@code code} 也取本枚举值。
 */
public enum PlatformErrorCode {

  /** 请求频率超限（助手每用户每分钟上限、知识库入库并发上限等） */
  RATE_LIMITED(96001, "请求过于频繁，请稍后再试"),

  /** 配额耗尽（存储配额、调用次数等，由 Lane D 聚合裁决） */
  QUOTA_EXCEEDED(96002, "配额已用尽，请联系管理员"),

  /** 未登录 / 身份缺失（D8：融合平台已移除匿名链路） */
  UNAUTHORIZED(96003, "请先登录"),

  /** 内容风险拦截（合规审核/敏感词/模型安全策略触发） */
  CONTENT_RISK(96004, "内容存在风险，已阻止本次处理"),

  /** 已登录但无权操作该资源（区别于未登录；用于 Actuator 敏感端点、治理接口等） */
  FORBIDDEN(96005, "无权执行该操作"),

  /** 本会话 Token 预算耗尽（区别于存储/次数配额；由 Lane D 预算控制触发） */
  TOKEN_BUDGET_EXCEEDED(96006, "本次会话 Token 预算已用尽"),

  /** 请求参数校验失败（契约层统一语义，避免各域自造 400 文案） */
  VALIDATION(96007, "请求参数不合法"),

  /** 依赖不可用（Qdrant/MinIO/模型服务不可达；用于健康降级与熔断反馈） */
  DEPENDENCY_UNAVAILABLE(96008, "依赖服务暂不可用，请稍后再试"),

  /**
   * 断线续传不可用（§5）：generation 缓冲已淘汰 / 服务重启 / 换实例。 语义：服务端<b>不再</b>重放缓冲事件，客户端应改为拉取已持久化的 {@code
   * ai_message} 渲染， 且<b>不得</b>期待服务端重跑 LLM/检索。
   */
  RESUME_UNAVAILABLE(96009, "会话输出已不在缓冲区，请查看已生成的回答"),

  /** 平台内部错误（兜底；对外脱敏，不透堆栈/SQL） */
  INTERNAL(96010, "服务开小差了，请稍后再试");

  private final Integer code;
  private final String message;

  PlatformErrorCode(Integer code, String message) {
    this.code = code;
    this.message = message;
  }

  /**
   * @return 稳定错误码（用于前端识别与监控告警）
   */
  public Integer getCode() {
    return code;
  }

  /**
   * @return 用户可读提示（直接透出给前端）
   */
  public String getMessage() {
    return message;
  }
}
