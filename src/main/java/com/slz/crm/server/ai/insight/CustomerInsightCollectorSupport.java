package com.slz.crm.server.ai.insight;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.CustomerContactEntity;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.CustomerContactMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 客户 360° 多实体数据聚合（add-ai-customer-360-insight 任务 1.1）。
 *
 * <p>在权限边界内聚合客户基础画像、跟进活动、商机与阶段审批、合同与回款、高优待办与联系人覆盖五维快照。 纯只读，零写路径。 生产环境的 mapper 查询仍会被 {@code
 * QueryWrapperAspect} 数据权限切面叠加过滤（纵深防御）， 公司级授权由调用方（ {@link CustomerInsightService}）经 {@code
 * BusinessRecordAccessService} 守卫先行校验。
 */
@Component
@RequiredArgsConstructor
public class CustomerInsightCollectorSupport {

  /** 关键决策人联系人的关系等级下限（relationLevel 1~9，越高越关键）。 */
  static final int KEY_CONTACT_MIN_RELATION = 7;

  /** 高优任务优先级下限（0~9 低到高）。 */
  static final int HIGH_PRIORITY_THRESHOLD = 7;

  /** 商机关闭阶段编号（5=关闭，不再统计阶段停滞）。 */
  static final int OPPORTUNITY_CLOSED_STAGE = 5;

  /** 合同已弃用状态（不计入履约统计）。 */
  static final int CONTRACT_ABANDONED_STATUS = 4;

  /** 商机阶段审批：拒绝 / 退回修改（均属推进摩擦）。 */
  static final int APPROVAL_REJECTED = 2;

  static final int APPROVAL_REVISION = 3;

  /** 回款状态：已确认。 */
  static final int PAYMENT_CONFIRMED = 0;

  /** 回款状态：待确认。 */
  static final int PAYMENT_PENDING = 1;

  /** 回款状态：已作废（统计时排除）。 */
  static final int PAYMENT_VOID = 2;

  /** 任务状态：已完成 / 已取消（开放待办统计时排除）。 */
  static final int TASK_COMPLETED = 2;

  static final int TASK_CANCELLED = 3;

  private final BusinessActivityMapper businessActivityMapper;
  private final SalesOpportunityMapper salesOpportunityMapper;
  private final SalesStageApprovalMapper salesStageApprovalMapper;
  private final ContractMapper contractMapper;
  private final PaymentRecordMapper paymentRecordMapper;
  private final ContactTaskMapper contactTaskMapper;
  private final CustomerContactMapper customerContactMapper;

  /**
   * 聚合目标客户的多维业务快照。
   *
   * @param company 已通过数据权限守卫的客户公司实体
   * @return 五维快照（无任何业务表写入）
   */
  public CustomerInsightSnapshot collect(CustomerCompanyEntity company) {
    LocalDateTime now = LocalDateTime.now();
    Long companyId = company.getId();
    List<BusinessActivityEntity> activities = loadActivities(companyId);
    List<SalesOpportunityEntity> opportunities = loadOpportunities(companyId);
    List<SalesStageApprovalEntity> approvals = loadApprovals(opportunities);
    List<ContractEntity> contracts = loadContracts(companyId);
    List<PaymentRecordEntity> payments = loadPayments(contracts);
    List<ContactTaskEntity> tasks = loadTasks(companyId);
    List<CustomerContactEntity> contacts = loadContacts(companyId);

    return new CustomerInsightSnapshot(
        companyId,
        company.getCompanyName(),
        company.getIndustry(),
        company.getStatus(),
        company.getOwnerId(),
        company.getCreatorId(),
        buildActivityFacts(activities, now),
        buildOpportunityFacts(opportunities, approvals, now),
        buildContractPaymentFacts(contracts, payments, now),
        buildTaskContactFacts(tasks, contacts, now));
  }

  private List<BusinessActivityEntity> loadActivities(Long companyId) {
    return businessActivityMapper.selectList(
        new LambdaQueryWrapper<BusinessActivityEntity>()
            .eq(BusinessActivityEntity::getCompanyId, companyId));
  }

  private List<SalesOpportunityEntity> loadOpportunities(Long companyId) {
    return salesOpportunityMapper.selectList(
        new LambdaQueryWrapper<SalesOpportunityEntity>()
            .eq(SalesOpportunityEntity::getCompanyId, companyId)
            .eq(SalesOpportunityEntity::getIsDeleted, false));
  }

