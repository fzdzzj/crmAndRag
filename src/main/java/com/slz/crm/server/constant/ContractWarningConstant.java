package com.slz.crm.server.constant;

/**
 * 合同履约到期预警与逾期应收账款待办联动常量（add-contract-warning-and-overdue-reminder 任务 2.x）。
 *
 * <p>集中承载预警扫描窗口、任务类型、优先级与系统指派标识，避免魔法值散落。
 */
public final class ContractWarningConstant {

  /** 到期预警默认窗口天数：未来 N 天内到期或已超期视为命中。 */
  public static final int DEFAULT_WARNING_DAYS = 30;

  /** 逾期应收账款判定：生效超过的天数。 */
  public static final int OVERDUE_START_DAYS = 30;

  /** 逾期应收账款判定：距到期不足的天数。 */
  public static final int OVERDUE_END_WINDOW_DAYS = 15;

  /** 合同到期预警待办类型（与 proposal 3.2 约定一致）。 */
  public static final String TASK_TYPE_CONTRACT_EXPIRING = "合同续签";

  /** 逾期应收账款催收待办类型（与 proposal 3.2 约定一致）。 */
  public static final String TASK_TYPE_OVERDUE_PAYMENT = "回款催收";

  /** 自动派发待办优先级（0-9 低到高，预警/催收取高优先级）。 */
  public static final int WARNING_TASK_PRIORITY = 7;

  /** 系统指派标识（operatorId=0，与客户公海自动回收口径一致）。 */
  public static final long SYSTEM_OPERATOR_ID = 0L;

  /** 任务状态：未开始。 */
  public static final int TASK_STATUS_NOT_STARTED = 0;

  /** 任务状态：进行中。 */
  public static final int TASK_STATUS_IN_PROGRESS = 1;

  private ContractWarningConstant() {}
}
