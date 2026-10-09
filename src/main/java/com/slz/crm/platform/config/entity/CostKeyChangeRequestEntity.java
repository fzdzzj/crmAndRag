package com.slz.crm.platform.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 成本键申请单实体（add-cost-key-approval-workflow 任务 1.3，表 {@code cost_key_change_request}，Flyway V31 建表）。
 *
 * <p>无 auto-table 注解，表结构由 Flyway V31 管理。
 */
@Data
@TableName("cost_key_change_request")
public class CostKeyChangeRequestEntity {

  /** 主键 ID */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 配置键（点分命名空间，仅限 COST 档位） */
  @TableField("config_key")
  private String configKey;

  /** 期望配置值 */
  @TableField("requested_value")
  private String requestedValue;

  /** 申请理由 */
  private String reason;

  /** 申请状态：PENDING / APPROVED / REJECTED / WITHDRAWN */
  private String status;

  /** 申请人用户 ID */
  @TableField("requester_id")
  private Long requesterId;

  /** 审批人用户 ID（超管） */
  @TableField("approver_id")
  private Long approverId;

  /** 驳回理由 */
  @TableField("reject_reason")
  private String rejectReason;

  /** 申请创建时间 */
  @TableField("created_at")
  private LocalDateTime createdAt;

  /** 决策时间（审批/驳回/撤回） */
  @TableField("decided_at")
  private LocalDateTime decidedAt;

  /** 写入后的配置版本号（审计锚） */
  @TableField("applied_config_version")
  private Long appliedConfigVersion;
}
