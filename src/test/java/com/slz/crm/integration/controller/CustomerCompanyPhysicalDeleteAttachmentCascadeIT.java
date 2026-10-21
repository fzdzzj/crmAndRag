package com.slz.crm.integration.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.CustomerCompanyService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.jdbc.Sql;

/**
 * 客户公司物理级联删的附件孤儿债真库集成测试（fix-backlog-batch3 任务 4.3）。
 *
 * <p><b>缺陷背景</b>：{@code approval_attachment} 是多态附件表（{@code and_id} + {@code model_name} 判别）、无
 * {@code is_deleted} 列，且不在 {@code ForeignKeyConfig} 的任何关联图里（子图为空）。公司物理删走 {@code deleteCascade}
 * 会把名下的 {@code business_activity} / {@code contact_task} 一并物理删掉，但级联机器带不了 {@code model_name}
 * 判别也无法做磁盘清理——这些宿主的附件行与磁盘文件此前全部残留成孤儿。
 *
 * <p><b>本 IT 的证据口径</b>（Testcontainers 真 MySQL + 生产表结构 + 真实事务）：
 *
 * <ol>
 *   <li>公司物理删成功，其名下商业活动与联络任务随级联删物理删除；
 *   <li>这两类宿主的附件行（{@code model_name=business_activity}/{@code contact_task}）清零，磁盘文件同删；
 *   <li>同 {@code and_id} 的他模型对照附件存活（{@code model_name=contact_task} 挂在活动 id 上）——证明清理按 {@code
 *       (and_id, model_name)} 精确命中，没有裸 {@code and_id} 级联式跨模型误删。
 * </ol>
 */
