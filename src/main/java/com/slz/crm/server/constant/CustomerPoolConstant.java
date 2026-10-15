package com.slz.crm.server.constant;

/**
 * 客户公海池常量（add-customer-public-sea-pool 任务 2.x）。 定义公海池状态、流转动作类型字符串与自动回收规则阈值，供 {@code
 * CustomerPoolServiceImpl} / {@code CustomerPoolRecycleScheduler} 复用。
 */
public final class CustomerPoolConstant {

  private CustomerPoolConstant() {}

  /** 公海状态 - 私海客户 */
  public static final int POOL_STATUS_PRIVATE = 0;

  /** 公海状态 - 公海池客户 */
  public static final int POOL_STATUS_PUBLIC = 1;

  /** 流转动作 - 业务员自主认领 */
  public static final String ACTION_CLAIM = "CLAIM";

  /** 流转动作 - 业务员主动退回公海 */
  public static final String ACTION_RELEASE = "RELEASE";

  /** 流转动作 - 主管分派 */
  public static final String ACTION_ASSIGN = "ASSIGN";

  /** 流转动作 - 系统超时自动回收 */
  public static final String ACTION_AUTO_RECYCLE = "AUTO_RECYCLE";

  /** 系统自动回收操作人 ID（customer_pool_log.operator_id） */
  public static final long SYSTEM_OPERATOR_ID = 0L;

  /** 系统超时自动回收原因文案 */
  public static final String REASON_AUTO_RECYCLE = "超时未跟进系统自动回收";

  /** 私海客户保有量配额上限（默认 50，满额禁止再认领/分派） */
  public static final int PRIVATE_POOL_QUOTA = 50;

  /** 超时回收阈值：有跟进记录的私海客户，last_activity_time 距今超过该天数自动回收 */
  public static final int RECYCLE_DAYS_FOLLOWED = 30;

  /** 超时回收阈值：无跟进记录（last_activity_time 为 NULL）的私海客户，create_time 距今超过该天数自动回收 */
  public static final int RECYCLE_DAYS_NO_FOLLOW = 15;
}
