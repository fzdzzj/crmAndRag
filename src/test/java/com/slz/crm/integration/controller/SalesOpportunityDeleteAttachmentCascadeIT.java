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
import com.slz.crm.server.service.SalesOpportunityService;
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
 * 已关闭商机删除的级联链路真库集成测试（fix-approval-attachment-cascade-dead-edge 任务 1.1/3.1）。
 *
 * <p><b>缺陷背景</b>：{@code ForeignKeyConfig} 曾声明 SALES_STAGE_APPROVAL_RELATIONS → {@code
 * ApprovalAttachmentEntity#approvalId}，而真库 {@code approval_attachment} 只有多态列 {@code and_id}（配
 * {@code model_name} 判别）、实体只有 {@code andId}；该边从 Wave 0 起不可解析。生产环境 MP {@code TableInfo} 已初始化时，
 * {@code SalesOpportunityServiceImpl#delete} 在 stage=5 闸后必经的 {@code deleteCascade} 递归到该边即抛 {@code
 * ID_NOT_EXISTS}，整个删除事务回滚——已关闭商机删除端点全断。
 *
 * <p><b>本 IT 的证据口径</b>（Testcontainers 真 MySQL + 生产 Flyway/auto-table 真表 + 真实事务）：
 *
 * <ol>
 *   <li>修复后 {@code delete} 成功（修复前此处抛 {@code ID_NOT_EXISTS}，前证见 handoff）；
 *   <li>阶段审批随 {@code deleteCascade} 现行语义物理删除；
 *   <li>目标附件（{@code model_name=approval_attachment}, {@code and_id=审批id}）DB 行物理删 + 磁盘文件删除；
 *   <li>他模型同 {@code and_id} 对照附件（{@code model_name=business_activity}）DB 行与磁盘文件均存活——跨模型误删回归网。
 * </ol>
 */
@DisplayName("已关闭商机删除-阶段审批附件级联")
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class SalesOpportunityDeleteAttachmentCascadeIT extends AbstractMySqlIT {

  /** 固定主键，便于 @AfterEach 精确清理且不与其他 IT 的种子数据碰撞。 */
  private static final long OPPORTUNITY_ID = 9500001L;

  private static final long APPROVAL_ID = 9600001L;

  private static final String APPROVAL_FILE_NAME = "it-approval-attachment.txt";

  private static final String OTHER_MODEL_FILE_NAME = "it-activity-attachment.txt";

  @Autowired private SalesOpportunityService salesOpportunityService;

  @Autowired private ApprovalAttachmentService approvalAttachmentService;

  @Autowired private JdbcTemplate jdbcTemplate;

  @BeforeEach
  void seedClosedOpportunityWithStageApprovalAndAttachments() {
    authenticateAs(1L);
    jdbcTemplate.update(
        "INSERT INTO sales_opportunity "
            + "(id, opportunity_name, company_id, stage, owner_id, creator_id, approver_id,"
            + " is_deleted) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        OPPORTUNITY_ID,
        "IT_级联死边_已关闭商机",
        1L,
        5,
        1L,
        1L,
        1L,
        false);
    jdbcTemplate.update(
        "INSERT INTO sales_stage_approval "
            + "(id, opportunity_id, current_stage, target_stage, applicant_id, approver_id,"
            + " approval_status, version) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        APPROVAL_ID,
        OPPORTUNITY_ID,
        3,
        5,
        1L,
        1L,
        1,
        0);
    // 目标附件：语义上真属于阶段审批（model_name=approval_attachment, and_id=审批id）
    approvalAttachmentService.saveAttachments(
        APPROVAL_ID, ModelName.APPROVAL_ATTACHMENT, List.of(attachmentDTO(APPROVAL_FILE_NAME)));
    // 他模型对照附件：同 and_id（=审批id），只有 model_name 不同——裸 and_id 级联会误杀这一行
    approvalAttachmentService.saveAttachments(
        APPROVAL_ID, ModelName.BUSINESS_ACTIVITY, List.of(attachmentDTO(OTHER_MODEL_FILE_NAME)));
  }

  @AfterEach
  void cleanup() {
    jdbcTemplate.update("DELETE FROM approval_attachment WHERE and_id = ?", APPROVAL_ID);
    jdbcTemplate.update(
        "DELETE FROM sales_stage_approval WHERE opportunity_id = ?", OPPORTUNITY_ID);
    jdbcTemplate.update("DELETE FROM sales_opportunity WHERE id = ?", OPPORTUNITY_ID);
    deleteRecursively(attachmentsDirectory(ModelName.APPROVAL_ATTACHMENT));
    deleteRecursively(attachmentsDirectory(ModelName.BUSINESS_ACTIVITY));
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("删除已关闭商机：审批附件按 canonical 删除（DB 物理删+磁盘清理），他模型同 and_id 附件存活")
  void deleteClosedOpportunity_removesApprovalAttachmentsOnly() throws IOException {
    List<ApprovalAttachmentEntity> approvals = targetAttachments(ModelName.APPROVAL_ATTACHMENT);
    List<ApprovalAttachmentEntity> otherModel = targetAttachments(ModelName.BUSINESS_ACTIVITY);
    assertEquals(1, approvals.size(), "种子：目标审批附件必须落库");
    assertEquals(1, otherModel.size(), "种子：他模型对照附件必须落库");

    Path approvalFile = filesystemPathOf(approvals.get(0));
    Path otherModelFile = filesystemPathOf(otherModel.get(0));
    assertTrue(Files.exists(approvalFile), "种子：目标附件磁盘文件必须存在");
    assertTrue(Files.exists(otherModelFile), "种子：对照附件磁盘文件必须存在");

    assertTrue(
        salesOpportunityService.delete(OPPORTUNITY_ID), "已关闭商机删除必须成功（修复前此处抛 ID_NOT_EXISTS，整个事务回滚）");

    assertEquals(
        0,
        count("SELECT COUNT(*) FROM sales_stage_approval WHERE opportunity_id = ?", OPPORTUNITY_ID),
        "阶段审批随 deleteCascade 现行语义物理删除");
    assertEquals(
        0,
        count("SELECT COUNT(*) FROM approval_attachment WHERE id = ?", approvals.get(0).getId()),
        "目标附件 DB 行必须物理删除（附件表无 is_deleted，canonical 删除即物理删）");
    assertEquals(
        1,
        count("SELECT COUNT(*) FROM approval_attachment WHERE id = ?", otherModel.get(0).getId()),
        "他模型同 and_id 对照附件必须存活（裸 and_id 级联=跨模型误删回归网）");
    assertFalse(Files.exists(approvalFile), "目标附件磁盘文件必须删除（canonical removeByIds 含磁盘清理）");
    assertTrue(Files.exists(otherModelFile), "对照附件磁盘文件必须存活");
    assertEquals(
        1,
        count(
            "SELECT COUNT(*) FROM sales_opportunity WHERE id = ? AND is_deleted = 1",
            OPPORTUNITY_ID),
        "商机本身走既有逻辑删除");
  }

  private void authenticateAs(Long userId) {
    RoleAO role = new RoleAO();
    role.setId(userId);
    BaseUnit.setCurrentRole(role);
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

  private List<ApprovalAttachmentEntity> targetAttachments(String modelName) {
    return approvalAttachmentService.listEntitiesByAndIds(List.of(APPROVAL_ID), modelName);
  }

  /** 附件磁盘路径 = 落库的 file_path（目录） + file_name，与 IOUtils#getFileByEntity 同口径。 */
  private static Path filesystemPathOf(ApprovalAttachmentEntity entity) {
    return Paths.get(entity.getFilePath()).resolve(entity.getFileName());
  }

  private static Path attachmentsDirectory(String modelName) {
    return Paths.get(System.getProperty("user.dir"), "test-files", modelName)
        .resolve(String.valueOf(APPROVAL_ID));
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
