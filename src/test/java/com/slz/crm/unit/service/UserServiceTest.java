package com.slz.crm.unit.service;

import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.UserDTO;
import com.slz.crm.pojo.entity.RoleEntity;
import com.slz.crm.pojo.entity.SysDeptEntity;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.RoleMapper;
import com.slz.crm.server.mapper.SysDeptMapper;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.service.DataConvertService;
import com.slz.crm.server.service.impl.UserServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
/**
 * 用户登录服务测试。
 *
 * @author fmz
 * @since 2026/08/02
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("用户登录服务")
class UserServiceTest {

    @Mock
    private UserMapper userMapper;

    @Mock
    private RoleMapper roleMapper;

    @Mock
    private SysDeptMapper sysDeptMapper;

    @Mock
    private DataConvertService dataConvertService;

    @InjectMocks
    private UserServiceImpl userService;

    @AfterEach
    void clearCurrentUser() {
        BaseUnit.removeCurrentId();
    }

    @Test
    @DisplayName("新增用户时，应保留所选的初始账户状态")
    void shouldPersistRequestedStatusWhenAddingUser() {
        authenticateAs(9001L);
        UserDTO request = validAddUserRequest();
        request.setStatus(1);
        lenient().when(roleMapper.selectById(request.getRoleId())).thenReturn(validRole(request.getRoleId()));
        when(sysDeptMapper.selectById(request.getDeptId())).thenReturn(activeDept(request.getDeptId()));
        when(userMapper.insert(any(UserEntity.class))).thenReturn(1);

        userService.addUser(request);

        ArgumentCaptor<UserEntity> userCaptor = ArgumentCaptor.forClass(UserEntity.class);
        verify(userMapper).insert(userCaptor.capture());
        assertEquals(1, userCaptor.getValue().getStatus());
    }

    private UserDTO validAddUserRequest() {
        UserDTO request = new UserDTO();
        request.setRealName("Jane Doe");
        request.setEmail("jane.doe@example.com");
        request.setPhone("13800138000");
        request.setDeptId(11L);
        request.setRoleId(8L);
        request.setStatus(1);
        request.setPassword("client-controlled-password");
        return request;
    }

    private RoleEntity validRole(Long roleId) {
        RoleEntity role = new RoleEntity();
        role.setId(roleId);
        role.setIsDeleted(false);
        return role;
    }

    private SysDeptEntity activeDept(Long deptId) {
        SysDeptEntity dept = new SysDeptEntity();
        dept.setId(deptId);
        dept.setStatus(1);
        return dept;
    }

    private void authenticateAs(Long userId) {
        RoleAO role = new RoleAO();
        role.setId(userId);
        BaseUnit.setCurrentRole(role);
    }
}
