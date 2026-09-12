package com.slz.crm.knowledge.document;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.KnowledgeBaseEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.KnowledgeBaseMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 文档入库编排：存储 → 解析分块 → 嵌入 → DB 快照 → 向量库。
 */
@Service
public class DocumentIngestionService {
    private static final Logger log = LoggerFactory.getLogger(DocumentIngestionService.class);

    /** 切片角色（V23 chunk_role）：CHILD=检索单元，PARENT=生成单元父块行。 */
    static final String CHUNK_ROLE_CHILD = "CHILD";
    static final String CHUNK_ROLE_PARENT = "PARENT";

    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final UploadedFileMapper uploadedFileMapper;
    private final DocumentVectorChunkMapper chunkMapper;
    private final KnowledgeBaseAuthorizationService authorizationService;
    private final FileStorageService fileStorageService;
    private final DocumentService documentService;
    private final EmbeddingService embeddingService;
    private final CrmVectorStore vectorStore;

    public DocumentIngestionService(KnowledgeBaseMapper knowledgeBaseMapper,
                                    UploadedFileMapper uploadedFileMapper,
                                    DocumentVectorChunkMapper chunkMapper,
                                    KnowledgeBaseAuthorizationService authorizationService,
                                    FileStorageService fileStorageService,
                                    DocumentService documentService,
                                    EmbeddingService embeddingService,
                                    CrmVectorStore vectorStore) {
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.uploadedFileMapper = uploadedFileMapper;
        this.chunkMapper = chunkMapper;
        this.authorizationService = authorizationService;
        this.fileStorageService = fileStorageService;
        this.documentService = documentService;
        this.embeddingService = embeddingService;
        this.vectorStore = vectorStore;
    }

    /** 入库前先做知识库写授权；未授权直接拒绝，不做任何文件写入。 */
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
        String storageKey = fileStorageService.store(command.content(), command.filename(), command.contentType());
        UploadedFileEntity file = createUploadedFile(command, documentId, storageKey);
        uploadedFileMapper.insert(file);