  private List<SalesStageApprovalEntity> loadApprovals(List<SalesOpportunityEntity> opportunities) {
    List<SalesStageApprovalEntity> result = new ArrayList<>();
    if (!opportunities.isEmpty()) {
      List<Long> ids =
          opportunities.stream().map(SalesOpportunityEntity::getId).collect(Collectors.toList());
      result =
          salesStageApprovalMapper.selectList(
              new LambdaQueryWrapper<SalesStageApprovalEntity>()
                  .in(SalesStageApprovalEntity::getOpportunityId, ids));
    }
    return result;
  }

  private List<ContractEntity> loadContracts(Long companyId) {
    return contractMapper.selectList(
        new LambdaQueryWrapper<ContractEntity>()
            .eq(ContractEntity::getCompanyId, companyId)
            .ne(ContractEntity::getContractStatus, CONTRACT_ABANDONED_STATUS));
  }

  private List<PaymentRecordEntity> loadPayments(List<ContractEntity> contracts) {
    List<PaymentRecordEntity> result = new ArrayList<>();
    if (!contracts.isEmpty()) {
      List<Long> ids = contracts.stream().map(ContractEntity::getId).collect(Collectors.toList());
      result =
          paymentRecordMapper.selectList(
              new LambdaQueryWrapper<PaymentRecordEntity>()
                  .in(PaymentRecordEntity::getContractId, ids)
                  .ne(PaymentRecordEntity::getPaymentStatus, PAYMENT_VOID));
    }
    return result;
  }

  private List<ContactTaskEntity> loadTasks(Long companyId) {
    return contactTaskMapper.selectList(
        new LambdaQueryWrapper<ContactTaskEntity>()
            .eq(ContactTaskEntity::getCompanyId, companyId)
            .ne(ContactTaskEntity::getStatus, TASK_COMPLETED)
            .ne(ContactTaskEntity::getStatus, TASK_CANCELLED));
  }

  private List<CustomerContactEntity> loadContacts(Long companyId) {
    return customerContactMapper.selectList(
        new LambdaQueryWrapper<CustomerContactEntity>()
            .eq(CustomerContactEntity::getCompanyId, companyId)
            .eq(CustomerContactEntity::getIsDeleted, false));
  }

  private CustomerInsightSnapshot.ActivityFacts buildActivityFacts(
      List<BusinessActivityEntity> activities, LocalDateTime now) {
    int count = activities.size();
    int countLast30Days = 0;
    LocalDateTime lastActivityTime = null;
    String lastActivityTitle = "";
    LocalDateTime windowStart = now.minusDays(30);
    for (BusinessActivityEntity activity : activities) {
      LocalDateTime time = activity.getActivityTime();
      if (time != null) {
        if (!time.isBefore(windowStart)) {
          countLast30Days++;
        }
        if (lastActivityTime == null || time.isAfter(lastActivityTime)) {
          lastActivityTime = time;
          lastActivityTitle = activity.getActivityTitle();
        }
      }
    }
    long daysSinceLastActivity =
        lastActivityTime == null ? -1L : daysBetween(lastActivityTime, now);
    return new CustomerInsightSnapshot.ActivityFacts(
        count, countLast30Days, lastActivityTime, daysSinceLastActivity, lastActivityTitle);
  }

  private CustomerInsightSnapshot.OpportunityFacts buildOpportunityFacts(
      List<SalesOpportunityEntity> opportunities,
      List<SalesStageApprovalEntity> approvals,
      LocalDateTime now) {
    int count = opportunities.size();
    BigDecimal totalAmount = BigDecimal.ZERO;
    int maxStagnationDays = 0;
    for (SalesOpportunityEntity opportunity : opportunities) {
      if (opportunity.getAmount() != null) {
        totalAmount = totalAmount.add(opportunity.getAmount());
      }
      Integer stage = opportunity.getStage();
      if (stage == null || stage < OPPORTUNITY_CLOSED_STAGE) {
        LocalDateTime anchor =
            opportunity.getUpdateTime() != null
                ? opportunity.getUpdateTime()
                : opportunity.getCreateTime();
        if (anchor != null) {
          maxStagnationDays = Math.max(maxStagnationDays, (int) daysBetween(anchor, now));
        }
      }
    }
    int approvalRejectedCount = 0;
    for (SalesStageApprovalEntity approval : approvals) {
      Integer status = approval.getApprovalStatus();
      if (status != null && (status == APPROVAL_REJECTED || status == APPROVAL_REVISION)) {
        approvalRejectedCount++;
      }
    }
    return new CustomerInsightSnapshot.OpportunityFacts(
        count, totalAmount, maxStagnationDays, approvalRejectedCount);
  }

