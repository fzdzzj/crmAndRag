package com.slz.crm.unit.service;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.TaskCommentDTO;
import com.slz.crm.server.mapper.TaskCommentMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.impl.TaskCommentServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * 任务评论服务测试。
 *
 * @author fmz
 * @since 2026/08/02
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("任务评论服务")
class TaskCommentServiceTest {

    @Mock
    private TaskCommentMapper taskCommentMapper;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private TaskCommentServiceImpl taskCommentService;

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
