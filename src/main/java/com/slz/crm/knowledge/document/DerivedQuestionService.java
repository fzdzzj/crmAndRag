package com.slz.crm.knowledge.document;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 衍生问题文档增强旁路（方案 06，enhance-query-transformation 任务 3.1）。
 *
 * <p>入库主链成功后经平台异步执行器旁路执行：每块用 {@code ModelProvider} 反向生成 {@code
 * rag.query.derived-questions.max-per-chunk}（默认 2，上限 5）个口语化问题 → 嵌入 → 向量记录关联<b>原块
 * chunkId</b>（命中衍生问题即回原块，{@code SourceReference} 指原块； {@code VectorRecord.text} 保持原块原文，问题文本只进
 * metadata 供观测，绝不进引用摘录）。
 *
 * <p>失败边界（任务 3.2）：单块失败该块退化为普通块（无衍生向量）；旁路整体失败只告警， <b>绝不阻塞/影响入库主链结果</b>。提交被拒绝（队列饱和）等价于旁路缺席。 token
 * 消耗（任务 3.3）按 {@code TokenUsageType.CHAT}/{@code EMBEDDING} 上报 {@code TokenUsageRecorder}，userIdRef
 * 取入库文件归属者快照（旁路线程不依赖请求上下文）。
 *
 * <p>幂等（任务 3.4）：衍生向量与块向量同 documentId，随 {@code reingest} 的按文档删除一并清空、 重建后重新生成；写入前按当前 DB 存活 chunkId
 * 过滤，防止与重建竞态写入指向已删块的陈旧向量。
 */
@Service
public class DerivedQuestionService {
  private static final Logger LOG = LoggerFactory.getLogger(DerivedQuestionService.class);

  public static final String VECTOR_KIND_DERIVED = "derived_question";
  public static final String ENABLED_KEY = "rag.query.derived-questions.enabled";
  private static final String MAX_PER_CHUNK_KEY = "rag.query.derived-questions.max-per-chunk";
  private static final int DEFAULT_MAX_PER_CHUNK = 2;
  private static final int MAX_PER_CHUNK_LIMIT = 5;

  private static final String SYSTEM_PROMPT =
      """
            你是 CRM 知识库入库标注器。阅读给定知识片段，站在用户提问的角度，写出最多 %d 个
            该片段能够回答的问题。要求：口语化、贴近真实问法；每行一个问题；不要编号；不要解释。
            """;

  private final ModelProvider modelProvider;
  private final EmbeddingService embeddingService;
  private final CrmVectorStore vectorStore;
  private final DocumentVectorChunkMapper chunkMapper;
  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  private final ObjectProvider<TokenUsageRecorder> usageRecorderProvider;
  private final Executor bypassExecutor;

  public DerivedQuestionService(
      ModelProvider modelProvider,
      EmbeddingService embeddingService,
      CrmVectorStore vectorStore,
      DocumentVectorChunkMapper chunkMapper,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider,
      ObjectProvider<TokenUsageRecorder> usageRecorderProvider,
      @Qualifier("derivedQuestionBypassThreadPool") Executor bypassExecutor) {
    this.modelProvider = modelProvider;
    this.embeddingService = embeddingService;
    this.vectorStore = vectorStore;
    this.chunkMapper = chunkMapper;
    this.dynamicConfigProvider = dynamicConfigProvider;
    this.usageRecorderProvider = usageRecorderProvider;
    this.bypassExecutor = bypassExecutor;
  }

