package com.slz.crm.platform.config;

/**
 * 成本键申请单状态枚举（add-cost-key-approval-workflow 任务 2.3）。
 *
 * <p>四态封闭集：PENDING -> APPROVED | REJECTED | WITHDRAWN，终态不可逆。
 */
public enum CostKeyChangeRequestStatus {
  /** 待审批 */
  PENDING,
  /** 已审批（已生效写入配置） */
  APPROVED,
  /** 已驳回 */
  REJECTED,
  /** 已撤回（仅本人可撤回） */
  WITHDRAWN
}
