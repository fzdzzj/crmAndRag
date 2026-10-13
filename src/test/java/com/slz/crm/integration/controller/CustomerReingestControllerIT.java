package com.slz.crm.integration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * 客户全量回埋端点权限集成测试（add-customer-semantic-retrieval 任务 4.2）。
 *
 * <p>覆盖 POST /knowledge/customer/reingest（{@code @RequirePermission(KNOWLEDGE_ADMIN_MANAGE=900)}） 的
 * 900 正反向：反向用例（roleId=3 无任何授权）断言 12002（PERMISSION_DENIED）；正向用例（用例内 jdbcTemplate 种子：sys_role id=2 +
 * permissions id=900 + role_permissions(2,900) + sys_user roleId=2、status=1，roleId≠1 避开超管硬放行）断言 200
 * + code=1 + data.total=0，证明 900 授权链路放行且端点真实执行回埋服务。
 *
 * <p>零外呼闸：正向用例先清空 customer_company——回埋扫描零行、零 embed 调用 （application-test.yml 的 DashScope key 为占位符，任何
 * embed 都会尝试真实外呼）。 用例内补种模式对齐 {@link PermissionControllerIT} 先例；@Transactional 回滚隔离，零波及其他 IT。
 */
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CustomerReingestControllerIT extends AbstractMySqlIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("无 900 授权的普通用户调用全量回埋应返回权限不足")
  void shouldDenyReingestForRoleWithoutPermission() throws Exception {
    // init_data.sql 只种了 sys_role id=1，反向用例角色（roleId=3）需先补种，否则插入 sys_user 撞 fk_user_role。
    jdbcTemplate.update(
        "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
        TestRole.NORMAL.getRoleId(),
        "销售经理",
        "反向用例角色（无任何授权）");
    jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", TestRole.NORMAL.getUserId());
    jdbcTemplate.update(
        "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
        TestRole.NORMAL.getUserId(),
        "e10adc3949ba59abbe56e057f20f883e",
        "普通用户75",
        "13900000075",
        "normal75@slz.com",
        TestRole.NORMAL.getRoleId(),
        1);
    String token = RoleTokenSupport.tokenOf(TestRole.NORMAL);

    mockMvc
        .perform(post("/knowledge/customer/reingest").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
  }

  @Test
  @DisplayName("拥有知识库管理900的非超管角色调用全量回埋成功且零嵌入外呼")
  void shouldAllowReingestForRoleGrantedKnowledgeAdminManage() throws Exception {
    jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", TestRole.NORMAL.getUserId());
    jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?", 2L);
    jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", 2L);
    jdbcTemplate.update(
        "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
        2L,
        "知识库管理员",
        "全量回埋（正向用例）");
    jdbcTemplate.update(
        "INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES (?, ?, ?)",
        900L,
        "KNOWLEDGE_ADMIN_MANAGE",
        "知识库管理");
    jdbcTemplate.update(
        "INSERT INTO role_permissions (role_id, permissions_id) VALUES (?, ?)", 2L, 900L);
    jdbcTemplate.update(
        "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
        TestRole.NORMAL.getUserId(),
        "e10adc3949ba59abbe56e057f20f883e",
        "知识库管理员用户",
        "13900000077",
        "kb-admin@slz.com",
        2L,
        1);
    // 零外呼闸：全部置回收站（is_deleted=1；直接 DELETE 撞 fk_contact_company 外键），
    // 回埋扫描零行、零 embed 调用（DashScope key 为测试占位符）。
    jdbcTemplate.update("UPDATE customer_company SET is_deleted = 1");
    String token = RoleTokenSupport.tokenOf(TestRole.NORMAL);

    mockMvc
        .perform(post("/knowledge/customer/reingest").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.total").value(0));
  }
}
