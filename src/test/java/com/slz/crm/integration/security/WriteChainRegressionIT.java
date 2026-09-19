package com.slz.crm.integration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.integration.RoleTokenSupport;
import com.slz.crm.integration.TestRole;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/** 红灯写链路回归 IT（阶段2 M5 + 阶段3 B3/B8）。 */
@AutoConfigureMockMvc
@Transactional
@Sql(
    scripts = {"/init_data.sql"},
    executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class WriteChainRegressionIT extends AbstractMySqlIT {

  @Autowired private MockMvc mockMvc;

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @DisplayName("删除商业活动应级联清除活动-联系人/活动-用户/附件")
  void deleteActivityShouldCascadeAssociationsAndAttachments() throws Exception {
    jdbcTemplate.update(
        "INSERT INTO business_activity (id, activity_title, activity_type, activity_content, activity_time, company_id, creator_id) "
            + "VALUES (20, '删除级联测试活动', '拜访', '内容', NOW(), 1, 1)");
    jdbcTemplate.update(
        "INSERT INTO business_activity_contact (id, activity_id, contact_id, creator_id) VALUES (20, 20, 1, 1)");
    jdbcTemplate.update(
        "INSERT INTO business_activity_user (id, activity_id, user_id, creator_id) VALUES (20, 20, 2, 1)");
    jdbcTemplate.update(
        "INSERT INTO approval_attachment (id, and_id, model_name, file_name, file_path, file_size, file_type) "
            + "VALUES (20, 20, 'BUSINESS_ACTIVITY', 'a.txt', '/tmp/a.txt', 10, 'txt')");

    mockMvc
        .perform(
            delete("/business/activity")
                .header("token", RoleTokenSupport.tokenOf(TestRole.ADMIN))
                .contentType(MediaType.APPLICATION_JSON)
                .content("[20]"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));

    assertThat(count("SELECT COUNT(*) FROM business_activity WHERE id = 20"))
        .as("活动主表应被删除")
        .isZero();
    assertThat(count("SELECT COUNT(*) FROM business_activity_contact WHERE activity_id = 20"))
        .as("活动-联系人关联应级联删除")
        .isZero();
    assertThat(count("SELECT COUNT(*) FROM business_activity_user WHERE activity_id = 20"))
        .as("活动-用户关联应级联删除")
        .isZero();
    assertThat(
            count(
                "SELECT COUNT(*) FROM approval_attachment WHERE and_id = 20 AND model_name = 'BUSINESS_ACTIVITY'"))
        .as("活动附件应级联删除")
        .isZero();
  }

  @Test
  @DisplayName("删除任务应级联删除任务评论")
  void deleteTaskShouldCascadeComments() throws Exception {
    jdbcTemplate.update(
        "INSERT INTO task_comment (id, task_id, content, creator_id) VALUES (20, 1, '待删除评论', 1)");

    mockMvc
        .perform(delete("/contactTask/1").header("token", RoleTokenSupport.tokenOf(TestRole.ADMIN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1));

    assertThat(count("SELECT COUNT(*) FROM task_comment WHERE task_id = 1"))
        .as("任务评论应随任务级联删除")
        .isZero();
  }

  private int count(String sql) {
    Integer value = jdbcTemplate.queryForObject(sql, Integer.class);
    return value == null ? 0 : value;
  }
}
