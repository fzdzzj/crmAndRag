package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.BusinessActivityQueryDTO;
import com.slz.crm.pojo.dto.ContactTaskDTO;
import com.slz.crm.pojo.dto.GetUserDTO;
import com.slz.crm.pojo.dto.SalesStageApprovalDTO;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.pojo.vo.ContractANDOrderVO;
import com.slz.crm.pojo.vo.ContractNumVO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.OpportunityDetailVO;
import com.slz.crm.pojo.vo.OrderVO;
import com.slz.crm.pojo.vo.SalesNumVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.pojo.vo.TaskCommentVO;
import com.slz.crm.pojo.vo.UserVO;
import com.slz.crm.server.service.BusinessActivityService;
import com.slz.crm.server.service.CompanyGroupService;
import com.slz.crm.server.service.ContactTaskService;
import com.slz.crm.server.service.ContractOrderItemService;
import com.slz.crm.server.service.ContractService;
import com.slz.crm.server.service.CustomerCompanyService;
import com.slz.crm.server.service.CustomerContactService;
import com.slz.crm.server.service.DataStatisticsService;
import com.slz.crm.server.service.InvoiceInfoService;
import com.slz.crm.server.service.PaymentRecordService;
import com.slz.crm.server.service.ReportService;
import com.slz.crm.server.service.SalesOpportunityService;
import com.slz.crm.server.service.SalesStageApprovalService;
import com.slz.crm.server.service.SysDeptService;
import com.slz.crm.server.service.TaskCommentService;
import com.slz.crm.server.service.UserService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * extend-ai-query-tools 任务 2.7：查询工具执行器单元测试（纯 JVM mock service）。 extend-ai-query-tools-c 任务 2.5：追加 C
 * 批 4 工具执行器行为单测。
 */
@ExtendWith(MockitoExtension.class)
class AiQueryToolExecutorsTest {

  @Mock private DataStatisticsService dataStatisticsService;
  @Mock private ContractOrderItemService contractOrderItemService;
  @Mock private ContractService contractService;
  @Mock private CustomerCompanyService customerCompanyService;
  @Mock private CompanyGroupService companyGroupService;
  @Mock private CustomerContactService customerContactService;
  @Mock private PaymentRecordService paymentRecordService;
  @Mock private InvoiceInfoService invoiceInfoService;
  @Mock private SalesOpportunityService salesOpportunityService;
  @Mock private ContactTaskService contactTaskService;
  @Mock private TaskCommentService taskCommentService;
  @Mock private BusinessActivityService businessActivityService;
  @Mock private SalesStageApprovalService salesStageApprovalService;
  @Mock private UserService userService;
  @Mock private SysDeptService sysDeptService;
  @Mock private ReportService reportService;
  @Mock private com.slz.crm.server.ai.insight.CustomerInsightService customerInsightService;

  private AiQueryToolExecutors executors;

  @BeforeEach
  void setUp() {
    AiExtendedQueryToolExecutors extendedQueryToolExecutors =
        new AiExtendedQueryToolExecutors(
            userService, sysDeptService, customerCompanyService, reportService);
    executors =
        new AiQueryToolExecutors(
            dataStatisticsService,
            contractOrderItemService,
            contractService,
            customerCompanyService,
            companyGroupService,
            customerContactService,
            paymentRecordService,
            invoiceInfoService,
            salesOpportunityService,
            contactTaskService,
            taskCommentService,
            businessActivityService,
            salesStageApprovalService,
            extendedQueryToolExecutors,
            customerInsightService);
  }

  @AfterEach
  void clearCurrentUser() {
    BaseUnit.removeCurrentId();
  }

