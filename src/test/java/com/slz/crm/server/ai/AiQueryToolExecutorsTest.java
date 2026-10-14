package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.BusinessActivityQueryDTO;
import com.slz.crm.pojo.dto.ContactTaskDTO;
import com.slz.crm.pojo.dto.SalesStageApprovalDTO;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.pojo.vo.ContractANDOrderVO;
import com.slz.crm.pojo.vo.OpportunityDetailVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.pojo.vo.TaskCommentVO;
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
import com.slz.crm.server.service.SalesOpportunityService;
import com.slz.crm.server.service.SalesStageApprovalService;
import com.slz.crm.server.service.TaskCommentService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** extend-ai-query-tools 任务 2.7：查询工具执行器单元测试（纯 JVM mock service）。 */
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

  private AiQueryToolExecutors executors;

  @BeforeEach
  void setUp() {
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
            salesStageApprovalService);
  }

  @Test
  void executeQueryContactTask_parsesArgumentsAndReturnsPageRecords() {
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
    ContractANDOrderVO vo = new ContractANDOrderVO();
    when(contractService.getDetailById(66L)).thenReturn(vo);

    Object result = executors.executeGetContractDetail(Map.of("contractId", 66L), null);

    assertThat(result).isSameAs(vo);
    verify(contractService).getDetailById(66L);
  }

  @Test
  void executeGetContractDetail_missingId_throwsIllegalArgumentException() {
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
}
