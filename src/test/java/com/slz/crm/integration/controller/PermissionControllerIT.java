package com.slz.crm.integration.controller;

import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.integration.RoleTokenSupport;
import com.slz.crm.integration.TestRole;
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
 * 权限接口集成测试：权限分配链路与敏感查询接口的权限注解。
 *
 * <p><b>已闭合的授权缺口（close-permission-read-gap，复用 606）</b>：
 * 原本 {@code GET /permission/list} 与 {@code GET /permission/getByRole} 没有任何权限注解，
 * 任何登录用户都能枚举全量权限目录。现两接口已加 {@code @RequirePermission(PermissionOperates.SYSTEM_ASSIGN_PERMISSION)}，
 * 取值复用 606（读写同权，用户已拍板，不新增 608 常量）。
 *
 * <p>鉴权机制（保留说明）：本项目的鉴权是方法级注解 {@code com.slz.crm.common.annotation.RequirePermission}
 * （{@code @Target(ElementType.METHOD)}，只能打在方法上，打在类上不生效），由
 * {@code com.slz.crm.server.interceptor.PermissionsInterceptor#preHandle} 强制执行
 * （在 {@code WebMvcConfiguration#addInterceptors} 注册）：注解缺失时该拦截器直接放行，
 * 注解存在且校验不过时抛 {@code BaseException(ErrorCode.PERMISSION_DENIED)}，即本类断言的 code 12002。
 *
 * <p>两个反向用例（{@code TestRole.NORMAL}，roleId=3 无任何授权）断言 12002；
 * 一个正向用例（用例内 jdbcTemplate 种子：sys_role id=2 + permissions id=606 + role_permissions(2,606)
 * + sys_user roleId=2、status=1、roleId≠1 避开超管硬放行）断言两接口返回 200，证明 606 存量持有者不受损。
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
        // init_data.sql 只种了 sys_role id=1，反向用例角色（roleId=3）需先在用例内补种，
        // 否则插入 sys_user 会撞 fk_user_role 外键（停放期从未真正跑过这两个用例）。
        jdbcTemplate.update(
                "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
                TestRole.NORMAL.getRoleId(), "销售经理", "反向用例角色（无任何授权）");
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", TestRole.NORMAL.getUserId());
        jdbcTemplate.update(
                "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
                TestRole.NORMAL.getUserId(), "e10adc3949ba59abbe56e057f20f883e", "普通用户75",
                "13900000075", "normal75@slz.com", TestRole.NORMAL.getRoleId(), 1);
        return RoleTokenSupport.tokenOf(TestRole.NORMAL);
    }

    @Test
    @DisplayName("普通用户查看全部权限列表应返回权限不足")
    void shouldDenyPermissionListForRoleWithoutPermission() throws Exception {
        mockMvc.perform(get("/permission/list")
                        .header("token", normalUserToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12002));
    }

    @Test
    @DisplayName("普通用户按角色查询权限应返回权限不足")
    void shouldDenyGetPermissionByRoleForRoleWithoutPermission() throws Exception {
        mockMvc.perform(get("/permission/getByRole")
                        .header("token", normalUserToken())
                        .param("roleId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12002));
    }

    @Test
    @DisplayName("拥有分配权限606的非超管角色可查看全部权限列表与按角色查询权限")
    void shouldAllowPermissionListForRoleGrantedAssignPermission() throws Exception {
        // 用例内 jdbcTemplate 种子（参照 normalUserToken() 模式）：sys_role id=2 + permissions id=606
        // + role_permissions(2,606) + sys_user roleId=2、status=1、roleId≠1 避开超管硬放行。
        // @Transactional 回滚隔离，不改共享 init_data.sql，零波及其他 IT。
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", TestRole.NORMAL.getUserId());
        jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?", 2L);
        jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", 2L);
        jdbcTemplate.update(
                "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
                2L, "权限管理员", "分配权限（正向用例）");
        jdbcTemplate.update(
                "INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES (?, ?, ?)",
                606L, "SYSTEM_ASSIGN_PERMISSION", "分配权限");
        jdbcTemplate.update(
                "INSERT INTO role_permissions (role_id, permissions_id) VALUES (?, ?)", 2L, 606L);
        jdbcTemplate.update(
                "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
                TestRole.NORMAL.getUserId(), "e10adc3949ba59abbe56e057f20f883e", "权限管理员用户",
                "13900000076", "perm-mgr@slz.com", 2L, 1);

        String token = RoleTokenSupport.tokenOf(TestRole.NORMAL);

        mockMvc.perform(get("/permission/list")
                        .header("token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));

        mockMvc.perform(get("/permission/getByRole")
                        .header("token", token)
                        .param("roleId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

}
