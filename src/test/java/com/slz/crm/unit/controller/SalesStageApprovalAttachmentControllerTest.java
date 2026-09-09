package com.slz.crm.unit.controller;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.server.controller.SalesStageApprovalController;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.SalesStageApprovalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SalesStageApprovalAttachmentControllerTest {

    @Mock private SalesStageApprovalService salesStageApprovalService;
    @Mock private ApprovalAttachmentService approvalAttachmentService;
    @Mock private ApprovalAttachmentMapper approvalAttachmentMapper;
    @Mock private AttachmentAccessService attachmentAccessService;
    @InjectMocks private SalesStageApprovalController controller;

    @BeforeEach
    void authenticate() {
        RoleAO current = new RoleAO();
        current.setId(2L);
        BaseUnit.setCurrentRole(current);
    }

    @AfterEach
    void clearAuthentication() {
        BaseUnit.removeCurrentId();
    }

    @Test
    @DisplayName("审批附件批量查询中任一记录无权时不返回任何附件元数据")
    void rejectsBatchBeforeReadingAttachmentMetadata() {
        ApprovalAttachmentEntity attachment = attachment(10L);
        when(approvalAttachmentMapper.selectList(any())).thenReturn(List.of(attachment));
        doThrow(new BaseException("无权读取该审批附件"))
                .when(attachmentAccessService).assertCanRead(attachment, 2L);

        assertThrows(BaseException.class, () -> controller.getApprovalAttachment(List.of(100L)));

        verify(attachmentAccessService).assertCanRead(attachment, 2L);
        verify(approvalAttachmentService, never()).getByAndIds(any(), any());
    }

    @Test
    @DisplayName("审批附件批量查询仅在全部记录授权通过后返回附件列表")
    void readsMetadataAfterAllRecordChecksPass() {
        ApprovalAttachmentEntity attachment = attachment(10L);
        when(approvalAttachmentMapper.selectList(any())).thenReturn(List.of(attachment));
        when(approvalAttachmentService.getByAndIds(List.of(100L), "approval_attachment"))
                .thenReturn(List.of(new ApprovalAttachmentVO()));

        assertDoesNotThrow(() -> controller.getApprovalAttachment(List.of(100L)));

        verify(attachmentAccessService).assertCanRead(attachment, 2L);
        verify(approvalAttachmentService).getByAndIds(List.of(100L), "approval_attachment");
    }

    private ApprovalAttachmentEntity attachment(Long id) {
        ApprovalAttachmentEntity attachment = new ApprovalAttachmentEntity();
        attachment.setId(id);
        attachment.setAndId(100L);
        attachment.setModelName("approval_attachment");
        return attachment;
    }
}
