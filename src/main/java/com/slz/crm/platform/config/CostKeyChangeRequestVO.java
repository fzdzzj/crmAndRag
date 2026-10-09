package com.slz.crm.platform.config;

import java.time.LocalDateTime;

/**
 * 成本键变更申请单视图（add-cost-key-approval-workflow 任务 2.1）。
 *
 * @param id 主键 ID
 * @param configKey 配置键
 * @param requestedValue 期望值
 * @param reason 申请理由
 * @param status 状态
 * @param requesterId 申请人 ID
 * @param approverId 审批人 ID
 * @param rejectReason 驳回理由
 * @param createdAt 创建时间
 * @param decidedAt 决策时间
 * @param appliedConfigVersion 生效版本号
 */
public record CostKeyChangeRequestVO(
    Long id,
    String configKey,
    String requestedValue,
    String reason,
    String status,
    Long requesterId,
    Long approverId,
    String rejectReason,
    LocalDateTime createdAt,
    LocalDateTime decidedAt,
    Long appliedConfigVersion) {}
