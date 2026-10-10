package com.slz.crm.quality;

import com.slz.crm.knowledge.document.ChunkHeaderText;
import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.DocumentService;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 基准评测语料的数据准备 runner（add-rag-quality-baseline 任务 2.2，测试域）。
 *
 * <p>职责：把 {@code src/test/resources/rag-quality/fixtures/} 的固定评测文档集幂等灌入指定 {@link CrmVectorStore}（真实
 * {@link DocumentService} 分块），产出「黄金占位 id → 真实 chunkId」 映射，并把基准集里的占位 id 全部替换为真实 id——占位 id 静默计空集是本
 * runner 明确拒绝的行为。
 *
 * <p>对齐机制（标记法）：语料正文里写 {@code 【GOLD:占位id】} 标记，标记所在切片即该占位 id 的
 * 黄金片段；入库前把标记从索引文本中剥离，保证向量与展示文本不含评测脚手架痕迹。标记法让黄金 片段位置随内容自动发现，不依赖手工数切片序号，语料改版不破坏对齐。分块 overlap（40 字符）
 * 大于标记长度，标记必然完整落在至少一个切片内；若因 overlap 复制出现在相邻两片，按最小 chunkIndex 首个命中者映射，结果确定可复现。
 *
 * <p>幂等性（任务 2.3）：chunkId = {@code <语料key>-<chunkIndex>}、documentId = {@code benchdoc-<key>}、 向量
 * point id = {@code documentId:chunkIndex} 全部确定性生成；入库前先按 documentId 清理旧向量， 因此同一环境重复执行产出完全一致的
 * chunkId 集合，向量库状态可覆盖复现。
 *
 * <p>真跑与单测共用本类：真检索基准 IT 传真实 {@code EmbeddingService::embed}； 幂等/对齐单测传确定性 fake embedder，无需外网与模型 key。
 */
public final class RagBenchmarkDataPreparer {

  /** 黄金标记：{@code 【GOLD:占位id】}；占位 id 允许字母/数字/下划线/连字符/中文。 */
  private static final Pattern GOLD_MARKER =
      Pattern.compile("【GOLD:([A-Za-z0-9_\\-\\u4e00-\\u9fff]+)】");

  /**
   * 剥离用宽松模式（expand-rag-benchmark 任务 1.3；expand-rag-benchmark-mismatch 任务 1.2 拓边）：滑窗 320/overlap 40
   * 的切点可能把标记切在半截——可能只含 {@code 【GOLD:...} 前缀、无闭合 {@code 】}，也可能整段正文较长 时把前缀切得更短、只剩 {@code
   * 【GOLD}（连冒号与占位 id 都落在下一片）。完整标记由 {@link #GOLD_MARKER} 负责映射； 本模式兼容上述任意残段形态，保证索引文本不留任何评测脚手架痕迹。
   */
  private static final Pattern GOLD_MARKER_FRAGMENT = Pattern.compile("【GOLD(?:[^】]*)?】?");

  /** 评测语料的业务类目元数据值（区别于生产文档的真实类目）。 */
  private static final String BENCHMARK_CATEGORY = "benchmark";

  private RagBenchmarkDataPreparer() {}

  /** 文本向量化函数：真跑传 {@code EmbeddingService::embed}，单测传确定性 fake。 */
  @FunctionalInterface
  public interface ChunkEmbedder {
    float[] embed(String text);
  }

  /**
   * @param key 语料稳定键（chunkId/documentId 前缀，改名 = 黄金映射失效）
   * @param filename 语料文件名（classpath {@code rag-quality/fixtures/} 下）
   */
  public record FixtureDocument(String key, String filename) {}

  /**
   * 数据准备产出。
   *
   * @param goldenToChunkId 黄金占位 id → 真实 chunkId（按语料顺序确定性排序）
   * @param chunkIds 本次入库的全部真实 chunkId
   * @param chunkTexts 实际索引的切片文本（已剥离 GOLD 标记；顺序同 chunkIds，供单测断言）
   * @param chunks 本次入库的切片明细（含 chunkId/文本/元数据），供稀疏路与邻居装配用例
   */
  public record Preparation(
      Map<String, String> goldenToChunkId,
      Set<String> chunkIds,
      List<String> chunkTexts,
      List<BenchmarkChunk> chunks) {}

  /**
   * 一次性入块明细（run-baseline-ladder）：测试侧稀疏/邻居用同一批块，chunkId 与向量库一致。
   *
   * @param chunkId 切片稳定 chunkId（{@code <key>-<chunkIndex>}）
   * @param text 已剥离 GOLD 标记的索引文本
   * @param chunkIndex 文档内切片序号
   * @param pageNo 页码（1 起，可空）
   * @param rowIndex Excel 行号（1 起，可空）
   */
  public record BenchmarkChunk(
      String chunkId,
      String text,
      String filename,
      String category,
      int chunkIndex,
      Integer pageNo,
      Integer rowIndex) {}

