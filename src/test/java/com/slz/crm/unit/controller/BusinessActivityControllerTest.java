package com.slz.crm.unit.controller;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.server.controller.BusinessActivityController;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.BusinessActivityService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class BusinessActivityControllerTest {

    @Mock private BusinessActivityService businessActivityService;
    @Mock private ApprovalAttachmentService approvalAttachmentService;
    @Mock private AttachmentAccessService attachmentAccessService;
    @InjectMocks private BusinessActivityController controller;

    @BeforeEach
    void authenticate() {
        RoleAO role = new RoleAO();
        role.setId(2L);
        BaseUnit.setCurrentRole(role);
    }

    @Test
    @DisplayName("活动附件接口先校验具体活动可见性")
    void rejectsAttachmentReadWhenActivityIsNotVisible() {
        doThrow(new BaseException("无权查看该业务记录"))
                .when(businessActivityService).getDetailById(100L);

        assertThrows(BaseException.class, () -> controller.getAttachments(100L));
        verifyNoInteractions(approvalAttachmentService);
    }

    @Test
    @DisplayName("活动可见后才读取该活动附件")
    void readsAttachmentsAfterActivityVisibilityCheck() {
        when(attachmentAccessService.canReadAttachments(eq("business_activity"), eq(100L), anyLong()))
                .thenReturn(true);
        when(approvalAttachmentService.getByAndIds(List.of(100L), "business_activity"))
                .thenReturn(List.of(new ApprovalAttachmentVO()));

        controller.getAttachments(100L);

        verify(businessActivityService).getDetailById(100L);
        verify(approvalAttachmentService).getByAndIds(List.of(100L), "business_activity");
    }
}
