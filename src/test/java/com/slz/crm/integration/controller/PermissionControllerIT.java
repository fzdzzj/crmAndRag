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
 *
 * <p><b>未闭合的授权缺口（本类两个用例被停放的唯一原因）</b>：
 * {@code GET /permission/list} 与 {@code GET /permission/getByRole} 目前没有任何权限注解。
 * 本项目的鉴权机制是方法级注解 {@code com.slz.crm.common.annotation.RequirePermission}
 * （{@code @Target(ElementType.METHOD)}，只能打在方法上，打在类上不生效），由
 * {@code com.slz.crm.server.interceptor.PermissionsInterceptor#preHandle} 强制执行
 * （在 {@code WebMvcConfiguration#addInterceptors} 注册）：注解缺失时该拦截器直接放行，
 * 注解存在且校验不过时抛 {@code BaseException(ErrorCode.PERMISSION_DENIED)}，即本类断言的 code 12002。
 *
 * <p>再启用的机械核对条件（三项全满足才能移除下面两个 {@code @Disabled}）：
 * <ol>
 *   <li>{@code PermissionController#list()}（{@code @RequestMapping("/list")}）上出现 {@code @RequirePermission(...)}；</li>
 *   <li>{@code PermissionController#getByRole()}（{@code @GetMapping("/getByRole")}）上出现 {@code @RequirePermission(...)}；</li>
 *   <li>注解取值确定：{@code PermissionOperates} 枚举当前没有「查看权限」常量，最接近的是
 *       {@code SYSTEM_ASSIGN_PERMISSION(606L, "分配权限")}（已用于同类第 57 行的分配接口）。
 *       owner 需二选一——复用 {@code SYSTEM_ASSIGN_PERMISSION}，或新增一个查看类常量
 *       （如 {@code SYSTEM_VIEW_PERMISSION}）并同步 {@code sys_permission} 种子数据与前端权限树。</li>
 * </ol>
 *
 * <p>核对命令（在仓库根执行，期望在 list() 与 getByRole() 上各命中一次，当前为 1 次——只有第 57 行）：
 * <pre>{@code grep -n "@RequirePermission" src/main/java/com/slz/crm/server/controller/PermissionController.java}</pre>
 *
 * <p>owner：{@code PermissionController} 与 {@code PermissionOperates} 的维护者（后端权限模块）。
 * 落地注解会改变产品鉴权行为（原本任何登录用户都能读到全量权限清单），属需单独授权的变更，
 * 不要顺手在无关任务里加。缺口同时登记在 {@code AGENTS.md} 的「未闭合的授权缺口」一节。
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

    @Disabled("再启用条件：PermissionController#list()（@RequestMapping(\"/list\")）上加 @RequirePermission；"
            + "该注解为 @Target(METHOD)，由 PermissionsInterceptor#preHandle 强制执行，缺失即放行。"
            + "取值待定：PermissionOperates 无查看权限常量，需复用 SYSTEM_ASSIGN_PERMISSION(606) 或新增常量并同步 sys_permission 种子。"
            + "owner=后端权限模块；核对：grep -n \"@RequirePermission\" src/main/java/com/slz/crm/server/controller/PermissionController.java")
    @Test
    @DisplayName("普通用户查看全部权限列表应返回权限不足")
    void shouldDenyPermissionListForRoleWithoutPermission() throws Exception {
        mockMvc.perform(get("/permission/list")
                        .header("token", normalUserToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12002));
    }

    @Disabled("再启用条件：PermissionController#getByRole()（@GetMapping(\"/getByRole\")）上加 @RequirePermission；"
            + "该注解为 @Target(METHOD)，由 PermissionsInterceptor#preHandle 强制执行，缺失即放行。"
            + "取值待定：PermissionOperates 无查看权限常量，需复用 SYSTEM_ASSIGN_PERMISSION(606) 或新增常量并同步 sys_permission 种子。"
            + "owner=后端权限模块；核对：grep -n \"@RequirePermission\" src/main/java/com/slz/crm/server/controller/PermissionController.java")
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
