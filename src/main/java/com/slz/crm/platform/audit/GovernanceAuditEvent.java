package com.slz.crm.platform.audit;

/**
 * 治理管理动作审计事件。
 *
 * @param eventType 事件类型，如 QUOTA_ADJUSTED、CLEANUP_TRIGGERED
 * @param actorUserRef 操作人 {@code user:<id>}；为空时取当前上下文
 * @param targetType 目标类型
 * @param targetId 目标业务 ID
 * @param action 具体动作
 * @param result 执行结果
 * @param detail 扩展信息；调用方需先脱敏
 */
public record GovernanceAuditEvent(
    String eventType,
    String actorUserRef,
    String targetType,
    String targetId,
    String action,
    GovernanceAuditResult result,
    String detail) {}
