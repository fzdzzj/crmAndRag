package com.slz.crm.knowledge.document;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.platform.audit.GovernanceAuditEvent;
import com.slz.crm.platform.audit.GovernanceAuditRecorder;
import com.slz.crm.platform.audit.GovernanceAuditResult;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 文档入库编排：存储 → 解析分块 → 嵌入 → DB 快照 → 向量库。
 *
 * <p>提案4 扩展：摄取侧块头注入（任务 2.1）、语义切分父块行落库（任务 3.2）、 按文档幂等重建 {@link #reingest}（任务 4.1，失败复用 markFailed
 * 清理语义并落平台审计）。
 */
@Service
public class DocumentIngestionService {
  private static final Logger LOG = LoggerFactory.getLogger(DocumentIngestionService.class);

  /** 重建入库审计事件类型/动作口径（任务 4.3）。 */
  public static final String REINGEST_EVENT_TYPE = "KNOWLEDGE_REINGEST";

  public static final String REINGEST_ACTION = "reingest";

  private final KnowledgeBaseMapper knowledgeBaseMapper;
  private final UploadedFileMapper uploadedFileMapper;
  private final DocumentVectorChunkMapper chunkMapper;
  private final KnowledgeBaseAuthorizationService authorizationService;
  private final FileStorageService fileStorageService;
  private final DocumentService documentService;
  private final EmbeddingService embeddingService;
  private final CrmVectorStore vectorStore;

  /** 治理审计记录器：重建入库为管理动作，成功/失败/拒绝均须可追溯。 */
  private final GovernanceAuditRecorder auditRecorder;

  /** 衍生问题旁路（提案5 任务 3.1，默认关闭）；null = 兼容构造下旁路缺席。 */
  private final DerivedQuestionService derivedQuestionService;

  @Autowired
  public DocumentIngestionService(
      KnowledgeBaseMapper knowledgeBaseMapper,
      UploadedFileMapper uploadedFileMapper,
      DocumentVectorChunkMapper chunkMapper,
      KnowledgeBaseAuthorizationService authorizationService,
      FileStorageService fileStorageService,
      DocumentService documentService,
      EmbeddingService embeddingService,
      CrmVectorStore vectorStore,
      GovernanceAuditRecorder auditRecorder,
      DerivedQuestionService derivedQuestionService) {
    this.knowledgeBaseMapper = knowledgeBaseMapper;
    this.uploadedFileMapper = uploadedFileMapper;
    this.chunkMapper = chunkMapper;
    this.authorizationService = authorizationService;
    this.fileStorageService = fileStorageService;
    this.documentService = documentService;
    this.embeddingService = embeddingService;
    this.vectorStore = vectorStore;
    this.auditRecorder = auditRecorder;
    this.derivedQuestionService = derivedQuestionService;
  }

  /** 兼容构造（提案4 九参）：不接衍生问题旁路。 */
  public DocumentIngestionService(
      KnowledgeBaseMapper knowledgeBaseMapper,
      UploadedFileMapper uploadedFileMapper,
      DocumentVectorChunkMapper chunkMapper,
      KnowledgeBaseAuthorizationService authorizationService,
      FileStorageService fileStorageService,
      DocumentService documentService,
      EmbeddingService embeddingService,
      CrmVectorStore vectorStore,
      GovernanceAuditRecorder auditRecorder) {
    this(
        knowledgeBaseMapper,
        uploadedFileMapper,
        chunkMapper,
        authorizationService,
        fileStorageService,
        documentService,
        embeddingService,
        vectorStore,
        auditRecorder,
        null);
  }

  /** 入库前先做知识库写授权；未授权直接拒绝，不做任何文件写入。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 文件I/O+DB+嵌入/向量外呼多源，宽捕获包装并标记失败后上抛
  public DocumentIngestionResult ingest(DocumentIngestionCommand command) {
    Objects.requireNonNull(command.knowledgeBaseId(), "knowledgeBaseId 不能为空");
    Objects.requireNonNull(command.user(), "UserContext 不能为空");
    KnowledgeBaseEntity knowledgeBase = knowledgeBaseMapper.selectById(command.knowledgeBaseId());
    if (knowledgeBase == null || !authorizationService.canWrite(knowledgeBase, command.user())) {
      throw new SecurityException("无知识库写入权限: " + command.knowledgeBaseId());
    }
    if (!documentService.supports(command.filename())) {
      throw new IllegalArgumentException("不支持的文档类型: " + command.filename());
    }

    String documentId = UUID.randomUUID().toString().replace("-", "");
    String storageKey =
        fileStorageService.store(command.content(), command.filename(), command.contentType());
    UploadedFileEntity file =
        DocumentIngestionSupport.createUploadedFile(
            command, documentId, storageKey, sha256OfStoredObject(storageKey));
    uploadedFileMapper.insert(file);

    List<DocumentVectorChunkEntity> savedChunks = new ArrayList<>();
    try (InputStream storedContent = fileStorageService.open(storageKey)) {
      List<DocumentChunk> chunks =
          documentService.process(storedContent, command.filename(), command.category());
      savedChunks.addAll(
          DocumentIngestionSupport.chunkEmbedAndWrite(
              file, chunks, embeddingService, vectorStore, chunkMapper, documentService));
      file.setStatus("COMPLETED");
      file.setSegmentCount(savedChunks.size());
      file.setVectorCount(savedChunks.size());
      uploadedFileMapper.updateById(file);
      // 提案5 任务 3.1：衍生问题旁路（异步，永不抛出；失败=该块退化为普通块）
      if (derivedQuestionService != null) {
        derivedQuestionService.submitAfterIngest(file, savedChunks);
      }
      return new DocumentIngestionResult(
          file.getId(), documentId, savedChunks.size(), savedChunks.size());
    } catch (RuntimeException exception) {
      markFailed(file, documentId, exception);
      throw exception;
    } catch (Exception exception) {
      RuntimeException wrapped = new IllegalStateException("文档入库失败", exception);
      markFailed(file, documentId, wrapped);
      throw wrapped;
    }
  }

  /**
   * 按文档重建入库（提案4 任务 4.1，方案02/05 上线后存量迁移的幂等入口）： 同一 documentId 重切分 → 重嵌入 → 重写 DB 与向量库；失败复用 {@link
   * #markFailed} 清理语义（清向量+物理清切片+标 FAILED 可重试，不留半量检索结果）。
   *
   * <p>授权：仅知识库写授权用户可触发（超管/库主/成员写角色）；类目沿用旧切片行的 快照（uploaded_file 不落类目，保证重建不改检索过滤口径）。每次触发（成功/失败/拒绝）
   * 均落平台治理审计（任务 4.3）。
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 文件I/O+DB+嵌入外呼多源，宽捕获标记失败并审计后上抛
  public DocumentIngestionResult reingest(String documentId, UserContext user) {
    Objects.requireNonNull(documentId, "documentId 不能为空");
    Objects.requireNonNull(user, "UserContext 不能为空");
    UploadedFileEntity file =
        uploadedFileMapper.selectOne(
            new QueryWrapper<UploadedFileEntity>().eq("document_id", documentId).last("LIMIT 1"));
    if (file == null) {
      throw new IllegalArgumentException("待重建文档不存在: " + documentId);
    }
    KnowledgeBaseEntity knowledgeBase =
        knowledgeBaseMapper.selectById(Long.valueOf(file.getKnowledgeBase()));
    if (knowledgeBase == null || !authorizationService.canWrite(knowledgeBase, user)) {
      auditReingest(documentId, user, GovernanceAuditResult.DENIED, "无知识库写权限");
      throw new SecurityException("无知识库写入权限: " + file.getKnowledgeBase());
    }
    String category = legacyCategoryOf(documentId);
    file.setStatus("PROCESSING");
    file.setErrorMessage(null);
    uploadedFileMapper.updateById(file);
    try (InputStream storedContent = fileStorageService.open(file.getStorageKey())) {
      List<DocumentChunk> chunks =
          documentService.process(storedContent, file.getOriginalFilename(), category);
      // 幂等重建前置：物理删旧切片行（软删行仍占 uk(document_id, chunk_index)），向量统一在写库前清
      chunkMapper.deletePhysicallyByDocumentId(documentId);
      List<DocumentVectorChunkEntity> children =
          DocumentIngestionSupport.chunkEmbedAndWrite(
              file, chunks, embeddingService, vectorStore, chunkMapper, documentService);
      file.setStatus("COMPLETED");
      file.setSegmentCount(children.size());
      file.setVectorCount(children.size());
      uploadedFileMapper.updateById(file);
      auditReingest(documentId, user, GovernanceAuditResult.SUCCESS, "segments=" + children.size());
      // 提案5 任务 3.1/3.4：重建后衍生问题随旁路重新生成（旧衍生向量已随 deleteByDocumentId 清空）
      if (derivedQuestionService != null) {
        derivedQuestionService.submitAfterIngest(file, children);
      }
      return new DocumentIngestionResult(
          file.getId(), documentId, children.size(), children.size());
    } catch (RuntimeException exception) {
      markFailed(file, documentId, exception);
      auditReingest(
          documentId,
          user,
          GovernanceAuditResult.FAILED,
          DocumentIngestionSupport.shortMessage(exception));
      throw exception;
    } catch (Exception exception) {
      RuntimeException wrapped = new IllegalStateException("文档重建入库失败", exception);
      markFailed(file, documentId, wrapped);
      auditReingest(
          documentId,
          user,
          GovernanceAuditResult.FAILED,
          DocumentIngestionSupport.shortMessage(wrapped));
      throw wrapped;
    }
  }

  /** 重建沿用的旧类目快照：取旧切片行首个非空类目；无历史行（上次失败在切分前）返回 null。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // ORM查询边界：失败按无类目继续重建
  private String legacyCategoryOf(String documentId) {
    String result = null;
    try {
      DocumentVectorChunkEntity row =
          chunkMapper.selectOne(
              new QueryWrapper<DocumentVectorChunkEntity>()
                  .eq("document_id", documentId)
                  .isNotNull("category")
                  .last("LIMIT 1"));
      if (row != null) {
        result = row.getCategory();
      }
    } catch (Exception exception) {
      LOG.warn("读取旧切片类目失败，重建按无类目继续 documentId={}", documentId, exception);
      result = null;
    }
    return result;
  }

  /** 重建入库审计（任务 4.3）：record 内部吞异常不阻断业务，这里不重复兜底。 */
  private void auditReingest(
      String documentId, UserContext user, GovernanceAuditResult result, String detail) {
    auditRecorder.record(
        new GovernanceAuditEvent(
            REINGEST_EVENT_TYPE,
            user.userIdRef(),
            "uploaded_file",
            documentId,
            REINGEST_ACTION,
            result,
            detail));
  }

  /** 失败时保留原始文件和 DB 记录，清理向量与切片（物理删，保证重建可重试不留半量）。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 清理边界：向量/切片清理各自单独吞异常，不阻断失败标记
  private void markFailed(UploadedFileEntity file, String documentId, Exception exception) {
    try {
      vectorStore.deleteByDocumentId(documentId);
    } catch (Exception cleanupException) {
      LOG.warn("入库失败后清理向量失败 documentId={}", documentId, cleanupException);
    }
    try {
      chunkMapper.deletePhysicallyByDocumentId(documentId);
    } catch (Exception cleanupException) {
      LOG.warn("入库失败后清理切片失败 documentId={}", documentId, cleanupException);
    }
    file.setStatus("FAILED");
    file.setErrorMessage(DocumentIngestionSupport.shortMessage(exception));
    uploadedFileMapper.updateById(file);
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 流边界+摘要多源，失败返回null不抛
  private String sha256OfStoredObject(String storageKey) {
    String result = null;
    try (InputStream content = fileStorageService.open(storageKey)) {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] buffer = new byte[8192];
      int read;
      while ((read = content.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
      result = HexFormat.of().formatHex(digest.digest());
    } catch (Exception exception) {
      result = null;
    }
    return result;
  }
}
