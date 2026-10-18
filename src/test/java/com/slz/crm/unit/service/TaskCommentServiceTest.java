package com.slz.crm.unit.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.TaskCommentDTO;
import com.slz.crm.pojo.entity.ContactTaskEntity;
import com.slz.crm.pojo.entity.TaskCommentEntity;
import com.slz.crm.pojo.vo.TaskCommentVO;
import com.slz.crm.server.mapper.ContactTaskMapper;
import com.slz.crm.server.mapper.TaskCommentMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.BusinessRecordAccessService;
import com.slz.crm.server.service.impl.TaskCommentServiceImpl;
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

/**
 * 任务评论服务测试。
 *
 * @author fmz
 * @since 2026/08/02
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("任务评论服务")
class TaskCommentServiceTest {

  @Mock private TaskCommentMapper taskCommentMapper;

  @Mock private UserMapper userMapper;

  @Mock private ContactTaskMapper contactTaskMapper;

  @Mock private BusinessRecordAccessService businessRecordAccessService;

  @InjectMocks private TaskCommentServiceImpl taskCommentService;

  @BeforeEach
  void injectMyBatisBaseMapper() {
    ReflectionTestUtils.setField(taskCommentService, "baseMapper", taskCommentMapper);
  }

  @AfterEach
  void clearCurrentUser() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("评论内容为空白时，应在访问数据库前拒绝创建")
  void shouldRejectCreateWhenContentIsBlank() {
    authenticateAs(1001L);
    TaskCommentDTO request = validRequest();
    request.setContent(" ");

    assertThrows(BaseException.class, () -> taskCommentService.create(request));

    verifyNoInteractions(taskCommentMapper, userMapper);
  }

  @Test
  @DisplayName("任务不存在时以 12002 fail-closed 拒绝，不区分缺失与越权")
  void getByTaskIdDeniedWhenTaskMissing() {
    authenticateAs(1001L);
    when(contactTaskMapper.selectById(2001L)).thenReturn(null);

    BaseException ex =
        assertThrows(BaseException.class, () -> taskCommentService.getByTaskId(2001L));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), ex.getCode());
    verifyNoInteractions(businessRecordAccessService);
  }

  @Test
  @DisplayName("跨属主任务评论以 12002 拦截（列表口）")
  void getByTaskIdDeniedForUnrelatedTask() {
    authenticateAs(1001L);
    ContactTaskEntity task = taskOwnedByOthers();
    when(contactTaskMapper.selectById(2001L)).thenReturn(task);
    doThrow(new BaseException(ErrorCode.PERMISSION_DENIED))
        .when(businessRecordAccessService)
        .assertCanReadTask(task);

    BaseException ex =
        assertThrows(BaseException.class, () -> taskCommentService.getByTaskId(2001L));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("跨属主任务评论以 12002 拦截（分页口，不构成旁路）")
  void getByTaskIdPagedDeniedForUnrelatedTask() {
    authenticateAs(1001L);
    ContactTaskEntity task = taskOwnedByOthers();
    when(contactTaskMapper.selectById(2001L)).thenReturn(task);
    doThrow(new BaseException(ErrorCode.PERMISSION_DENIED))
        .when(businessRecordAccessService)
        .assertCanReadTask(task);

    BaseException ex =
        assertThrows(BaseException.class, () -> taskCommentService.getByTaskId(2001L, 1, 10));

    assertEquals(ErrorCode.PERMISSION_DENIED.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("合法属主正常返回任务评论")
  void getByTaskIdReturnsForOwnerInScope() {
    authenticateAs(1001L);
    ContactTaskEntity task = new ContactTaskEntity();
    task.setId(2001L);
    task.setCreatorId(1001L);
    task.setAssigneeId(1001L);
    task.setAssignerId(1001L);
    when(contactTaskMapper.selectById(2001L)).thenReturn(task);
    when(businessRecordAccessService.assertCanReadTask(task)).thenReturn(true);
    when(taskCommentMapper.selectList(any())).thenReturn(List.of(new TaskCommentEntity()));

    List<TaskCommentVO> result = taskCommentService.getByTaskId(2001L);

    assertEquals(1, result.size());
  }

  @Test
  @DisplayName("合法属主分页查询任务评论正常返回")
  void getByTaskIdPagedReturnsForOwnerInScope() {
    authenticateAs(1001L);
    ContactTaskEntity task = new ContactTaskEntity();
    task.setId(2001L);
    task.setAssigneeId(1001L);
    when(contactTaskMapper.selectById(2001L)).thenReturn(task);
    when(businessRecordAccessService.assertCanReadTask(task)).thenReturn(true);
    Page<TaskCommentEntity> pageResult = new Page<>(1, 10, 1);
    pageResult.setRecords(List.of(new TaskCommentEntity()));
    when(taskCommentMapper.selectPage(any(), any())).thenReturn(pageResult);

    Page<TaskCommentVO> result = taskCommentService.getByTaskId(2001L, 1, 10);

    assertEquals(1, result.getRecords().size());
  }

  private ContactTaskEntity taskOwnedByOthers() {
    ContactTaskEntity task = new ContactTaskEntity();
    task.setId(2001L);
    task.setCreatorId(8L);
    task.setAssigneeId(9L);
    task.setAssignerId(10L);
    return task;
  }

  private TaskCommentDTO validRequest() {
    TaskCommentDTO request = new TaskCommentDTO();
    request.setTaskId(2001L);
    request.setContent("已联系客户，等待回复。");
    return request;
  }

  private void authenticateAs(Long userId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    BaseUnit.setCurrentRole(role);
  }
}