  /** 入库/重建成功后的旁路提交入口（DocumentIngestionService 主链尾段调用）。 契约：<b>永不抛出</b>、不产生主链可感知的副作用；开关关闭时零开销直接返回。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 旁路提交顶层兜底：阻塞式执行器可抛多种运行时异常，捕获后不阻塞入库主链
  public void submitAfterIngest(UploadedFileEntity file, List<DocumentVectorChunkEntity> children) {
    if (file == null || children == null || children.isEmpty() || !enabled()) {
      return;
    }
    try {
      bypassExecutor.execute(() -> generateForDocument(file, children));
    } catch (RejectedExecutionException exception) {
      LOG.warn("衍生问题旁路提交被拒绝（队列饱和），本文档退化为无衍生向量 documentId={}", file.getDocumentId());
    } catch (RuntimeException exception) {
      LOG.warn("衍生问题旁路提交失败（不阻塞入库主链）documentId={}", file.getDocumentId(), exception);
    }
  }

  /** 旁路主体：逐块生成→嵌入→聚合 upsert；整体兜底捕获，绝不向提交方抛出。（public 供跨包单测直调） */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 旁路整体+单块双层兜底：底稿为外呼SDK/嵌入多源抛出，按块降级不冒泡到主链
  public void generateForDocument(
      UploadedFileEntity file, List<DocumentVectorChunkEntity> children) {
    try {
      List<VectorRecord> records = new ArrayList<>();
      for (DocumentVectorChunkEntity child : children) {
        try {
          records.addAll(questionsForChunk(file, child));
        } catch (Exception exception) {
          LOG.warn(
              "衍生问题生成失败，该块退化为普通块 documentId={} chunkId={}",
              file.getDocumentId(),
              child.getId(),
              exception);
        }
      }
      Set<String> currentChunkIds =
          records.isEmpty() ? Set.of() : currentChunkIds(file.getDocumentId());
      if (!records.isEmpty()) {
        records.removeIf(record -> !currentChunkIds.contains(record.chunkId()));
      }
      if (!records.isEmpty()) {
        vectorStore.upsertAll(records);
      }
    } catch (Exception exception) {
      LOG.warn("衍生问题旁路失败（不阻塞入库主链）documentId={}", file.getDocumentId(), exception);
    }
  }