  /** 固定评测文档集：与 fixtures 目录一一对应；新增语料 = 在此登记并同步扩充基准集。 */
  public static final List<FixtureDocument> FIXTURES =
      List.of(
          new FixtureDocument("sales-flow", "sales-contract-flow.md"),
          new FixtureDocument("payment-plan", "payment-plan.md"),
          new FixtureDocument("regional-q3", "regional-sales-q3.xlsx"),
          new FixtureDocument("arch", "architecture-diagram.txt"),
          new FixtureDocument("models", "product-model-catalog.md"),
          new FixtureDocument("codes", "contract-code-registry.txt"),
          // expand-rag-benchmark 任务 1.3：新增 5 语料（客户 SOP/价格政策/区域政策/SLA/维保周期表）
          new FixtureDocument("customer-sop", "customer-sop.md"),
          new FixtureDocument("pricing", "pricing-policy.md"),
          new FixtureDocument("regional-policy", "regional-policy.txt"),
          new FixtureDocument("sla", "sla-terms.md"),
          // fix-i05-caption-chunk 任务 1.3：图注独立成文件，避免 320 滑窗与赔偿/责任段粘连
          // fix-i05-caption-chunk 任务 1.3：图注独立成文件，避免 320 滑窗与赔偿/责任段粘连
          new FixtureDocument("sla-arch", "sla-arch-diagram.md"),
          new FixtureDocument("maint", "maintenance-schedule.xlsx"),
          // expand-rag-benchmark-mismatch 任务 1.1：三类失配语料（行话/编号体系/口语-术语同义）
          new FixtureDocument("trade-jargon", "trade-jargon-glossary.md"),
          new FixtureDocument("equipment", "equipment-codebook.txt"),
          new FixtureDocument("expense", "expense-colloquial-faq.md"));

  /**
   * 幂等入库全部评测语料：清旧向量 → 真实分块 → 剥标记 → 向量化 → 写入向量库。
   *
   * @param store 目标向量库（评测环境通常是 {@code InMemoryVectorStore}）
   * @param embedder 切片向量化函数
   * @param evalKbId 评测知识库 id（写入 metadata.knowledgeBaseId，与检索侧过滤一致）
   * @return 黄金映射与 chunkId 集合
   * @throws IllegalStateException 语料缺失或解析失败时（黄金对齐校验在 {@link #validateAlignment}）
   */
  public static Preparation prepare(CrmVectorStore store, ChunkEmbedder embedder, long evalKbId) {
    return prepare(store, embedder, evalKbId, new DocumentService());
  }

  /**
   * 幂等入库全部评测语料：清旧向量 → 真实分块 → 剥标记 → 向量化 → 写入向量库。
   *
   * @param store 目标向量库（评测环境通常是 {@code InMemoryVectorStore}）
   * @param embedder 切片向量化函数
   * @param evalKbId 评测知识库 id（写入 metadata.knowledgeBaseId，与检索侧过滤一致）
   * @param documentService 分块服务（默认 fixed；run-baseline-ladder 第四跑传按 {@code rag.chunking.strategy}
   *     装配的实例以走 semantic）
   * @return 黄金映射与 chunkId 集合
   * @throws IllegalStateException 语料缺失或解析失败时（黄金对齐校验在 {@link #validateAlignment}）
   */
  public static Preparation prepare(
      CrmVectorStore store,
      ChunkEmbedder embedder,
      long evalKbId,
      DocumentService documentService) {
    Map<String, String> goldenToChunkId = new LinkedHashMap<>();
    Set<String> chunkIds = new LinkedHashSet<>();
    List<String> chunkTexts = new ArrayList<>();
    List<BenchmarkChunk> chunkList = new ArrayList<>();

    for (FixtureDocument fixture : FIXTURES) {
      String documentId = "benchdoc-" + fixture.key();
      // 幂等第一步：先清掉本语料的旧向量，语料改版后重跑不留脏切片
      store.deleteByDocumentId(documentId);
      List<DocumentChunk> chunks = parse(documentService, fixture);

      for (DocumentChunk chunk : chunks) {
        Matcher marker = GOLD_MARKER.matcher(chunk.text());
        List<String> goldIds = new ArrayList<>();
        while (marker.find()) {
          goldIds.add(marker.group(1));
        }
        // 剥离标记后再索引：向量库里的文本是"干净"的生产形态（宽松模式兼容滑窗切半标记的残段）
        String indexedText = GOLD_MARKER_FRAGMENT.matcher(chunk.text()).replaceAll("").strip();
        String chunkId = fixture.key() + "-" + chunk.chunkIndex();
        for (String goldId : goldIds) {
          // 首个（chunkIndex 最小）完整命中者获胜：overlap 复制时结果仍确定
          goldenToChunkId.putIfAbsent(goldId, chunkId);
        }

        chunkIds.add(chunkId);
        chunkTexts.add(indexedText);
        chunkList.add(
            new BenchmarkChunk(
                chunkId,
                indexedText,
                fixture.filename(),
                BENCHMARK_CATEGORY,
                chunk.chunkIndex(),
                chunk.pageNo() == null ? null : chunk.pageNo().intValue(),
                chunk.rowIndex() == null ? null : chunk.rowIndex().intValue()));
        // 嵌入输入与生产入库（DocumentIngestionService）同口径：块头 + 切片文本（提案4 任务 2.1）；
        // 向量记录文本仍为剥离标记后的干净原文，引用展示不受头污染
        String embedText =
            ChunkHeaderText.wrap(
                fixture.filename(),
                BENCHMARK_CATEGORY,
                chunk.pageNo(),
                chunk.rowIndex(),
                indexedText);
        store.upsert(
            new VectorRecord(
                documentId + ":" + chunk.chunkIndex(),
                documentId,
                chunkId,
                chunk.chunkIndex(),
                indexedText,
                embedder.embed(embedText),
                metadata(fixture, chunk, evalKbId, chunkId)));
      }
    }
    return new Preparation(
        Map.copyOf(goldenToChunkId),
        Set.copyOf(chunkIds),
        List.copyOf(chunkTexts),
        List.copyOf(chunkList));
  }

