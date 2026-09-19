package com.slz.crm.unit.controller;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.dto.AssistAppendDTO;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.pojo.vo.OpportunityDetailVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.server.controller.AssistController;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.CustomerCompanyService;
import com.slz.crm.server.service.CustomerContactService;
import com.slz.crm.server.service.SalesOpportunityService;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("协助关联只读详情接口")
class AssistControllerTest {

  @Mock private AssistRequestService assistRequestService;
  @Mock private ApprovalAttachmentService approvalAttachmentService;
  @Mock private SalesOpportunityService salesOpportunityService;
  @Mock private CustomerCompanyService customerCompanyService;
  @Mock private CustomerContactService customerContactService;

  @InjectMocks private AssistController controller;

  @Test
  @DisplayName("销售机会接口只使用 assistId 反查的商机ID")
  void opportunityUsesResolvedId() {
    AssistRelatedRecordVO related = new AssistRelatedRecordVO();
    related.setOpportunityId(50L);
    OpportunityDetailVO detail = new OpportunityDetailVO();
    when(assistRequestService.getRelatedRecord(1L)).thenReturn(related);
    when(salesOpportunityService.getOpportunityDetailById(50L)).thenReturn(detail);

    assertSame(detail, controller.opportunity(1L).getData());
    verify(salesOpportunityService).getOpportunityDetailById(50L);
  }

  @Test
  @DisplayName("公司与联系人接口拒绝没有关联对象的协助记录")
  void companyAndContactRejectMissingRelations() {
    when(assistRequestService.getRelatedRecord(1L)).thenReturn(new AssistRelatedRecordVO());

    assertThrows(BaseException.class, () -> controller.company(1L));
    assertThrows(BaseException.class, () -> controller.contact(1L));
  }

  @Test
  @DisplayName("公司与联系人接口返回后端反查的只读详情")
  void companyAndContactUseResolvedIds() {
    AssistRelatedRecordVO related = new AssistRelatedRecordVO();
    related.setCompanyId(60L);
    related.setContactId(70L);
    CustomerCompanyVO company = new CustomerCompanyVO();
    CustomerContactVO contact = new CustomerContactVO();
    when(assistRequestService.getRelatedRecord(1L)).thenReturn(related);
    when(customerCompanyService.getCompanyDetail(60L)).thenReturn(company);
    when(customerContactService.get(70L)).thenReturn(contact);

    assertSame(company, controller.company(1L).getData());
    assertSame(contact, controller.contact(1L).getData());
  }

  @Test
  @DisplayName("审批、活动、任务详情统一按 assistId 交给服务层校验")
  void relatedDetailsUseAssistScopedLookup() {
    SalesStageApprovalVO approval = new SalesStageApprovalVO();
    BusinessActivityVO activity = new BusinessActivityVO();
    ContactTaskVO task = new ContactTaskVO();
    when(assistRequestService.getRelatedApproval(1L)).thenReturn(approval);
    when(assistRequestService.getRelatedActivity(2L)).thenReturn(activity);
    when(assistRequestService.getRelatedTask(3L)).thenReturn(task);

    assertSame(approval, controller.approval(1L).getData());
    assertSame(activity, controller.activity(2L).getData());
    assertSame(task, controller.task(3L).getData());

    verify(assistRequestService).getRelatedApproval(1L);
    verify(assistRequestService).getRelatedActivity(2L);
    verify(assistRequestService).getRelatedTask(3L);
  }

  @Test
  @DisplayName("追加协助接口只委托给追加服务，不修改原申请")
  void appendDelegatesToService() {
    AssistAppendDTO dto = new AssistAppendDTO();
    dto.setOriginalAssistId(10L);

    assertSame(Boolean.TRUE, controller.append(dto).getData());
    verify(assistRequestService).appendAssists(10L, null);
  }

  @Test
  @DisplayName("业务负责人发起协助时，控制器原样委托来源模型和业务记录")
  void applyDelegatesSourceContextAndItems() {
    AssistApplyItem item = new AssistApplyItem();
    item.setAssistUserId(20L);
    item.setApplyPurpose("补充报价");
    item.setApplyRequirement("上传报价文件");

    assertSame(
        Boolean.TRUE, controller.apply(ModelName.BUSINESS_ACTIVITY, 100L, List.of(item)).getData());

    verify(assistRequestService).applyAssists(ModelName.BUSINESS_ACTIVITY, 100L, List.of(item));
  }

  @Test
  @DisplayName("活动来源附件必须由协助详情反查来源活动")
  void sourceActivityAttachmentsUseAssistScopedActivity() {
    AssistVO assist = new AssistVO();
    assist.setModelName(ModelName.BUSINESS_ACTIVITY);
    assist.setRecordId(100L);
    List<ApprovalAttachmentVO> attachments = List.of(new ApprovalAttachmentVO());
    when(assistRequestService.getDetail(1L)).thenReturn(assist);
    when(assistRequestService.getRelatedActivityAttachments(1L, 100L)).thenReturn(attachments);

    assertSame(attachments, controller.sourceActivityAttachments(1L).getData());

    verify(assistRequestService).getRelatedActivityAttachments(1L, 100L);
  }

  @Test
  @DisplayName("任务来源附件读写删除均只携带 assistId，由服务层校验协助状态和记录范围")
  void sourceTaskAttachmentsWriteAndDeleteDelegateToAssistService() {
    List<ApprovalAttachmentVO> attachments = List.of(new ApprovalAttachmentVO());
    ApprovalAttachmentDTO attachment = new ApprovalAttachmentDTO();
    AssistController.UploadAttachmentsRequest request =
        new AssistController.UploadAttachmentsRequest();
    request.setAttachments(List.of(attachment));
    when(assistRequestService.getRelatedTaskAttachments(1L)).thenReturn(attachments);

    assertSame(attachments, controller.sourceTaskAttachments(1L).getData());
    assertSame(Boolean.TRUE, controller.uploadSourceAttachments(1L, request).getData());
    controller.deleteSourceAttachments(1L, List.of(10L, 11L));

    verify(assistRequestService).getRelatedTaskAttachments(1L);
    verify(assistRequestService).uploadRelatedAttachments(1L, List.of(attachment));
    verify(assistRequestService).deleteRelatedAttachments(1L, List.of(10L, 11L));
  }
}
