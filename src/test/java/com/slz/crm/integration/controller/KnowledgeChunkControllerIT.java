package com.slz.crm.integration.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slz.crm.integration.AbstractMySqlIT;
import com.slz.crm.integration.RoleTokenSupport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * add-source-citation-and-dashboard 任务 1.4：知识库文档 chunk 读取端点正反向 IT。
 *
 * <p>端点 {@code GET /knowledge/documents/{documentId}/chunks} 挂冻结 803 AI_CHAT_SESSION + 服务内知识库授权链
 * 双保险。覆盖：正向（有 803 且为知识库授权成员 → 200 且含定位字段 chunkIndex/pageNo/rowIndex），反向（有 803 但 非目标库成员 →
 * 12002）。全部假数据 via JdbcTemplate，@Transactional 回滚隔离，零外呼零重嵌入。
 */
@AutoConfigureMockMvc
@Transactional
@Sql(scripts = "/init_data.sql", executionPhase = Sql.ExecutionPhase.BEFORE_TEST_METHOD)
class KnowledgeChunkControllerIT extends AbstractMySqlIT {

  private static final long USER_ID = 500L;
  private static final long ROLE_ID = 20L;
  private static final long KB_ID = 600L;

  @Autowired private MockMvc mockMvc;

  @Autowired private JdbcTemplate jdbcTemplate;

  /** 种子：带 803 权限的非超管用户 + 自有知识库 + 文档 + 切片。返回该用户 token。 */
  private String seedAuthorizedUser(String documentId) {
    seedPermissionUser(documentId, true);
    return RoleTokenSupport.tokenOfUserId(USER_ID);
  }

  /** 种子：带 803 权限但文档所属库非授权（库为他人私有且无成员行）的用户。 */
  private String seedUnauthorizedUser(String documentId) {
    seedPermissionUser(documentId, false);
    return RoleTokenSupport.tokenOfUserId(USER_ID);
  }

  private void seedPermissionUser(String documentId, boolean grantedKb) {
    // 复用 init_data.sql 已存在的用户/角色表与权限表结构；全部用例内补种，@Transactional 回滚。
    jdbcTemplate.update("DELETE FROM role_permissions WHERE role_id = ?", ROLE_ID);
    jdbcTemplate.update("DELETE FROM sys_role WHERE id = ?", ROLE_ID);
    jdbcTemplate.update("DELETE FROM permissions WHERE id = ?", 803L);
    jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", USER_ID);
    jdbcTemplate.update("DELETE FROM uploaded_file WHERE document_id = ?", documentId);
    jdbcTemplate.update("DELETE FROM document_vector_chunk WHERE document_id = ?", documentId);
    jdbcTemplate.update("DELETE FROM knowledge_base_member WHERE knowledge_base_id = ?", KB_ID);
    jdbcTemplate.update("DELETE FROM knowledge_base WHERE id = ?", KB_ID);

    jdbcTemplate.update(
        "INSERT INTO sys_role (id, role_name, role_desc, create_time) VALUES (?, ?, ?, NOW())",
        ROLE_ID,
        "chunk-reader",
        "chunk 端点 IT 角色");
    jdbcTemplate.update(
        "INSERT INTO permissions (id, permissions_name, permissions_desc) VALUES (?, ?, ?)",
        803L,
        "AI_CHAT_SESSION",
        "AI 会话");
    jdbcTemplate.update(
        "INSERT INTO role_permissions (role_id, permissions_id) VALUES (?, ?)", ROLE_ID, 803L);
    jdbcTemplate.update(
        "INSERT INTO sys_user (id, password, real_name, phone, email, role_id, status, create_time) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())",
        USER_ID,
        "e10adc3949ba59abbe56e057f20f883e",
        "chunk-reader-user",
        "13900000803",
        "chunk803@slz.com",
        ROLE_ID,
        1);
    jdbcTemplate.update(
        "INSERT INTO knowledge_base (id, name, display_name, owner_user_id, visibility, is_deleted) "
            + "VALUES (?, ?, ?, ?, ?, ?)",
        KB_ID,
        "it-kb",
        "IT KB",
        grantedKb ? "user:" + USER_ID : "user:99999",
        "PRIVATE",
        0);
    if (grantedKb) {
      jdbcTemplate.update(
          "INSERT INTO knowledge_base_member (knowledge_base_id, user_id, member_role, is_deleted) "
              + "VALUES (?, ?, ?, ?)",
          KB_ID,
          "user:" + USER_ID,
          "READER",
          0);
    }
    jdbcTemplate.update(
        "INSERT INTO uploaded_file (user_id, filename, original_filename, file_type, document_id, "
            + "storage_key, status, knowledge_base) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        "user:" + USER_ID,
        documentId + ".md",
        documentId + ".md",
        "md",
        documentId,
        "storage/" + documentId,
        "COMPLETED",
        String.valueOf(KB_ID));
    jdbcTemplate.update(
        "INSERT INTO document_vector_chunk (document_id, chunk_index, chunk_text, chunk_hash, "
            + "filename, category, page_no, row_index, chunk_role) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        documentId,
        0,
        documentId + " 切片正文零号",
        "hash-" + documentId,
        documentId + ".md",
        "aftermarket",
        1,
        null,
        "CHILD");
  }

  @Test
  @DisplayName("授权成员用户读取文档切片应返回 200 且含定位字段")
  void authorizedMember_canReadChunksWithLocationFields() throws Exception {
    String documentId = "kn-chunk-it-doc-auth";
    String token = seedAuthorizedUser(documentId);

    mockMvc
        .perform(
            get("/knowledge/documents/{documentId}/chunks", documentId)
                .header("token", token)
                .param("pageNum", "1")
                .param("pageSize", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(1))
        .andExpect(jsonPath("$.data.records[0].chunkIndex").value(0))
        .andExpect(jsonPath("$.data.records[0].pageNo").value(1))
        .andExpect(jsonPath("$.data.records[0].documentId").value(documentId))
        .andExpect(jsonPath("$.data.records[0].chunkText").isNotEmpty());
  }

  @Test
  @DisplayName("持有 803 但非知识库授权成员读取切片应返回 12002 权限不足")
  void nonMemberUser_readingChunks_returnsPermissionDenied() throws Exception {
    String documentId = "kn-chunk-it-doc-deny";
    String token = seedUnauthorizedUser(documentId);

    mockMvc
        .perform(
            get("/knowledge/documents/{documentId}/chunks", documentId)
                .header("token", token)
                .param("pageNum", "1")
                .param("pageSize", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(12002));
  }
}
