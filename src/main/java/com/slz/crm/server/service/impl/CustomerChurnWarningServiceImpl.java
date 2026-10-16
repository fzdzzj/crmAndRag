package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import com.slz.crm.pojo.vo.CustomerChurnWarningVO;
import com.slz.crm.server.constant.CustomerChurnWarningConstant;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import com.slz.crm.server.service.CustomerChurnWarningService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 客户流失预警与再激活任务流服务实现（add-customer-churn-warning-and-reactivation 任务 1.1）。
 *
 * <p>判定为纯规则、零 LLM、零迁移：私海客户（{@code owner_id} 非空）在三源互动信号上近 N 天全部为空即判流失风险—— ①已完成的跟进任务 （{@code
 * contact_task}，以完成时间 {@code update_time} 为互动时间）②商业活动（{@code business_activity}，以 {@code
 * activity_time} 为互动时间）③合同回款进展（{@code payment_record} 经 {@code contract.company_id} 归集，以 {@code
 * create_time} 为互动时间）。三源皆空时以客户创建时间为基线计无互动天数。
 *
 * <p>幂等防重同 {@code ContractWarningServiceImpl#hasInProgressTask}：同客户 + 同负责人 + 同「流失预警」类型存在进行中（status
 * IN (0,1)）任务时跳过，杜绝每日调度重复轰炸销售员。
 */
@Slf4j
@Service
public class CustomerChurnWarningServiceImpl implements CustomerChurnWarningService {

  /** 单次扫描最大候选客户数（防止一次调度拉取过大；与合同预警分批页大小同量级）。 */
  private static final long SCAN_PAGE_SIZE = 500L;

  /** 风险清单默认返回上限。 */
  private static final int DEFAULT_LIST_LIMIT = 200;

  @Autowired private CustomerCompanyMapper customerCompanyMapper;

  @Autowired private ContactTaskMapper contactTaskMapper;

  @Autowired private BusinessActivityMapper businessActivityMapper;

  @Autowired private ContractMapper contractMapper;

  @Autowired private PaymentRecordMapper paymentRecordMapper;

  /** 无互动判定窗口天数（默认 60，可用 {@code crm.customer-churn.inactive-days} 覆盖）。 */
  @Value("${crm.customer-churn.inactive-days:60}")
  private int inactiveDays = 60;

  @Override
  @Transactional(rollbackFor = Exception.class)
  public int scanAndGenerateChurnWarningTasks() {
    int created = 0;
    for (RiskItem risk : detectInactiveCustomers()) {
      if (createTaskIfAbsent(risk)) {
        created++;
      }
    }
    if (created > 0) {
      log.info("客户流失预警待办扫描完成，本次新生成待办 {} 条", created);
    }
    return created;
  }

  @Override
  public List<CustomerChurnWarningVO> queryInactiveCustomers(Integer limit) {
    int safeLimit = limit == null || limit <= 0 ? DEFAULT_LIST_LIMIT : limit;
    List<CustomerChurnWarningVO> result = new ArrayList<>();
    for (RiskItem risk : detectInactiveCustomers()) {
      result.add(
          new CustomerChurnWarningVO(
              risk.companyId(),
              risk.companyName(),
              risk.ownerId(),
              risk.lastInteractionTime(),
              risk.daysSinceLastInteraction(),
              risk.windowDays()));
      if (result.size() >= safeLimit) {
        break;
      }
    }
    return result;
  }

  /**
   * 判定私海客户的流失风险清单（即时计算，零写路径）。
   *
   * @return 三源近 N 天皆空的风险条目（按无互动天数降序）
   */
  private List<RiskItem> detectInactiveCustomers() {
    LocalDateTime now = LocalDateTime.now();
    int windowDays = resolveWindowDays();
    List<CustomerCompanyEntity> candidates = loadCandidateCompanies();
    List<RiskItem> risks = new ArrayList<>();
    if (!candidates.isEmpty()) {
      List<Long> companyIds =
          candidates.stream().map(CustomerCompanyEntity::getId).collect(Collectors.toList());
      Map<Long, LastInteraction> lastInteractions =
          collectLastInteractions(companyIds, now.minusDays(windowDays));
      for (CustomerCompanyEntity company : candidates) {
        LastInteraction last = lastInteractions.get(company.getId());
        if (last == null || !last.withinWindow()) {
          LocalDateTime baseline =
              last == null ? company.getCreateTime() : last.lastInteractionTime();
          Long days = baseline == null ? null : daysBetween(baseline, now);
          risks.add(
              new RiskItem(
                  company.getId(),
                  company.getCompanyName(),
                  company.getOwnerId(),
                  last == null ? null : last.lastInteractionTime(),
                  days,
                  windowDays));
        }
      }
    }
    risks.sort(
        Comparator.comparing(
            RiskItem::daysSinceLastInteraction, Comparator.nullsLast(Comparator.reverseOrder())));
    return risks;
  }

  /** 载入私海候选客户（负责人非空且未删除）。 */
  private List<CustomerCompanyEntity> loadCandidateCompanies() {
    return customerCompanyMapper.selectList(
        new LambdaQueryWrapper<CustomerCompanyEntity>()
            .isNotNull(CustomerCompanyEntity::getOwnerId)
            .eq(CustomerCompanyEntity::getIsDeleted, false)
            .last("limit " + SCAN_PAGE_SIZE));
  }

  /**
   * 归集目标客户三源互动信号的最新一笔互动时间，并标记其是否落在判定窗口内。
   *
   * @param companyIds 目标客户公司 ID 集合
   * @param windowStart 窗口起点（now - N 天）
   * @return 公司 ID → 最新互动（{@code withinWindow=true} 表示近 N 天有互动，即非风险）
   */
  private Map<Long, LastInteraction> collectLastInteractions(
      List<Long> companyIds, LocalDateTime windowStart) {
    Map<Long, LastInteraction> result = new LinkedHashMap<>();
    collectActivities(companyIds, windowStart, result);
    collectPayments(companyIds, windowStart, result);
    collectCompletedTasks(companyIds, windowStart, result);
    return result;
  }

  /** 源 ②：商业活动（互动时间 = activity_time）。 */
  private void collectActivities(
      List<Long> companyIds, LocalDateTime windowStart, Map<Long, LastInteraction> result) {
    List<BusinessActivityEntity> activities =
        businessActivityMapper.selectList(
            new LambdaQueryWrapper<BusinessActivityEntity>()
                .in(BusinessActivityEntity::getCompanyId, companyIds));
    for (BusinessActivityEntity activity : activities) {
      if (activity.getCompanyId() != null) {
        mergeLatest(result, activity.getCompanyId(), activity.getActivityTime(), windowStart);
      }
    }
  }

  /** 源 ③：合同回款进展（payment_record 经 contract.company_id 归集，互动时间 = create_time）。 */
  private void collectPayments(
      List<Long> companyIds, LocalDateTime windowStart, Map<Long, LastInteraction> result) {
    List<ContractEntity> contracts =
        contractMapper.selectList(
            new LambdaQueryWrapper<ContractEntity>().in(ContractEntity::getCompanyId, companyIds));
    Map<Long, List<Long>> contractIdsByCompany = new LinkedHashMap<>();
    for (ContractEntity contract : contracts) {
      Long companyId = contract.getCompanyId();
      Long contractId = contract.getId();
      if (companyId != null && contractId != null) {
        contractIdsByCompany.computeIfAbsent(companyId, key -> new ArrayList<>()).add(contractId);
      }
    }
    List<Long> contractIds =
        contracts.stream().map(ContractEntity::getId).filter(id -> id != null).distinct().toList();
    if (!contractIds.isEmpty()) {
      List<PaymentRecordEntity> payments =
          paymentRecordMapper.selectList(
              new LambdaQueryWrapper<PaymentRecordEntity>()
                  .in(PaymentRecordEntity::getContractId, contractIds));
      for (PaymentRecordEntity payment : payments) {
        Long companyId = findCompanyId(contractIdsByCompany, payment.getContractId());
        if (companyId != null) {
          mergeLatest(result, companyId, payment.getCreateTime(), windowStart);
        }
      }
    }
  }

  /** 源 ①：已完成的跟进任务（status=2，完成时间取 update_time，缺失回退 create_time）。 */
  private void collectCompletedTasks(
      List<Long> companyIds, LocalDateTime windowStart, Map<Long, LastInteraction> result) {
    List<ContactTaskEntity> tasks =
        contactTaskMapper.selectList(
            new LambdaQueryWrapper<ContactTaskEntity>()
                .in(ContactTaskEntity::getCompanyId, companyIds)
                .eq(
                    ContactTaskEntity::getStatus,
                    CustomerChurnWarningConstant.TASK_STATUS_COMPLETED));
    for (ContactTaskEntity task : tasks) {
      LocalDateTime doneAt = toLocalDateTime(task.getUpdateTime(), task.getCreateTime());
      if (task.getCompanyId() != null && doneAt != null) {
        mergeLatest(result, task.getCompanyId(), doneAt, windowStart);
      }
    }
  }

  /** 幂等防重生成待办：同客户 + 同负责人 + 「流失预警」类型无进行中任务时才创建。 */
  private boolean createTaskIfAbsent(RiskItem risk) {
    Long companyId = risk.companyId();
    Long assigneeId = risk.ownerId();
    boolean created = false;
    if (companyId != null && assigneeId != null && !hasInProgressTask(companyId, assigneeId)) {
      ContactTaskEntity task = new ContactTaskEntity();
      task.setTaskTitle(CustomerChurnWarningConstant.TASK_TYPE_CHURN_WARNING);
      task.setTaskType(CustomerChurnWarningConstant.TASK_TYPE_CHURN_WARNING);
      task.setTaskContent(warningContent(risk));
      task.setCompanyId(companyId);
      task.setAssigneeId(assigneeId);
      task.setAssignerId(CustomerChurnWarningConstant.SYSTEM_OPERATOR_ID);
      task.setCreatorId(CustomerChurnWarningConstant.SYSTEM_OPERATOR_ID);
      task.setPriority(CustomerChurnWarningConstant.WARNING_TASK_PRIORITY);
      task.setStatus(CustomerChurnWarningConstant.TASK_STATUS_NOT_STARTED);
      Date now = new Date();
      task.setStartTime(now);
      task.setCreateTime(now);
      task.setUpdateTime(now);
      created = contactTaskMapper.insert(task) > 0;
    }
    return created;
  }

  /** 判定同客户同负责人「流失预警」进行中待办是否已存在（幂等防重核心）。 */
  private boolean hasInProgressTask(Long companyId, Long assigneeId) {
    LambdaQueryWrapper<ContactTaskEntity> wrapper =
        new LambdaQueryWrapper<ContactTaskEntity>()
            .eq(ContactTaskEntity::getCompanyId, companyId)
            .eq(ContactTaskEntity::getAssigneeId, assigneeId)
            .eq(
                ContactTaskEntity::getTaskType,
                CustomerChurnWarningConstant.TASK_TYPE_CHURN_WARNING)
            .in(
                ContactTaskEntity::getStatus,
                List.of(
                    CustomerChurnWarningConstant.TASK_STATUS_NOT_STARTED,
                    CustomerChurnWarningConstant.TASK_STATUS_IN_PROGRESS));
    return contactTaskMapper.selectCount(wrapper) > 0;
  }

  /** 待办描述模板（硬编码）：含引导语，指向客户诊断 → Insight→Action 转化链。 */
  private String warningContent(RiskItem risk) {
    String days =
        risk.daysSinceLastInteraction() == null ? "未知" : risk.daysSinceLastInteraction() + "";
    return String.format(
        "流失预警：客户[%s]近 %d 天无互动（无已完成跟进 / 无商业活动 / 无合同回款进展），距最后一笔互动 %s 天，建议尽快回访。"
            + "回访前先走客户诊断（客户详情 → 客户诊断），基于诊断建议用 Insight→Action 转化链转待办或转活动，再推进商机。",
        risk.companyName(), risk.windowDays(), days);
  }

  private int resolveWindowDays() {
    return inactiveDays <= 0 ? 60 : inactiveDays;
  }

  private Long findCompanyId(Map<Long, List<Long>> contractIdsByCompany, Long contractId) {
    Long companyId = null;
    if (contractId != null) {
      for (Map.Entry<Long, List<Long>> entry : contractIdsByCompany.entrySet()) {
        if (entry.getValue().contains(contractId)) {
          companyId = entry.getKey();
          break;
        }
      }
    }
    return companyId;
  }

  /** 合并单源互动信号：保留最新一笔互动时间，并标记其是否落在判定窗口内。 */
  private void mergeLatest(
      Map<Long, LastInteraction> result,
      Long companyId,
      LocalDateTime candidate,
      LocalDateTime windowStart) {
    if (companyId == null || candidate == null) {
      return;
    }
    boolean withinWindow = !candidate.isBefore(windowStart);
    result.merge(
        companyId,
        new LastInteraction(candidate, withinWindow),
        (left, right) -> {
          LocalDateTime latest =
              right.lastInteractionTime().isAfter(left.lastInteractionTime())
                  ? right.lastInteractionTime()
                  : left.lastInteractionTime();
          return new LastInteraction(latest, left.withinWindow() || right.withinWindow());
        });
  }

  private LocalDateTime toLocalDateTime(Date primary, Date fallback) {
    Date source = primary != null ? primary : fallback;
    return source == null
        ? null
        : LocalDateTime.ofInstant(source.toInstant(), ZoneId.systemDefault());
  }

  private long daysBetween(LocalDateTime start, LocalDateTime end) {
    long days = Duration.between(start, end).toDays();
    return days < 0 ? 0 : days;
  }

  /** 单客户三源最新互动（withinWindow=true 表示近 N 天有互动，即非风险）。 */
  private record LastInteraction(LocalDateTime lastInteractionTime, boolean withinWindow) {}

  /** 风险条目内部载体（companyId + 负责人 + 最后一笔互动时间 + 无互动天数 + 窗口天数）。 */
  private record RiskItem(
      Long companyId,
      String companyName,
      Long ownerId,
      LocalDateTime lastInteractionTime,
      Long daysSinceLastInteraction,
      int windowDays) {}
}
