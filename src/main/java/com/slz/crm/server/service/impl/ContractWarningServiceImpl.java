package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.vo.ContractExpiringVO;
import com.slz.crm.pojo.vo.ContractOverduePaymentVO;
import com.slz.crm.server.constant.ContractWarningConstant;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.service.ContractWarningService;
import java.util.Date;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 合同履约到期预警与逾期应收账款待办联动服务实现（add-contract-warning-and-overdue-reminder 任务 2.1/2.2/2.3）。
 *
 * <p>查询走 {@link ContractMapper} 关联统计 SQL；扫描命中后为合同负责人（ownerId）创建高优先级 {@code ContactTask}。 幂等防重
 * （2.3）：同一合同（以 companyId+assigneeId 关联）且相同预警类型存在进行中（status IN (0,1)）任务时跳过——与 proposal 3.2 的
 * contact_task 查询口径一致，杜绝每日定时重复轰炸销售员。
 */
@Slf4j
@Service
public class ContractWarningServiceImpl implements ContractWarningService {

  /** 扫描分批页大小（防止单次拉取过大）。 */
  private static final long SCAN_PAGE_SIZE = 500L;

  @Autowired private ContractMapper contractMapper;

  @Autowired private ContactTaskMapper contactTaskMapper;

  @Override
  public Page<ContractExpiringVO> queryExpiringContracts(
      Integer pageNum, Integer pageSize, Integer days) {
    int safePageNum = pageNum == null || pageNum <= 0 ? 1 : pageNum;
    int safePageSize = pageSize == null || pageSize <= 0 ? 10 : pageSize;
    int safeDays = days == null || days <= 0 ? ContractWarningConstant.DEFAULT_WARNING_DAYS : days;
    return contractMapper.selectExpiringContracts(new Page<>(safePageNum, safePageSize), safeDays);
  }

  @Override
  public Page<ContractOverduePaymentVO> queryOverduePaymentContracts(
      Integer pageNum, Integer pageSize) {
    int safePageNum = pageNum == null || pageNum <= 0 ? 1 : pageNum;
    int safePageSize = pageSize == null || pageSize <= 0 ? 10 : pageSize;
    return contractMapper.selectOverduePaymentContracts(new Page<>(safePageNum, safePageSize));
  }

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int scanAndGenerateWarningTasks() {
    int created = 0;
    created += scanExpiringTasks();
    created += scanOverduePaymentTasks();
    if (created > 0) {
      log.info("合同预警待办扫描完成，本次新生成待办 {} 条", created);
    }
    return created;
  }

  /** 扫描到期/临期合同并为负责人生成「合同续签」待办。 */
  private int scanExpiringTasks() {
    int created = 0;
    long pageNum = 1L;
    while (true) {
      Page<ContractExpiringVO> page =
          contractMapper.selectExpiringContracts(
              new Page<>(pageNum, SCAN_PAGE_SIZE), ContractWarningConstant.DEFAULT_WARNING_DAYS);
      List<ContractExpiringVO> records = page.getRecords();
      if (records.isEmpty()) {
        break;
      }
      for (ContractExpiringVO vo : records) {
        if (createTaskIfAbsent(
            vo.getCompanyId(),
            vo.getOwnerId(),
            vo.getOpportunityId(),
            ContractWarningConstant.TASK_TYPE_CONTRACT_EXPIRING,
            expiringContent(vo))) {
          created++;
        }
      }
      if (records.size() < SCAN_PAGE_SIZE) {
        break;
      }
      pageNum++;
    }
    return created;
  }

