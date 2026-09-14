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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 报表/统计端点权限 IT（apply-permission-matrix 任务 2.3，D2 拍板复用 501/502）。
 *
 * <p>Report 两端点挂 {@code REPORT_VIEW_REPORT(501)}、DataStatistics 按 501/502 挂（见报告 §4.2）。
 * 验证：已授权非超管角色正向 code=1；未授权角色反向 12002。禁止改共享 {@code init_data.sql}，
 * 一律用例内 jdbcTemplate seeding + {@link Transactional} 回滚隔离。</p>
 */
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PermissionApplyReportIT extends AbstractMySqlIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 无任何授权的普通用户（反向用例）；沿用 PermissionControllerIT 的普通用户模式（roleId=3）。 */
    private String normalUserToken() {
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

    /**
     * 种子一个非超管角色（roleId 避开 0/1/2，模拟 V26 授权后的业务角色）并返回其 token。
     * 权限按需种入 role_permissions，模拟 V26 INSERT...SELECT 授权后的访问面。
     */
    private String roleToken(Long roleId, Long... permissionIds) {
        jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", 200L);
        jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
        jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", roleId);
        jdbcTemplate.update(
                "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
                roleId, "报表角色", "apply-permission-matrix 任务 2.3 正向用例");
        for (Long pid : permissionIds) {
            jdbcTemplate.update(
                    "DELETE FROM permissions WHERE id = ?", pid);
            jdbcTemplate.update(
                    "INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES (?, 'T', 'T')", pid);
            jdbcTemplate.update(
                    "INSERT INTO role_permissions (role_id, permissions_id) VALUES (?, ?)", roleId, pid);
        }
        jdbcTemplate.update(
                "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
                200L, "e10adc3949ba59abbe56e057f20f883e", "报表角色用户",
                "13900000200", "report-role@slz.com", roleId, 1);
        return RoleTokenSupport.tokenOfUserId(200L);
    }

    @Test
    @DisplayName("持有报表查看权限501的非超管角色可读签约合同数量")
    void shouldAllowReportContractForRoleGrantedViewReport() throws Exception {
        mockMvc.perform(get("/report/contract")
                        .header("token", roleToken(6L, 501L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    @DisplayName("持有报表查看权限501的非超管角色可读综合统计")
    void shouldAllowSummaryForRoleGrantedViewReport() throws Exception {
        mockMvc.perform(post("/dataStatistics/summary")
                        .header("token", roleToken(6L, 501L))
                        .contentType("application/json")
                        .content("{\"startTime\":\"2026-01-01 00:00:00\",\"endTime\":\"2026-12-31 23:59:59\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }

    @Test
    @DisplayName("未授权角色查询报表应返回权限不足12002")
    void shouldDenyReportContractForRoleWithoutPermission() throws Exception {
        mockMvc.perform(get("/report/contract")
                        .header("token", normalUserToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12002));
    }

    @Test
    @DisplayName("未授权角色调用图表数据接口应返回权限不足12002")
    void shouldDenyChartDataForRoleWithoutGeneratePermission() throws Exception {
        mockMvc.perform(post("/dataStatistics/chartData")
                        .header("token", normalUserToken())
                        .contentType("application/json")
                        .content("{\"dataType\":\"CUSTOMER_SOURCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(12002));
    }

    @Test
    @DisplayName("持有报表生成权限502的非超管角色可生成图表JSON")
    void shouldAllowChartDataForRoleGrantedGenerateReport() throws Exception {
        mockMvc.perform(post("/dataStatistics/chartData")
                        .header("token", roleToken(7L, 502L))
                        .contentType("application/json")
                        .content("{\"dataType\":\"CUSTOMER_SOURCE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1));
    }
}