  @Test
  void executeQueryContactTask_parsesArgumentsAndReturnsPageRecords() {
    BaseUnit.setCurrentRole(buildRole(3001L, 3L, 11L));
    ContactTaskVO vo = new ContactTaskVO();
    vo.setId(10L);
    vo.setTaskTitle("跟进任务1");
    Page<ContactTaskVO> page = new Page<>(1, 10);
    page.setRecords(List.of(vo));

    when(contactTaskService.query(eq(2), eq(5), any(ContactTaskDTO.class))).thenReturn(page);

    Map<String, Object> args =
        Map.of(
            "assigneeId", 1001L,
            "companyId", 2001L,
            "contactId", 3001L,
            "opportunityId", 4001L,
            "pageNum", 2,
            "pageSize", 5);

    Object result = executors.executeQueryContactTask(args, null);

    assertThat(result).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    List<ContactTaskVO> list = (List<ContactTaskVO>) result;
    assertThat(list).hasSize(1);
    assertThat(list.get(0).getTaskTitle()).isEqualTo("跟进任务1");

    ArgumentCaptor<ContactTaskDTO> captor = ArgumentCaptor.forClass(ContactTaskDTO.class);
    verify(contactTaskService).query(eq(2), eq(5), captor.capture());
    ContactTaskDTO dto = captor.getValue();
    assertThat(dto.getAssigneeId()).isEqualTo(1001L);
    assertThat(dto.getCompanyId()).isEqualTo(2001L);
    assertThat(dto.getContactId()).isEqualTo(3001L);
    assertThat(dto.getOpportunityId()).isEqualTo(4001L);
  }

  @Test
  void executeQueryTaskComment_validTaskId_callsService() {
    BaseUnit.setCurrentRole(buildRole(3001L, 3L, 11L));
    TaskCommentVO comment = new TaskCommentVO();
    comment.setId(1L);
    comment.setContent("评论内容");
    Page<TaskCommentVO> page = new Page<>(1, 10);
    page.setRecords(List.of(comment));

    when(taskCommentService.getByTaskId(123L, 1, 10)).thenReturn(page);

    Object result =
        executors.executeQueryTaskComment(
            Map.of("taskId", 123L, "pageNum", 1, "pageSize", 10), null);

    assertThat(result).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    List<TaskCommentVO> list = (List<TaskCommentVO>) result;
    assertThat(list).hasSize(1);
    assertThat(list.get(0).getContent()).isEqualTo("评论内容");
    verify(taskCommentService).getByTaskId(123L, 1, 10);
  }

