package com.slz.crm.integration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.integration.RoleTokenSupport;
import com.slz.crm.integration.TestRole;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * 超管「AI 用量」接口集成测试（token-usage-admin-view 任务 2.5/2.6，Testcontainers MySQL）。
 *
 * <p><b>数据面</b>：种 2 个真实用户（901/902）+ {@code user:system} + {@code user:unknown} + 一个已删除用户引用 {@code
 * user:999}，另加三条边界行——恰在 start（含）、恰在 end（不含）、start 前 1 秒（不含）。断言聚合值 / 排序 / 分页 / 区间半开边界 / usage_type
 * 过滤（含历史小写口径）。
 *
 * <p><b>鉴权口径</b>：810 AI_TOKEN_USAGE_VIEW 只种权限行不授业务角色（V43），超管 roleId=1 由拦截器直通；本 IT 用 jdbcTemplate
 * 补种角色/用户，正向=授 810 的业务角色 200、反向=无 810 的普通用户 12002。
 *
 * <p><b>真库建表</b>：Spring IT 上下文不跑 Flyway（auto-table 不覆盖 platform 域），本类 {@code @BeforeAll} 以 {@code
 * CREATE TABLE IF NOT EXISTS} 兜底建出 {@code platform_token_usage}；若上下文已由 Flyway 建表则整句空转。
 */
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class AiTokenUsageControllerIT extends AbstractMySqlIT {

  private static final String PASSWORD = "e10adc3949ba59abbe56e057f20f883e";

  /** 基准时间（秒级），窗口 = [BASE-10d, BASE-5d)，保证终点不晚于「当前时间」。 */
  private static final LocalDateTime BASE = LocalDateTime.now().withNano(0);

  private static final LocalDateTime WINDOW_START = BASE.minusDays(10);
  private static final LocalDateTime WINDOW_END = BASE.minusDays(5);

  @Autowired private MockMvc mockMvc;

  @Autowired private JdbcTemplate jdbcTemplate;

  /** 无 Flyway 时平台计量表不存在：幂等建表，供本类用例插入明细。 */
  @BeforeAll
  static void ensurePlatformTokenUsageTable() throws Exception {
    try (Connection connection =
            DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TABLE IF NOT EXISTS platform_token_usage ("
              + " id bigint auto_increment primary key,"
              + " model varchar(100) not null,"
              + " user_id_ref varchar(100) not null,"
              + " session_id varchar(100) null,"
              + " knowledge_base_id bigint null,"
              + " usage_type varchar(30) not null,"
              + " prompt_tokens bigint not null default 0,"
              + " completion_tokens bigint not null default 0,"
              + " total_tokens bigint not null default 0,"
              + " success tinyint not null default 1,"
              + " create_time datetime not null default CURRENT_TIMESTAMP,"
              + " update_time datetime null"
              // 刻意不建索引：若上下文随后跑 Flyway，V5 会无条件 CREATE INDEX（无 IF NOT EXISTS），
              // 预先建同名索引会让全链迁移撞号失败；本类数据量极小，无索引不影响断言。
              + ")");
    }
  }

  @Test
  @DisplayName("by-user：聚合值/失败次数/用户名还原/占比/排序 + 半开区间边界（start 含、end 不含）")
  void byUserAggregatesWithinHalfOpenWindow() throws Exception {
    seedUsageUsers();
    seedUsageRows();

    mockMvc
        .perform(
            get("/ai/token-usage/by-user")
                .header("token", RoleTokenSupport.tokenOf(TestRole.ADMIN))
                .param("startTime", WINDOW_START.toString())
                .param("endTime", WINDOW_END.toString())
                .param("pageNum", "1")
                .param("pageSize", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.total").value(5))
        .andExpect(jsonPath("$.data.records[0].userRef").value("user:902"))
        .andExpect(jsonPath("$.data.records[0].userName").value("李四"))
        .andExpect(jsonPath("$.data.records[0].totalTokens").value(300))
        .andExpect(jsonPath("$.data.records[0].calls").value(2))
        .andExpect(jsonPath("$.data.records[0].failedCalls").value(1))
        .andExpect(jsonPath("$.data.records[0].sharePct").value(61.6))
        // 901 = 150 + 边界行 10；start 前 1 秒的 999 不计入，故不是 1159
        .andExpect(jsonPath("$.data.records[1].userId").value(901))
        .andExpect(jsonPath("$.data.records[1].userName").value("张三"))
        .andExpect(jsonPath("$.data.records[1].totalTokens").value(160))
        .andExpect(jsonPath("$.data.records[1].calls").value(3))
        .andExpect(jsonPath("$.data.records[2].userName").value("系统"))
        .andExpect(jsonPath("$.data.records[3].userName").value("已删除用户(#999)"))
        .andExpect(jsonPath("$.data.records[4].userName").value("未知来源"));
  }

  @Test
  @DisplayName("分页：pageSize=2 第 2/3 页切片正确，total 恒为去重用户数")
  void byUserPaginates() throws Exception {
    seedUsageUsers();
    seedUsageRows();

    mockMvc
        .perform(
            get("/ai/token-usage/by-user")
                .header("token", RoleTokenSupport.tokenOf(TestRole.ADMIN))
                .param("startTime", WINDOW_START.toString())
                .param("endTime", WINDOW_END.toString())
                .param("pageNum", "2")
                .param("pageSize", "2"))
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.total").value(5))
        .andExpect(jsonPath("$.data.records.length()").value(2))
        .andExpect(jsonPath("$.data.records[0].userRef").value("user:system"))
        .andExpect(jsonPath("$.data.records[1].userRef").value("user:999"));

    mockMvc
        .perform(
            get("/ai/token-usage/by-user")
                .header("token", RoleTokenSupport.tokenOf(TestRole.ADMIN))
                .param("startTime", WINDOW_START.toString())
                .param("endTime", WINDOW_END.toString())
                .param("pageNum", "3")
                .param("pageSize", "2"))
        .andExpect(jsonPath("$.data.total").value(5))
        .andExpect(jsonPath("$.data.records.length()").value(1))
        .andExpect(jsonPath("$.data.records[0].userRef").value("user:unknown"));
  }

  @Test
  @DisplayName("usageType 过滤：chat 命中大写 CHAT 与历史小写 chat 两种写入口径")
  void byUserFiltersByUsageType() throws Exception {
    seedUsageUsers();
    seedUsageRows();

    mockMvc
        .perform(
            get("/ai/token-usage/by-user")
                .header("token", RoleTokenSupport.tokenOf(TestRole.ADMIN))
                .param("startTime", WINDOW_START.toString())
                .param("endTime", WINDOW_END.toString())
                .param("usageType", "chat")
                .param("pageNum", "1")
                .param("pageSize", "10"))
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.total").value(4))
        .andExpect(jsonPath("$.data.records[0].userRef").value("user:902"))
        .andExpect(jsonPath("$.data.records[1].userRef").value("user:901"))
        .andExpect(jsonPath("$.data.records[2].userRef").value("user:999"))
        .andExpect(jsonPath("$.data.records[3].userRef").value("user:unknown"));
  }

  @Test
  @DisplayName("summary：总 token/调用/失败/用户数 + 按 usage_type 分组占比（大小写归并）")
  void summaryAggregatesByType() throws Exception {
    seedUsageUsers();
    seedUsageRows();

    mockMvc
        .perform(
            get("/ai/token-usage/summary")
                .header("token", RoleTokenSupport.tokenOf(TestRole.ADMIN))
                .param("startTime", WINDOW_START.toString())
                .param("endTime", WINDOW_END.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.totalTokens").value(487))
        .andExpect(jsonPath("$.data.calls").value(8))
        .andExpect(jsonPath("$.data.failedCalls").value(2))
        .andExpect(jsonPath("$.data.userCount").value(5))
        .andExpect(jsonPath("$.data.usageTypes[0].usageType").value("chat"))
        .andExpect(jsonPath("$.data.usageTypes[0].totalTokens").value(472))
        .andExpect(jsonPath("$.data.usageTypes[0].calls").value(5))
        .andExpect(jsonPath("$.data.usageTypes[0].failedCalls").value(2))
        .andExpect(jsonPath("$.data.usageTypes[0].sharePct").value(96.92))
        // 按总 token 降序：chat 472 → summary 15 → embedding 0
        .andExpect(jsonPath("$.data.usageTypes[1].usageType").value("summary"))
        .andExpect(jsonPath("$.data.usageTypes[1].totalTokens").value(15))
        .andExpect(jsonPath("$.data.usageTypes[1].calls").value(1))
        .andExpect(jsonPath("$.data.usageTypes[1].sharePct").value(3.08))
        .andExpect(jsonPath("$.data.usageTypes[2].usageType").value("embedding"))
        .andExpect(jsonPath("$.data.usageTypes[2].totalTokens").value(0));
  }

  @Test
  @DisplayName("权限反向：无 810 的普通登录用户 → 12002")
  void rejectsUserWithoutPermission() throws Exception {
    seedRoleAndUser(3L, TestRole.NORMAL.getUserId(), "普通用户", false);
    mockMvc
        .perform(
            get("/ai/token-usage/by-user")
                .header("token", RoleTokenSupport.tokenOf(TestRole.NORMAL)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
  }

  @Test
  @DisplayName("权限正向：授 810 的业务角色 → 200 且返回结构正确")
  void allowsRoleWithPermission() throws Exception {
    seedRoleAndUser(20L, 201L, "用量查看员", true);
    mockMvc
        .perform(
            get("/ai/token-usage/summary")
                .header("token", RoleTokenSupport.tokenOfUserId(201L))
                .param("startTime", WINDOW_START.toString())
                .param("endTime", WINDOW_END.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.totalTokens").value(0))
        .andExpect(jsonPath("$.data.calls").value(0))
        .andExpect(jsonPath("$.data.usageTypes.length()").value(0));
  }

  @Test
  @DisplayName("参数校验：pageSize 越界/区间倒置/非法 usageType/单端缺省 → 96007")
  void rejectsInvalidQueryParameters() throws Exception {
    String token = RoleTokenSupport.tokenOf(TestRole.ADMIN);
    String[] badQueries = {
      "/ai/token-usage/by-user?pageSize=201",
      "/ai/token-usage/by-user?pageNum=0",
      "/ai/token-usage/by-user?startTime=" + WINDOW_END + "&endTime=" + WINDOW_START,
      "/ai/token-usage/by-user?usageType=image",
      "/ai/token-usage/summary?startTime=" + WINDOW_START
    };
    for (String query : badQueries) {
      mockMvc
          .perform(get(query).header("token", token))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.code").value(96007));
    }
  }

  /** 业务角色 + 在职用户；grant810 控制是否种 810 权限并授权。 */
  private void seedRoleAndUser(long roleId, long userId, String realName, boolean grant810) {
    jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
    jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?", roleId);
    jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", roleId);
    jdbcTemplate.update(
        "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
        roleId,
        "AI用量角色" + roleId,
        "token-usage-admin-view IT 用例角色");
    if (grant810) {
      jdbcTemplate.update(
          "INSERT IGNORE INTO permissions (id, permissions_name, permissions_desc)"
              + " VALUES (810, 'AI_TOKEN_USAGE_VIEW', '查看AI用量')");
      jdbcTemplate.update(
          "INSERT INTO role_permissions (role_id, permissions_id) VALUES (?, 810)", roleId);
    }
    jdbcTemplate.update(
        "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time)"
            + " VALUES (?, ?, ?, ?, ?, ?, 1, NOW())",
        userId,
        PASSWORD,
        realName,
        "13900000" + userId,
        "token-usage-" + userId + "@slz.com",
        roleId);
  }

  /** 两个真实用户（901 张三 / 902 李四），供用户名还原与已删除用户对照。 */
  private void seedUsageUsers() {
    for (long userId : new long[] {901L, 902L}) {
      String name = userId == 901L ? "张三" : "李四";
      jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
      jdbcTemplate.update(
          "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time)"
              + " VALUES (?, ?, ?, ?, ?, 1, 1, NOW())",
          userId,
          PASSWORD,
          name,
          "13900000" + userId,
          "token-usage-" + userId + "@slz.com");
    }
  }

  /** 窗口内 8 行 + 3 行边界/区间外，覆盖 system/unknown/已删除用户与小写 usage_type。 */
  private void seedUsageRows() {
    insertUsage("user:901", "CHAT", 100, 50, true, WINDOW_START.plusDays(1));
    insertUsage("user:901", "EMBEDDING", 0, 0, true, WINDOW_START.plusDays(2));
    insertUsage("user:902", "CHAT", 200, 100, false, WINDOW_START.plusDays(1));
    insertUsage("user:902", "EMBEDDING", 0, 0, true, WINDOW_START.plusDays(2));
    insertUsage("user:system", "SUMMARY", 10, 5, true, WINDOW_START.plusDays(3));
    insertUsage("user:unknown", "CHAT", 1, 1, false, WINDOW_START.plusDays(4));
    // 历史写入口径的另一种大小写：过滤 chat 时也必须命中
    insertUsage("user:999", "chat", 5, 5, true, WINDOW_START.plusDays(4));
    // 恰在起点：含（半开区间左闭）
    insertUsage("user:901", "CHAT", 7, 3, true, WINDOW_START);
    // 恰在终点：不含（半开区间右开）
    insertUsage("user:902", "CHAT", 1000, 0, true, WINDOW_END);
    // 起点前 1 秒：不含
    insertUsage("user:901", "CHAT", 999, 0, true, WINDOW_START.minusSeconds(1));
  }

  private void insertUsage(
      String userRef,
      String usageType,
      long prompt,
      long completion,
      boolean success,
      LocalDateTime createTime) {
    jdbcTemplate.update(
        "INSERT INTO platform_token_usage"
            + " (model, user_id_ref, usage_type, prompt_tokens, completion_tokens,"
            + " total_tokens, success, create_time) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        "qwen-plus",
        userRef,
        usageType,
        prompt,
        completion,
        prompt + completion,
        success ? 1 : 0,
        Timestamp.valueOf(createTime));
  }
}
