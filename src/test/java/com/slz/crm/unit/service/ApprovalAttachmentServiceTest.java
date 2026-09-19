package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.pojo.vo.AttachmentDeleteResultVO;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.BusinessActivityMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.SalesStageApprovalMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.impl.ApprovalAttachmentServiceImpl;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** 附件删除必须按真实归属和操作人逐项处理，不能依赖前端传入的 ID。 */
@ExtendWith(MockitoExtension.class)
@DisplayName("附件删除授权")
class ApprovalAttachmentServiceTest {

  @Mock private ApprovalAttachmentMapper approvalAttachmentMapper;
  @Mock private UserMapper userMapper;
  @Mock private BusinessActivityMapper businessActivityMapper;
  @Mock private ContactTaskMapper contactTaskMapper;
  @Mock private AssistRequestMapper assistRequestMapper;
  @Mock private SalesStageApprovalMapper salesStageApprovalMapper;
  @Mock private HttpServletRequest request;
  @Mock private com.slz.crm.common.untils.AttachmentDownloadTokenUtil downloadTokenUtil;
  @InjectMocks private ApprovalAttachmentServiceImpl attachmentService;

  @BeforeEach
  void injectMybatisBaseMapper() {
    // ServiceImpl 的 baseMapper 定义在父类中，Mockito 的 @InjectMocks 不会自动注入它。
    ReflectionTestUtils.setField(attachmentService, "baseMapper", approvalAttachmentMapper);
  }

  @AfterEach
  void clearCurrentUser() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("同一批次应删除本人附件，同时保留越权和不存在的附件")
  void removeAuthorizedByIdsShouldClassifyEachAttachment() {
    authenticateAs(9L);
    UserEntity normalUser = new UserEntity();
    normalUser.setId(9L);
    normalUser.setRoleId(2L);
    when(userMapper.selectById(9L)).thenReturn(normalUser);

    ApprovalAttachmentEntity own = attachment(1L, 100L, 9L);
    ApprovalAttachmentEntity foreignRecord = attachment(2L, 101L, 9L);
    when(approvalAttachmentMapper.selectBatchIds(List.of(1L, 2L, 3L)))
        .thenReturn(List.of(own, foreignRecord));

    AttachmentDeleteResultVO result =
        attachmentService.removeAuthorizedByIds(
            List.of(1L, 2L, 3L), ModelName.ASSIST_REQUEST, 100L);

    assertAll(
        () -> assertEquals(List.of(1L), result.getDeletedIds()),
        () -> assertEquals(List.of(3L), result.getNotFoundIds()),
        () -> assertEquals("附件不属于当前业务记录", result.getDenied().get(2L)));
    verify(approvalAttachmentMapper).deleteBatchIds(List.of(1L));
  }

  private ApprovalAttachmentEntity attachment(Long id, Long andId, Long uploaderId) {
    ApprovalAttachmentEntity entity = new ApprovalAttachmentEntity();
    entity.setId(id);
    entity.setAndId(andId);
    entity.setUploaderId(uploaderId);
    entity.setModelName(ModelName.ASSIST_REQUEST);
    entity.setFileName("missing-file.txt");
    entity.setFilePath("missing-file.txt");
    return entity;
  }

  private void authenticateAs(Long userId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    BaseUnit.setCurrentRole(role);
  }
}
