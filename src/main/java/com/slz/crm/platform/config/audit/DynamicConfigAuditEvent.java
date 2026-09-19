package com.slz.crm.platform.config.audit;

import java.time.LocalDateTime;

/**
 * 动态配置变更审计事件（谁、何时、改了哪一项、旧值→新值）。
 *
 * <p>敏感值在进入事件前已由服务层掩码（与静态配置同等的保护要求）， 审计落盘/日志不得泄露明文。
 */
public record DynamicConfigAuditEvent(
    /** 操作人跨域引用 user:&lt;id&gt; */
    String operatorRef,
    /** 操作人可读姓名（可能为空） */
    String operatorName,
    /** 配置键 */
    String key,
    /** 命名空间 */
    String namespace,
    /** 操作类型：CREATE/UPDATE/ROLLBACK/DELETE/REVIVE */
    String operationType,
    /** 变更前值（已按敏感度掩码） */
    String oldValue,
    /** 变更后值（已按敏感度掩码） */
    String newValue,
    /** 操作备注 */
    String remark,
    /** 发生时间 */
    LocalDateTime occurredAt) {}
