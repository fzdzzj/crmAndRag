package com.slz.crm.pojo.vo;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 客户流失风险条目 VO（add-customer-churn-warning-and-reactivation 任务 2.1）。
 *
 * <p>风险即时计算不落库，故以 VO 承载「客户 + 负责人 + 三源中最后一笔互动时间 + 距今无互动天数」。 三源（已完成跟进任务 / 商业活动 / 合同回款进展）全部为空时 {@code
 * lastInteractionTime} 为 null，{@code daysSinceLastInteraction} 表示客户创建至今的天数。
 */
@Data
public class CustomerChurnWarningVO {

  /** 客户公司 ID。 */
  private Long companyId;

  /** 客户公司名称。 */
  private String companyName;

  /** 客户负责人（待办接收人）ID。 */
  private Long ownerId;

  /** 三源中最后一笔互动时间（三源皆空时为 null）。 */
  private LocalDateTime lastInteractionTime;

  /** 距今无互动天数（三源皆空时为客户创建至今天数）。 */
  private Long daysSinceLastInteraction;

  /** 风险判定窗口天数（近 N 天无互动）。 */
  private Integer windowDays;

  /**
   * 构造风险条目。
   *
   * @param companyId 客户公司 ID
   * @param companyName 客户公司名称
   * @param ownerId 负责人 ID
   * @param lastInteractionTime 最后一笔互动时间（可空）
   * @param daysSinceLastInteraction 距今无互动天数
   * @param windowDays 判定窗口天数
   */
  public CustomerChurnWarningVO(
      Long companyId,
      String companyName,
      Long ownerId,
      LocalDateTime lastInteractionTime,
      Long daysSinceLastInteraction,
      Integer windowDays) {
    this.companyId = companyId;
    this.companyName = companyName;
    this.ownerId = ownerId;
    this.lastInteractionTime = lastInteractionTime;
    this.daysSinceLastInteraction = daysSinceLastInteraction;
    this.windowDays = windowDays;
  }
}
