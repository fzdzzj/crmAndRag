package com.slz.crm.integration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.integration.RoleTokenSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 动态配置键级权限分层 ACL 端到端 IT（add-dynamic-config-key-tier-acl 任务 3.3）。
 *
 * <p>真库 + 真拦截器链验证四象限：
 *
 * <ul>
 *   <li>持 608 业务角色：读 3 端点 + 写运营档键（topK）+ 缓存刷新全 code=1；
 *   <li>无 608 角色：全 7 端点 PERMISSION_DENIED(12002)；
 *   <li>持 608 非超管写成本/结构档键：服务层档位闸 FORBIDDEN(96005)；超管（roleId=1 拦截器直通）写成本档键 code=1；
 *   <li>冻结（roleId=0 → 12006）/离职（roleId=2 → 12007）状态闸先于权限注解（D3 语义在新挂注解端点上防回归）。
 * </ul>
 *
 * <p>Spring IT 上下文不跑 Flyway（auto-table 建表），608 权限种子由用例内 jdbcTemplate 落库； {@code @Transactional}
 * 回滚隔离，禁止改共享 init_data.sql（同 PermissionsInterceptorStatusIT 模式）。
 */
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class DynamicConfigTierAclIT extends AbstractMySqlIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private JdbcTemplate jdbcTemplate;

  /** 种业务角色 + 在职用户（status=1）并返回 token；grant608 控制是否种 608 权限并授权。 */
  private String seedActiveUser(long userId, long roleId, boolean grant608) {
    jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
    jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", roleId);
    jdbcTemplate.update("DELETE FROM role_permissions WHERE permissions_id = 608");
    jdbcTemplate.update("DELETE FROM permissions WHERE id = 608");
    jdbcTemplate.update(
        "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
        roleId,
        "tier-acl用例角色-" + roleId,
        "键级ACL用例业务角色");
    if (grant608) {
      jdbcTemplate.update(
          "INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES "
              + "(608, 'PLATFORM_DYNAMIC_CONFIG_MANAGE', '平台动态配置管理')");
      jdbcTemplate.update(
          "INSERT INTO role_permissions (role_id, permissions_id) VALUES (?, 608)", roleId);
    }
    jdbcTemplate.update(
        "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time) "
            + "VALUES (?, ?, ?, ?, ?, ?, 1, NOW())",
        userId,
        "e10adc3949ba59abbe56e057f20f883e",
        "tier-acl用例用户",
        "13900000" + userId,
        "tier-acl-" + userId + "@slz.com",
        roleId);
    return RoleTokenSupport.tokenOfUserId(userId);
  }

  /** 种状态哨兵角色（roleId=0 需 NO_AUTO_VALUE_ON_ZERO）+ 指定 status 用户并返回 token。 */
  private String seedStatusUser(long userId, long roleId, int status) {
    jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
    jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", roleId);
    String previousSqlMode = null;
    if (roleId == 0L) {
      previousSqlMode = jdbcTemplate.queryForObject("SELECT @@SESSION.sql_mode", String.class);
      jdbcTemplate.execute(
          "SET SESSION sql_mode = CONCAT(@@SESSION.sql_mode, ',NO_AUTO_VALUE_ON_ZERO')");
    }
    try {
      jdbcTemplate.update(
          "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
          roleId,
          "状态哨兵角色-" + roleId,
          "状态闸用例角色");
    } finally {
      if (previousSqlMode != null) {
        jdbcTemplate.update("SET SESSION sql_mode = ?", previousSqlMode);
      }
    }
    jdbcTemplate.update(
        "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
        userId,
        "e10adc3949ba59abbe56e057f20f883e",
        "状态哨兵用户",
        "13900000" + userId,
        "status-acl-" + userId + "@slz.com",
        roleId,
        status);
    return RoleTokenSupport.tokenOfUserId(userId);
  }

  @Test
  @DisplayName("持 608 业务角色：读 3 端点 + 写运营档键 topK + 缓存刷新全 code=1")
  void holderOf608CanReadAndWriteOperationalKey() throws Exception {
    String token = seedActiveUser(301L, 300L, true);
    mockMvc
        .perform(get("/platform/config/items").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
    mockMvc
        .perform(get("/platform/config/items/rag.retrieval.topK").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
    mockMvc
        .perform(get("/platform/config/items/rag.retrieval.topK/history").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
    mockMvc
        .perform(
            post("/platform/config/items")
                .header("token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"key\":\"rag.retrieval.topK\",\"value\":\"8\",\"remark\":\"tier-acl IT 运营档写入\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
    mockMvc
        .perform(post("/platform/config/cache/refresh").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
  }

  @Test
  @DisplayName("无 608 角色：全 7 端点 PERMISSION_DENIED(12002)")
  void roleWithout608RejectedOnAllEndpoints() throws Exception {
    String token = seedActiveUser(311L, 310L, false);
    mockMvc
        .perform(get("/platform/config/items").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
    mockMvc
        .perform(get("/platform/config/items/rag.retrieval.topK").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
    mockMvc
        .perform(get("/platform/config/items/rag.retrieval.topK/history").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
    mockMvc
        .perform(
            post("/platform/config/items")
                .header("token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"key\":\"rag.retrieval.topK\",\"value\":\"8\",\"remark\":\"无权角色\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
    mockMvc
        .perform(
            post("/platform/config/items/rag.retrieval.topK/rollback")
                .header("token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
    mockMvc
        .perform(delete("/platform/config/items/rag.retrieval.topK").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
    mockMvc
        .perform(post("/platform/config/cache/refresh").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
  }

  @Test
  @DisplayName("持 608 非超管写成本/结构档键 → 96005；超管直通写成本档键 code=1")
  void tierGateBlocksNonSuperAdminOnCostAndStructuralKeys() throws Exception {
    String token = seedActiveUser(321L, 320L, true);
    mockMvc
        .perform(
            post("/platform/config/items")
                .header("token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"key\":\"ai.model.chatModel\",\"value\":\"qwen-turbo\",\"remark\":\"成本档越权\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(96005));
    mockMvc
        .perform(
            post("/platform/config/items")
                .header("token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"key\":\"rag.retrieval.chunkSize\",\"value\":\"600\",\"remark\":\"结构档越权\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(96005));
    mockMvc
        .perform(
            post("/platform/config/items/rag.retrieval.chunkSize/rollback")
                .header("token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(96005));
    mockMvc
        .perform(delete("/platform/config/items/rag.retrieval.chunkSize").header("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(96005));
    // 超管（roleId=1，init_data 已种 userId=1）拦截器直通 + 服务层档位闸放行 → 写成本档键 code=1
    String adminToken = RoleTokenSupport.tokenOfUserId(1L);
    mockMvc
        .perform(
            post("/platform/config/items")
                .header("token", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"key\":\"ai.model.chatModel\",\"value\":\"qwen-turbo\",\"remark\":\"超管调档\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));
  }

  @Test
  @DisplayName("冻结(12006)/离职(12007)状态闸先于权限注解（新挂注解端点 D3 防回归）")
  void statusGatePrecedesPermissionAnnotation() throws Exception {
    String frozenToken = seedStatusUser(331L, 0L, 0);
    mockMvc
        .perform(get("/platform/config/items").header("token", frozenToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12006));
    String quitToken = seedStatusUser(332L, 2L, 0);
    mockMvc
        .perform(get("/platform/config/items").header("token", quitToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12007));
  }
}
