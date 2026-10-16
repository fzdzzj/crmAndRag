package com.slz.crm.server.constant;

/**
 * 客户流失预警与再激活任务流常量（add-customer-churn-warning-and-reactivation 任务 1.1）。
 *
 * <p>集中承载流失预警待办类型、优先级与任务状态口径，避免魔法值散落；与 {@link ContractWarningConstant} 同型（合同预警 = 到期/回款，客户预警 =
 * 私海客户长期无互动）。
 */
public final class CustomerChurnWarningConstant {

  /** 流失预警待办类型（与 proposal 第 1 节约定一致）。 */
  public static final String TASK_TYPE_CHURN_WARNING = "流失预警";

  /** 自动派发待办优先级（0-9 低到高，流失预警取高优先级）。 */
  public static final int WARNING_TASK_PRIORITY = 7;

  /** 系统指派标识（operatorId=0，与客户公海自动回收 / 合同预警口径一致）。 */
  public static final long SYSTEM_OPERATOR_ID = 0L;

  /** 任务状态：未开始。 */
  public static final int TASK_STATUS_NOT_STARTED = 0;

  /** 任务状态：进行中。 */
  public static final int TASK_STATUS_IN_PROGRESS = 1;

  /** 任务状态：已完成（唯一计入「已跟进」互动信号的终态）。 */
  public static final int TASK_STATUS_COMPLETED = 2;

  private CustomerChurnWarningConstant() {}
}