  private CustomerInsightSnapshot.ContractPaymentFacts buildContractPaymentFacts(
      List<ContractEntity> contracts, List<PaymentRecordEntity> payments, LocalDateTime now) {
    int contractCount = contracts.size();
    BigDecimal contractTotalAmount = BigDecimal.ZERO;
    BigDecimal paidAmount = BigDecimal.ZERO;
    BigDecimal pendingPaymentAmount = BigDecimal.ZERO;
    int unpaidContractCount = 0;
    BigDecimal overdueUnpaidAmount = BigDecimal.ZERO;

    for (ContractEntity contract : contracts) {
      if (contract.getTotalAmount() != null) {
        contractTotalAmount = contractTotalAmount.add(contract.getTotalAmount());
      }
    }
    java.util.Map<Long, BigDecimal> paidByContract = new java.util.HashMap<>();
    for (PaymentRecordEntity payment : payments) {
      BigDecimal amount = payment.getPaymentAmount();
      if (amount == null) {
        continue;
      }
      Integer status = payment.getPaymentStatus();
      if (status != null && status == PAYMENT_CONFIRMED) {
        paidAmount = paidAmount.add(amount);
        paidByContract.merge(payment.getContractId(), amount, BigDecimal::add);
      } else if (status != null && status == PAYMENT_PENDING) {
        pendingPaymentAmount = pendingPaymentAmount.add(amount);
      }
    }
    for (ContractEntity contract : contracts) {
      BigDecimal total = contract.getTotalAmount();
      if (total == null) {
        continue;
      }
      BigDecimal paid = paidByContract.getOrDefault(contract.getId(), BigDecimal.ZERO);
      BigDecimal outstanding = total.subtract(paid);
      if (outstanding.signum() > 0) {
        unpaidContractCount++;
        if (contract.getEndDate() != null && contract.getEndDate().isBefore(now)) {
          overdueUnpaidAmount = overdueUnpaidAmount.add(outstanding);
        }
      }
    }
    return new CustomerInsightSnapshot.ContractPaymentFacts(
        contractCount,
        contractTotalAmount,
        paidAmount,
        pendingPaymentAmount,
        overdueUnpaidAmount,
        unpaidContractCount);
  }

  private CustomerInsightSnapshot.TaskContactFacts buildTaskContactFacts(
      List<ContactTaskEntity> tasks, List<CustomerContactEntity> contacts, LocalDateTime now) {
    Date nowDate = Date.from(now.atZone(java.time.ZoneId.systemDefault()).toInstant());
    int highPriorityOpenTaskCount = 0;
    int overdueHighPriorityTaskCount = 0;
    for (ContactTaskEntity task : tasks) {
      Integer priority = task.getPriority();
      if (priority != null && priority >= HIGH_PRIORITY_THRESHOLD) {
        highPriorityOpenTaskCount++;
        if (task.getEndTime() != null && task.getEndTime().before(nowDate)) {
          overdueHighPriorityTaskCount++;
        }
      }
    }
    int contactCount = contacts.size();
    int keyContactCount = 0;
    for (CustomerContactEntity contact : contacts) {
      Integer relationLevel = contact.getRelationLevel();
      if (relationLevel != null && relationLevel >= KEY_CONTACT_MIN_RELATION) {
        keyContactCount++;
      }
    }
    return new CustomerInsightSnapshot.TaskContactFacts(
        highPriorityOpenTaskCount, overdueHighPriorityTaskCount, contactCount, keyContactCount);
  }

  private long daysBetween(LocalDateTime start, LocalDateTime end) {
    long days = java.time.Duration.between(start, end).toDays();
    return days < 0 ? 0 : days;
  }
}
