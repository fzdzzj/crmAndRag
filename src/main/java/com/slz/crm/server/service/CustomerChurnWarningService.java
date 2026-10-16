package com.slz.crm.server.service;

import com.slz.crm.pojo.vo.CustomerChurnWarningVO;
import java.util.List;

/**
 * 客户流失预警与再激活任务流服务（add-customer-churn-warning-and-reactivation 任务 1.1）。
 *
 * <p>零 LLM、零迁移的确定性规则侧漏斗：扫描私海客户（{@code owner_id} 非空），判定近 N 天（{@code
 * crm.customer-churn.inactive-days}，默认 60）三源互动信号是否全部为空——无已完成跟进任务（{@code contact_task}）、无商业活动（{@code
 * business_activity}）、无合同回款进展（{@code payment_record} 经 {@code contract} 关联）——命中即为流失风险；为负责人幂等创建高优先级
 * {@code ContactTask}（严格防重：同客户同负责人同「流失预警」类型存在进行中任务时跳过）。
 *
 * <p>风险为即时计算、不落库（无新列新表），不改客户归属、不触碰公海回池与客户诊断本体。三源查询限私海客户， 生产环境仍会被 {@code QueryWrapperAspect}
 * 数据权限切面叠加过滤（纵深防御）。
 */
public interface CustomerChurnWarningService {

  /**
   * 扫描三源互动信号为空（近 N 天无任何互动）的私海客户，并为负责人生成高优先级「流失预警」待办。
   *
   * @return 本次实际新创建的待办数量
   */
  int scanAndGenerateChurnWarningTasks();

  /**
   * 查询当前流失风险客户清单（即时计算，不落库、不建待办）。
   *
   * @param limit 最多返回条数（null 或非正数时取默认上限）
   * @return 风险清单（公司、负责人、距今无互动天数与最后一笔互动时间）
   */
  List<CustomerChurnWarningVO> queryInactiveCustomers(Integer limit);
}