  /**
   * 对齐校验（任务 2.4）：黄金集引用的占位 id 必须全部能映射到真实 chunkId。
   *
   * @param goldenIds 待校验的占位 id 集合（通常是基准集里全部 expectedChunkIds）
   * @param preparation 数据准备产出
   * @throws IllegalStateException 列出所有未对齐的占位 id——拒绝静默按空集计分
   */
  public static void validateAlignment(Collection<String> goldenIds, Preparation preparation) {
    List<String> unaligned =
        goldenIds.stream().filter(id -> !preparation.goldenToChunkId().containsKey(id)).toList();
    if (!unaligned.isEmpty()) {
      throw new IllegalStateException(
          "基准集黄金片段未对齐到入库 chunkId（占位 id 缺少 GOLD 标记或语料缺失）: "
              + unaligned
              + "；已对齐映射="
              + preparation.goldenToChunkId());
    }
  }

  /**
   * 把基准集的占位 id 替换为真实 chunkId（真检索基准运行前的最后一步）。
   *
   * @throws IllegalStateException 任一占位 id 未对齐时显式失败
   */
  public static List<RagBenchmarkCase> rewriteSuite(
      List<RagBenchmarkCase> suite, Preparation preparation) {
    Set<String> placeholders = new LinkedHashSet<>();
    suite.forEach(c -> placeholders.addAll(c.expectedChunkIds()));
    validateAlignment(placeholders, preparation);
    return suite.stream()
        .map(
            c ->
                new RagBenchmarkCase(
                    c.id(),
                    c.category(),
                    c.question(),
                    c.expectedChunkIds().stream()
                        .map(preparation.goldenToChunkId()::get)
                        .collect(LinkedHashSet::new, LinkedHashSet::add, LinkedHashSet::addAll),
                    c.expectedAnswerPoints(),
                    c.useKnowledgeBase()))
        .toList();
  }

  private static List<DocumentChunk> parse(
      DocumentService documentService, FixtureDocument fixture) {
    String resourcePath = "/rag-quality/fixtures/" + fixture.filename();
    try (InputStream in = RagBenchmarkDataPreparer.class.getResourceAsStream(resourcePath)) {
      if (in == null) {
        throw new IllegalStateException("评测语料缺失: " + resourcePath);
      }
      byte[] bytes = in.readAllBytes();
      // DocumentService.process 声明受检异常（IO/解析），runner 统一转非法状态
      return documentService.process(
          new ByteArrayInputStream(bytes), fixture.filename(), BENCHMARK_CATEGORY);
    } catch (IOException exception) {
      throw new UncheckedIOException("读取评测语料失败: " + resourcePath, exception);
    } catch (Exception exception) {
      if (exception instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException("解析评测语料失败: " + resourcePath, exception);
    }
  }

  /** metadata 键与生产入库（DocumentIngestionService）同口径，保证检索侧过滤/引用行为一致。 */
  private static Map<String, Object> metadata(
      FixtureDocument fixture, DocumentChunk chunk, long evalKbId, String chunkId) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", String.valueOf(evalKbId));
    metadata.put("category", BENCHMARK_CATEGORY);
    metadata.put("filename", fixture.filename());
    metadata.put("fileType", fileType(fixture.filename()));
    metadata.put("pageNo", chunk.pageNo() == null ? 0L : chunk.pageNo().longValue());
    metadata.put("rowIndex", chunk.rowIndex() == null ? 0L : chunk.rowIndex().longValue());
    // chunkIndex：生产 QdrantVectorStore.upsert 单独写 payload（toMetadata 还原进命中 metadata），
    // ContextBuilder 邻居拼装读它；InMemoryVectorStore 直存本 map，故在此补齐对齐生产命中口径
    metadata.put("chunkIndex", chunk.chunkIndex());
    metadata.put("chunkId", chunkId);
    return metadata;
  }

  private static String fileType(String filename) {
    int dot = filename.lastIndexOf('.');
    return dot < 0 ? "txt" : filename.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
  }
}
