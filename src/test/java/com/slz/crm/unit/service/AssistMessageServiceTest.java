package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.AssistMessageEntity;
import com.slz.crm.pojo.entity.AssistRequestEntity;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.AssistMessageMapper;
import com.slz.crm.server.mapper.AssistRequestMapper;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.impl.AssistMessageServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
@DisplayName("协助过程消息")
class AssistMessageServiceTest {

  @Mock private AssistMessageMapper assistMessageMapper;
  @Mock private AssistRequestMapper assistRequestMapper;
  @Mock private UserMapper userMapper;
  @Mock private ContactTaskMapper contactTaskMapper;
  @InjectMocks private AssistMessageServiceImpl service;

  @AfterEach
  void clearCurrentUser() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("待协助时申请人可发送文本消息")
  void applicantCanSendTextWhilePending() {
    authenticateAs(7L);
    AssistRequestEntity assist = assist(1L, 7L, 8L, 0);
    when(assistRequestMapper.selectById(1L)).thenReturn(assist);
    when(userMapper.selectById(7L)).thenReturn(user(7L));
    when(assistMessageMapper.insert(any(AssistMessageEntity.class))).thenReturn(1);

    service.sendText(1L, "  请补充报价材料  ");

    ArgumentCaptor<AssistMessageEntity> captor = ArgumentCaptor.forClass(AssistMessageEntity.class);
    verify(assistMessageMapper).insert(captor.capture());
    assertEquals("请补充报价材料", captor.getValue().getContent());
    assertEquals("TEXT", captor.getValue().getMessageType());
  }

  @Test
  @DisplayName("终态协助的人工消息必须拒绝")
  void terminalAssistRejectsTextMessage() {
    authenticateAs(7L);
    when(assistRequestMapper.selectById(1L)).thenReturn(assist(1L, 7L, 8L, 1));
    when(userMapper.selectById(7L)).thenReturn(user(7L));

    assertThrows(BaseException.class, () -> service.sendText(1L, "继续补充"));
    verify(assistMessageMapper, never()).insert(any());
  }

  @Test
  @DisplayName("待协助时任务执行人可以参与协助对话")
  void assigneeCanSendTextForPendingTaskAssist() {
    authenticateAs(9L);
    AssistRequestEntity assist = assist(1L, 7L, 8L, 0);
    assist.setModelName(ModelName.CONTACT_TASK);
    assist.setRecordId(100L);
    ContactTaskEntity task = new ContactTaskEntity();
    task.setId(100L);
    task.setAssigneeId(9L);
    when(assistRequestMapper.selectById(1L)).thenReturn(assist);
    when(userMapper.selectById(9L)).thenReturn(user(9L));
    when(contactTaskMapper.selectById(100L)).thenReturn(task);
    when(assistMessageMapper.insert(any(AssistMessageEntity.class))).thenReturn(1);

    service.sendText(1L, "执行人已补充报价材料");

    verify(assistMessageMapper).insert(any(AssistMessageEntity.class));
  }

  @Test
  @DisplayName("无关用户仅知道 assistId 不能发送或读取消息（服务层重新确认关系）")
  void unrelatedUserDeniedOnSendAndList() {
    authenticateAs(6L);
    AssistRequestEntity assist = assist(1L, 7L, 8L, 0);
    when(assistRequestMapper.selectById(1L)).thenReturn(assist);
    when(userMapper.selectById(6L)).thenReturn(user(6L));

    assertThrows(BaseException.class, () -> service.sendText(1L, "无关用户消息"));
    assertThrows(BaseException.class, () -> service.listVisible(1L));
    verify(assistMessageMapper, never()).insert(any());
    verify(assistMessageMapper, never()).selectList(any());
  }

  @Test
  @DisplayName("超管豁免：非参与人也可读取协助消息")
  void adminCanReadMessagesAsOutsider() {
    authenticateAs(6L);
    AssistRequestEntity assist = assist(1L, 7L, 8L, 1);
    UserEntity admin = user(6L);
    admin.setRoleId(1L);
    when(assistRequestMapper.selectById(1L)).thenReturn(assist);
    when(userMapper.selectById(6L)).thenReturn(admin);
    when(assistMessageMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

    org.junit.jupiter.api.Assertions.assertTrue(service.listVisible(1L).isEmpty());
  }

  @Test
  @DisplayName("终态协助历史参与人仅可查看消息，不能新增")
  void historicalParticipantCanOnlyReadTerminalMessages() {
    authenticateAs(7L);
    when(assistRequestMapper.selectById(1L)).thenReturn(assist(1L, 7L, 8L, 1));
    when(userMapper.selectById(7L)).thenReturn(user(7L));
    when(assistMessageMapper.selectList(any())).thenReturn(java.util.Collections.emptyList());

    org.junit.jupiter.api.Assertions.assertTrue(service.listVisible(1L).isEmpty());
    assertThrows(BaseException.class, () -> service.sendText(1L, "终态后不能再发"));
  }

  private AssistRequestEntity assist(Long id, Long applicantId, Long assistUserId, Integer status) {
    AssistRequestEntity entity = new AssistRequestEntity();
    entity.setId(id);
    entity.setApplicantId(applicantId);
    entity.setAssistUserId(assistUserId);
    entity.setAssistStatus(status);
    return entity;
  }

  private UserEntity user(Long id) {
    UserEntity entity = new UserEntity();
    entity.setId(id);
    entity.setRoleId(4L);
    return entity;
  }

  private void authenticateAs(Long id) {
    RoleAO role = new RoleAO();
    role.setId(id);
    BaseUnit.setCurrentRole(role);
  }
}
