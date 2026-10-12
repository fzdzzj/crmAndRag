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
 * 提示词模板管理接口集成测试（add-prompt-template-admin 任务 3.3 + prompt-template-enhance 任务 3.2，Testcontainers
 * MySQL）。
 *
 * <p><b>鉴权口径</b>：方法级 {@code @RequirePermission}，由 {@code PermissionsInterceptor#preHandle} 强制执行；
 * 注解存在而用户无对应权限时抛 {@code BaseException(PERMISSION_DENIED)} → code 12002。两档分权：808
 * PROMPT_TEMPLATE_VIEW （读/渲染/反馈），809 PROMPT_TEMPLATE_MANAGE（写/回滚）。
 *
 * <p><b>正向</b>：种子角色同时持 808/809 → create→detail→history→render→feedback→revert→history（审计链）→delete
 * 全链。 <b>反向</b>：仅持 808（VIEW）写 create/revert → 12002；无 808（读）→ 12002。真库由 auto-table 在上下文启动建表，零
 * Flyway 种子；权限与模板在用例内 jdbcTemplate/API 补种，不依赖 V33。
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
    // 权限 808/809 在同事务内被多次 seed 时需先清 role_permissions 引用再删权限，避免重复插入与 FK 冲突
    jdbcTemplate.update("DELETE FROM role_permissions WHERE permissions_id IN (808, 809)");
    jdbcTemplate.update("DELETE FROM permissions WHERE id IN (808, 809)");
    jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
    jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
    jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", roleId);
    jdbcTemplate.update(
        "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
        roleId,
        "提示词模板角色" + roleId,
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
        "13900000" + (100 + userId),
        "pt" + userId + "@slz.com",
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

  // ===== prompt-template-enhance：revert / feedback =====

  private String updateTpl(String token, String id, Integer expectedVersion) throws Exception {
    String body =
        "{\"id\":"
            + id
            + ",\"expectedVersion\":"
            + expectedVersion
            + ",\"code\":\"it-tpl\",\"name\":\"IT模板改\",\"content\":\"你好 {{name}} (v2)\","
            + "\"variables\":\"[{\\\"name\\\":\\\"name\\\",\\\"required\\\":true,"
            + "\\\"description\\\":\\\"名字\\\"}]\",\"remark\":\"updated\"}";
    return mockMvc
        .perform(
            post("/promptTemplate/update")
                .header("token", token)
                .content(body)
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  @Test
  @DisplayName("808+809：revert 端到端含审计链（REVERT 笔 sourceVersion + 源版本内容回填）")
  void revertEndToEndKeepsAuditChain() throws Exception {
    String token = seedToken(22L, 203L, true, true);
    String id = createTpl(token); // v1 CREATE，content="你好 {{name}} ({{role}})"

    // 更新 → v2：content 变为 v2
    updateTpl(token, id, 1);
    mockMvc
        .perform(get("/promptTemplate/{id}/history", id).header("token", token))
        .andExpect(jsonPath("$.data[0].operation").value("UPDATE"))
        .andExpect(jsonPath("$.data[0].version").value(2));

    // 回滚到 v1（expectedVersion=2）→ 新版本 v3，REVERT 笔回填 v1 内容
    String revertBody = "{\"targetVersion\":1,\"expectedVersion\":2}";
    mockMvc
        .perform(
            post("/promptTemplate/{id}/revert", id)
                .header("token", token)
                .content(revertBody)
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data").value(3));

    // 审计链：history 倒序 [REVERT v3, UPDATE v2, CREATE v1]
    mockMvc
        .perform(get("/promptTemplate/{id}/history", id).header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data[0].operation").value("REVERT"))
        .andExpect(jsonPath("$.data[0].version").value(3))
        .andExpect(jsonPath("$.data[0].sourceVersion").value(1))
        .andExpect(jsonPath("$.data[0].content").value("你好 {{name}} ({{role}})"))
        .andExpect(jsonPath("$.data[1].operation").value("UPDATE"))
        .andExpect(jsonPath("$.data[2].operation").value("CREATE"));

    // 仅 808 的 VIEW 角色写 revert → 12002
    String viewOnly = seedToken(23L, 204L, true, false);
    mockMvc
        .perform(
            post("/promptTemplate/{id}/revert", id)
                .header("token", viewOnly)
                .content(revertBody)
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
  }

  @Test
  @DisplayName("808：feedback 记录与分页查询（score/version 校验 + 权限正反向）")
  void feedbackRecordAndListWithPermission() throws Exception {
    String token = seedToken(24L, 205L, true, true);
    String id = createTpl(token); // v1

    // 记录合法反馈
    mockMvc
        .perform(
            post("/promptTemplate/{id}/feedback", id)
                .header("token", token)
                .content("{\"version\":1,\"score\":5,\"comment\":\"效果很好\"}")
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));

    // 分页查询出该条
    mockMvc
        .perform(get("/promptTemplate/{id}/feedback/list", id).header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.records[0].version").value(1))
        .andExpect(jsonPath("$.data.records[0].score").value(5))
        .andExpect(jsonPath("$.data.records[0].comment").value("效果很好"))
        .andExpect(jsonPath("$.data.total").value(1));

    // 越界 score → 拒绝（50002）
    mockMvc
        .perform(
            post("/promptTemplate/{id}/feedback", id)
                .header("token", token)
                .content("{\"version\":1,\"score\":6,\"comment\":\"bad\"}")
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(50002));

    // 版本不存在 → 拒绝（92008）
    mockMvc
        .perform(
            post("/promptTemplate/{id}/feedback", id)
                .header("token", token)
                .content("{\"version\":999,\"score\":4}")
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(92008));

    // 仅 808 的 VIEW 角色也能 feedback（记录成功）→ 权限正反向
    String viewOnly = seedToken(25L, 206L, true, false);
    mockMvc
        .perform(
            post("/promptTemplate/{id}/feedback", id)
                .header("token", viewOnly)
                .content("{\"version\":1,\"score\":4,\"comment\":\"view 反馈\"}")
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
  }

  // ===== add-prompt-template-nesting：嵌套引用渲染端到端 =====

  @Test
  @DisplayName("808+809：子模板嵌套引用渲染端到端（{{> code}} 展开 + 变量并集强制）")
  void nestingRenderEndToEndExpandsChildAndEnforcesUnion() throws Exception {
    String token = seedToken(26L, 207L, true, true);

    // 1) 建子模板 it-nest-child（含 {{childVar}}，required 声明）
    String childBody =
        "{\"code\":\"it-nest-child\",\"name\":\"嵌套子模板\",\"content\":\"纪律条款：{{childVar}}\","
            + "\"variables\":\"[{\\\"name\\\":\\\"childVar\\\",\\\"required\\\":true,"
            + "\\\"description\\\":\\\"纪律变量\\\"}]\"}";
    mockMvc
        .perform(
            post("/promptTemplate/create")
                .header("token", token)
                .content(childBody)
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));

    // 2) 建父模板 it-nest-parent，content 引用 {{> it-nest-child}} + 自身 {{task}}
    String parentBody =
        "{\"code\":\"it-nest-parent\",\"name\":\"嵌套父模板\",\"content\":\"执行 {{task}}\\n先看：\\n{{> it-nest-child}}\","
            + "\"variables\":\"[{\\\"name\\\":\\\"task\\\",\\\"required\\\":true,"
            + "\\\"description\\\":\\\"任务\\\"}]\"}";
    mockMvc
        .perform(
            post("/promptTemplate/create")
                .header("token", token)
                .content(parentBody)
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));

    // 3) 渲染父模板：并提供主+子变量并集 → 展开 + 替换
    mockMvc
        .perform(
            post("/promptTemplate/render")
                .header("token", token)
                .content(
                    "{\"code\":\"it-nest-parent\",\"variables\":{\"task\":\"巡检\",\"childVar\":\"零外呼\"}}")
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.content").value("执行 巡检\n先看：\n纪律条款：零外呼"))
        .andExpect(jsonPath("$.data.version").value(1));

    // 4) 缺子模板必填变量 → 并集强制 PARAM_REQUIRED（50008）
    mockMvc
        .perform(
            post("/promptTemplate/render")
                .header("token", token)
                .content("{\"code\":\"it-nest-parent\",\"variables\":{\"task\":\"巡检\"}}")
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(50008));

    // 5) 循环引用（父引父）→ PARAM_FORMAT_ERROR（50002）
    String cycleBody =
        "{\"code\":\"it-nest-cycle\",\"name\":\"环\",\"content\":\"自环 {{> it-nest-cycle}}\","
            + "\"variables\":\"[]\"}";
    mockMvc
        .perform(
            post("/promptTemplate/create")
                .header("token", token)
                .content(cycleBody)
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
    mockMvc
        .perform(
            post("/promptTemplate/render")
                .header("token", token)
                .content("{\"code\":\"it-nest-cycle\",\"variables\":{}}")
                .contentType("application/json"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(50002));
  }
}