  /** 单块：一次 LLM 生成 N 问 → 逐问嵌入 → 关联原块的向量记录。失败抛出由上层按块降级。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // LLM外呼与嵌入外呼混抛（SDK多源含checked），原样上抛由外层按块降级
  private List<VectorRecord> questionsForChunk(
      UploadedFileEntity file, DocumentVectorChunkEntity child) {
    int maxQuestions = resolveMaxPerChunk();
    List<Message> messages =
        List.of(
            new SystemMessage(SYSTEM_PROMPT.formatted(maxQuestions)),
            new UserMessage(child.getChunkText() == null ? "" : child.getChunkText().strip()));
    ModelCallOptions options = new ModelCallOptions(null, false, 0.3d, 512, null, null, Map.of());
    ModelCallResult<String> result;
    try {
      result = modelProvider.chat(new Prompt(messages), options);
      recordUsage(result, TokenUsageType.CHAT, file, true);
    } catch (Exception exception) {
      recordUsage(null, TokenUsageType.CHAT, file, false);
      throw exception;
    }
    List<VectorRecord> records = new ArrayList<>();
    for (String question : parseQuestions(result == null ? null : result.content(), maxQuestions)) {
      ModelCallResult<float[]> embedResult;
      try {
        embedResult = embeddingService.embedWithUsage(question);
        recordUsage(embedResult, TokenUsageType.EMBEDDING, file, true);
      } catch (Exception exception) {
        recordUsage(null, TokenUsageType.EMBEDDING, file, false);
        throw exception;
      }
      records.add(derivedRecord(file, child, question, embedResult.vector()));
    }
    return records;
  }

  /** 衍生向量记录：chunkId/文本/锚点全部取原块（命中即回原块），问题文本只进 metadata。 */
  private VectorRecord derivedRecord(
      UploadedFileEntity file,
      DocumentVectorChunkEntity child,
      String question,
      float[] embedding) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", file.getKnowledgeBase());
    metadata.put("category", child.getCategory() == null ? "" : child.getCategory());
    metadata.put("filename", file.getOriginalFilename());
    metadata.put("fileType", file.getFileType());
    metadata.put("pageNo", child.getPageNo() == null ? 0L : child.getPageNo().longValue());
    metadata.put("rowIndex", child.getRowIndex() == null ? 0L : child.getRowIndex().longValue());
    metadata.put("chunkId", String.valueOf(child.getId()));
    metadata.put("vectorKind", VECTOR_KIND_DERIVED);
    metadata.put("derivedQuestion", question);
    return new VectorRecord(
        UUID.randomUUID().toString(),
        file.getDocumentId(),
        String.valueOf(child.getId()),
        child.getChunkIndex(),
        child.getChunkText(),
        embedding,
        metadata);
  }

  /** 问题解析：逐行 → 去编号/净化 → 去重 → 截断到上限。 */
  private List<String> parseQuestions(String output, int maxQuestions) {
    Set<String> seen = new LinkedHashSet<>();
    List<String> questions = new ArrayList<>(maxQuestions);
    if (output != null && !output.isBlank()) {
      for (String line : output.split("\\r?\\n")) {
        String question = sanitize(line);
        if (question.isBlank() || !seen.add(question.toLowerCase(Locale.ROOT))) {
          continue;
        }
        questions.add(question);
        if (questions.size() >= maxQuestions) {
          break;
        }
      }
    }
    return questions;
  }

  private String sanitize(String content) {
    String value = content == null ? "" : content.strip();
    if (!value.isEmpty()) {
      value = value.replaceAll("^\\s*\\d+\\s*[.、)．]\\s*", "");
      value = value.replaceAll("[\\r\\n]+", " ").strip();
      if (value.length() > 256) {
        value = value.substring(0, 256).strip();
      }
      if (value.length() >= 2
          && ((value.startsWith("\"") && value.endsWith("\""))
              || (value.startsWith("“") && value.endsWith("”")))) {
        value = value.substring(1, value.length() - 1).strip();
      }
    }
    return value;
  }

  /** 竞态护栏：只保留当前 DB 仍存活的 chunkId（reingest 并发时丢弃指向已删块的陈旧衍生向量）。 */
  private Set<String> currentChunkIds(String documentId) {
    List<DocumentVectorChunkEntity> rows =
        chunkMapper.selectList(
            new QueryWrapper<DocumentVectorChunkEntity>().eq("document_id", documentId));
    Set<String> ids = new LinkedHashSet<>();
    for (DocumentVectorChunkEntity row : rows) {
      ids.add(String.valueOf(row.getId()));
    }
    return ids;
  }

  private void recordUsage(
      ModelCallResult<?> result, TokenUsageType type, UploadedFileEntity file, boolean success) {
    TokenUsageRecorder recorder = usageRecorderProvider.getIfAvailable();
    if (recorder == null) {
      return;
    }
    String model = result == null ? "unknown" : result.model();
    recorder.record(
        new TokenUsageRecord(
            model,
            file.getUserId() == null ? "user:system" : file.getUserId(),
            null,
            parseKnowledgeBaseId(file),
            type,
            result == null ? null : result.promptTokens(),
            result == null ? null : result.completionTokens(),
            result == null ? null : result.totalTokens(),
            success));
  }

  private Long parseKnowledgeBaseId(UploadedFileEntity file) {
    Long result = null;
    if (file.getKnowledgeBase() != null) {
      try {
        result = Long.valueOf(file.getKnowledgeBase());
      } catch (NumberFormatException exception) {
        result = null;
      }
    }
    return result;
  }

  private boolean enabled() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    return config != null && Boolean.TRUE.equals(config.get(ENABLED_KEY, Boolean.class, false));
  }

  private int resolveMaxPerChunk() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Integer configured =
        config == null ? null : config.get(MAX_PER_CHUNK_KEY, Integer.class, DEFAULT_MAX_PER_CHUNK);
    int value = DEFAULT_MAX_PER_CHUNK;
    if (configured != null && configured >= 1) {
      value = Math.min(configured, MAX_PER_CHUNK_LIMIT);
    }
    return value;
  }
}
