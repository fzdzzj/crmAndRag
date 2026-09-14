package com.slz.crm.integration.controller;

import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.integration.RoleTokenSupport;
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
 * 冻结/离职状态检查前置反向 IT（apply-permission-matrix 任务 4.2，D3 拍板纳入）。
 *
 * <p>修复后 {@code PermissionsInterceptor} 的用户状态检查在 {@code @RequirePermission} 判空之前执行，
 * 任何登录请求（含零注解端点）先过状态闸。验证：</p>
 * <ul>
 *   <li>冻结用户（roleId=0）调零注解端点 {@code GET /user/my} → 12006（ER.USER_IS_FROZEN）；</li>
 *   <li>离职用户（roleId=2）调零注解端点 → 12007（ER.USER_IS_QUIT）；</li>
 *   <li>在职用户调零注解端点仍放行 code=1（现状口径防回归，不因状态检查前置改变行为）。</li>
 * </ul>
 * <p>禁止改共享 {@code init_data.sql}，一律用例内 jdbcTemplate seeding + {@link Transactional} 回滚隔离。</p>
 */
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PermissionsInterceptorStatusIT extends AbstractMySqlIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 种一个用户并以给定 roleId/status 返回其 token（userID 固定 203 避免与外键逻辑冲突）。 */
    private String seedUser(Long userId, Long roleId, Integer status) {
        if (roleId != 1L && roleId != 0L && roleId != 2L) {
            jdbcTemplate.update(
                    "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
                    roleId, "在职角色", "反向用例业务角色");
        }
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        jdbcTemplate.update(
                "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
                userId, "e10adc3949ba59abbe56e057f20f883e", "状态用例用户",
                "13900000203", "status-case@slz.com", roleId, status);
        return RoleTokenSupport.tokenOfUserId(userId);
    }

    @Test
    @DisplayName("冻结用户(roleId=0)调用零注解端点应被拒 USER_IS_FROZEN(12006)")
    void shouldDenyFrozenUserOnZeroAnnotationEndpoint() throws Exception {
        mockMvc.perform(get("/user/my")
                        .header("token", seedUser(203L, 0L, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12006));
    }

    @Test
    @DisplayName("离职用户(roleId=2)调用零注解端点应被拒 USER_IS_QUIT(12007)")
    void shouldDenyQuitUserOnZeroAnnotationEndpoint() throws Exception {
        mockMvc.perform(get("/user/my")
                        .header("token", seedUser(203L, 2L, 0)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12007));
    }

    @Test
    @DisplayName("在职用户调用零注解端点仍放行 code=1（不因状态检查前置改变现状）")
    void shouldAllowActiveUserOnZeroAnnotationEndpoint() throws Exception {
        mockMvc.perform(get("/user/my")
                        .header("token", seedUser(203L, 3L, 1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }
}