package com.slz.crm.server.ai.insight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

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
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** 客户 360° 多实体聚合单测（add-ai-customer-360-insight 任务 5.1）：验证跟进活跃度、商机停滞、 回款履约与待办/联系人覆盖的快照确定性聚合。 */
@ExtendWith(MockitoExtension.class)
class CustomerInsightCollectorSupportTest {

  @Mock private BusinessActivityMapper businessActivityMapper;
  @Mock private SalesOpportunityMapper salesOpportunityMapper;
  @Mock private SalesStageApprovalMapper salesStageApprovalMapper;
  @Mock private ContractMapper contractMapper;
  @Mock private PaymentRecordMapper paymentRecordMapper;
  @Mock private ContactTaskMapper contactTaskMapper;
  @Mock private CustomerContactMapper customerContactMapper;

  private CustomerInsightCollectorSupport collector;

  @BeforeEach
  void setUp() {
    collector =
        new CustomerInsightCollectorSupport(
            businessActivityMapper,
            salesOpportunityMapper,
            salesStageApprovalMapper,
            contractMapper,
            paymentRecordMapper,
            contactTaskMapper,
            customerContactMapper);
  }

  @Test
  void collect_aggregatesFiveDimensionsDeterministically() {
    CustomerCompanyEntity company = new CustomerCompanyEntity();
    company.setId(1001L);
    company.setCompanyName("北京测试科技有限公司");
    company.setIndustry("软件");
    company.setStatus("正常");
    company.setOwnerId(7L);
    company.setCreatorId(9L);

    LocalDateTime now = LocalDateTime.now();
    BusinessActivityEntity recent = new BusinessActivityEntity();
    recent.setId(1L);
    recent.setActivityTitle("产品演示会");
    recent.setActivityTime(now.minusDays(5));
    BusinessActivityEntity older = new BusinessActivityEntity();
    older.setId(2L);
    older.setActivityTitle("电话沟通");
    older.setActivityTime(now.minusDays(40));
    when(businessActivityMapper.selectList(any())).thenReturn(List.of(recent, older));

    SalesOpportunityEntity opportunity = new SalesOpportunityEntity();
    opportunity.setId(50L);
    opportunity.setCompanyId(1001L);
    opportunity.setStage(2);
    opportunity.setAmount(new BigDecimal("100000"));
    opportunity.setUpdateTime(now.minusDays(40));
    when(salesOpportunityMapper.selectList(any())).thenReturn(List.of(opportunity));

    SalesStageApprovalEntity approval = new SalesStageApprovalEntity();
    approval.setId(1L);
    approval.setOpportunityId(50L);
    approval.setApprovalStatus(2);
    when(salesStageApprovalMapper.selectList(any())).thenReturn(List.of(approval));

    ContractEntity contract = new ContractEntity();
    contract.setId(30L);
    contract.setCompanyId(1001L);
    contract.setContractStatus(1);
    contract.setTotalAmount(new BigDecimal("10000"));
    contract.setEndDate(now.minusDays(10));
    when(contractMapper.selectList(any())).thenReturn(List.of(contract));

    PaymentRecordEntity confirmed = new PaymentRecordEntity();
    confirmed.setContractId(30L);
    confirmed.setPaymentAmount(new BigDecimal("4000"));
    confirmed.setPaymentStatus(0);
    PaymentRecordEntity pending = new PaymentRecordEntity();
    pending.setContractId(30L);
    pending.setPaymentAmount(new BigDecimal("1000"));
    pending.setPaymentStatus(1);
    when(paymentRecordMapper.selectList(any())).thenReturn(List.of(confirmed, pending));

    ContactTaskEntity highTask = new ContactTaskEntity();
    highTask.setCompanyId(1001L);
    highTask.setPriority(8);
    highTask.setStatus(1);
    highTask.setEndTime(
        Date.from(now.minusDays(3).atZone(java.time.ZoneId.systemDefault()).toInstant()));
    when(contactTaskMapper.selectList(any())).thenReturn(List.of(highTask));

    CustomerContactEntity keyContact = new CustomerContactEntity();
    keyContact.setCompanyId(1001L);
    keyContact.setRelationLevel(8);
    CustomerContactEntity normalContact = new CustomerContactEntity();
    normalContact.setCompanyId(1001L);
    normalContact.setRelationLevel(3);
    when(customerContactMapper.selectList(any())).thenReturn(List.of(keyContact, normalContact));

    CustomerInsightSnapshot snapshot = collector.collect(company);

    assertThat(snapshot.companyId()).isEqualTo(1001L);
    assertThat(snapshot.companyName()).isEqualTo("北京测试科技有限公司");
    // 跟进活跃度：2 条活动，近 30 天 1 条，最后跟进 5 天前，标题取最近一条
    assertThat(snapshot.activity().count()).isEqualTo(2);
    assertThat(snapshot.activity().countLast30Days()).isEqualTo(1);
    assertThat(snapshot.activity().lastActivityTitle()).isEqualTo("产品演示会");
    assertThat(snapshot.activity().daysSinceLastActivity()).isEqualTo(5);
    // 商机健康度：1 条商机、金额 100000、停滞 40 天、审批驳回 1 次
    assertThat(snapshot.opportunity().count()).isEqualTo(1);
    assertThat(snapshot.opportunity().totalAmount()).isEqualByComparingTo("100000");
    assertThat(snapshot.opportunity().maxStageStagnationDays()).isEqualTo(40);
    assertThat(snapshot.opportunity().approvalRejectedCount()).isEqualTo(1);
    // 合同回款履约：已确认 4000、待确认 1000、逾期未回款 6000、未结清合同 1 份
    assertThat(snapshot.contractPayment().contractCount()).isEqualTo(1);
    assertThat(snapshot.contractPayment().paidAmount()).isEqualByComparingTo("4000");
    assertThat(snapshot.contractPayment().pendingPaymentAmount()).isEqualByComparingTo("1000");
    assertThat(snapshot.contractPayment().overdueUnpaidAmount()).isEqualByComparingTo("6000");
    assertThat(snapshot.contractPayment().unpaidContractCount()).isEqualTo(1);
    // 待办与联系人：高优进行中 1、超期 1、联系人 2、关键决策人 1
    assertThat(snapshot.taskContact().highPriorityOpenTaskCount()).isEqualTo(1);
    assertThat(snapshot.taskContact().overdueHighPriorityTaskCount()).isEqualTo(1);
    assertThat(snapshot.taskContact().contactCount()).isEqualTo(2);
    assertThat(snapshot.taskContact().keyContactCount()).isEqualTo(1);
  }

  @Test
  void collect_emptyCompany_returnsNeutralFacts() {
    CustomerCompanyEntity company = new CustomerCompanyEntity();
    company.setId(2001L);
    company.setCompanyName("空客户");
    when(businessActivityMapper.selectList(any())).thenReturn(List.of());
    when(salesOpportunityMapper.selectList(any())).thenReturn(List.of());
    when(contractMapper.selectList(any())).thenReturn(List.of());
    when(contactTaskMapper.selectList(any())).thenReturn(List.of());
    when(customerContactMapper.selectList(any())).thenReturn(List.of());

    CustomerInsightSnapshot snapshot = collector.collect(company);

    assertThat(snapshot.activity().count()).isZero();
    assertThat(snapshot.activity().hasActivity()).isFalse();
    assertThat(snapshot.opportunity().count()).isZero();
    assertThat(snapshot.contractPayment().contractCount()).isZero();
    assertThat(snapshot.taskContact().contactCount()).isZero();
  }
}