  /** 扫描逾期应收账款合同并为负责人生成「回款催收」待办。 */
  private int scanOverduePaymentTasks() {
    int created = 0;
    long pageNum = 1L;
    while (true) {
      Page<ContractOverduePaymentVO> page =
          contractMapper.selectOverduePaymentContracts(new Page<>(pageNum, SCAN_PAGE_SIZE));
      List<ContractOverduePaymentVO> records = page.getRecords();
      if (records.isEmpty()) {
        break;
      }
      for (ContractOverduePaymentVO vo : records) {
        if (createTaskIfAbsent(
            vo.getCompanyId(),
            vo.getOwnerId(),
            vo.getOpportunityId(),
            ContractWarningConstant.TASK_TYPE_OVERDUE_PAYMENT,
            overdueContent(vo))) {
          created++;
        }
      }
      if (records.size() < SCAN_PAGE_SIZE) {
        break;
      }
      pageNum++;
    }
    return created;
  }

  /**
   * 幂等防重生成待办：公司 + 负责人 + 预警类型下无进行中（status IN (0,1)）任务时才创建。
   *
   * @param companyId 客户公司 ID
   * @param assigneeId 负责人 ID
   * @param opportunityId 关联商机 ID（可空）
   * @param taskType 预警类型（合同续签 / 回款催收）
   * @param content 待办内容
   * @return 是否实际创建
   */
  private boolean createTaskIfAbsent(
      Long companyId, Long assigneeId, Long opportunityId, String taskType, String content) {
    boolean created = false;
    if (companyId != null
        && assigneeId != null
        && !hasInProgressTask(companyId, assigneeId, taskType)) {
      ContactTaskEntity task = new ContactTaskEntity();
      task.setTaskTitle(taskType);
      task.setTaskType(taskType);
      task.setTaskContent(content);
      task.setCompanyId(companyId);
      task.setOpportunityId(opportunityId);
      task.setAssigneeId(assigneeId);
      task.setAssignerId(ContractWarningConstant.SYSTEM_OPERATOR_ID);
      task.setCreatorId(ContractWarningConstant.SYSTEM_OPERATOR_ID);
      task.setPriority(ContractWarningConstant.WARNING_TASK_PRIORITY);
      task.setStatus(ContractWarningConstant.TASK_STATUS_NOT_STARTED);
      Date now = new Date();
      task.setStartTime(now);
      task.setCreateTime(now);
      task.setUpdateTime(now);
      created = contactTaskMapper.insert(task) > 0;
    }
    return created;
  }

  /** 判定同公司同类型进行中待办是否已存在（幂等防重核心）。 */
  private boolean hasInProgressTask(Long companyId, Long assigneeId, String taskType) {
    LambdaQueryWrapper<ContactTaskEntity> wrapper =
        new LambdaQueryWrapper<ContactTaskEntity>()
            .eq(ContactTaskEntity::getCompanyId, companyId)
            .eq(ContactTaskEntity::getAssigneeId, assigneeId)
            .eq(ContactTaskEntity::getTaskType, taskType)
            .in(
                ContactTaskEntity::getStatus,
                List.of(
                    ContractWarningConstant.TASK_STATUS_NOT_STARTED,
                    ContractWarningConstant.TASK_STATUS_IN_PROGRESS));
    return contactTaskMapper.selectCount(wrapper) > 0;
  }

  private String expiringContent(ContractExpiringVO vo) {
    return String.format(
        "合同[%s](编号:%s) 剩余 %d 天（已超期请立即跟进续签）。合同总额 %s，未结回款 %s，请及时安排续签与回款确认。",
        vo.getContractName(),
        vo.getContractNo(),
        vo.getRemainDays() == null ? 0 : vo.getRemainDays(),
        vo.getTotalAmount(),
        vo.getRemainingAmount());
  }

  private String overdueContent(ContractOverduePaymentVO vo) {
    return String.format(
        "合同[%s](编号:%s) 存在逾期应收账款，未结回款 %s（合同总额 %s 已回款 %s），请及时催款。生效已超过 %d 天或距到期不足 %d 天。",
        vo.getContractName(),
        vo.getContractNo(),
        vo.getRemainingAmount(),
        vo.getTotalAmount(),
        vo.getPaidAmount(),
        ContractWarningConstant.OVERDUE_START_DAYS,
        ContractWarningConstant.OVERDUE_END_WINDOW_DAYS);
  }
}
