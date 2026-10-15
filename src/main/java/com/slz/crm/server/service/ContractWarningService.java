package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.vo.ContractExpiringVO;
import com.slz.crm.pojo.vo.ContractOverduePaymentVO;

/**
 * 合同履约到期预警与逾期应收账款待办联动服务（add-contract-warning-and-overdue-reminder 任务 2.1）。
 *
 * <p>职责：到期/临期合同分页查询、逾期应收账款分页查询，以及自动化扫描并为合同负责人生成 {@code ContactTask} 待办（严格幂等防重：同合同同预警类型进行中任务存在时跳过）。
 */
public interface ContractWarningService {

  /**
   * 分页查询到期/临期合同（contract_status=1 且未来 {@code days} 天内到期或已超期）。
   *
   * @param pageNum 页码
   * @param pageSize 每页条数
   * @param days 预警窗口天数（null 用默认 30）
   * @return 到期/临期合同分页 VO
   */
  Page<ContractExpiringVO> queryExpiringContracts(Integer pageNum, Integer pageSize, Integer days);

  /**
   * 分页查询逾期应收账款合同（未结敞口&gt;0 且 生效超 30 天 或 距到期不足 15 天）。
   *
   * @param pageNum 页码
   * @param pageSize 每页条数
   * @return 逾期应收账款合同分页 VO
   */
  Page<ContractOverduePaymentVO> queryOverduePaymentContracts(Integer pageNum, Integer pageSize);

  /**
   * 自动化扫描并生成预警待办（add-contract-warning-and-overdue-reminder 任务 2.2/2.3）。
   *
   * <p>扫描到期/临期与逾期应收账款合同，为每份合同负责人（ownerId）生成 {@code ContactTask} 高优先级待办； 同一合同且相同预警类型存在进行中（status IN
   * (0,1)）任务时跳过，杜绝重复创建。
   *
   * @return 本次实际新创建的任务数
   */
  int scanAndGenerateWarningTasks();
}
