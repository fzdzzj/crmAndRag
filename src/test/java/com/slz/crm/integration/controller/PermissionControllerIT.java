package com.slz.crm.integration.controller;

import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.integration.RoleTokenSupport;
import com.slz.crm.integration.TestRole;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 权限接口集成测试：权限分配链路与敏感查询接口权限注解缺失。
 */
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PermissionControllerIT extends AbstractMySqlIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String normalUserToken() {
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", TestRole.NORMAL.getUserId());
        jdbcTemplate.update(
                "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
                TestRole.NORMAL.getUserId(), "e10adc3949ba59abbe56e057f20f883e", "普通用户75",
                "13900000075", "normal75@slz.com", TestRole.NORMAL.getRoleId(), 1);
        return RoleTokenSupport.tokenOf(TestRole.NORMAL);
    }

    @Disabled("权限注解尚未添加，等待后端修复后启用")
    @Test
    @DisplayName("普通用户查看全部权限列表应返回权限不足")
    void shouldDenyPermissionListForRoleWithoutPermission() throws Exception {
        mockMvc.perform(get("/permission/list")
                        .header("token", normalUserToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12002));
    }

    @Disabled("权限注解尚未添加，等待后端修复后启用")
    @Test
    @DisplayName("普通用户按角色查询权限应返回权限不足")
    void shouldDenyGetPermissionByRoleForRoleWithoutPermission() throws Exception {
        mockMvc.perform(get("/permission/getByRole")
                        .header("token", normalUserToken())
                        .param("roleId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12002));
    }

}