  @Test
  void executeQueryTaskComment_missingTaskId_throwsIllegalArgumentException() {
    BaseUnit.setCurrentRole(buildRole(3001L, 3L, 11L));
    assertThatThrownBy(() -> executors.executeQueryTaskComment(Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("taskId 不能为空");
  }

  @Test
  void executeQueryBusinessActivity_byId_callsDetailService() {
    BusinessActivityVO vo = new BusinessActivityVO();
    vo.setId(88L);
    vo.setActivityTitle("商务拜访");
    when(businessActivityService.getDetailById(88L)).thenReturn(vo);

    Object result = executors.executeQueryBusinessActivity(Map.of("activityId", 88L), null);

    assertThat(result).isSameAs(vo);
    verify(businessActivityService).getDetailById(88L);
  }

  @Test
  void executeQueryBusinessActivity_byFilter_callsQueryService() {
    BusinessActivityVO vo = new BusinessActivityVO();
    vo.setId(89L);
    vo.setActivityTitle("技术交流");
    Page<BusinessActivityVO> page = new Page<>(1, 10);
    page.setRecords(List.of(vo));

    when(businessActivityService.businessActivityQuery(
            eq(1), eq(10), any(BusinessActivityQueryDTO.class)))
        .thenReturn(page);

    Map<String, Object> args =
        Map.of(
            "activityTitle", "技术",
            "companyName", "某某科技",
            "opportunityName", "某某项目",
            "activityType", "MEETING");

    Object result = executors.executeQueryBusinessActivity(args, null);

    assertThat(result).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    List<BusinessActivityVO> list = (List<BusinessActivityVO>) result;
    assertThat(list).hasSize(1);
    assertThat(list.get(0).getActivityTitle()).isEqualTo("技术交流");

    ArgumentCaptor<BusinessActivityQueryDTO> captor =
        ArgumentCaptor.forClass(BusinessActivityQueryDTO.class);
    verify(businessActivityService).businessActivityQuery(eq(1), eq(10), captor.capture());
    BusinessActivityQueryDTO dto = captor.getValue();
    assertThat(dto.getActivityTitle()).isEqualTo("技术");
    assertThat(dto.getCompanyName()).isEqualTo("某某科技");
    assertThat(dto.getOpportunityName()).isEqualTo("某某项目");
    assertThat(dto.getActivityType()).isEqualTo("MEETING");
  }

  @Test
  void executeGetContractDetail_validId_callsService() {
    BaseUnit.setCurrentRole(buildRole(3001L, 3L, 11L));
    ContractANDOrderVO vo = new ContractANDOrderVO();
    when(contractService.getDetailById(66L)).thenReturn(vo);

    Object result = executors.executeGetContractDetail(Map.of("contractId", 66L), null);

    assertThat(result).isSameAs(vo);
    verify(contractService).getDetailById(66L);
  }

  @Test
  void executeGetContractDetail_missingId_throwsIllegalArgumentException() {
    BaseUnit.setCurrentRole(buildRole(3001L, 3L, 11L));
    assertThatThrownBy(() -> executors.executeGetContractDetail(Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("contractId 不能为空");
  }

  @Test
  void executeGetOpportunityDetail_validId_callsService() {
    OpportunityDetailVO vo = new OpportunityDetailVO();
    when(salesOpportunityService.getOpportunityDetailById(77L)).thenReturn(vo);

    Object result = executors.executeGetOpportunityDetail(Map.of("opportunityId", 77L), null);

    assertThat(result).isSameAs(vo);
    verify(salesOpportunityService).getOpportunityDetailById(77L);
  }

  @Test
  void executeGetOpportunityDetail_missingId_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> executors.executeGetOpportunityDetail(Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("opportunityId 不能为空");
  }

  @Test
  void executeQueryStageApproval_parsesArgumentsAndCallsService() {
    SalesStageApprovalVO vo = new SalesStageApprovalVO();
    vo.setId(99L);
    Page<SalesStageApprovalVO> page = new Page<>(1, 10);
    page.setRecords(List.of(vo));

    when(salesStageApprovalService.getPage(any(), any(SalesStageApprovalDTO.class)))
        .thenReturn(page);

    Map<String, Object> args =
        Map.of(
            "opportunityId", 501L, "approvalStatus", List.of(0, 1), "pageNum", 1, "pageSize", 10);

    Object result = executors.executeQueryStageApproval(args, null);

    assertThat(result).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    List<SalesStageApprovalVO> list = (List<SalesStageApprovalVO>) result;
    assertThat(list).hasSize(1);

    ArgumentCaptor<SalesStageApprovalDTO> captor =
        ArgumentCaptor.forClass(SalesStageApprovalDTO.class);
    verify(salesStageApprovalService).getPage(any(), captor.capture());
    SalesStageApprovalDTO dto = captor.getValue();
    assertThat(dto.getOpportunityId()).isEqualTo(501L);
    assertThat(dto.getApprovalStatus()).containsExactly(0, 1);
  }

  @Test
  void executeQueryStageApproval_singleStatusNumber_parsesToList() {
    Page<SalesStageApprovalVO> page = new Page<>(1, 10);
    page.setRecords(List.of());
    when(salesStageApprovalService.getPage(any(), any(SalesStageApprovalDTO.class)))
        .thenReturn(page);

    Map<String, Object> args = Map.of("opportunityId", 502L, "approvalStatus", 2);

    executors.executeQueryStageApproval(args, null);

    ArgumentCaptor<SalesStageApprovalDTO> captor =
        ArgumentCaptor.forClass(SalesStageApprovalDTO.class);
    verify(salesStageApprovalService).getPage(any(), captor.capture());
    SalesStageApprovalDTO dto = captor.getValue();
    assertThat(dto.getApprovalStatus()).containsExactly(2);
  }

  @Test
  void trimPageResult_trimsOverTenRecords() {
    BaseUnit.setCurrentRole(buildRole(3001L, 3L, 11L));
    List<ContactTaskVO> records = new ArrayList<>();
    for (int i = 0; i < 15; i++) {
      ContactTaskVO item = new ContactTaskVO();
      item.setId((long) i);
      records.add(item);
    }
    Page<ContactTaskVO> page = new Page<>(1, 20);
    page.setRecords(records);
    when(contactTaskService.query(any(), any(), any())).thenReturn(page);

    Object result = executors.executeQueryContactTask(Map.of(), null);

    @SuppressWarnings("unchecked")
    List<ContactTaskVO> list = (List<ContactTaskVO>) result;
    assertThat(list).hasSize(10);
  }

  /** extend-ai-query-tools-c 任务 2.1：executeQueryUser 参数解析与分页裁剪。 */
  @Test
  void executeQueryUser_parsesArgumentsAndReturnsPageRecords() {
    UserVO vo = new UserVO();
    vo.setId(500L);
    vo.setRealName("张伟");
    Page<UserVO> page = new Page<>(2, 5);
    page.setRecords(List.of(vo));

    // 超管（roleId=1）为豁免角色，走全量路径，仍可验证参数解析与分页裁剪
    BaseUnit.setCurrentRole(buildRole(999L, 1L, 11L));
    when(userService.findPage(any(GetUserDTO.class))).thenReturn(page);

    Map<String, Object> args =
        Map.of(
            "realName",
            "张伟",
            "phone",
            "13800000000",
            "email",
            "a@b.com",
            "roleName",
            "销售",
            "roleId",
            5,
            "status",
            1,
            "pageNum",
            2,
            "pageSize",
            5);

    Object result = executors.executeQueryUser(args, null);

    assertThat(result).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    List<UserVO> list = (List<UserVO>) result;
    assertThat(list).hasSize(1);
    assertThat(list.get(0).getRealName()).isEqualTo("张伟");

    ArgumentCaptor<GetUserDTO> captor = ArgumentCaptor.forClass(GetUserDTO.class);
    verify(userService).findPage(captor.capture());
    GetUserDTO dto = captor.getValue();
    assertThat(dto.getRealName()).isEqualTo("张伟");
    assertThat(dto.getPhone()).isEqualTo("13800000000");
    assertThat(dto.getEmail()).isEqualTo("a@b.com");
    assertThat(dto.getRoleName()).isEqualTo("销售");
    assertThat(dto.getRoleId()).containsExactly(5L);
    assertThat(dto.getStatus()).containsExactly(1);
    assertThat(dto.getPageNum()).isEqualTo(2);
    assertThat(dto.getPageSize()).isEqualTo(5);
  }

  /** extend-ai-query-tools-c 任务 2.1：缺省分页回退 1/10。 */
  @Test
  void executeQueryUser_defaultPaging_fallsBackToFirstPage() {
    Page<UserVO> page = new Page<>(1, 10);
    page.setRecords(List.of());
    // 非豁免操作者带 deptId；空记录集经过滤后仍为空，此处验证缺省分页回退 1/10
    BaseUnit.setCurrentRole(buildRole(1001L, 3L, 11L));
    when(userService.findPage(any(GetUserDTO.class))).thenReturn(page);

    executors.executeQueryUser(Map.of(), null);

    ArgumentCaptor<GetUserDTO> captor = ArgumentCaptor.forClass(GetUserDTO.class);
    verify(userService).findPage(captor.capture());
    GetUserDTO dto = captor.getValue();
    assertThat(dto.getPageNum()).isEqualTo(1);
    assertThat(dto.getPageSize()).isEqualTo(10);
  }

  /** add-queryuser-dept-isolation 任务 2.1：同部门可见——非豁免操作者仅返回本部门用户。 */
  @Test
  void executeQueryUser_sameDept_nonExempt_returnsOnlySameDept() {
    UserVO sameDept = new UserVO();
    sameDept.setId(10L);
    sameDept.setRealName("同部门张三");
    sameDept.setDeptId(11L);
    Page<UserVO> page = new Page<>(1, 10);
    page.setRecords(List.of(sameDept));

    BaseUnit.setCurrentRole(buildRole(2001L, 3L, 11L));
    when(userService.findPage(any(GetUserDTO.class))).thenReturn(page);

    Object result = executors.executeQueryUser(Map.of(), null);

    @SuppressWarnings("unchecked")
    List<UserVO> list = (List<UserVO>) result;
    assertThat(list).hasSize(1);
    assertThat(list.get(0).getDeptId()).isEqualTo(11L);
    assertThat(list.get(0).getRealName()).isEqualTo("同部门张三");
    verify(userService).findPage(any(GetUserDTO.class));
  }

  /** add-queryuser-dept-isolation 任务 2.2：跨部门不可见——非豁免操作者过滤掉不同部门用户。 */
  @Test
  void executeQueryUser_crossDept_excludedByDeptFilter() {
    UserVO sameDept = new UserVO();
    sameDept.setId(20L);
    sameDept.setRealName("同部门李四");
    sameDept.setDeptId(11L);
    UserVO crossDept = new UserVO();
    crossDept.setId(21L);
    crossDept.setRealName("跨部门王五");
    crossDept.setDeptId(99L);
    Page<UserVO> page = new Page<>(1, 10);
    page.setRecords(List.of(sameDept, crossDept));

    BaseUnit.setCurrentRole(buildRole(2002L, 3L, 11L));
    when(userService.findPage(any(GetUserDTO.class))).thenReturn(page);

    Object result = executors.executeQueryUser(Map.of(), null);

    @SuppressWarnings("unchecked")
    List<UserVO> list = (List<UserVO>) result;
    assertThat(list).hasSize(1);
    assertThat(list.get(0).getId()).isEqualTo(20L);
    assertThat(list.get(0).getRealName()).isEqualTo("同部门李四");
    assertThat(list).extracting(UserVO::getRealName).doesNotContain("跨部门王五");
    assertThat(list).extracting(UserVO::getDeptId).containsOnly(11L);
  }

  /** add-queryuser-dept-isolation 任务 2.3：豁免角色（超级管理员 roleId=1）全量可见——不过滤跨部门用户。 */
  @Test
  void executeQueryUser_superAdmin_exemptFromDeptFilter_allVisible() {
    UserVO deptA = new UserVO();
    deptA.setId(30L);
    deptA.setRealName("部门甲");
    deptA.setDeptId(1L);
    UserVO deptB = new UserVO();
    deptB.setId(31L);
    deptB.setRealName("部门乙");
    deptB.setDeptId(2L);
    Page<UserVO> page = new Page<>(1, 10);
    page.setRecords(List.of(deptA, deptB));

    // 超管 roleId=1，即使本无 deptId 也保持全量
    BaseUnit.setCurrentRole(buildRole(2003L, 1L, null));
    when(userService.findPage(any(GetUserDTO.class))).thenReturn(page);

    Object result = executors.executeQueryUser(Map.of(), null);

    @SuppressWarnings("unchecked")
    List<UserVO> list = (List<UserVO>) result;
    assertThat(list).hasSize(2);
    assertThat(list).extracting(UserVO::getDeptId).containsExactlyInAnyOrder(1L, 2L);
  }

  /** add-queryuser-dept-isolation 任务 2.4：无部门用户边界——非豁免操作者无 deptId 时返回空列表。 */
  @Test
  void executeQueryUser_noDept_nonExempt_returnsEmpty() {
    UserVO any = new UserVO();
    any.setId(40L);
    any.setRealName("任何人");
    any.setDeptId(11L);
    Page<UserVO> page = new Page<>(1, 10);
    page.setRecords(List.of(any));

    BaseUnit.setCurrentRole(buildRole(2004L, 3L, null));
    when(userService.findPage(any(GetUserDTO.class))).thenReturn(page);

    Object result = executors.executeQueryUser(Map.of(), null);

    @SuppressWarnings("unchecked")
    List<UserVO> list = (List<UserVO>) result;
    assertThat(list).isEmpty();
    verify(userService).findPage(any(GetUserDTO.class));
  }

  /** extend-ai-query-tools-c 任务 2.2：默认查询启用部门。 */
  @Test
  void executeQueryDept_defaultReturnsEnabledList() {
    SysDeptEntity dept = new SysDeptEntity();
    dept.setId(3L);
    dept.setDeptName("销售部");
    when(sysDeptService.listEnabled()).thenReturn(List.of(dept));

    Object result = executors.executeQueryDept(Map.of(), null);

    assertThat(result).isInstanceOf(List.class);
    @SuppressWarnings("unchecked")
    List<SysDeptEntity> list = (List<SysDeptEntity>) result;
    assertThat(list).hasSize(1);
    assertThat(list.get(0).getDeptName()).isEqualTo("销售部");
    verify(sysDeptService).listEnabled();
  }

  /** extend-ai-query-tools-c 任务 2.2：includeDisabled=true 查询含停用部门。 */
  @Test
  void executeQueryDept_includeDisabled_returnsAllList() {
    when(sysDeptService.listAll()).thenReturn(List.of());

    Object result = executors.executeQueryDept(Map.of("includeDisabled", true), null);

    assertThat(result).isEqualTo(List.of());
    verify(sysDeptService).listAll();
  }

  /** extend-ai-query-tools-c 任务 2.3：客户公司详情正常返回。 */
  @Test
  void executeGetCustomerCompanyDetail_validId_callsService() {
    CustomerCompanyVO vo = new CustomerCompanyVO();
    vo.setId(55L);
    when(customerCompanyService.getCompanyDetail(55L)).thenReturn(vo);

    Object result = executors.executeGetCustomerCompanyDetail(Map.of("companyId", 55L), null);

    assertThat(result).isSameAs(vo);
    verify(customerCompanyService).getCompanyDetail(55L);
  }

  /** extend-ai-query-tools-c 任务 2.3：companyId 缺失抛参数异常。 */
  @Test
  void executeGetCustomerCompanyDetail_missingId_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> executors.executeGetCustomerCompanyDetail(Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("companyId 不能为空");
  }

  /** extend-ai-query-tools-c 任务 2.4：CONTRACT 报表走合同统计服务。 */
  @Test
  void executeQueryReport_contractType_callsContractReportService() {
    ContractNumVO vo = ContractNumVO.builder().totalSignContractNum(12L).build();
    LocalDateTime start = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
    LocalDateTime end = LocalDateTime.of(2026, 1, 31, 23, 59, 59);
    when(reportService.getTotalSignContractNum(start, end)).thenReturn(vo);

    Map<String, Object> args =
        Map.of(
            "reportType", "CONTRACT",
            "startTime", "2026-01-01 00:00:00",
            "endTime", "2026-01-31 23:59:59");

    Object result = executors.executeQueryReport(args, null);

    assertThat(result).isSameAs(vo);
    verify(reportService).getTotalSignContractNum(start, end);
  }

  /** extend-ai-query-tools-c 任务 2.4：BUSINESS 报表走商机统计服务。 */
  @Test
  void executeQueryReport_businessType_callsBusinessReportService() {
    SalesNumVO vo = SalesNumVO.builder().totalSalesOpportunityNum(7L).build();
    when(reportService.getTotalBusinessNum(null, null)).thenReturn(vo);

    Object result = executors.executeQueryReport(Map.of("reportType", "BUSINESS"), null);

    assertThat(result).isSameAs(vo);
    verify(reportService).getTotalBusinessNum(null, null);
  }

  /** extend-ai-query-tools-c 任务 2.4：reportType 缺失抛参数异常。 */
  @Test
  void executeQueryReport_missingType_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> executors.executeQueryReport(Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("reportType 不能为空");
  }

  /** add-ai-customer-360-insight 任务 3.1：客户健康度诊断委托洞察服务。 */
  @Test
  void executeGetCustomerInsight_validId_callsInsightService() {
    com.slz.crm.server.ai.insight.CustomerInsightResult result =
        new com.slz.crm.server.ai.insight.CustomerInsightResult(
            88L,
            "北京测试科技有限公司",
            96,
            com.slz.crm.server.ai.insight.CustomerInsightHealthLevel.EXCELLENT,
            "客户整体健康",
            List.of(),
            List.of("保持定期跟进"),
            List.of(),
            List.of(),
            true);
    when(customerInsightService.diagnose(88L)).thenReturn(result);

    Object actual = executors.executeGetCustomerInsight(Map.of("companyId", 88L), null);

    assertThat(actual).isSameAs(result);
    verify(customerInsightService).diagnose(88L);
  }

  /** add-ai-customer-360-insight 任务 3.1：companyId 缺失抛参数异常。 */
  @Test
  void executeGetCustomerInsight_missingId_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> executors.executeGetCustomerInsight(Map.of(), null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("companyId 不能为空");
  }

  /** harden-consistency-datascope-and-gate 任务 2.2：无登录身份时详情类与结构化查询类工具一律 fail-closed。 */
  @Test
  void detailAndStructuredQueryTools_failClosedWithoutLoginIdentity() {
    Map<String, Object> args = Map.of();
    assertThatThrownBy(() -> executors.executeGetOrderDetail(args, null))
        .isInstanceOf(BaseException.class);
    assertThatThrownBy(() -> executors.executeQueryContract(args, null))
        .isInstanceOf(BaseException.class);
    assertThatThrownBy(() -> executors.executeQueryPayment(args, null))
        .isInstanceOf(BaseException.class);
    assertThatThrownBy(() -> executors.executeQueryInvoice(args, null))
        .isInstanceOf(BaseException.class);
    assertThatThrownBy(() -> executors.executeQueryContactTask(args, null))
        .isInstanceOf(BaseException.class);
    assertThatThrownBy(() -> executors.executeQueryTaskComment(args, null))
        .isInstanceOf(BaseException.class);
    assertThatThrownBy(() -> executors.executeGetContractDetail(args, null))
        .isInstanceOf(BaseException.class);
  }

  /** harden-consistency-datascope-and-gate 任务 2.2：fail-closed 拦截码为 12002 PERMISSION_DENIED。 */
  @Test
  void executeGetOrderDetail_withoutIdentity_failsClosedWithPermissionDenied() {
    assertThatThrownBy(() -> executors.executeGetOrderDetail(Map.of("orderId", 15L), null))
        .isInstanceOf(BaseException.class)
        .hasFieldOrPropertyWithValue("code", ErrorCode.PERMISSION_DENIED.getCode());
  }

  /** harden-consistency-datascope-and-gate 任务 2.3：合法属主携带身份时订单详情正常委托。 */
  @Test
  void executeGetOrderDetail_withIdentity_callsDetailService() {
    BaseUnit.setCurrentRole(buildRole(3001L, 3L, 11L));
    OrderVO vo = new OrderVO();
    vo.setId(15L);
    when(contractOrderItemService.getDetailById(15L)).thenReturn(vo);

    Object result = executors.executeGetOrderDetail(Map.of("orderId", 15L), null);

    assertThat(result).isSameAs(vo);
    verify(contractOrderItemService).getDetailById(15L);
  }

  /** add-queryuser-dept-isolation 测试辅助：构造当前操作用户（id/roleId/deptId）。 */
  private RoleAO buildRole(Long id, Long roleId, Long deptId) {
    RoleAO role = new RoleAO();
    role.setId(id);
    role.setRoleId(roleId);
    role.setDeptId(deptId);
    return role;
  }
}
