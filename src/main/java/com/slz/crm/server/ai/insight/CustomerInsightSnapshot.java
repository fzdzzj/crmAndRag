package com.slz.crm.server.ai.insight;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 客户 360° 多维业务快照（add-ai-customer-360-insight 任务 1.1）。
 *
 * <p>由 {@link CustomerInsightCollectorSupport} 在权限边界内实时聚合生成，是规则引擎与 LLM 洞察 的唯一数据输入，不含任何写路径。
 *
 * @param companyId 客户公司 ID
 * @param companyName 客户公司名称
 * @param industry 所属行业
 * @param status 客户当前状态
 * @param ownerId 跟进负责人 ID
 * @param creatorId 客户创建人 ID
 * @param activity 跟进活跃度事实
 * @param opportunity 商机健康度事实
 * @param contractPayment 合同回款履约事实
 * @param taskContact 待办任务与联系人覆盖事实
 */
public record CustomerInsightSnapshot(
    Long companyId,
    String companyName,
    String industry,
    String status,
    Long ownerId,
    Long creatorId,
    ActivityFacts activity,
    OpportunityFacts opportunity,
    ContractPaymentFacts contractPayment,
    TaskContactFacts taskContact) {

  /** 跟进活跃度事实。 */
  public record ActivityFacts(
      int count,
      int countLast30Days,
      LocalDateTime lastActivityTime,
      long daysSinceLastActivity,
      String lastActivityTitle) {

    public boolean hasActivity() {
      return lastActivityTime != null;
    }
  }

  /** 商机健康度事实。 */
  public record OpportunityFacts(
      int count, BigDecimal totalAmount, int maxStageStagnationDays, int approvalRejectedCount) {}

  /** 合同回款履约事实。 */
  public record ContractPaymentFacts(
      int contractCount,
      BigDecimal contractTotalAmount,
      BigDecimal paidAmount,
      BigDecimal pendingPaymentAmount,
      BigDecimal overdueUnpaidAmount,
      int unpaidContractCount) {}

  /** 待办任务与联系人覆盖事实。 */
  public record TaskContactFacts(
      int highPriorityOpenTaskCount,
      int overdueHighPriorityTaskCount,
      int contactCount,
      int keyContactCount) {}
}