        List<DocumentVectorChunkEntity> savedChunks = new ArrayList<>();
        try (InputStream storedContent = fileStorageService.open(storageKey)) {
            List<DocumentChunk> chunks = documentService.process(storedContent, command.filename(), command.category());
            vectorStore.deleteByDocumentId(documentId);
            savedChunks.addAll(persistChunks(file, chunks));
            List<VectorRecord> vectorRecords = new ArrayList<>(savedChunks.size());
            for (DocumentVectorChunkEntity entity : savedChunks) {
                // 块头只进嵌入输入（方案05）：文件名/类目/页级锚点给碎片块全局视野；
                // DB chunk_text 与 VectorRecord.text 保持原文，引用展示不受前缀污染（任务 2.1）
                String embedText = ChunkHeaderText.wrap(file.getOriginalFilename(),
                        entity.getCategory(), entity.getPageNo(), entity.getRowIndex(), entity.getChunkText());
                float[] embedding = embeddingService.embed(embedText);
                vectorRecords.add(createVectorRecord(file, entity, command, embedding));
            }
            vectorStore.upsertAll(vectorRecords);

            file.setStatus("COMPLETED");
            file.setSegmentCount(savedChunks.size());
            file.setVectorCount(vectorRecords.size());
            uploadedFileMapper.updateById(file);
            return new DocumentIngestionResult(
                    file.getId(), documentId, savedChunks.size(), vectorRecords.size());
        } catch (RuntimeException exception) {
            markFailed(file, documentId, savedChunks, exception);
            throw exception;
        } catch (Exception exception) {
            RuntimeException wrapped = new IllegalStateException("文档入库失败", exception);
            markFailed(file, documentId, savedChunks, wrapped);
            throw wrapped;
        }
    }

    private UploadedFileEntity createUploadedFile(DocumentIngestionCommand command,
                                                  String documentId,
                                                  String storageKey) {
        UploadedFileEntity file = new UploadedFileEntity();
        file.setUserId(command.user().userIdRef());
        file.setFilename(command.filename());
        file.setOriginalFilename(command.filename());
        file.setFileType(fileType(command.filename()));
        file.setDocumentId(documentId);
        file.setStorageKey(storageKey);
        file.setFileSize(command.fileSize());
        file.setContentType(command.contentType());
        file.setSegmentCount(0);
        file.setVectorCount(0);
        file.setStatus("PROCESSING");
        file.setFileHash(sha256OfStoredObject(storageKey));
        file.setBatchTaskId(command.batchTaskId());
        file.setKnowledgeBase(String.valueOf(command.knowledgeBaseId()));
        return file;
    }

    private DocumentVectorChunkEntity createChunkEntity(UploadedFileEntity file, DocumentChunk chunk) {
        DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
        entity.setDocumentId(file.getDocumentId());
        entity.setChunkIndex(chunk.chunkIndex());
        entity.setChunkText(chunk.text());
        entity.setChunkHash(sha256(chunk.text()));
        entity.setFilename(file.getOriginalFilename());
        entity.setCategory(chunk.category());
        entity.setKeywords(String.join(",", chunk.keywords()));
        entity.setPageNo(chunk.pageNo());
        entity.setRowIndex(chunk.rowIndex());
        entity.setChunkRole(CHUNK_ROLE_CHILD);
        return entity;
    }

    /**
     * 切片落库（提案4 任务 3.2，双粒度索引）：子块行 + 语义切分产生的父块行。
     *
     * <p>父块分组规则：相邻且 parentText 逐字相同、页锚点一致的连续子块属同一逻辑段；
     * 段被切成 ≥2 个子块时才落一行父块（chunk_role=PARENT，chunk_index 从子块总数+1 起编号、
     * 与子块序号空间隔离），子块挂 parent_chunk_id。恰好 1 个子块的逻辑段自身即父块，
     * 不落父块行、parent_chunk_id 保持空（fixed 策略全部子块如此，行为与升级前一致）。</p>
     *
     * <p>父块行不嵌入、不产向量记录——父块是生成单元不是检索单元（稀疏召回与邻居
     * 增强只消费 CHILD 行）。</p>
     *
     * @return 已落库的子块行（按 chunkIndex 升序，即向量写库与计数的口径）
     */
    private List<DocumentVectorChunkEntity> persistChunks(UploadedFileEntity file, List<DocumentChunk> chunks) {
        List<DocumentVectorChunkEntity> children = new ArrayList<>(chunks.size());
        int index = 0;
        int parentOrdinal = 0;
        while (index < chunks.size()) {
            int runEnd = groupRunEnd(chunks, index);
            DocumentVectorChunkEntity parent = null;
            if (runEnd - index >= 2) {
                parent = createParentEntity(file, chunks.get(index));
                parent.setChunkIndex(chunks.size() + 1 + parentOrdinal);
                parentOrdinal++;
                chunkMapper.insert(parent);
            }
            for (int i = index; i < runEnd; i++) {
                DocumentVectorChunkEntity entity = createChunkEntity(file, chunks.get(i));
                if (parent != null) {
                    entity.setParentChunkId(parent.getId());
                }
                chunkMapper.insert(entity);
                children.add(entity);
            }
            index = runEnd;
        }
        return children;
    }

    /** 从 index 起的同一逻辑段连续区段：parentText 非空逐字相同且页锚点一致才延续。 */
    private int groupRunEnd(List<DocumentChunk> chunks, int index) {
        DocumentChunk first = chunks.get(index);
        if (first.parentText() == null) {
            return index + 1;
        }
        int end = index + 1;
        while (end < chunks.size()) {
            DocumentChunk next = chunks.get(end);
            if (next.parentText() == null || !next.parentText().equals(first.parentText())
                    || !Objects.equals(next.pageNo(), first.pageNo())
                    || !Objects.equals(next.rowIndex(), first.rowIndex())) {
                break;
            }
            end++;
        }
        return end;
    }

    /** 父块行：逻辑段全文；锚点/类目取段内子块口径（同段同页），关键词从段全文提取。 */
    private DocumentVectorChunkEntity createParentEntity(UploadedFileEntity file, DocumentChunk first) {
        String parentText = first.parentText();
        DocumentVectorChunkEntity parent = new DocumentVectorChunkEntity();
        parent.setDocumentId(file.getDocumentId());
        parent.setChunkText(parentText);
        parent.setChunkHash(sha256(parentText));
        parent.setFilename(file.getOriginalFilename());
        parent.setCategory(first.category());
        parent.setKeywords(String.join(",", documentService.extractKeywords(parentText)));
        parent.setPageNo(first.pageNo());
        parent.setRowIndex(first.rowIndex());
        parent.setChunkRole(CHUNK_ROLE_PARENT);
        return parent;
    }

    /** point id 使用 UUID；chunkId 使用 DB 主键，保证前端引用与 DB 可回查。 */
    private VectorRecord createVectorRecord(UploadedFileEntity file,
                                            DocumentVectorChunkEntity entity,
                                            DocumentIngestionCommand command,
                                            float[] embedding) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("knowledgeBaseId", String.valueOf(command.knowledgeBaseId()));
        metadata.put("category", command.category() == null ? "" : command.category());
        metadata.put("filename", file.getOriginalFilename());
        metadata.put("fileType", file.getFileType());
        metadata.put("pageNo", entity.getPageNo() == null ? 0L : entity.getPageNo().longValue());
        metadata.put("rowIndex", entity.getRowIndex() == null ? 0L : entity.getRowIndex().longValue());
        metadata.put("chunkId", String.valueOf(entity.getId()));
        return new VectorRecord(
                UUID.randomUUID().toString(),
                entity.getDocumentId(),
                String.valueOf(entity.getId()),
                entity.getChunkIndex(),
                entity.getChunkText(),
                embedding,
                metadata);
    }

    /** 失败时保留原始文件和 DB 记录，清理向量与 DB 切片，避免半量检索结果。 */
    private void markFailed(UploadedFileEntity file,
                            String documentId,
                            List<DocumentVectorChunkEntity> savedChunks,
                            Exception exception) {
        try {
            vectorStore.deleteByDocumentId(documentId);
        } catch (Exception cleanupException) {
            log.warn("入库失败后清理向量失败 documentId={}", documentId, cleanupException);
        }
        try {
            chunkMapper.delete(new QueryWrapper<DocumentVectorChunkEntity>().eq("document_id", documentId));
        } catch (Exception cleanupException) {
            log.warn("入库失败后清理切片失败 documentId={}", documentId, cleanupException);
        }
        file.setStatus("FAILED");
        file.setErrorMessage(shortMessage(exception));
        uploadedFileMapper.updateById(file);
    }

    private String sha256OfStoredObject(String storageKey) {
        try (InputStream content = fileStorageService.open(storageKey)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = content.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception exception) {
            return null;
        }
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("计算切片哈希失败", exception);
        }
    }

    private String fileType(String filename) {
        if (filename == null || filename.lastIndexOf('.') < 0) {
            return "file";
        }
        return filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
    }

    private String shortMessage(Exception exception) {
        String message = exception.getMessage();
        return message == null ? exception.getClass().getSimpleName() : message.substring(0, Math.min(900, message.length()));
    }
}