@DisplayName("客户公司物理删-子宿主附件级联清理")
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class CustomerCompanyPhysicalDeleteAttachmentCascadeIT extends AbstractMySqlIT {

  /** 固定主键，便于 @AfterEach 精确清理且不与其他 IT 的种子数据碰撞。 */
  private static final long COMPANY_ID = 9500101L;

  private static final long ACTIVITY_ID = 9600101L;

  private static final long TASK_ID = 9600201L;

  private static final String ACTIVITY_FILE_NAME = "it-company-activity-attachment.txt";

  private static final String TASK_FILE_NAME = "it-company-task-attachment.txt";

  private static final String CROSS_MODEL_FILE_NAME = "it-company-cross-model-attachment.txt";

  @Autowired private CustomerCompanyService customerCompanyService;

  @Autowired private ApprovalAttachmentService approvalAttachmentService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seedCompanyWithChildHostsAndAttachments() {
    BaseUnit.setCurrentRole(roleOf(1L));
    jdbcTemplate.update(
        "INSERT INTO customer_company (id, company_name, creator_id, owner_id, is_deleted, grade)"
            + " VALUES (?, ?, ?, ?, ?, ?)",
        COMPANY_ID,
        "IT_附件孤儿债_公司",
        1L,
        1L,
        false,
        1);
    jdbcTemplate.update(
        "INSERT INTO business_activity "
            + "(id, activity_title, activity_type, activity_time, company_id, creator_id)"
            + " VALUES (?, ?, ?, NOW(), ?, ?)",
        ACTIVITY_ID,
        "IT_附件孤儿债_活动",
        "电话",
        COMPANY_ID,
        1L);
    jdbcTemplate.update(
        "INSERT INTO contact_task "
            + "(id, task_title, company_id, task_type, priority, assignee_id, creator_id)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        TASK_ID,
        "IT_附件孤儿债_任务",
        COMPANY_ID,
        "电话",
        5,
        1L,
        1L);
    // 目标附件：分别真属于商业活动与联络任务
    approvalAttachmentService.saveAttachments(
        ACTIVITY_ID, ModelName.BUSINESS_ACTIVITY, List.of(attachmentDTO(ACTIVITY_FILE_NAME)));
    approvalAttachmentService.saveAttachments(
        TASK_ID, ModelName.CONTACT_TASK, List.of(attachmentDTO(TASK_FILE_NAME)));
    // 跨模型对照附件：同 and_id（=活动 id），只有 model_name 不同——裸 and_id 清理会误杀这一行
    approvalAttachmentService.saveAttachments(
        ACTIVITY_ID, ModelName.CONTACT_TASK, List.of(attachmentDTO(CROSS_MODEL_FILE_NAME)));
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update(
        "DELETE FROM approval_attachment WHERE and_id IN (?, ?)", ACTIVITY_ID, TASK_ID);
    jdbcTemplate.update("DELETE FROM business_activity WHERE id = ?", ACTIVITY_ID);
    jdbcTemplate.update("DELETE FROM contact_task WHERE id = ?", TASK_ID);
    jdbcTemplate.update("DELETE FROM customer_company WHERE id = ?", COMPANY_ID);
    deleteRecursively(attachmentsDirectory(ModelName.BUSINESS_ACTIVITY, ACTIVITY_ID));
    deleteRecursively(attachmentsDirectory(ModelName.CONTACT_TASK, TASK_ID));
    deleteRecursively(attachmentsDirectory(ModelName.CONTACT_TASK, ACTIVITY_ID));
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("物理删公司：子宿主附件行与磁盘文件同清，同 and_id 他模型附件存活")
  void physicalDeleteCompany_cleansChildHostAttachmentsOnly() throws IOException {
    List<ApprovalAttachmentEntity> activityAttachments =
        approvalAttachmentService.listEntitiesByAndIds(
            List.of(ACTIVITY_ID), ModelName.BUSINESS_ACTIVITY);
    List<ApprovalAttachmentEntity> taskAttachments =
        approvalAttachmentService.listEntitiesByAndIds(List.of(TASK_ID), ModelName.CONTACT_TASK);
    List<ApprovalAttachmentEntity> crossModel =
        approvalAttachmentService.listEntitiesByAndIds(
            List.of(ACTIVITY_ID), ModelName.CONTACT_TASK);
    assertEquals(1, activityAttachments.size(), "种子：活动附件必须落库");
    assertEquals(1, taskAttachments.size(), "种子：任务附件必须落库");
    assertEquals(1, crossModel.size(), "种子：跨模型对照附件必须落库");

    Path activityFile = filesystemPathOf(activityAttachments.get(0));
    Path taskFile = filesystemPathOf(taskAttachments.get(0));
    Path crossModelFile = filesystemPathOf(crossModel.get(0));
    assertTrue(Files.exists(activityFile), "种子：活动附件磁盘文件必须存在");
    assertTrue(Files.exists(taskFile), "种子：任务附件磁盘文件必须存在");
    assertTrue(Files.exists(crossModelFile), "种子：对照附件磁盘文件必须存在");

    assertTrue(customerCompanyService.deleteByIds(List.of(COMPANY_ID)) >= 1, "公司物理删必须成功");

    assertEquals(
        0, count("SELECT COUNT(*) FROM customer_company WHERE id = ?", COMPANY_ID), "公司本身物理删除");
    assertEquals(
        0,
        count("SELECT COUNT(*) FROM business_activity WHERE id = ?", ACTIVITY_ID),
        "商业活动随级联物理删除");
    assertEquals(
        0, count("SELECT COUNT(*) FROM contact_task WHERE id = ?", TASK_ID), "联络任务随级联物理删除");
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM approval_attachment WHERE id = ?",
            activityAttachments.get(0).getId()),
        "活动附件行必须清零（孤儿债修复前此处残留）");
    assertEquals(
        0,
        count(
            "SELECT COUNT(*) FROM approval_attachment WHERE id = ?",
            taskAttachments.get(0).getId()),
        "任务附件行必须清零（孤儿债修复前此处残留）");
    assertFalse(Files.exists(activityFile), "活动附件磁盘文件必须删除");
    assertFalse(Files.exists(taskFile), "任务附件磁盘文件必须删除");
    assertEquals(
        1,
        count("SELECT COUNT(*) FROM approval_attachment WHERE id = ?", crossModel.get(0).getId()),
        "同 and_id 他模型对照附件必须存活（裸 and_id 清理=跨模型误删回归网）");
    assertTrue(Files.exists(crossModelFile), "对照附件磁盘文件必须存活");
  }

  private static RoleAO roleOf(Long userId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    return role;
  }

  private static ApprovalAttachmentDTO attachmentDTO(String fileName) {
    ApprovalAttachmentDTO dto = new ApprovalAttachmentDTO();
    dto.setFileName(fileName);
    dto.setFileData(
        new MockMultipartFile(
            "file",
            fileName,
            "text/plain",
            (fileName + " content").getBytes(StandardCharsets.UTF_8)));
    return dto;
  }

  /** 附件磁盘路径 = 落库的 file_path（目录） + file_name，与 IOUtils#getFileByEntity 同口径。 */
  private static Path filesystemPathOf(ApprovalAttachmentEntity entity) {
    return Paths.get(entity.getFilePath()).resolve(entity.getFileName());
  }

  private static Path attachmentsDirectory(String modelName, long andId) {
    return Paths.get(System.getProperty("user.dir"), "test-files", modelName)
        .resolve(String.valueOf(andId));
  }

  private long count(String sql, Object... args) {
    Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
    return value == null ? 0L : value;
  }

  private static void deleteRecursively(Path directory) {
    if (!Files.exists(directory)) {
      return;
    }
    try (var paths = Files.walk(directory)) {
      paths
          .sorted(java.util.Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // 清理阶段的残留不改变测试结论
                }
              });
    } catch (IOException ignored) {
      // 同上：清理失败不影响断言
    }
  }
}
