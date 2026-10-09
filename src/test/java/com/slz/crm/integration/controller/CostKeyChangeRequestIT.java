package com.slz.crm.integration.controller;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.integration.RoleTokenSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成本键申请-审批流端到端集成测试（add-cost-key-approval-workflow 任务 3.3）。
 *
 * <p>真库 + 真实拦截器与权限验证：
 *
 * <ul>
 *   <li>608 角色提交申请 -> 超管审批 -> 配置真写入 + 版本递增 + 回填 applied_config_version；
 *   <li>本人在途撤回；
 *   <li>超管驳回；
 *   <li>一键一单在途排他；
 *   <li>无 608 拦截 12002；非超管审批/驳回服务层档位闸拒绝 96005。
 * </ul>
 */
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CostKeyChangeRequestIT extends AbstractMySqlIT {

  private static final String COST_KEY = "rag.query.hyde.enabled";

  @Autowired private MockMvc mockMvc;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Autowired private ObjectMapper objectMapper;

  @BeforeEach
  void cleanUp() {
    // 自足清理：不依赖共享库状态（P-ac 教训②）
    jdbcTemplate.update("DELETE FROM cost_key_change_request");
    jdbcTemplate.update("DELETE FROM dynamic_config_history WHERE config_key = ?", COST_KEY);
    jdbcTemplate.update("DELETE FROM dynamic_config_item WHERE config_key = ?", COST_KEY);
  }

  /** 种业务角色 + 在职用户（status=1）并返回 token；grant608 控制是否赋予 608 权限。 */
  private String seedActiveUser(long userId, long roleId, boolean grant608) {
    jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
    jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", roleId);
    jdbcTemplate.update(
        "DELETE FROM role_permissions WHERE permissions_id = 608 AND role_id = ?", roleId);
    jdbcTemplate.update(
        "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
        roleId,
        "cost-approval-角色-" + roleId,
        "成本键审批测试角色");
    if (grant608) {
      jdbcTemplate.update(
          "INSERT IGNORE INTO permissions (id, permissions_name, permissions_desc) VALUES "
              + "(608, 'PLATFORM_DYNAMIC_CONFIG_MANAGE', '平台动态配置管理')");
      jdbcTemplate.update(
          "INSERT INTO role_permissions (role_id, permissions_id) VALUES (?, 608)", roleId);
    }
    jdbcTemplate.update(
        "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time) "
            + "VALUES (?, ?, ?, ?, ?, ?, 1, NOW())",
        userId,
        "e10adc3949ba59abbe56e057f20f883e",
        "cost-approval-用户-" + userId,
        "1380000" + userId,
        "cost-" + userId + "@slz.com",
        roleId);
    return RoleTokenSupport.tokenOfUserId(userId);
  }

  @Test
  @DisplayName("全链路：608 角色提交 -> 超管审批通过 -> 动态配置真写入且版本回填")
  void fullApprovalWorkflowWithAppliedVersion() throws Exception {
    String requesterToken = seedActiveUser(401L, 400L, true);
    String adminToken = RoleTokenSupport.tokenOfUserId(1L);

    // 1. 提交申请
    String submitPayload =
        "{\"configKey\":\""
            + COST_KEY
            + "\",\"requestedValue\":\"true\",\"reason\":\"评测需要开启Hyde\"}";
    MvcResult submitRes =
        mockMvc
            .perform(
                post("/platform/config/cost-requests")
                    .header("token", requesterToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(submitPayload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(1))
            .andExpect(jsonPath("$.data.status").value("PENDING"))
            .andExpect(jsonPath("$.data.configKey").value(COST_KEY))
            .andExpect(jsonPath("$.data.requestedValue").value("true"))
            .andReturn();

    JsonNode json = objectMapper.readTree(submitRes.getResponse().getContentAsString());
    long requestId = json.path("data").path("id").asLong();

    // 2. 超管审批通过
    mockMvc
        .perform(
            post("/platform/config/cost-requests/" + requestId + "/approve")
                .header("token", adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.status").value("APPROVED"))
        .andExpect(jsonPath("$.data.approverId").value(1))
        .andExpect(jsonPath("$.data.appliedConfigVersion").value(notNullValue()))
        .andExpect(jsonPath("$.data.appliedConfigVersion").value(greaterThan(0)));

    // 3. 验证动态配置真写入
    mockMvc
        .perform(get("/platform/config/items/" + COST_KEY).header("token", adminToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.value").value("true"));
  }

  @Test
  @DisplayName("撤回流程：申请人本人撤回处于 PENDING 态的单据成功")
  void withdrawWorkflow() throws Exception {
    String requesterToken = seedActiveUser(411L, 410L, true);

    String submitPayload =
        "{\"configKey\":\"" + COST_KEY + "\",\"requestedValue\":\"true\",\"reason\":\"测试撤回\"}";
    MvcResult submitRes =
        mockMvc
            .perform(
                post("/platform/config/cost-requests")
                    .header("token", requesterToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(submitPayload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(1))
            .andReturn();

    JsonNode json = objectMapper.readTree(submitRes.getResponse().getContentAsString());
    long requestId = json.path("data").path("id").asLong();

    // 撤回
    mockMvc
        .perform(
            post("/platform/config/cost-requests/" + requestId + "/withdraw")
                .header("token", requesterToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.status").value("WITHDRAWN"));
  }

  @Test
  @DisplayName("驳回流程：超管驳回 PENDING 态单据并记录驳回理由")
  void rejectWorkflow() throws Exception {
    String requesterToken = seedActiveUser(421L, 420L, true);
    String adminToken = RoleTokenSupport.tokenOfUserId(1L);

    String submitPayload =
        "{\"configKey\":\"" + COST_KEY + "\",\"requestedValue\":\"true\",\"reason\":\"申请开通\"}";
    MvcResult submitRes =
        mockMvc
            .perform(
                post("/platform/config/cost-requests")
                    .header("token", requesterToken)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(submitPayload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(1))
            .andReturn();

    JsonNode json = objectMapper.readTree(submitRes.getResponse().getContentAsString());
    long requestId = json.path("data").path("id").asLong();

    // 超管驳回
    mockMvc
        .perform(
            post("/platform/config/cost-requests/" + requestId + "/reject")
                .header("token", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rejectReason\":\"预算超标\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.status").value("REJECTED"))
        .andExpect(jsonPath("$.data.rejectReason").value("预算超标"));
  }

  @Test
  @DisplayName("一键一单在途：同键已有 PENDING 申请单时再次提交被拒绝")
  void duplicatePendingRequestBlocked() throws Exception {
    String requesterToken = seedActiveUser(431L, 430L, true);

    String submitPayload =
        "{\"configKey\":\"" + COST_KEY + "\",\"requestedValue\":\"true\",\"reason\":\"首单\"}";
    mockMvc
        .perform(
            post("/platform/config/cost-requests")
                .header("token", requesterToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(submitPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));

    // 重复提交同一键
    mockMvc
        .perform(
            post("/platform/config/cost-requests")
                .header("token", requesterToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"configKey\":\""
                        + COST_KEY
                        + "\",\"requestedValue\":\"false\",\"reason\":\"二单\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(96007));
  }

  @Test
  @DisplayName("权限防线：无 608 角色拦截 12002；非超管审批/驳回服务层档位闸拒绝 96005")
  void permissionGuards() throws Exception {
    String userWithout608 = seedActiveUser(441L, 440L, false);
    String normal608User = seedActiveUser(442L, 442L, true);

    // 1. 无 608 角色访问 5 端点均被拦截器拒绝（12002）
    mockMvc
        .perform(get("/platform/config/cost-requests").header("token", userWithout608))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));

    mockMvc
        .perform(
            post("/platform/config/cost-requests")
                .header("token", userWithout608)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"configKey\":\"" + COST_KEY + "\",\"requestedValue\":\"true\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));

    // 2. 有 608 但非超管尝试审批/驳回 -> 服务层超管闸拒绝（96005）
    // 先由 normal608User 提交一单
    MvcResult submitRes =
        mockMvc
            .perform(
                post("/platform/config/cost-requests")
                    .header("token", normal608User)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"configKey\":\"" + COST_KEY + "\",\"requestedValue\":\"true\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(1))
            .andReturn();

    JsonNode json = objectMapper.readTree(submitRes.getResponse().getContentAsString());
    long requestId = json.path("data").path("id").asLong();

    mockMvc
        .perform(
            post("/platform/config/cost-requests/" + requestId + "/approve")
                .header("token", normal608User))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(96005));

    mockMvc
        .perform(
            post("/platform/config/cost-requests/" + requestId + "/reject")
                .header("token", normal608User)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rejectReason\":\"非超管越权\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(96005));
  }
}
