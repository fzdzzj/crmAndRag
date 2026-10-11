package com.slz.crm.integration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * 提示词模板管理接口集成测试（add-prompt-template-admin 任务 3.3，Testcontainers MySQL）。
 *
 * <p><b>鉴权口径</b>：方法级 {@code @RequirePermission}，由 {@code PermissionsInterceptor#preHandle} 强制执行；
 * 注解存在而用户无对应权限时抛 {@code BaseException(PERMISSION_DENIED)} → code 12002。两档分权：808
 * PROMPT_TEMPLATE_VIEW （读/渲染），809 PROMPT_TEMPLATE_MANAGE（写）。
 *
 * <p><b>正向</b>：种子角色同时持 808/809 → create→detail→history→render→delete 全链。 <b>反向</b>：仅持 808（VIEW）写
 * create → 12002；无 808（读）→ 12002。真库由 auto-table 在上下文启动建表，零 Flyway 种子；权限与模板在用例内 jdbcTemplate/API
 * 补种，不依赖 V33。
 */
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class PromptTemplateControllerIT extends AbstractMySqlIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private JdbcTemplate jdbcTemplate;

  private static final String REQ =
      "{\"code\":\"it-tpl\",\"name\":\"IT模板\",\"content\":\"你好 {{name}} ({{role}})\","
          + "\"variables\":\"[{\\\"name\\\":\\\"name\\\",\\\"required\\\":true,"
          + "\\\"description\\\":\\\"名字\\\"},{\\\"name\\\":\\\"role\\\",\\\"required\\\":true,"
          + "\\\"description\\\":\\\"角色\\\"}]\"}";

  /** 种子角色并挂权限，返回该角色下的用户 token。 */
  private String seedToken(Long roleId, Long userId, boolean view, boolean manage) {
    jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
    jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
    jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", roleId);
    jdbcTemplate.update(
        "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
        roleId,
        "提示词模板角色",
        "IT 用例角色");
    if (view) {
      jdbcTemplate.update(
          "INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES (?, ?, ?)",
          808L,
          "PROMPT_TEMPLATE_VIEW",
          "查看提示词模板");
      jdbcTemplate.update(
          "INSERT INTO role_permissions (role_id, permissions_id) VALUES (?, ?)", roleId, 808L);
    }
    if (manage) {
      jdbcTemplate.update(
          "INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES (?, ?, ?)",
          809L,
          "PROMPT_TEMPLATE_MANAGE",
          "管理提示词模板");
      jdbcTemplate.update(
          "INSERT INTO role_permissions (role_id, permissions_id) VALUES (?, ?)", roleId, 809L);
    }
    jdbcTemplate.update(
        "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
        userId,
        "e10adc3949ba59abbe56e057f20f883e",
        "提示词模板用户",
        "13900000088",
        "pt@slz.com",
        roleId,
        1);
    return RoleTokenSupport.tokenOfUserId(userId);
  }

  private String createTpl(String token) throws Exception {
    String resp =
        mockMvc
            .perform(
                post("/promptTemplate/create")
                    .header("token", token)
                    .content(REQ)
                    .contentType("application/json"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    Number id = com.jayway.jsonpath.JsonPath.read(resp, "$.data");
    return id.toString();
  }

  @Test
  @DisplayName("808+809 角色：创建→详情→历史→渲染→删除 全链")
  void fullCrudWithRender() throws Exception {
    String token = seedToken(20L, 201L, true, true);
    String id = createTpl(token);

    // 详情含正文
    mockMvc
        .perform(get("/promptTemplate/{id}", id).header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.content").value("你好 {{name}} ({{role}})"));

    // 历史至少 1 笔 CREATE
    mockMvc
        .perform(get("/promptTemplate/{id}/history", id).header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data[0].operation").value("CREATE"));

    // 渲染正常替换
    mockMvc
        .perform(
            post("/promptTemplate/render")
                .header("token", token)
                .content("{\"code\":\"it-tpl\",\"variables\":{\"name\":\"张三\",\"role\":\"Admin\"}}")
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.content").value("你好 张三 (Admin)"))
        .andExpect(jsonPath("$.data.version").value(1));

    // 渲染 required 缺失 → 拒绝
    mockMvc
        .perform(
            post("/promptTemplate/render")
                .header("token", token)
                .content("{\"code\":\"it-tpl\",\"variables\":{}}")
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(50008));

    // 删除
    mockMvc
        .perform(delete("/promptTemplate/{id}", id).header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
  }

  @Test
  @DisplayName("仅持 808 的 VIEW 角色写 create 返回权限不足 12002")
  void viewOnlyCannotWrite() throws Exception {
    String token = seedToken(21L, 202L, true, false);
    mockMvc
        .perform(
            post("/promptTemplate/create")
                .header("token", token)
                .content(REQ)
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
  }

  @Test
  @DisplayName("无 808 的普通用户读详情返回权限不足 12002")
  void noPermissionCannotRead() throws Exception {
    // 无任何授权角色（TestRole.NORMAL，roleId=3）
    seedToken(3L, TestRole.NORMAL.getUserId(), false, false);
    String token = RoleTokenSupport.tokenOfUserId(TestRole.NORMAL.getUserId());
    mockMvc
        .perform(get("/promptTemplate/1").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
  }
}
