package com.slz.crm.server.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.slz.crm.pojo.entity.BusinessActivityEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.CustomerCompanyEntity;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import com.slz.crm.pojo.vo.CustomerChurnWarningVO;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.CustomerCompanyMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 客户流失预警与再激活服务单测（add-customer-churn-warning-and-reactivation 任务 4.1）。
 *
 * <p>覆盖：三源互动信号边界（各源有/无、窗口临界）、三源皆空判定、幂等防重（已有进行中「流失预警」待办不重复建）、 待办字段（高优先级 / 负责人 / 系统指派 /
 * 描述含诊断引导语）、无候选与无负责人零写库。 mock mapper 隔离数据库与 LLM，杜绝外呼与真库依赖。
 */
@DisplayName("客户流失预警服务")
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CustomerChurnWarningServiceTest {

  private static final int WINDOW_DAYS = 60;

  @Mock private CustomerCompanyMapper customerCompanyMapper;
  @Mock private ContactTaskMapper contactTaskMapper;
  @Mock private BusinessActivityMapper businessActivityMapper;
  @Mock private ContractMapper contractMapper;
  @Mock private PaymentRecordMapper paymentRecordMapper;

  @InjectMocks private CustomerChurnWarningServiceImpl service;

  @BeforeEach
  void setUpWindow() {
    ReflectionTestUtils.setField(service, "inactiveDays", WINDOW_DAYS);
  }

  @Test
  @DisplayName("源②商业活动在窗口内：不判风险、不建待办")
  void activityWithinWindow_noRisk() {
    stubCandidates(List.of(company(1L, 1001L, "客户甲")));
    stubActivities(List.of(activity(1L, LocalDateTime.now().minusDays(WINDOW_DAYS - 1))));
    stubTasks(List.of());
    stubContracts(List.of());
    stubPayments(List.of());
    stubNoInProgressTask();

    assertEquals(0, service.scanAndGenerateChurnWarningTasks());
    verify(contactTaskMapper, never()).insert(any());
  }

  @Test
  @DisplayName("源②商业活动早于窗口：判风险，最后一笔互动为该活动、天数按它计算")
  void activityOutsideWindow_flagged() {
    LocalDateTime staleActivityAt = LocalDateTime.now().minusDays(WINDOW_DAYS + 1);
    stubCandidates(List.of(company(1L, 1001L, "客户甲")));
    stubActivities(List.of(activity(1L, staleActivityAt)));
    stubTasks(List.of());
    stubContracts(List.of());
    stubPayments(List.of());
    stubNoInProgressTask();

    int created = service.scanAndGenerateChurnWarningTasks();

    assertEquals(1, created);
    List<CustomerChurnWarningVO> risks = service.queryInactiveCustomers(10);
    assertEquals(1, risks.size());
    assertEquals(1L, risks.get(0).getCompanyId());
    assertEquals(1001L, risks.get(0).getOwnerId());
    assertEquals(WINDOW_DAYS, risks.get(0).getWindowDays());
    // 窗口外最新互动取代客户创建时间成为天数基准
    assertEquals(WINDOW_DAYS + 1, risks.get(0).getDaysSinceLastInteraction());
    assertTrue(
        risks.get(0).getLastInteractionTime().isBefore(LocalDateTime.now().minusDays(WINDOW_DAYS)));
  }

  @Test
  @DisplayName("源①已完成跟进任务在窗口内（update_time 为完成时间）：不判风险")
  void completedTaskWithinWindow_noRisk() {
    stubCandidates(List.of(company(1L, 1001L, "客户甲")));
    stubActivities(List.of());
    stubTasks(List.of(task(1L, 2, LocalDateTime.now().minusDays(5))));
    stubContracts(List.of());
    stubPayments(List.of());
    stubNoInProgressTask();

    assertEquals(0, service.scanAndGenerateChurnWarningTasks());
  }

  @Test
  @DisplayName("源①仅有未完成/已取消任务：查询口径不含未完成任务，仍判风险")
  void unfinishedTaskOnly_stillFlagged() {
    stubCandidates(List.of(company(1L, 1001L, "客户甲")));
    stubActivities(List.of());
    // status=2 的查询不会返回未完成任务，桩按查询口径给空表
    stubTasks(List.of());
    stubContracts(List.of());
    stubPayments(List.of());
    stubNoInProgressTask();

    assertEquals(1, service.scanAndGenerateChurnWarningTasks());
  }

  @Test
  @DisplayName("N 天临界：最后一笔互动落在 N 天窗口起点（含边界）计入互动，不判风险")
  void exactlyWindowDaysAgo_countsAsInteraction() {
    stubCandidates(List.of(company(1L, 1001L, "客户甲")));
    // +1 分钟抵消测试构造与判定时刻之间的墙钟漂移，保持"恰在窗口起点"语义
    stubActivities(
        List.of(activity(1L, LocalDateTime.now().minusDays(WINDOW_DAYS).plusMinutes(1))));
    stubTasks(List.of());
    stubContracts(List.of());
    stubPayments(List.of());
    stubNoInProgressTask();

    assertEquals(0, service.scanAndGenerateChurnWarningTasks());
  }

  @Test
  @DisplayName("源③合同回款进展在窗口内（经 contract.company_id 归集）：不判风险")
  void paymentWithinWindow_noRisk() {
    stubCandidates(List.of(company(1L, 1001L, "客户甲")));
    stubActivities(List.of());
    stubTasks(List.of());
    stubContracts(List.of(contract(11L, 1L)));
    stubPayments(List.of(payment(11L, LocalDateTime.now().minusDays(10))));
    stubNoInProgressTask();

    assertEquals(0, service.scanAndGenerateChurnWarningTasks());
  }

  @Test
  @DisplayName("源③回款早于窗口且无其他互动：判风险并记录最后一笔互动时间")
  void paymentOutsideWindow_flaggedWithLastInteraction() {
    stubCandidates(List.of(company(1L, 1001L, "客户甲")));
    stubActivities(List.of());
    stubTasks(List.of());
    stubContracts(List.of(contract(11L, 1L)));
    stubPayments(List.of(payment(11L, LocalDateTime.now().minusDays(120))));
    stubNoInProgressTask();

    assertEquals(1, service.scanAndGenerateChurnWarningTasks());

    List<CustomerChurnWarningVO> risks = service.queryInactiveCustomers(10);
    assertEquals(120L, risks.get(0).getDaysSinceLastInteraction());
    assertTrue(risks.get(0).getLastInteractionTime().isBefore(LocalDateTime.now().minusDays(119)));
  }

  @Test
  @DisplayName("三源皆空：判风险并按客户创建时间计无互动天数")
  void allSourcesEmpty_flagged() {
    stubCandidates(List.of(company(1L, 1001L, "客户甲")));
    stubActivities(List.of());
    stubTasks(List.of());
    stubContracts(List.of());
    stubPayments(List.of());
    stubNoInProgressTask();

    assertEquals(1, service.scanAndGenerateChurnWarningTasks());
    assertEquals(1, service.queryInactiveCustomers(null).size());
  }

  @Test
  @DisplayName("待办字段：高优先级 7 / 未开始 0 / 负责人 / 系统指派 0 / 描述含诊断引导语")
  void createdTaskHasExpectedFields() {
    stubCandidates(List.of(company(1L, 1001L, "客户甲")));
    stubActivities(List.of());
    stubTasks(List.of());
    stubContracts(List.of());
    stubPayments(List.of());
    stubNoInProgressTask();
    when(contactTaskMapper.insert(any())).thenReturn(1);

    assertEquals(1, service.scanAndGenerateChurnWarningTasks());

    ArgumentCaptor<ContactTaskEntity> captor = ArgumentCaptor.forClass(ContactTaskEntity.class);
    verify(contactTaskMapper, times(1)).insert(captor.capture());
    ContactTaskEntity task = captor.getValue();
    assertEquals("流失预警", task.getTaskType());
    assertEquals("流失预警", task.getTaskTitle());
    assertEquals(7, task.getPriority());
    assertEquals(0, task.getStatus());
    assertEquals(1L, task.getCompanyId());
    assertEquals(1001L, task.getAssigneeId());
    assertEquals(0L, task.getAssignerId());
    assertEquals(0L, task.getCreatorId());
    assertTrue(task.getTaskContent().contains("客户甲"));
    assertTrue(task.getTaskContent().contains("客户诊断"));
    assertTrue(task.getTaskContent().contains("Insight→Action"));
  }

  @Test
  @DisplayName("幂等防重：已有进行中「流失预警」待办时跳过，不重复创建")
  void inProgressTaskExists_skipsCreation() {
    stubCandidates(List.of(company(1L, 1001L, "客户甲")));
    stubActivities(List.of());
    stubTasks(List.of());
    stubContracts(List.of());
    stubPayments(List.of());
    when(contactTaskMapper.selectCount(any())).thenReturn(1L);

    assertEquals(0, service.scanAndGenerateChurnWarningTasks());
    verify(contactTaskMapper, never()).insert(any());
  }

  @Test
  @DisplayName("无候选客户：返回 0 且不查三源、不写库")
  void noCandidates_noWrite() {
    stubCandidates(List.of());

    assertEquals(0, service.scanAndGenerateChurnWarningTasks());
    assertTrue(service.queryInactiveCustomers(10).isEmpty());
    verify(contactTaskMapper, never()).insert(any());
    verify(contactTaskMapper, never()).selectCount(any());
  }

  @Test
  @DisplayName("公海客户（无负责人）不入候选：不建待办")
  void companyWithoutOwner_isExcludedByQuery() {
    stubCandidates(List.of(company(2L, null, "公海客户")));
    stubActivities(List.of());
    stubTasks(List.of());
    stubContracts(List.of());
    stubPayments(List.of());
    stubNoInProgressTask();

    assertEquals(0, service.scanAndGenerateChurnWarningTasks());
    verify(contactTaskMapper, never()).insert(any());
  }

  @Test
  @DisplayName("风险清单上限：limit 生效，超出即截断")
  void queryInactiveCustomers_respectsLimit() {
    stubCandidates(
        List.of(company(1L, 1001L, "客户甲"), company(2L, 1002L, "客户乙"), company(3L, 1003L, "客户丙")));
    stubActivities(List.of());
    stubTasks(List.of());
    stubContracts(List.of());
    stubPayments(List.of());
    stubNoInProgressTask();

    assertEquals(2, service.queryInactiveCustomers(2).size());
    assertFalse(service.queryInactiveCustomers(2).isEmpty());
  }

  private void stubCandidates(List<CustomerCompanyEntity> companies) {
    when(customerCompanyMapper.selectList(anyWrapper())).thenReturn(companies);
  }

  private void stubActivities(List<BusinessActivityEntity> activities) {
    when(businessActivityMapper.selectList(anyWrapper())).thenReturn(activities);
  }

  private void stubTasks(List<ContactTaskEntity> tasks) {
    when(contactTaskMapper.selectList(anyWrapper())).thenReturn(tasks);
  }

  private void stubContracts(List<ContractEntity> contracts) {
    when(contractMapper.selectList(anyWrapper())).thenReturn(contracts);
  }

  private void stubPayments(List<PaymentRecordEntity> payments) {
    when(paymentRecordMapper.selectList(anyWrapper())).thenReturn(payments);
  }

  private void stubNoInProgressTask() {
    lenient().when(contactTaskMapper.selectCount(anyWrapper())).thenReturn(0L);
    lenient().when(contactTaskMapper.insert(any())).thenReturn(1);
  }

  @SuppressWarnings("unchecked")
  private static <T> Wrapper<T> anyWrapper() {
    return any(Wrapper.class);
  }

  private CustomerCompanyEntity company(Long id, Long ownerId, String name) {
    CustomerCompanyEntity entity = new CustomerCompanyEntity();
    entity.setId(id);
    entity.setOwnerId(ownerId);
    entity.setCompanyName(name);
    entity.setIsDeleted(false);
    entity.setCreateTime(LocalDateTime.now().minusDays(90));
    return entity;
  }

  private BusinessActivityEntity activity(Long companyId, LocalDateTime activityTime) {
    BusinessActivityEntity entity = new BusinessActivityEntity();
    entity.setCompanyId(companyId);
    entity.setActivityTime(activityTime);
    entity.setActivityTitle("拜访");
    return entity;
  }

  private ContactTaskEntity task(Long companyId, Integer status, LocalDateTime doneAt) {
    ContactTaskEntity entity = new ContactTaskEntity();
    entity.setCompanyId(companyId);
    entity.setStatus(status);
    entity.setTaskType("电话");
    entity.setAssigneeId(1001L);
    entity.setCreatorId(1001L);
    entity.setPriority(3);
    entity.setTaskTitle("跟进");
    entity.setUpdateTime(toDate(doneAt));
    entity.setCreateTime(toDate(doneAt));
    return entity;
  }

  private ContractEntity contract(Long id, Long companyId) {
    ContractEntity entity = new ContractEntity();
    entity.setId(id);
    entity.setCompanyId(companyId);
    return entity;
  }

  private PaymentRecordEntity payment(Long contractId, LocalDateTime createTime) {
    PaymentRecordEntity entity = new PaymentRecordEntity();
    entity.setContractId(contractId);
    entity.setCreateTime(createTime);
    return entity;
  }

  private Date toDate(LocalDateTime time) {
    return Date.from(time.atZone(ZoneId.systemDefault()).toInstant());
  }
}
