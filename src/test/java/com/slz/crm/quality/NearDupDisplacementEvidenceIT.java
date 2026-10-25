package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.ImageSliceMetadata;
import com.slz.crm.knowledge.document.ImageVectorFlushSupport;
import com.slz.crm.knowledge.document.PdfImageSliceIngestService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.NearDupAblationPositiveControlTest;
import com.slz.crm.knowledge.retrieval.RetrievalCandidate;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.retrieval.SourceReferenceFactory;
import com.slz.crm.knowledge.vector.ImageVectorStore;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import reactor.core.publisher.Flux;

/**
 * 近重复位移证据 G1 真跑（near-dup-missing-evidence 任务组 1，failsafe IT，A 档真跑）： {@code
 * docs/rag-quality/near-dup-displacement-evidence-v1.json} 的<b>唯一写入口</b>。
 *
 * <p><b>观测缺口是什么</b>：上一卡 {@code ProductionGrayRealRunIT} 的视觉路与文本路是两个独立检索服务（视觉路的文本集合为空）， 从未有一次调用同时产出
 * TEXT 与 IMAGE 候选——真实混合查询的「双路共现 + 分差」从未被观测。本 IT 把<b>同一个</b> {@link KnowledgeRetrievalServiceImpl}
 * 同时装上文本集合与图片集合，对每条真实查询<b>一次</b> {@code retrieve}（query 文本与 imageVector 同参）读 {@code sources()}，逐条落
 * {@code (sourceType, relevanceScore, chunkId)}——这正是被补的那个缺口本身， <b>严禁</b>用两次独立 retrieve 拼凑共现。
 *
 * <p><b>三模式（判据 tasks.md D1–D5，跑之前冻结）</b>：
 *
 * <ol>
 *   <li><b>未 opt-in</b>（默认 CI/verify）：整类 {@link Assumptions} 跳过、零外呼、不落文件；
 *   <li><b>opt-in + {@code -Dnear.dup.displacement.write=true}</b>：全量真跑（真 VLM 描述 + 真视觉/文本 embedding
 *       + 生产检索链）， 全部输入向量与文本存进锚的 {@code corpusMaterial}，写锚；
 *   <li><b>opt-in 无 write（默认复跑）</b>：从锚的 {@code corpusMaterial} 本地重建两集合，重放同一条生产检索链，重算全部判据字段
 *       逐值断言相等，再整体序列化与磁盘锚<b>逐字节比对</b>——零外呼（桩 provider 对 embedImage/vision 一律抛错自证）。
 *       为什么不让默认复跑再真跑一遍：VLM 描述与跨次编码不可保证逐字节确定（M2 的 1e-3 容差本身就排除跨次逐字节一致），
 *       「逐字节一致」唯一可辩护的实现就是把输入向量冻结在锚里、复跑只重放确定性的本地检索算术。
 * </ol>
 *
 * <p><b>模态判别（硬边界 6）</b>：只认 {@code sourceType=IMAGE}（+ {@code chunkId=IMAGE:{assetId}}）； {@code
 * route} 的取值域是 {@code vector|keyword|hybrid|ocr}，<b>不是</b>图文路由，判别函数 {@link #isImageSource} 禁止引用它
 * （守卫 {@code NearDupDisplacementEvidenceGuardTest} 读本类源码断言）。
 *
 * <p><b>文本集合构成（生产口径）</b>：图片切片的 VLM 描述块（与生产 {@code DocumentIngestionSupport#createVectorRecord} 同形，
 * 带 {@code sourceType=IMAGE}）+ PDF 正文逐页文本块（不带 image 元数据 → {@code sourceType=pdf}，即「非 IMAGE
 * 竞争者」的来源）。 逐页文本是测试侧 PDFBox 抽取（语料仍是既有 3 份真实 PDF，不下载新资产）；{@link #MAX_TEXT_CHUNKS_PER_DOCUMENT}
 * 是成本帽不是能力上限，落锚 {@code textComposition}。
 */
@DisplayName("近重复位移证据 G1：真实混合查询双路共现与分差（RAG_NEAR_DUP_DISPLACEMENT_REAL 显式 opt-in）")
class NearDupDisplacementEvidenceIT {

  /** 真跑 opt-in 键（本卡门控变量名冻结，任务卡硬边界 7）。 */
  static final String OPT_IN_ENV = "RAG_NEAR_DUP_DISPLACEMENT_REAL";

  /** 锚写入门控属性（冻结）。 */
  static final String WRITE_PROP = "near.dup.displacement.write";

  /** G1 锚落盘路径（写集内唯一 JSON 产物）。 */
  static final Path ANCHOR_PATH =
      Path.of("docs", "rag-quality", "near-dup-displacement-evidence-v1.json");

  /** v2 定标锚（只读：D4 反事实重建取 near-duplicate 档 cos）。 */
  private static final Path CALIBRATION_ANCHOR =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  /**
   * 序列化显式锁 LF：INDENT_OUTPUT 的默认对象缩进器用系统行尾（Windows 落 \r\n），而 .gitattributes 对 *.json 强制 eol=lf， 锚的字节
   * SHA 会在 CI checkout 后漂移、默认复跑逐字节比对必红——只换对象缩进器的行尾为 LF，数组缩进器保持 Jackson 默认（FixedSpace 行内标量数组）。
   */
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .setDefaultPrettyPrinter(
              new com.fasterxml.jackson.core.util.DefaultPrettyPrinter()
                  .withObjectIndenter(
                      new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n")));

  /** compatible-mode 端点（与既有真跑 IT 相同装配）。 */
  private static final String COMPATIBLE_BASE_URL =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";

  /** 视觉 embedding 的 MIME（生产 {@code PdfImageSliceIngestService.IMAGE_MIME_TYPE} 同值）。 */
  private static final String IMAGE_MIME = "image/png";

  /** 图片切片类目（生产切片链的类目过滤键，本卡不自造）。 */
  private static final String IMAGE_CATEGORY = ImageCrossModalFixture.IMAGE_CATEGORY;

  /** 评测上下文用户 id / 知识库 id（独立取值，不与既有真跑 IT 共享，避免语义混用）。 */
  private static final long EVAL_USER_ID = 99006L;

  private static final String EVAL_KB_ID = "9904";

  private static final long EVAL_KB_ID_LONG = 9904L;

  /** 切片/文本块行主键起点（测试侧合成 DB 主键，跨记录全局唯一）。 */
  private static final long FIRST_CHUNK_ID = 9400L;

  /** 召回截断（与既有套件同口径）。 */
  private static final int TOP_K = ImageCrossModalSuite.TOP_K;

  /** 逐文档正文文本块成本帽（页序取前 N 个含足够文本的页；落锚 textComposition）。 */
  static final int MAX_TEXT_CHUNKS_PER_DOCUMENT = 12;

  /** 正文页文本最短字符数（短于它的页不构成可召回文本块）。 */
  static final int MIN_PAGE_TEXT_CHARS = 40;

  /** embedImage 调用节奏（毫秒）：定标卡实测 2 QPS 连打中途 429，压到 ~0.9 QPS。 */
  private static final long PACE_MILLIS = 1100L;

  /** 429 有界退避重试次数（仅限流；其余失败一律整体失败，不落半成品锚）。 */
  private static final int THROTTLE_RETRIES = 3;

  private static final long THROTTLE_BACKOFF_MILLIS = 5000L;

  /** 视觉模型覆盖属性（与 {@code ProductionGrayRealRunIT} 同枚；本机该 key 实测 qwen-vl-plus model_not_found）。 */
  private static final String VISION_MODEL_PROP = "rag.pdf.image.vision-model";

  private static final String DEFAULT_VISION_MODEL = "qwen-vl-plus";

  /** 图像路融合权重（RetrievalConfigResolver 字面值 0.30；W_max 推导的底数之一）。 */
  private static final double IMAGE_ROUTE_WEIGHT = 0.30D;

  /** 位移窗口 W_max = 0.30 × (1 − 0.5) × 1.0（w 最小档 0.5、cos 最大 1.0 的极端值；判据 D3 冻结）。 */
  static final double W_MAX = IMAGE_ROUTE_WEIGHT * (1D - 0.5D) * 1.0D;

  /** 三档降权权重（fusion 锚 bounds 同值；D4 反事实代入用）。 */
  private static final double[] WEIGHT_TIERS = {0.75D, 0.6D, 0.5D};

  /** 本卡起点基线（master @ 5519c43，任务 0.5 记录项）。 */
  private static final String BASELINE_HEAD = "5519c43bd61c886eb2b129a7498e796d20e43d8d";

  private static final String ASSUMPTION_TEXT = "「竞争者分差跨查询可迁移」是假设，不作因果结论；本节是反事实重建（D4），不是实测（D2/D3）";

  @Test
  @DisplayName("G1：混合查询一次调用读 sources()，落共现与分差证据锚（写锚 / 默认复跑逐字节校验）")
  void runMixedQueryCooccurrenceEvidence() throws Exception {
    if (!"1".equals(System.getenv(OPT_IN_ENV))) {
      Assumptions.assumeTrue(false, "未显式 opt-in（" + OPT_IN_ENV + "=1）：跳过近重复位移证据真跑（默认 CI 零外呼）");
    }
    if (isWriteMode()) {
      String apiKey = resolveApiKey();
      Assumptions.assumeTrue(
          StringUtils.hasText(apiKey), "DASHSCOPE_API_KEY 未配置（env 或仓库根 .env）：跳过写锚真跑");
      runRealAndWrite(apiKey);
    } else {
      replayAndVerifyByteIdentity();
    }
  }

  /** 是否写锚模式（opt-in 且显式 {@code -Dnear.dup.displacement.write=true}）。 */
  private static boolean isWriteMode() {
    return "true".equalsIgnoreCase(System.getProperty(WRITE_PROP));
  }

  // ==================== 写锚模式：全量真跑 ====================

  private void runRealAndWrite(String apiKey) throws Exception {
    TrackingProvider provider =
        new TrackingProvider(realProvider(properties(apiKey), apiKey), PACE_MILLIS);
    EmbeddingService embeddingService = new EmbeddingService(provider, properties(apiKey));

    // 1) 真实 PDF → 生产切片链 → image 集合 + 描述文本块；PDFBox 逐页正文 → 文本块
    CorpusBuild build = buildCorpus(provider, embeddingService);
    assertTrue(
        build.rows().size() >= 3, "整批真实 PDF 至少 3 个图片切片（少于 3 说明链路没走通），实测 " + build.rows().size());

    // 2) 每条查询：真 embedImage（与灰卡同口径：落盘 PNG 重新编码）+ 一次混合 retrieve
    List<QueryEvidence> evidences = runMixedQueries(provider, embeddingService, build);
    assertEquals(build.rows().size(), evidences.size(), "逐切片查询数必须等于切片数（漏查即证据面缺失）");

    // 3) 判据计算（D1–D5）
    ModelProviderProperties runProperties = properties(apiKey);
    AnchorInputs inputs =
        new AnchorInputs(
            build,
            evidences,
            provider.visionCalls.get(),
            provider.embedImageCalls.get(),
            provider.embedCalls.get(),
            provider.embedCache(),
            visionModel(),
            runProperties.getEmbeddingModel(),
            runProperties.getImageEmbeddingModel());
    ObjectNode root = buildAnchor(inputs, computeResults(evidences, inputs));

    Files.createDirectories(ANCHOR_PATH.toAbsolutePath().getParent());
    Files.writeString(ANCHOR_PATH, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
    System.out.println(
        "[near-dup-displacement] 锚已落盘: "
            + ANCHOR_PATH.toAbsolutePath()
            + "（queryCount="
            + evidences.size()
            + " externalCalls="
            + (provider.visionCalls.get()
                + provider.embedImageCalls.get()
                + provider.embedCalls.get())
            + "）");
  }

  /** 语料构建产物：切片行、两个集合的全部记录、逐查询文本。 */
  private record CorpusBuild(
      List<SliceRow> rows,
      List<StoredRecord> imageRecords,
      List<StoredRecord> textRecords,
      List<PendingQuery> pendingQueries) {}

  /** 一条待执行查询（文本 + 查询图 PNG；向量在执行时真编码）。 */
  private record PendingQuery(SliceRow row, byte[] png) {}

  private CorpusBuild buildCorpus(TrackingProvider provider, EmbeddingService embeddingService)
      throws Exception {
    CapturingInMemoryStore captureStore = new CapturingInMemoryStore();
    ImageVectorStore imageStore = new ImageVectorStore(captureStore);
    InMemoryVectorStore textStore = new InMemoryVectorStore();
    ObjectProvider<DynamicConfigService> configProvider =
        RagBenchmarkPipelineFactory.provider(RagBenchmarkPipelineFactory.dynamicConfig(config()));

    List<RealPdfAssets.Asset> assets = RealPdfAssets.assets();
    assertEquals(3, assets.size(), "语料基座必须是登记的 3 份真实 PDF（不下载新资产），实测 " + assets.size());

    List<SliceRow> rows = new ArrayList<>();
    List<StoredRecord> imageRecords = new ArrayList<>();
    List<StoredRecord> textRecords = new ArrayList<>();
    List<PendingQuery> pendingQueries = new ArrayList<>();
    long nextChunkId = FIRST_CHUNK_ID;

    for (RealPdfAssets.Asset asset : assets) {
      byte[] pdf = asset.pdf();
      PdfIngestImageFixture.InMemoryImageStorage storage = PdfIngestImageFixture.inMemoryStorage();
      PdfImageSliceIngestService ingestService =
          new PdfImageSliceIngestService(
              provider, configProvider, provider((TokenUsageRecorder) null), provider(storage));
      List<DocumentChunk> slices =
          ingestService.buildSlices(pdf, asset.file(), IMAGE_CATEGORY, EVAL_KB_ID);
      // 暂存表必须在 flush 之前读：flush 消费后清 ThreadLocal，那时读到的是空表
      Map<String, float[]> staged = RealPdfAssets.stagedVectors();

      List<DocumentVectorChunkEntity> children = new ArrayList<>(slices.size());
      List<Long> chunkIds = new ArrayList<>(slices.size());
      for (int index = 0; index < slices.size(); index++) {
        long chunkId = nextChunkId++;
        chunkIds.add(chunkId);
        children.add(row(chunkId, index, slices.get(index), asset));
      }
      ImageVectorFlushSupport.flush(file(asset), children, slices, imageStore);

      for (int index = 0; index < slices.size(); index++) {
        DocumentChunk slice = slices.get(index);
        long chunkId = chunkIds.get(index);
        String storageKey = ImageSliceMetadata.storageKeyOf(slice.extraMetadata());
        float[] vector = staged.get(storageKey);
        assertNotNull(vector, asset.key() + " 第 " + slice.pageNo() + " 页图缺视觉向量（视觉路未生效）");
        byte[] png = storage.open(storageKey).readAllBytes();
        rows.add(
            new SliceRow(
                asset.key(), asset.file(), chunkId, slice.text(), vector, png, slice.pageNo()));
        pendingQueries.add(new PendingQuery(rows.get(rows.size() - 1), png));

        // 描述文本块：生产口径 createVectorRecord 同形（sourceType=IMAGE + imageAssetId），与 image 记录同 chunkId
        float[] textVector = embeddingService.embed(slice.text());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("knowledgeBaseId", EVAL_KB_ID);
        metadata.put("category", "");
        metadata.put("filename", asset.file());
        metadata.put("fileType", "pdf");
        metadata.put("pageNo", (long) slice.pageNo());
        metadata.put("rowIndex", 0L);
        metadata.put("chunkId", String.valueOf(chunkId));
        metadata.put(ImageSliceMetadata.KEY_SOURCE_TYPE, ImageSliceMetadata.SOURCE_TYPE_IMAGE);
        metadata.put(ImageSliceMetadata.KEY_IMAGE_ASSET_ID, String.valueOf(chunkId));
        metadata.put(ImageSliceMetadata.KEY_IMAGE_STORAGE_KEY, storageKey);
        textRecords.add(
            new StoredRecord(
                "neardup-disp-txt-" + chunkId,
                documentIdOf(asset),
                String.valueOf(chunkId),
                index,
                slice.text(),
                textVector,
                metadata));
      }

      // 正文逐页文本块（成本帽内）：不带 image 元数据 → sourceType=pdf（非 IMAGE 竞争者的来源）
      int pageChunks = 0;
      try (PDDocument document = Loader.loadPDF(pdf)) {
        for (int page = 1;
            page <= document.getNumberOfPages() && pageChunks < MAX_TEXT_CHUNKS_PER_DOCUMENT;
            page++) {
          PDFTextStripper stripper = new PDFTextStripper();
          stripper.setStartPage(page);
          stripper.setEndPage(page);
          String text = stripper.getText(document).strip();
          if (text.length() < MIN_PAGE_TEXT_CHARS) {
            continue;
          }
          long chunkId = nextChunkId++;
          float[] textVector = embeddingService.embed(text);
          Map<String, Object> metadata = new LinkedHashMap<>();
          metadata.put("knowledgeBaseId", EVAL_KB_ID);
          metadata.put("category", "");
          metadata.put("filename", asset.file());
          metadata.put("fileType", "pdf");
          metadata.put("pageNo", (long) page);
          metadata.put("rowIndex", 0L);
          metadata.put("chunkId", String.valueOf(chunkId));
          textRecords.add(
              new StoredRecord(
                  "neardup-page-txt-" + chunkId,
                  documentIdOf(asset),
                  String.valueOf(chunkId),
                  pageChunks,
                  text,
                  textVector,
                  metadata));
          pageChunks++;
        }
      }
      System.out.println(
          "[near-dup-displacement] "
              + asset.key()
              + " sliced="
              + slices.size()
              + " pageTextChunks="
              + pageChunks);
    }

    // 捕获 flush 写入的 image 集合记录（生产 createVectorRecord 口径，含随机 point id——落锚供 replay 重建）
    imageRecords.addAll(captureStore.captured());
    for (StoredRecord record : textRecords) {
      textStore.upsert(record.toVectorRecord());
    }
    return new CorpusBuild(
        List.copyOf(rows),
        List.copyOf(imageRecords),
        List.copyOf(textRecords),
        List.copyOf(pendingQueries));
  }

  /** 逐条混合查询：真 embedImage + 一次 retrieve（text 与 imageVector 同参）。 */
  private List<QueryEvidence> runMixedQueries(
      TrackingProvider provider, EmbeddingService embeddingService, CorpusBuild build) {
    KnowledgeBaseAuthorizationService authorization =
        Mockito.mock(KnowledgeBaseAuthorizationService.class);
    Mockito.when(
            authorization.authorizedKnowledgeBaseIds(
                ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(List.of(EVAL_KB_ID_LONG));
    ObjectProvider<DynamicConfigService> configProvider =
        RagBenchmarkPipelineFactory.provider(RagBenchmarkPipelineFactory.dynamicConfig(config()));
    KnowledgeRetrievalServiceImpl service =
        new KnowledgeRetrievalServiceImpl(
            authorization,
            embeddingService,
            textStoreOf(build),
            new RetrievalQueryRewriteService(provider, configProvider),
            new Bm25Scorer(),
            configProvider);
    ReflectionTestUtils.setField(service, "imageVectorStore", imageStoreOf(build));

    List<QueryEvidence> evidences = new ArrayList<>();
    UserContext evaluatorContext =
        new UserContext(
            EVAL_USER_ID, 5L, 101L, DataScopeLevel.ALL, "near-dup-displacement-evidence");
    UserContextHolder.set(evaluatorContext);
    try {
      int index = 0;
      for (PendingQuery pending : build.pendingQueries()) {
        float[] queryVector = embedImageWithPace(provider, pending.png(), pending.row().assetKey());
        KnowledgeRetrievalPort.RetrievalResult result =
            service.retrieve(
                new KnowledgeRetrievalPort.RetrievalQuery(
                    pending.row().description(),
                    EVAL_USER_ID,
                    List.of(EVAL_KB_ID),
                    TOP_K,
                    queryVector,
                    null));
        evidences.add(
            new QueryEvidence(
                index,
                pending.row(),
                result.sources() == null ? List.of() : result.sources(),
                queryVector));
        index++;
        System.out.println(
            "[near-dup-displacement] query "
                + index
                + "/"
                + build.pendingQueries().size()
                + " sources="
                + (result.sources() == null ? 0 : result.sources().size()));
      }
    } finally {
      UserContextHolder.clear();
    }
    return evidences;
  }

  // ==================== 判据计算（D1–D5，跑前冻结） ====================

  /** 一次判据计算的产物（write 与 replay 两模式共用同一实现，保证口径一致）。 */
  private record ComputedResults(
      int queryCount,
      int cooccurrenceQueryCount,
      List<ObjectNode> queryNodes,
      Double minDownwardGap,
      ArrayNode withinWindow,
      String verdict,
      String verdictReason,
      int counterfactualComparisons,
      int counterfactualDisplacingPairs,
      Map<String, Integer> counterfactualPerWeight) {}

  private record AnchorInputs(
      CorpusBuild build,
      List<QueryEvidence> evidences,
      int visionCalls,
      int embedImageCalls,
      int embedCalls,
      Map<String, float[]> embedCache,
      String visionModel,
      String textEmbeddingModel,
      String imageEmbeddingModel) {}

  private ComputedResults computeResults(List<QueryEvidence> evidences, AnchorInputs inputs) {
    int cooccurrence = 0;
    List<ObjectNode> queryNodes = new ArrayList<>();
    List<ObjectNode> withinWindow = new ArrayList<>();
    List<Double> gaps = new ArrayList<>();
    for (QueryEvidence evidence : evidences) {
      ObjectNode queryNode = MAPPER.createObjectNode();
      queryNode.put("queryIndex", evidence.index());
      queryNode.put("assetKey", evidence.row().assetKey());
      queryNode.put("pageNo", evidence.row().pageNo());
      queryNode.put("chunkId", String.valueOf(evidence.row().chunkId()));
      queryNode.put("queryText", evidence.row().description());
      List<SourceRef> sources = new ArrayList<>();
      for (com.slz.crm.platform.contract.SourceReference source : evidence.sources()) {
        sources.add(
            new SourceRef(
                source.sourceType(), source.chunkId(), source.relevanceScore(), source.pageNo()));
      }
      ArrayNode sourceNodes = queryNode.putArray("sources");
      for (SourceRef source : sources) {
        ObjectNode node = sourceNodes.addObject();
        node.put("sourceType", source.sourceType());
        node.put("chunkId", source.chunkId());
        node.put("relevanceScore", source.relevanceScore());
        if (source.pageNo() != null) {
          node.put("pageNo", source.pageNo());
        } else {
          node.putNull("pageNo");
        }
      }
      // 模态判别：只认 sourceType（isImageSource 单点函数，守卫读源码断言不引用 route）
      List<SourceRef> imageSources = sources.stream().filter(this::isImageSource).toList();
      List<SourceRef> nonImageSources = sources.stream().filter(s -> !isImageSource(s)).toList();
      boolean cooccurred = !imageSources.isEmpty() && !nonImageSources.isEmpty();
      if (cooccurred) {
        cooccurrence++;
      }
      queryNode.put("cooccurred", cooccurred);
      queryNode.put("imageCandidateCount", imageSources.size());

      // D2：对每个带下位竞争者的图像候选取 gap（score − 比它低的最大竞争者分）；查询级 gap = 最小（最易位移者）
      Double queryGap = null;
      String imageCandidateChunkId = null;
      Double maxCompetitorBelow = null;
      for (SourceRef image : imageSources) {
        Double strongestBelow = null;
        for (SourceRef other : sources) {
          if (other == image) {
            continue;
          }
          if (other.relevanceScore() != null
              && image.relevanceScore() != null
              && other.relevanceScore() < image.relevanceScore()
              && (strongestBelow == null || other.relevanceScore() > strongestBelow)) {
            strongestBelow = other.relevanceScore();
          }
        }
        if (strongestBelow == null) {
          continue;
        }
        double gap = image.relevanceScore() - strongestBelow;
        if (queryGap == null || gap < queryGap) {
          queryGap = gap;
          imageCandidateChunkId = image.chunkId();
          maxCompetitorBelow = strongestBelow;
        }
      }
      // 图像候选存在但全部末位（无任何下位来源）⇒ 该查询 gap=null；预注册解释：无下位竞争者即无位移目标
      boolean imageIsLast = !imageSources.isEmpty() && queryGap == null;
      if (queryGap == null) {
        queryNode.putNull("downwardGap");
      } else {
        queryNode.put("downwardGap", queryGap);
        if (queryGap <= W_MAX) {
          ObjectNode windowNode = MAPPER.createObjectNode();
          windowNode.put("queryIndex", evidence.index());
          windowNode.put("assetKey", evidence.row().assetKey());
          windowNode.put("gap", queryGap);
          withinWindow.add(windowNode);
        }
      }
      queryNode.put("imageCandidateIsLast", imageIsLast);
      if (imageCandidateChunkId != null) {
        queryNode.put("imageCandidateChunkId", imageCandidateChunkId);
      } else {
        queryNode.putNull("imageCandidateChunkId");
      }
      if (maxCompetitorBelow != null) {
        queryNode.put("maxCompetitorBelowScore", maxCompetitorBelow);
      } else {
        queryNode.putNull("maxCompetitorBelowScore");
      }
      queryNodes.add(queryNode);
      gaps.add(queryGap);
    }

    Double minDownwardGap = null;
    for (Double gap : gaps) {
      if (gap != null && (minDownwardGap == null || gap < minDownwardGap)) {
        minDownwardGap = gap;
      }
    }

    // D3：verdict（判据冻结：W_max = 0.15；gap=null 的预注册解释：图像候选全部末位 ⇒ 无下位竞争者即无位移目标）
    String verdict;
    String verdictReason;
    if (cooccurrence == 0) {
      verdict = "NO-COOCCURRENCE";
      verdictReason =
          "D1：全轮 0 次共现（一次调用同时产不出 IMAGE 与非 IMAGE 候选）——按判据停 G1，共现缺口保持「仍缺」，" + "严禁退化成两次独立 retrieve 凑数";
    } else if (minDownwardGap != null && minDownwardGap > W_MAX) {
      verdict = "NOT-DISPLACED-IN-CORPUS";
      verdictReason =
          "D3：全轮最小 downwardGap " + minDownwardGap + " > W_max " + W_MAX + "，实测分差全部落在位移窗口外";
    } else if (minDownwardGap == null) {
      verdict = "NOT-DISPLACED-IN-CORPUS";
      verdictReason = "D3：全部共现查询的图像候选已是末位（downwardGap=null）——无下位竞争者即无位移目标（预注册解释）";
    } else {
      verdict = "POSSIBLY-DISPLACED";
      verdictReason =
          "D3：存在 downwardGap ≤ W_max " + W_MAX + " 的共现查询（withinWindow 逐条列出），跨路位移在本语料上可能发生";
    }

    // D4：反事实重建（与实测分两节，禁止并表）
    int comparisons = 0;
    int displacing = 0;
    Map<String, Integer> perWeight = new LinkedHashMap<>();
    for (double w : WEIGHT_TIERS) {
      perWeight.put(String.valueOf(w), 0);
    }
    if (cooccurrence > 0) {
      List<Double> nearDupCosines = calibrationNearDuplicateCosines();
      for (QueryEvidence evidence : evidences) {
        List<SourceRef> refs = toRefs(evidence);
        boolean hasImage = refs.stream().anyMatch(this::isImageSource);
        Double maxCompetitor = null;
        for (SourceRef source : refs) {
          if (!isImageSource(source)
              && source.relevanceScore() != null
              && (maxCompetitor == null || source.relevanceScore() > maxCompetitor)) {
            maxCompetitor = source.relevanceScore();
          }
        }
        // 反事实只对共现查询做（D4 重建的是 A2 的共现面）；无图像候选或无实测竞争者则无比较对象
        if (!hasImage || maxCompetitor == null) {
          continue;
        }
        for (double cosine : nearDupCosines) {
          for (double w : WEIGHT_TIERS) {
            comparisons++;
            if (IMAGE_ROUTE_WEIGHT * cosine * w < maxCompetitor) {
              displacing++;
              perWeight.merge(String.valueOf(w), 1, Integer::sum);
            }
          }
        }
      }
    }

    return new ComputedResults(
        evidences.size(),
        cooccurrence,
        queryNodes,
        minDownwardGap,
        withinWindowNode(withinWindow),
        verdict,
        verdictReason,
        comparisons,
        displacing,
        perWeight);
  }

  private ArrayNode withinWindowNode(List<ObjectNode> nodes) {
    ArrayNode array = MAPPER.createArrayNode();
    nodes.forEach(array::add);
    return array;
  }

  /** 模态判别单点函数（硬边界：禁用 route；守卫读本源码断言本方法体不引用 route）。 */
  private boolean isImageSource(SourceRef source) {
    return ImageSliceMetadata.SOURCE_TYPE_IMAGE.equals(source.sourceType())
        && source.chunkId() != null
        && source.chunkId().startsWith(SourceReferenceFactory.IMAGE_CHUNK_PREFIX);
  }

  private List<SourceRef> toRefs(QueryEvidence evidence) {
    List<SourceRef> refs = new ArrayList<>();
    for (SourceReference source : evidence.sources()) {
      refs.add(
          new SourceRef(
              source.sourceType(), source.chunkId(), source.relevanceScore(), source.pageNo()));
    }
    return refs;
  }

  /** v2 定标锚 near-duplicate 档的 cos（只读；D4 反事实代入值域）。 */
  static List<Double> calibrationNearDuplicateCosines() {
    try {
      JsonNode root = MAPPER.readTree(Files.readAllBytes(CALIBRATION_ANCHOR));
      List<Double> cosines = new ArrayList<>();
      for (JsonNode pair : root.path("pairs")) {
        if ("near-duplicate".equals(pair.path("stratum").asText())) {
          cosines.add(pair.path("cosine").asDouble());
        }
      }
      assertEquals(120, cosines.size(), "定标锚 near-duplicate 档必须 120 对，实测 " + cosines.size());
      return cosines;
    } catch (Exception exception) {
      throw new IllegalStateException("定标锚读取失败（上游锚只读）: " + CALIBRATION_ANCHOR, exception);
    }
  }

  // ==================== 锚构建（write 与 replay 共用，字段顺序即序列化顺序） ====================

  private ObjectNode buildAnchor(AnchorInputs inputs, ComputedResults results) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", "near-dup-missing-evidence");
    root.put("group", "G1");
    root.put("decisionVersion", "NEAR-DUP-DISPLACEMENT-EVIDENCE-1.0");
    root.put("decisionOwner", "OWNER");

    ObjectNode criteria = root.putObject("criteria");
    criteria.put(
        "D1",
        "共现定义：一次调用（text 与 imageVector 同参）返回 ≥1 条 sourceType=IMAGE 且 ≥1 条非 IMAGE 来源才算共现；"
            + "cooccurrenceQueryCount/queryCount 落锚；0 次共现 ⇒ NO-COOCCURRENCE 停 G1，严禁两次独立 retrieve 拼凑");
    criteria.put(
        "D2",
        "downwardGap = 图像候选 relevanceScore − 比它低的竞争者中最大 relevanceScore（图像候选=sourceType=IMAGE 的来源；"
            + "查询级取最易位移图像候选的 gap）；图像候选已是末位记 null + imageCandidateIsLast；取全轮 minDownwardGap");
    criteria.put(
        "D3",
        "W_max = 0.30 × (1 − 0.5) × 1.0 = 0.15；minDownwardGap > W_max ⇒ NOT-DISPLACED-IN-CORPUS；"
            + "否则 POSSIBLY-DISPLACED 并逐条列出落入窗口的 (query, gap)");
    criteria.put(
        "D4",
        "反事实重建（单独字段，禁止与实测并表）：定标锚 near-duplicate 档 cos 代入 0.30×cos×w（三档 w），"
            + "与同查询实测竞争者分比较，落 counterfactualDisplacingPairs + assumption");
    criteria.put("D5", "本轮真实查询数落 queryCount；结论只到存在性/量级，禁止外推为「生产上位移不可见」；要真分布须另立卡");

    ObjectNode sourceAnchors = root.putObject("sourceAnchors");
    putAnchorSha(sourceAnchors, "corpusAnchor", "image-threshold-calibration-v2.json");
    putAnchorSha(sourceAnchors, "fusionImpactAnchor", "near-dup-fusion-impact-v1.json");
    putAnchorSha(sourceAnchors, "exchangeRateAnchor", "near-dup-exchange-rate-v1.json");
    putAnchorSha(sourceAnchors, "truthAnchor", "image-near-dup-truth-v1.json");

    ObjectNode constants = root.putObject("productionConstants");
    constants.put("threshold", 0.9944D);
    constants.put("nearDuplicateFloor", 0.9887D);
    constants.put("weightHigh", 0.75D);
    constants.put("weightMid", 0.6D);
    constants.put("weightLow", 0.5D);
    constants.put("textRouteWeight", 0.7D);
    constants.put("imageRouteWeight", IMAGE_ROUTE_WEIGHT);
    constants.put("wMax", W_MAX);
    constants.put("wMaxFormula", "0.30 × (1 − 0.5) × 1.0（w 最小档 0.5、cos 最大 1.0 的极端值）");
    constants.put("routeIsNotModality", true);
    constants.put("routeDomain", "vector|keyword|hybrid|ocr");
    constants.put(
        "modalityDiscriminator",
        "sourceType=IMAGE + chunkId=IMAGE:{assetId}（SourceReferenceFactory 唯一判别位）");
    root.put("baselineHead", BASELINE_HEAD);

    ArrayNode assetNodes = root.putArray("corpusAssets");
    for (RealPdfAssets.Asset asset : RealPdfAssets.assets()) {
      ObjectNode node = assetNodes.addObject();
      node.put("key", asset.key());
      node.put("file", asset.file());
      node.put("sha256", asset.sha256());
      node.put("pages", asset.pages());
      node.put("license", asset.license());
    }

    ObjectNode textComposition = root.putObject("textComposition");
    textComposition.put(
        "note",
        "文本集合 = 图片切片 VLM 描述块（生产 createVectorRecord 同形，sourceType=IMAGE）+ PDF 正文逐页文本块"
            + "（PDFBox 测试侧抽取，不带 image 元数据 → sourceType=pdf）；语料仍是既有 3 份真实 PDF，不下载新资产");
    textComposition.put("maxTextChunksPerDocument", MAX_TEXT_CHUNKS_PER_DOCUMENT);
    textComposition.put("minPageTextChars", MIN_PAGE_TEXT_CHARS);
    textComposition.put("descriptionChunks", countDescriptionChunks(inputs));
    textComposition.put("pageTextChunks", countPageTextChunks(inputs));

    ObjectNode retrievalScope = root.putObject("retrievalScope");
    retrievalScope.put("topK", TOP_K);
    retrievalScope.put("intentCategory", "null（两路均不过滤）");
    retrievalScope.put("fusion", "0.7/0.3 分数制同切片累加（RetrievalConfigResolver.fuseRoutes，降权在融合前）");
    retrievalScope.put("imageFloor", 0.9887D);
    retrievalScope.put(
        "queryShape",
        "一次 retrieve(queryText=VLM 描述, imageVector=落盘 PNG 重编码)——text 与 imageVector 同参");

    root.put("queryCount", results.queryCount());
    root.put("cooccurrenceQueryCount", results.cooccurrenceQueryCount());
    root.put(
        "externalCalls", inputs.visionCalls() + inputs.embedImageCalls() + inputs.embedCalls());
    ObjectNode calls = root.putObject("callBreakdown");
    calls.put("visionModel", inputs.visionModel());
    calls.put("textEmbeddingModel", inputs.textEmbeddingModel());
    calls.put("imageEmbeddingModel", inputs.imageEmbeddingModel());
    calls.put("visionCalls", inputs.visionCalls());
    calls.put("embedImageCalls", inputs.embedImageCalls());
    calls.put("embedCalls", inputs.embedCalls());
    calls.put(
        "semantics",
        "三计数由 TrackingProvider 在真调用点累加（vision=VLM 描述、embedImage=视觉编码、embed=文本编码），非公式推导；"
            + "被描述闸门剔除的图同样计数");

    ArrayNode queryArray = root.putArray("queries");
    results.queryNodes().forEach(queryArray::add);
    if (results.minDownwardGap() == null) {
      root.putNull("minDownwardGap");
    } else {
      root.put("minDownwardGap", results.minDownwardGap());
    }
    root.set("withinWindow", results.withinWindow());
    root.put("verdict", results.verdict());
    root.put("verdictReason", results.verdictReason());

    ObjectNode counterfactual = root.putObject("counterfactual");
    counterfactual.put(
        "source", "image-threshold-calibration-v2.json pairs[near-duplicate].cosine（120 值）");
    counterfactual.put("formula", "0.30 × cos × w");
    ArrayNode tiers = counterfactual.putArray("weightTiers");
    for (double w : WEIGHT_TIERS) {
      tiers.add(w);
    }
    counterfactual.put("comparisons", results.counterfactualComparisons());
    counterfactual.put("displacingPairs", results.counterfactualDisplacingPairs());
    ObjectNode perWeight = counterfactual.putObject("perWeight");
    results.counterfactualPerWeight().forEach(perWeight::put);
    counterfactual.put("competitorScoreBasis", "同查询实测非 IMAGE 来源 relevanceScore 的最大值（融合分口径）");
    counterfactual.put("assumption", ASSUMPTION_TEXT);

    root.put(
        "scopeNote",
        "queryCount=" + results.queryCount() + " 条量级 = 存在性/量级证据，禁止外推为「生产上位移不可见」；要真分布须另立卡");
    root.put(
        "byteIdentityBasis",
        "锚内 corpusMaterial + embedCache 冻结全部输入文本与向量；默认复跑（opt-in 无 write）从锚本地重建两集合、"
            + "重放生产检索链、重算全部判据字段逐值断言后整体序列化与磁盘锚逐字节比对，零外呼");
    root.put(
        "paceNote",
        "embedImage 按 "
            + PACE_MILLIS
            + "ms 主动降速（定标卡实测 2 QPS 中途 429），429 有界退避 "
            + THROTTLE_RETRIES
            + " 次；节奏只影响墙钟，不改判据");

    ObjectNode material = root.putObject("corpusMaterial");
    ArrayNode imageArray = material.putArray("imageRecords");
    for (StoredRecord record : inputs.build().imageRecords()) {
      imageArray.add(record.toJson());
    }
    ArrayNode textArray = material.putArray("textRecords");
    for (StoredRecord record : inputs.build().textRecords()) {
      textArray.add(record.toJson());
    }
    ArrayNode queryVectors = material.putArray("queryVectors");
    for (QueryEvidence evidence : inputs.evidences()) {
      ObjectNode node = queryVectors.addObject();
      node.put("queryIndex", evidence.index());
      node.put("vector", base64(evidence.queryVector()));
    }
    ArrayNode cache = material.putArray("embedCache");
    for (Map.Entry<String, float[]> entry : inputs.embedCache().entrySet()) {
      ObjectNode node = cache.addObject();
      node.put("text", entry.getKey());
      node.put("vector", base64(entry.getValue()));
    }
    return root;
  }

  private int countDescriptionChunks(AnchorInputs inputs) {
    return (int)
        inputs.build().textRecords().stream()
            .filter(record -> record.metadata().containsKey(ImageSliceMetadata.KEY_IMAGE_ASSET_ID))
            .count();
  }

  private int countPageTextChunks(AnchorInputs inputs) {
    return inputs.build().textRecords().size() - countDescriptionChunks(inputs);
  }

  private void putAnchorSha(ObjectNode parent, String field, String fileName) {
    Path path = Path.of("docs", "rag-quality", fileName);
    try {
      parent.put(field, "docs/rag-quality/" + fileName);
      parent.put(field + "Sha256", RealPdfAssets.sha256(Files.readAllBytes(path)));
    } catch (Exception exception) {
      throw new IllegalStateException("上游锚读取失败（只读）: " + path, exception);
    }
  }

  // ==================== 默认复跑：从锚重建 + 逐字节比对（零外呼） ====================

  private void replayAndVerifyByteIdentity() throws Exception {
    assertTrue(Files.exists(ANCHOR_PATH), "锚不存在：默认复跑必须先有写锚产物（跳过≠复跑）: " + ANCHOR_PATH);
    String stored = Files.readString(ANCHOR_PATH, StandardCharsets.UTF_8);
    JsonNode root = MAPPER.readTree(stored);
    ReplayMaterial material = parseAnchorMaterial(root);

    // 零外呼自证：桩 provider 对 embedImage/vision 一律抛错；embed 只从锚缓存取
    StubEmbeddingProvider stub = new StubEmbeddingProvider(material.embedCache());
    KnowledgeRetrievalServiceImpl service =
        assembleReplayService(stub, material.imageRecords(), material.textRecords());

    // 逐查询重放检索链（输入向量全部来自锚）
    List<QueryEvidence> evidences =
        replayAnchorQueries(root.path("queries"), material.queryVectors(), service);
    assertEquals(root.path("queries").size(), evidences.size(), "复跑查询数必须与锚一致");
    assertEquals(
        0, stub.embedImageCalls + stub.visionCalls, "默认复跑必须零外呼（embedImage/vision 桩被触发即失败）");

    // 重算全部判据字段（与写锚同一实现）并逐值断言
    CorpusBuild build =
        new CorpusBuild(
            List.of(),
            List.copyOf(material.imageRecords()),
            List.copyOf(material.textRecords()),
            List.of());
    AnchorInputs inputs =
        new AnchorInputs(
            build,
            evidences,
            root.path("callBreakdown").path("visionCalls").asInt(),
            root.path("callBreakdown").path("embedImageCalls").asInt(),
            root.path("callBreakdown").path("embedCalls").asInt(),
            material.embedCache(),
            root.path("callBreakdown").path("visionModel").asText(),
            root.path("callBreakdown").path("textEmbeddingModel").asText(),
            root.path("callBreakdown").path("imageEmbeddingModel").asText());
    ComputedResults recomputed = computeResults(evidences, inputs);
    ObjectNode rebuilt = buildAnchor(inputs, recomputed);
    String rebuiltJson = MAPPER.writeValueAsString(rebuilt);
    assertEquals(stored, rebuiltJson, "默认复跑序列化与磁盘锚不一致（锚被手改或重放算术漂移）——逐字节比对失败");
    System.out.println(
        "[near-dup-displacement] 默认复跑逐字节一致（queryCount="
            + recomputed.queryCount()
            + " verdict="
            + recomputed.verdict()
            + " 零外呼）");
  }

  /**
   * 锚 corpusMaterial 四件的解析产物（replay 与卡 28 消融 A/B 共用同一套重建； 卡 28 纯搬运自 {@code
   * replayAndVerifyByteIdentity}，语义与断言不变）。
   */
  private record ReplayMaterial(
      List<StoredRecord> imageRecords,
      List<StoredRecord> textRecords,
      Map<Integer, float[]> queryVectors,
      Map<String, float[]> embedCache) {}

  /** 解析锚 corpusMaterial 四件（卡 28 纯搬运：重建段原样移入，断言原样保留）。 */
  private ReplayMaterial parseAnchorMaterial(JsonNode root) {
    JsonNode material = root.path("corpusMaterial");
    List<StoredRecord> imageRecords = new ArrayList<>();
    for (JsonNode node : material.path("imageRecords")) {
      imageRecords.add(StoredRecord.fromJson(node));
    }
    List<StoredRecord> textRecords = new ArrayList<>();
    for (JsonNode node : material.path("textRecords")) {
      textRecords.add(StoredRecord.fromJson(node));
    }
    Map<Integer, float[]> queryVectors = new LinkedHashMap<>();
    for (JsonNode node : material.path("queryVectors")) {
      queryVectors.put(node.path("queryIndex").asInt(), fromBase64(node.path("vector").asText()));
    }
    Map<String, float[]> embedCache = new LinkedHashMap<>();
    for (JsonNode node : material.path("embedCache")) {
      embedCache.put(node.path("text").asText(), fromBase64(node.path("vector").asText()));
    }
    assertTrue(imageRecords.size() > 0, "锚缺 imageRecords");
    assertTrue(textRecords.size() > 0, "锚缺 textRecords");
    return new ReplayMaterial(imageRecords, textRecords, queryVectors, embedCache);
  }

  /**
   * 重放装配：两集合重建 + 生产检索服务装配（卡 28 纯搬运：装配段原样移入）。 provider 由调用方给定——replay 传锚缓存桩， 消融 A/B 传 embed
   * 计数桩（两跑各自全新装配，互不共享可变状态）。
   */
  private KnowledgeRetrievalServiceImpl assembleReplayService(
      ModelProvider provider, List<StoredRecord> imageRecords, List<StoredRecord> textRecords) {
    EmbeddingService embeddingService = new EmbeddingService(provider, properties("replay-no-key"));

    InMemoryVectorStore textStore = new InMemoryVectorStore();
    for (StoredRecord record : textRecords) {
      textStore.upsert(record.toVectorRecord());
    }
    ImageVectorStore imageStore = new ImageVectorStore(new InMemoryVectorStore());
    List<VectorRecord> imageVectorRecords = new ArrayList<>();
    for (StoredRecord record : imageRecords) {
      imageVectorRecords.add(record.toVectorRecord());
    }
    imageStore.upsertAll(imageVectorRecords);

    KnowledgeBaseAuthorizationService authorization =
        Mockito.mock(KnowledgeBaseAuthorizationService.class);
    Mockito.when(
            authorization.authorizedKnowledgeBaseIds(
                ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(List.of(EVAL_KB_ID_LONG));
    ObjectProvider<DynamicConfigService> configProvider =
        RagBenchmarkPipelineFactory.provider(RagBenchmarkPipelineFactory.dynamicConfig(config()));
    KnowledgeRetrievalServiceImpl service =
        new KnowledgeRetrievalServiceImpl(
            authorization,
            embeddingService,
            textStore,
            new RetrievalQueryRewriteService(provider, configProvider),
            new Bm25Scorer(),
            configProvider);
    ReflectionTestUtils.setField(service, "imageVectorStore", imageStore);
    return service;
  }

  /** 逐查询重放检索链（卡 28 纯搬运：循环体原样移入，输入向量全部来自锚，断言原样保留）。 */
  private List<QueryEvidence> replayAnchorQueries(
      JsonNode queriesNode,
      Map<Integer, float[]> queryVectors,
      KnowledgeRetrievalServiceImpl service) {
    List<QueryEvidence> evidences = new ArrayList<>();
    UserContextHolder.set(
        new UserContext(
            EVAL_USER_ID, 5L, 101L, DataScopeLevel.ALL, "near-dup-displacement-evidence"));
    try {
      for (JsonNode storedQuery : queriesNode) {
        int index = storedQuery.path("queryIndex").asInt();
        float[] queryVector = queryVectors.get(index);
        assertNotNull(queryVector, "锚缺 queryIndex=" + index + " 的查询向量");
        KnowledgeRetrievalPort.RetrievalResult result =
            service.retrieve(
                new KnowledgeRetrievalPort.RetrievalQuery(
                    storedQuery.path("queryText").asText(),
                    EVAL_USER_ID,
                    List.of(EVAL_KB_ID),
                    TOP_K,
                    queryVector,
                    null));
        evidences.add(
            new QueryEvidence(
                index,
                new SliceRow(
                    storedQuery.path("assetKey").asText(),
                    "",
                    Long.parseLong(storedQuery.path("chunkId").asText()),
                    storedQuery.path("queryText").asText(),
                    null,
                    null,
                    storedQuery.path("pageNo").asInt()),
                result.sources() == null ? List.of() : result.sources(),
                queryVector));
      }
    } finally {
      UserContextHolder.clear();
    }
    return evidences;
  }

  // ==================== 装配与辅助 ====================

  private InMemoryVectorStore textStoreOf(CorpusBuild build) {
    InMemoryVectorStore store = new InMemoryVectorStore();
    for (StoredRecord record : build.textRecords()) {
      store.upsert(record.toVectorRecord());
    }
    return store;
  }

  private ImageVectorStore imageStoreOf(CorpusBuild build) {
    ImageVectorStore store = new ImageVectorStore(new InMemoryVectorStore());
    List<VectorRecord> records = new ArrayList<>();
    for (StoredRecord record : build.imageRecords()) {
      records.add(record.toVectorRecord());
    }
    store.upsertAll(records);
    return store;
  }

  /** 生产配置矩阵（与灰卡同款：切片总开关 + 视觉路开 + 图片数帽 + 关查询改写）。 */
  private static Map<String, Object> config() {
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put(PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.MAX_IMAGES_KEY, RealPdfAssets.MAX_IMAGES_PER_DOCUMENT);
    matrix.put("rag.retrieval.query-rewrite.enabled", Boolean.FALSE);
    return matrix;
  }

  private static String visionModel() {
    String override = System.getProperty(VISION_MODEL_PROP);
    return override == null || override.isBlank() ? DEFAULT_VISION_MODEL : override.strip();
  }

  /** 切片行替身：主键由调用方顺序分配（生产口径"自增主键即 chunkId"），与灰卡逐字段同源。 */
  private static DocumentVectorChunkEntity row(
      long chunkId, int index, DocumentChunk chunk, RealPdfAssets.Asset asset) {
    DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
    entity.setId(chunkId);
    entity.setDocumentId(documentIdOf(asset));
    entity.setChunkIndex(index);
    entity.setChunkText(chunk.text());
    entity.setCategory(chunk.category());
    entity.setFilename(asset.file());
    entity.setPageNo(chunk.pageNo());
    entity.setRowIndex(chunk.rowIndex());
    entity.setChunkRole("CHILD");
    entity.setExtraMetadataJson(ImageSliceMetadata.toJson(chunk.extraMetadata()));
    return entity;
  }

  /** 文档实体（payload 口径：knowledgeBaseId / 文件名 / 类型）。 */
  private static UploadedFileEntity file(RealPdfAssets.Asset asset) {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setDocumentId(documentIdOf(asset));
    file.setOriginalFilename(asset.file());
    file.setFilename(asset.file());
    file.setFileType("pdf");
    file.setKnowledgeBase(EVAL_KB_ID);
    file.setStorageKey("near-dup-displacement/" + asset.file());
    return file;
  }

  private static String documentIdOf(RealPdfAssets.Asset asset) {
    return "near-dup-displacement-" + asset.key();
  }

  private static ModelProviderProperties properties(String apiKey) {
    ModelProviderProperties properties = new ModelProviderProperties();
    properties.setApiKey(apiKey);
    properties.setBaseUrl(COMPATIBLE_BASE_URL);
    properties.setVisionModel(visionModel());
    // 超时口径按定标卡实测放宽：JDK HttpClient 进程内首次连接实测出现过 >10s 挂起
    properties.setTimeoutSeconds(120);
    properties.setConnectTimeoutSeconds(60);
    return properties;
  }

  private static ModelProvider realProvider(ModelProviderProperties properties, String apiKey) {
    DashScopeApi nativeApi = DashScopeApi.builder().apiKey(apiKey).build();
    ChatModel nativeChat = DashScopeChatModel.builder().dashScopeApi(nativeApi).build();
    EmbeddingModel embeddingModel = new DashScopeEmbeddingModel(nativeApi, MetadataMode.EMBED);
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.ai.dashscope.api-key", apiKey);
    environment.setProperty("spring.ai.dashscope.base-url", COMPATIBLE_BASE_URL);
    return new ModelProviderImpl(
        providerOf(nativeChat), providerOf(embeddingModel), properties, environment);
  }

  private static <T> ObjectProvider<T> providerOf(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }

      @Override
      public T getIfAvailable() {
        return value;
      }

      @Override
      public T getIfUnique() {
        return value;
      }
    };
  }

  private static <T> ObjectProvider<T> provider(T value) {
    return providerOf(value);
  }

  /** 读 key：优先环境变量，其次仓库根 .env（与既有真跑 IT 同序；不打印明文）。 */
  static String resolveApiKey() {
    String fromEnv = System.getenv("DASHSCOPE_API_KEY");
    if (StringUtils.hasText(fromEnv)) {
      return fromEnv;
    }
    Path dotEnv = Path.of(".env");
    if (Files.exists(dotEnv)) {
      try {
        return Files.readAllLines(dotEnv, StandardCharsets.UTF_8).stream()
            .filter(line -> line.startsWith("DASHSCOPE_API_KEY="))
            .map(line -> line.substring("DASHSCOPE_API_KEY=".length()).trim())
            .findFirst()
            .orElse(null);
      } catch (Exception ignored) {
        // 读不到 .env 就走"跳过"分支
      }
    }
    return null;
  }

  /** 单次真 embedImage（先降速再调；429 有界退避；其余失败直接抛，不落半成品锚）。 */
  private static float[] embedImageWithPace(TrackingProvider provider, byte[] png, String key) {
    ModelCallResult<float[]> result = provider.embedImageTracked(png, IMAGE_MIME, key);
    return result.vector();
  }

  private static void pause(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("真跑被中断（不落半成品锚）", exception);
    }
  }

  private static String base64(float[] vector) {
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(vector.length * 4);
    for (float value : vector) {
      buffer.putFloat(value);
    }
    return Base64.getEncoder().encodeToString(buffer.array());
  }

  private static float[] fromBase64(String encoded) {
    byte[] bytes = Base64.getDecoder().decode(encoded);
    java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(bytes);
    float[] vector = new float[bytes.length / 4];
    for (int i = 0; i < vector.length; i++) {
      vector[i] = buffer.getFloat();
    }
    return vector;
  }

  // ==================== 数据载体 ====================

  /** 一条入库图片切片（G1 的查询源与图像证据）。 */
  private record SliceRow(
      String assetKey,
      String filename,
      long chunkId,
      String description,
      float[] imageVector,
      byte[] png,
      int pageNo) {}

  /** sources() 单条的最小投影（模态判别只看 sourceType/chunkId）。 */
  record SourceRef(String sourceType, String chunkId, Double relevanceScore, Integer pageNo) {}

  /** 一次混合查询的证据（sources() 原样保留 + 查询向量）。 */
  private record QueryEvidence(
      int index, SliceRow row, List<SourceReference> sources, float[] queryVector) {}

  /** 锚内冻结的一条向量记录（两个集合通用；replay 据此原样重建）。 */
  record StoredRecord(
      String id,
      String documentId,
      String chunkId,
      Integer chunkIndex,
      String text,
      float[] vector,
      Map<String, Object> metadata) {

    VectorRecord toVectorRecord() {
      return new VectorRecord(id, documentId, chunkId, chunkIndex, text, vector, metadata);
    }

    ObjectNode toJson() {
      ObjectNode node = MAPPER.createObjectNode();
      node.put("id", id);
      node.put("documentId", documentId);
      node.put("chunkId", chunkId);
      if (chunkIndex != null) {
        node.put("chunkIndex", chunkIndex);
      } else {
        node.putNull("chunkIndex");
      }
      node.put("text", text);
      node.put("vector", base64(vector));
      ObjectNode metadataNode = node.putObject("metadata");
      new LinkedHashMap<>(metadata)
          .forEach(
              (key, value) -> {
                if (value instanceof Long longValue) {
                  metadataNode.put(key, longValue);
                } else if (value instanceof Integer intValue) {
                  metadataNode.put(key, intValue);
                } else {
                  metadataNode.put(key, String.valueOf(value));
                }
              });
      return node;
    }

    static StoredRecord fromJson(JsonNode node) {
      Map<String, Object> metadata = new LinkedHashMap<>();
      JsonNode metadataNode = node.path("metadata");
      metadataNode
          .fieldNames()
          .forEachRemaining(
              key -> {
                JsonNode value = metadataNode.get(key);
                if (value.isLong() || value.isInt()) {
                  metadata.put(key, value.asLong());
                } else {
                  metadata.put(key, value.asText());
                }
              });
      JsonNode chunkIndex = node.path("chunkIndex");
      return new StoredRecord(
          node.path("id").asText(),
          node.path("documentId").asText(),
          node.path("chunkId").asText(),
          chunkIndex.isMissingNode() || chunkIndex.isNull() ? null : chunkIndex.asInt(),
          node.path("text").asText(),
          fromBase64(node.path("vector").asText()),
          metadata);
    }
  }

  /**
   * 捕获型内存向量库：转发给真 {@link InMemoryVectorStore}，同时截获写入记录（image 集合记录由生产 {@code createVectorRecord}
   * 生成、point id 是随机 UUID，必须截获落锚 replay 才能原样重建）。
   */
  static final class CapturingInMemoryStore implements CrmVectorStore {

    private final InMemoryVectorStore delegate = new InMemoryVectorStore();

    private final List<StoredRecord> captured = new ArrayList<>();

    List<StoredRecord> captured() {
      return List.copyOf(captured);
    }

    @Override
    public void upsert(VectorRecord record) {
      delegate.upsert(record);
      captured.add(
          new StoredRecord(
              record.id(),
              record.documentId(),
              record.chunkId(),
              record.chunkIndex(),
              record.text(),
              record.embedding(),
              new LinkedHashMap<>(record.metadata())));
    }

    @Override
    public void upsertAll(List<VectorRecord> records) {
      delegate.upsertAll(records);
      for (VectorRecord record : records) {
        captured.add(
            new StoredRecord(
                record.id(),
                record.documentId(),
                record.chunkId(),
                record.chunkIndex(),
                record.text(),
                record.embedding(),
                new LinkedHashMap<>(record.metadata())));
      }
    }

    @Override
    public List<VectorSearchHit> search(VectorSearchRequest request) {
      return delegate.search(request);
    }

    @Override
    public void deleteByDocumentId(String documentId) {
      delegate.deleteByDocumentId(documentId);
    }
  }

  /**
   * 真调用点计数装饰器：embedImage 先降速再调（定标卡实测 2 QPS 中途 429）+ 429 有界退避； embed/vision 原样转发并计数，同时把 embed 的
   * (text → vector) 收进缓存供锚冻结。
   */
  static final class TrackingProvider implements ModelProvider {

    private final ModelProvider delegate;

    private final long paceMillis;

    final java.util.concurrent.atomic.AtomicInteger visionCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    final java.util.concurrent.atomic.AtomicInteger embedImageCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    final java.util.concurrent.atomic.AtomicInteger embedCalls =
        new java.util.concurrent.atomic.AtomicInteger();

    private final Map<String, float[]> embedCache = new LinkedHashMap<>();

    TrackingProvider(ModelProvider delegate, long paceMillis) {
      this.delegate = delegate;
      this.paceMillis = paceMillis;
    }

    Map<String, float[]> embedCache() {
      return embedCache;
    }

    ModelCallResult<float[]> embedImageTracked(byte[] png, String mime, String key) {
      for (int attempt = 0; ; attempt++) {
        try {
          ModelCallResult<float[]> result = embedImage(png, mime);
          if (result == null || result.vector() == null || result.vector().length == 0) {
            throw new IllegalStateException("视觉 embedding 返回空向量: " + key);
          }
          return result;
        } catch (HttpClientErrorException exception) {
          boolean throttled = exception.getStatusCode().value() == 429;
          if (!throttled || attempt >= THROTTLE_RETRIES) {
            throw exception;
          }
          long wait = THROTTLE_BACKOFF_MILLIS << attempt;
          System.out.println(
              "[near-dup-displacement] 429 限流，"
                  + key
                  + " 第 "
                  + (attempt + 1)
                  + " 次退避 "
                  + wait
                  + " ms 后重试");
          pause(wait);
        }
      }
    }

    @Override
    public String provider() {
      return delegate.provider();
    }

    @Override
    public ModelCallResult<String> chat(Prompt prompt) {
      return delegate.chat(prompt);
    }

    @Override
    public ModelCallResult<String> chat(Prompt prompt, ModelCallOptions options) {
      return delegate.chat(prompt, options);
    }

    @Override
    public Flux<ChatResponse> streamChat(Prompt prompt) {
      return delegate.streamChat(prompt);
    }

    @Override
    public Flux<ChatResponse> streamChat(Prompt prompt, ModelCallOptions options) {
      return delegate.streamChat(prompt, options);
    }

    @Override
    public ModelCallResult<float[]> embed(EmbeddingRequest request) {
      embedCalls.incrementAndGet();
      ModelCallResult<float[]> result = delegate.embed(request);
      if (result != null && result.vector() != null) {
        for (String text : request.getInstructions()) {
          embedCache.putIfAbsent(text, result.vector());
        }
      }
      return result;
    }

    @Override
    public ModelCallResult<float[]> embedImage(byte[] imageBytes, String mimeType) {
      // 所有 embedImage 调用（摄取侧内部 + 查询侧直调）统一先降速：定标卡实测 2 QPS 连打中途 429
      pause(paceMillis);
      embedImageCalls.incrementAndGet();
      return delegate.embedImage(imageBytes, mimeType);
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      visionCalls.incrementAndGet();
      return delegate.vision(prompt);
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt, ModelCallOptions options) {
      visionCalls.incrementAndGet();
      return delegate.vision(prompt, options);
    }
  }

  /**
   * replay 桩 provider：embed 只从锚缓存取（未命中即抛——缓存面就是写锚时链路真实嵌入过的全部文本）； embedImage/vision/chat
   * 一律抛错，触发即证明复跑发生了外呼（违反零外呼语义）。
   */
  static final class StubEmbeddingProvider implements ModelProvider {

    private final Map<String, float[]> embedCache;

    final int embedImageCalls;

    final int visionCalls;

    StubEmbeddingProvider(Map<String, float[]> embedCache) {
      this.embedCache = embedCache;
      this.embedImageCalls = 0;
      this.visionCalls = 0;
    }

    @Override
    public String provider() {
      return "anchor-replay-stub";
    }

    @Override
    public ModelCallResult<String> chat(Prompt prompt) {
      throw new IllegalStateException("默认复跑不允许 chat 外呼/桩调用");
    }

    @Override
    public ModelCallResult<String> chat(Prompt prompt, ModelCallOptions options) {
      throw new IllegalStateException("默认复跑不允许 chat 外呼/桩调用");
    }

    @Override
    public Flux<ChatResponse> streamChat(Prompt prompt) {
      throw new IllegalStateException("默认复跑不允许 streamChat 外呼/桩调用");
    }

    @Override
    public Flux<ChatResponse> streamChat(Prompt prompt, ModelCallOptions options) {
      throw new IllegalStateException("默认复跑不允许 streamChat 外呼/桩调用");
    }

    @Override
    public ModelCallResult<float[]> embed(EmbeddingRequest request) {
      for (String text : request.getInstructions()) {
        float[] vector = embedCache.get(text);
        if (vector == null) {
          throw new IllegalStateException("锚 embedCache 未命中（写锚后链路嵌入面变了？）: " + prefix(text));
        }
        return ModelCallResult.ofVector(vector, "anchor-replay-stub", null, null);
      }
      throw new IllegalStateException("空嵌入请求");
    }

    @Override
    public ModelCallResult<float[]> embedImage(byte[] imageBytes, String mimeType) {
      throw new IllegalStateException("默认复跑必须零外呼：embedImage 桩被触发");
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      throw new IllegalStateException("默认复跑必须零外呼：vision 桩被触发");
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt, ModelCallOptions options) {
      throw new IllegalStateException("默认复跑必须零外呼：vision 桩被触发");
    }

    private static String prefix(String text) {
      return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
  }

  // ==================== 卡 28 消融 A/B（near-dup-ablation-redesign 纯增；零外呼、无 opt-in 门：skipped 不得 +1）
  // ====================

  /** 消融锚落盘路径（本卡写集内唯一新增 JSON 产物）。 */
  static final Path ABLATION_ANCHOR_PATH =
      Path.of("docs", "rag-quality", "near-dup-ablation-v1.json");

  /** 消融锚写入门控属性（冻结）。 */
  static final String ABLATION_WRITE_PROP = "near.dup.ablation.write";

  /** 本卡起点基线（master @ 1884e31）。 */
  private static final String ABLATION_BASELINE_HEAD = "1884e31928f2eabf07e6afb0d9c74092268dd4b4";

  /**
   * 卡 28 消融 A/B（判据 R0–R6）：冻结向量零外呼重放生产检索链， A 跑 = 生产降权（真实方法 + 旁路观测）， B 跑 = {@code mockStatic} 只把
   * {@code downWeightNearDuplicates} 替换为恒等；逐查询逐候选对比落锚 {@code near-dup-ablation-v1.json}（write 模式
   * {@code -Dnear.dup.ablation.write=true}，默认模式逐字节复算）。
   */
  @Test
  @DisplayName("卡 28 消融 A/B：冻结向量零外呼重放，A=生产降权（真实方法+旁路观测），B=mockStatic 恒等替换，逐候选对比落锚")
  void runNearDupAblationAtoBOnFrozenCorpus() throws Exception {
    assertTrue(Files.exists(ANCHOR_PATH), "位移锚缺失（消融复跑的冻结语料来源）: " + ANCHOR_PATH);
    JsonNode root = MAPPER.readTree(Files.readAllBytes(ANCHOR_PATH));
    ReplayMaterial material = parseAnchorMaterial(root);
    NearDupAblationPositiveControlTest.PositiveControlOutcome positiveControl =
        NearDupAblationPositiveControlTest.runPositiveControl();

    // A 跑（生产降权）先完整落快照，再进 B 跑（恒等替换）——静态 mock 进程级，两跑严禁交叉（坑 2）
    AblationPass passA = runAblationPass(root.path("queries"), material, false);
    AblationPass passB = runAblationPass(root.path("queries"), material, true);
    assertEquals(
        passA.embedInvocations(),
        passB.embedInvocations(),
        "两跑 embed 请求数必须相等（消融只许替换 downWeightNearDuplicates 一个方法，其余面零变化）");

    AblationResults results =
        computeAblationResults(root.path("queries").size(), positiveControl, passA, passB);
    ObjectNode ablationRoot = buildAblationAnchor(material, positiveControl, passA, passB, results);
    String json = MAPPER.writeValueAsString(ablationRoot);
    assertFalse(json.contains("\r"), "消融锚 JSON 必须纯 LF（DefaultIndenter 已锁行尾，检出 CR 即装配漂移）");
    if ("true".equalsIgnoreCase(System.getProperty(ABLATION_WRITE_PROP))) {
      Files.createDirectories(ABLATION_ANCHOR_PATH.toAbsolutePath().getParent());
      Files.writeString(ABLATION_ANCHOR_PATH, json, StandardCharsets.UTF_8);
      System.out.println(
          "[near-dup-ablation] 锚已落盘: "
              + ABLATION_ANCHOR_PATH.toAbsolutePath()
              + " verdict="
              + results.verdict());
    } else {
      assertTrue(
          Files.exists(ABLATION_ANCHOR_PATH),
          "消融锚不存在：默认复跑必须先有写锚产物（-D" + ABLATION_WRITE_PROP + "=true）: " + ABLATION_ANCHOR_PATH);
      String storedAblation = Files.readString(ABLATION_ANCHOR_PATH, StandardCharsets.UTF_8);
      assertEquals(storedAblation, json, "消融锚默认复跑逐字节比对失败（锚被手改或重放算术漂移）");
      System.out.println(
          "[near-dup-ablation] 默认复跑逐字节一致（verdict="
              + results.verdict()
              + " maxImageScoreDelta="
              + results.maxImageScoreDelta()
              + " 零外呼）");
    }
  }

  /** 单次消融跑产物：逐查询 sources 证据 + 降权调用捕获（入参/返回）+ embed 请求实计。 */
  private record AblationPass(
      List<QueryEvidence> evidences,
      Map<Integer, List<RetrievalCandidate>> downWeightInputs,
      Map<Integer, List<RetrievalCandidate>> downWeightReturns,
      int embedInvocations,
      boolean identityStub) {}

  /**
   * 单次消融跑（冻结锚材料全新装配两集合与生产检索链，两跑互不共享可变状态——坑 4）。
   *
   * <ul>
   *   <li><b>A 跑（identityStub=false）</b>：生产降权。{@code mockStatic(CALLS_REAL_METHODS)}
   *       打开（其余静态方法全部走真实实现）， 仅对 {@code downWeightNearDuplicates} 做「捕获入参 → callRealMethod（真实生产方法）→
   *       捕获返回」旁路观测——行为与生产一致， 返回即生产降权产物（带 {@code BAND_WEIGHT_METADATA_KEY}）。
   *   <li><b>B 跑（identityStub=true）</b>：恒等替换。同一框架下仅把该方法替换为「原样返回入参」，其余静态方法仍走真实实现—— 消融面唯一差异点。
   * </ul>
   *
   * <p>逐查询独立 mockStatic 作用域并在关闭前 verify 恰调用 1 次；embed 经计数桩实测（每次到达 provider 即一次未命中请求）。
   */
  private AblationPass runAblationPass(
      JsonNode queriesNode, ReplayMaterial material, boolean identityStub) {
    java.util.concurrent.atomic.AtomicInteger embedInvocations =
        new java.util.concurrent.atomic.AtomicInteger();
    ModelProvider provider =
        embedCountingProvider(new StubEmbeddingProvider(material.embedCache()), embedInvocations);
    KnowledgeRetrievalServiceImpl service =
        assembleReplayService(provider, material.imageRecords(), material.textRecords());
    Map<Integer, List<RetrievalCandidate>> inputs = new LinkedHashMap<>();
    Map<Integer, List<RetrievalCandidate>> returns = new LinkedHashMap<>();
    List<QueryEvidence> evidences = new ArrayList<>();
    UserContextHolder.set(
        new UserContext(
            EVAL_USER_ID, 5L, 101L, DataScopeLevel.ALL, "near-dup-displacement-evidence"));
    try {
      for (JsonNode storedQuery : queriesNode) {
        int index = storedQuery.path("queryIndex").asInt();
        float[] queryVector = material.queryVectors().get(index);
        assertNotNull(queryVector, "锚缺 queryIndex=" + index + " 的查询向量");
        List<RetrievalCandidate> capturedInput = new ArrayList<>();
        List<RetrievalCandidate> capturedReturn = new ArrayList<>();
        KnowledgeRetrievalPort.RetrievalResult result;
        try (MockedStatic<ImageSimilarityGate> gate =
            Mockito.mockStatic(ImageSimilarityGate.class, Mockito.CALLS_REAL_METHODS)) {
          gate.when(() -> ImageSimilarityGate.downWeightNearDuplicates(ArgumentMatchers.any()))
              .thenAnswer(
                  invocation -> {
                    List<RetrievalCandidate> input = invocation.getArgument(0);
                    capturedInput.addAll(input);
                    if (identityStub) {
                      List<RetrievalCandidate> identity = List.copyOf(input);
                      capturedReturn.addAll(identity);
                      return identity;
                    }
                    Object real = invocation.callRealMethod();
                    capturedReturn.addAll(castCandidates(real));
                    return real;
                  });
          result =
              service.retrieve(
                  new KnowledgeRetrievalPort.RetrievalQuery(
                      storedQuery.path("queryText").asText(),
                      EVAL_USER_ID,
                      List.of(EVAL_KB_ID),
                      TOP_K,
                      queryVector,
                      null));
          gate.verify(
              () -> ImageSimilarityGate.downWeightNearDuplicates(ArgumentMatchers.any()),
              Mockito.times(1));
        }
        inputs.put(index, List.copyOf(capturedInput));
        returns.put(index, List.copyOf(capturedReturn));
        evidences.add(
            new QueryEvidence(
                index,
                new SliceRow(
                    storedQuery.path("assetKey").asText(),
                    "",
                    Long.parseLong(storedQuery.path("chunkId").asText()),
                    storedQuery.path("queryText").asText(),
                    null,
                    null,
                    storedQuery.path("pageNo").asInt()),
                result.sources() == null ? List.of() : result.sources(),
                queryVector));
      }
    } finally {
      UserContextHolder.clear();
    }
    return new AblationPass(
        List.copyOf(evidences), inputs, returns, embedInvocations.get(), identityStub);
  }

  /** 消融判据产物（R4/R5/R6 机械推导；write 与默认复跑共用同一实现，保证口径一致）。 */
  private record AblationResults(
      int queryCount,
      List<ObjectNode> queryNodes,
      List<ObjectNode> candidateNodes,
      List<ObjectNode> windowNodes,
      int imageCandidateCount,
      int downWeightedCount,
      double maxImageScoreDelta,
      double maxTextScoreDelta,
      double imageDeltaSum,
      boolean allChunkIdSetsEqual,
      boolean anyImageScoreChange,
      boolean anyImageRankChange,
      String verdict,
      String verdictReason) {}

  private AblationResults computeAblationResults(
      int queryCount,
      NearDupAblationPositiveControlTest.PositiveControlOutcome positiveControl,
      AblationPass passA,
      AblationPass passB) {
    assertEquals(
        queryCount,
        passA.downWeightInputs().size(),
        "A 跑降权捕获次数必须等于查询数（每查询恰 1 次，Mockito verify 已逐查询断言）");
    assertEquals(
        queryCount,
        passB.downWeightInputs().size(),
        "B 跑降权捕获次数必须等于查询数（每查询恰 1 次，Mockito verify 已逐查询断言）");
    // 输入恒等（R3/坑 4 的结构证明）：两跑进入 downWeightNearDuplicates 的生产入参必须逐条字节相等
    assertEquals(
        passA.downWeightInputs().keySet(), passB.downWeightInputs().keySet(), "两跑降权调用覆盖的查询集必须一致");
    for (Map.Entry<Integer, List<RetrievalCandidate>> entry : passA.downWeightInputs().entrySet()) {
      List<RetrievalCandidate> inputA = entry.getValue();
      List<RetrievalCandidate> inputB = passB.downWeightInputs().get(entry.getKey());
      assertEquals(inputA.size(), inputB.size(), "queryIndex=" + entry.getKey() + " 两跑降权入参长度必须一致");
      for (int index = 0; index < inputA.size(); index++) {
        assertEquals(
            inputA.get(index).fusionKey(),
            inputB.get(index).fusionKey(),
            "queryIndex=" + entry.getKey() + " 第 " + index + " 条两跑入参候选不一致（重放非确定性）");
        assertEquals(
            0,
            Double.compare(inputA.get(index).rerankScore(), inputB.get(index).rerankScore()),
            "queryIndex=" + entry.getKey() + " 第 " + index + " 条两跑入参分数不一致（重放非确定性）");
      }
    }
    // B 跑恒等回执（坑 3）：返回列表与入参逐条 Double.compare == 0，不是「没抛异常」
    for (Map.Entry<Integer, List<RetrievalCandidate>> entry : passB.downWeightInputs().entrySet()) {
      List<RetrievalCandidate> input = entry.getValue();
      List<RetrievalCandidate> returned = passB.downWeightReturns().get(entry.getKey());
      assertEquals(
          input.size(), returned.size(), "queryIndex=" + entry.getKey() + " B 跑恒等返回长度必须与入参一致");
      for (int index = 0; index < input.size(); index++) {
        assertEquals(
            0,
            Double.compare(input.get(index).rerankScore(), returned.get(index).rerankScore()),
            "queryIndex=" + entry.getKey() + " 第 " + index + " 条 B 跑恒等返回分数被改动（坑 3）");
      }
    }

    // tier/band/降权标记建图（来自真实生产入参与 A 跑真实出参；tier/band 是逐查询属性——
    // 同一切片对不同查询的 cos 不同 ⇒ 档位可不同，禁跨查询合并）
    Map<Integer, Map<String, String>> tierByKeyByQuery = new LinkedHashMap<>();
    Map<Integer, Map<String, String>> bandByKeyByQuery = new LinkedHashMap<>();
    Map<Integer, Map<String, Double>> weightByKeyByQuery = new LinkedHashMap<>();
    for (Map.Entry<Integer, List<RetrievalCandidate>> entry : passA.downWeightInputs().entrySet()) {
      Map<String, String> tiers = new LinkedHashMap<>();
      Map<String, String> bands = new LinkedHashMap<>();
      Map<String, Double> weights = new LinkedHashMap<>();
      for (RetrievalCandidate candidate : entry.getValue()) {
        String key = sourceKeyOf(candidate.hit());
        Map<String, Object> metadata = candidate.hit().metadata();
        tiers.putIfAbsent(key, stringOf(metadata.get(ImageSimilarityGate.TIER_METADATA_KEY)));
        bands.putIfAbsent(key, stringOf(metadata.get(ImageSimilarityGate.BAND_METADATA_KEY)));
      }
      for (RetrievalCandidate candidate : passA.downWeightReturns().get(entry.getKey())) {
        Map<String, Object> metadata = candidate.hit().metadata();
        if (metadata != null
            && metadata.containsKey(ImageSimilarityGate.BAND_WEIGHT_METADATA_KEY)) {
          weights.put(
              sourceKeyOf(candidate.hit()),
              ((Number) metadata.get(ImageSimilarityGate.BAND_WEIGHT_METADATA_KEY)).doubleValue());
        }
      }
      weightByKeyByQuery.put(entry.getKey(), weights);
      tierByKeyByQuery.put(entry.getKey(), tiers);
      bandByKeyByQuery.put(entry.getKey(), bands);
    }
    int downWeightedCount = 0;
    for (Map<String, Double> weights : weightByKeyByQuery.values()) {
      downWeightedCount += weights.size();
    }

    // 逐查询逐候选表（R4）
    List<ObjectNode> queryNodes = new ArrayList<>();
    List<ObjectNode> candidateNodes = new ArrayList<>();
    Map<Integer, Map<String, Double>> fusedAByKeyByQuery = new LinkedHashMap<>();
    Map<Integer, Map<String, Double>> fusedBByKeyByQuery = new LinkedHashMap<>();
    int imageCandidateCount = 0;
    boolean allChunkIdSetsEqual = true;
    boolean anyImageScoreChange = false;
    boolean anyImageRankChange = false;
    double maxImageScoreDelta = 0.0D;
    double maxTextScoreDelta = 0.0D;
    double imageDeltaSum = 0.0D;
    for (QueryEvidence evidenceA : passA.evidences()) {
      QueryEvidence evidenceB = evidenceByIndex(passB.evidences(), evidenceA.index());
      List<SourceRef> sourcesA = toRefs(evidenceA);
      List<SourceRef> sourcesB = toRefs(evidenceB);
      List<String> orderedA = sourcesA.stream().map(SourceRef::chunkId).toList();
      List<String> orderedB = sourcesB.stream().map(SourceRef::chunkId).toList();
      Set<String> idsA = new LinkedHashSet<>(orderedA);
      Set<String> idsB = new LinkedHashSet<>(orderedB);
      boolean chunkIdSetEqual = idsA.equals(idsB);
      allChunkIdSetsEqual &= chunkIdSetEqual;
      List<String> unionKeys = new ArrayList<>(orderedA);
      for (String chunkId : orderedB) {
        if (idsA.add(chunkId)) {
          unionKeys.add(chunkId);
        }
      }
      Map<String, Double> fusedAByKey = new LinkedHashMap<>();
      Map<String, Double> fusedBByKey = new LinkedHashMap<>();
      int imageCount = 0;
      for (String chunkId : unionKeys) {
        SourceRef fromA = sourceByKey(sourcesA, chunkId);
        SourceRef fromB = sourceByKey(sourcesB, chunkId);
        String sourceType = fromA != null ? fromA.sourceType() : fromB.sourceType();
        boolean image = ImageSliceMetadata.SOURCE_TYPE_IMAGE.equals(sourceType);
        if (image) {
          imageCount++;
        }
        Double fusedA = fromA == null ? null : fromA.relevanceScore();
        Double fusedB = fromB == null ? null : fromB.relevanceScore();
        Integer rankA = fromA == null ? null : sourcesA.indexOf(fromA) + 1;
        Integer rankB = fromB == null ? null : sourcesB.indexOf(fromB) + 1;
        if (fusedA != null) {
          fusedAByKey.put(chunkId, fusedA);
        }
        if (fusedB != null) {
          fusedBByKey.put(chunkId, fusedB);
        }
        if (image) {
          if (fusedA != null && fusedB != null) {
            double delta = Math.abs(fusedA - fusedB);
            if (delta > maxImageScoreDelta) {
              maxImageScoreDelta = delta;
            }
            imageDeltaSum += delta;
            if (Double.compare(fusedA, fusedB) != 0) {
              anyImageScoreChange = true;
            }
          }
          if (rankA != null && rankB != null && !rankA.equals(rankB)) {
            anyImageRankChange = true;
          }
        } else if (fusedA != null && fusedB != null) {
          assertEquals(
              0,
              Double.compare(fusedA, fusedB),
              "非 IMAGE 来源融合分两跑不一致（装置污染，R3 隔离）: query=" + evidenceA.index() + " chunkId=" + chunkId);
          double delta = Math.abs(fusedA - fusedB);
          if (delta > maxTextScoreDelta) {
            maxTextScoreDelta = delta;
          }
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("queryIndex", evidenceA.index());
        node.put("sourceType", sourceType);
        node.put("chunkId", chunkId);
        String tier = tierByKeyByQuery.get(evidenceA.index()).get(chunkId);
        String band = bandByKeyByQuery.get(evidenceA.index()).get(chunkId);
        if (tier != null) {
          node.put("tier", tier);
        } else {
          node.putNull("tier");
        }
        if (band != null) {
          node.put("band", band);
        } else {
          node.putNull("band");
        }
        node.put("downWeighted", weightByKeyByQuery.get(evidenceA.index()).containsKey(chunkId));
        if (fusedA != null) {
          node.put("fusedA", fusedA);
        } else {
          node.putNull("fusedA");
        }
        if (fusedB != null) {
          node.put("fusedB", fusedB);
        } else {
          node.putNull("fusedB");
        }
        if (rankA != null) {
          node.put("rankA", rankA);
        } else {
          node.putNull("rankA");
        }
        if (rankB != null) {
          node.put("rankB", rankB);
        } else {
          node.putNull("rankB");
        }
        candidateNodes.add(node);
      }
      fusedAByKeyByQuery.put(evidenceA.index(), fusedAByKey);
      fusedBByKeyByQuery.put(evidenceA.index(), fusedBByKey);
      imageCandidateCount += imageCount;
      ObjectNode queryNode = MAPPER.createObjectNode();
      queryNode.put("queryIndex", evidenceA.index());
      queryNode.put("assetKey", evidenceA.row().assetKey());
      queryNode.put("chunkIdSetEqual", chunkIdSetEqual);
      queryNode.put("imageCandidateCount", imageCount);
      queryNodes.add(queryNode);
    }

    // 窗口算术（R6）：逐条降权事件的 gap vs 0.30×(1−w)，w 取 A 跑出参实测标记值
    List<ObjectNode> windowNodes = new ArrayList<>();
    for (Map.Entry<Integer, List<RetrievalCandidate>> entry : passA.downWeightInputs().entrySet()) {
      int queryIndex = entry.getKey();
      Map<String, Double> weights = weightByKeyByQuery.get(queryIndex);
      Map<String, String> bands = bandByKeyByQuery.get(queryIndex);
      Map<String, Double> fusedAByKey = fusedAByKeyByQuery.get(queryIndex);
      Map<String, Double> fusedBByKey = fusedBByKeyByQuery.get(queryIndex);
      for (RetrievalCandidate candidate : entry.getValue()) {
        String chunkId = sourceKeyOf(candidate.hit());
        Double weight = weights.get(chunkId);
        if (weight == null) {
          continue;
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("queryIndex", queryIndex);
        node.put("chunkId", chunkId);
        String band = bands.get(chunkId);
        if (band != null) {
          node.put("band", band);
        } else {
          node.putNull("band");
        }
        node.put("weight", weight);
        node.put("window", IMAGE_ROUTE_WEIGHT * (1D - weight));
        Double fusedB = fusedBByKey.get(chunkId);
        if (fusedB == null) {
          node.putNull("fusedA");
          node.putNull("fusedB");
          node.putNull("delta");
          node.putNull("strongestCompetitorBelowFusedB");
          node.putNull("gap");
          node.put("gapExceedsWindow", false);
          node.put("note", "该被降权候选未入本查询 topK，最终结果面无融合分可测（禁融合分÷w 反解）；" + "其降权不可能改变任何已返回候选的相对顺序");
        } else {
          Double strongestBelow = null;
          for (Map.Entry<String, Double> competitor : fusedBByKey.entrySet()) {
            if (!competitor.getKey().equals(chunkId)
                && competitor.getValue() < fusedB
                && (strongestBelow == null || competitor.getValue() > strongestBelow)) {
              strongestBelow = competitor.getValue();
            }
          }
          Double fusedA = fusedAByKey.get(chunkId);
          if (fusedA == null) {
            node.putNull("fusedA");
            node.putNull("delta");
          } else {
            node.put("fusedA", fusedA);
            node.put("delta", Math.abs(fusedA - fusedB));
          }
          node.put("fusedB", fusedB);
          if (strongestBelow == null) {
            node.putNull("strongestCompetitorBelowFusedB");
            node.putNull("gap");
            node.put("gapExceedsWindow", true);
          } else {
            node.put("strongestCompetitorBelowFusedB", strongestBelow);
            node.put("gap", fusedB - strongestBelow);
            node.put(
                "gapExceedsWindow", fusedB - strongestBelow > IMAGE_ROUTE_WEIGHT * (1D - weight));
          }
        }
        windowNodes.add(node);
      }
    }
    assertEquals(downWeightedCount, windowNodes.size(), "窗口算术条目数必须等于降权事件数");

    // verdict 三选一（R6 机械推导，禁自创第四种）
    String verdict;
    String verdictReason;
    if (!positiveControl.flipped()) {
      verdict = "INCONCLUSIVE";
      verdictReason = "R1 正对照未翻转（装置无鉴别力），本锚任何「无位移/有位移」结论都不可采信；缺的证据：装置鉴别力证明";
    } else if (anyImageScoreChange || anyImageRankChange || !allChunkIdSetsEqual) {
      verdict = "RANK-CHANGE";
      verdictReason =
          "R6 机械推导：存在 IMAGE 候选融合分变化（anyImageScoreChange="
              + anyImageScoreChange
              + "）或名次变化（anyImageRankChange="
              + anyImageRankChange
              + "）或集合不等（allChunkIdSetsEqual="
              + allChunkIdSetsEqual
              + "）；maxImageScoreDelta="
              + maxImageScoreDelta
              + " maxTextScoreDelta="
              + maxTextScoreDelta;
    } else {
      assertEquals(0.0D, maxImageScoreDelta, "NO-RANK-CHANGE 分支必须 maxImageScoreDelta==0（机械自洽）");
      verdict = "NO-RANK-CHANGE";
      verdictReason =
          "R6 机械推导：maxImageScoreDelta==0 且全部查询 chunkIdSetEqual；窗口算术见 windowCheck（逐条被降权候选 gap vs 0.30×(1−w)）；"
              + "不可外推为「生产无位移」——结论只对本冻结语料与本装配成立";
    }
    return new AblationResults(
        queryCount,
        queryNodes,
        candidateNodes,
        windowNodes,
        imageCandidateCount,
        downWeightedCount,
        maxImageScoreDelta,
        maxTextScoreDelta,
        imageDeltaSum,
        allChunkIdSetsEqual,
        anyImageScoreChange,
        anyImageRankChange,
        verdict,
        verdictReason);
  }

  /** 消融锚构建（write 与默认复跑共用；字段顺序即序列化顺序，逐字节复算的稳定面）。 */
  private ObjectNode buildAblationAnchor(
      ReplayMaterial material,
      NearDupAblationPositiveControlTest.PositiveControlOutcome positiveControl,
      AblationPass passA,
      AblationPass passB,
      AblationResults results) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", "near-dup-ablation-redesign");
    root.put("group", "S0");
    root.put("decisionVersion", "NEAR-DUP-ABLATION-1.0");
    root.put("decisionOwner", "OWNER");

    ObjectNode criteria = root.putObject("criteria");
    criteria.put(
        "R0",
        "零外呼 + src/main 零 diff：只用冻结向量与桩，不读 DASHSCOPE_API_KEY、不构造真实 provider；"
            + "B 跑只经 mockStatic 替换 downWeightNearDuplicates 一个方法，其余走真实实现（CALLS_REAL_METHODS）");
    criteria.put(
        "R1",
        "正对照必翻转（鉴别力）：纯 JVM 直构三候选，A（降权）时该图像候选排后、B（不降权）排前，两次顺序相反；" + "不成立 ⇒ verdict=INCONCLUSIVE");
    criteria.put("R2", "负对照：非 NEAR_DUPLICATE 档候选两跑融合分逐条相等");
    criteria.put(
        "R3",
        "隔离：B 跑 downWeightNearDuplicates 恰调用 1 次且返回恒等（逐条 Double.compare==0）；"
            + "A 跑返回带 BAND_WEIGHT_METADATA_KEY；两跑非 IMAGE 来源融合分逐条相等");
    criteria.put(
        "R4",
        "逐查询 chunkIdSetEqual + 逐候选 (sourceType, tier, band, fusedA, fusedB, rankA, rankB)；"
            + "另落 imageCandidateCount/downWeightedCount/maxImageScoreDelta/maxTextScoreDelta；rank 从 1 起（融合分降序）");
    criteria.put(
        "R5",
        "分母实测：childRows=冻结锚重建的图片切片行+文本块行（逐条 chunkId）；"
            + "embedInvocations=重放链真实到达 provider 的 embed 请求实计（按未命中计）；禁任何 ×常数推导与融合分÷w 反解");
    criteria.put(
        "R6",
        "verdict 三选一机械推导：RANK-CHANGE（任一 IMAGE 候选分数或名次变化，或集合不等）/ "
            + "NO-RANK-CHANGE（maxImageScoreDelta==0 且集合相等，必附窗口算术+不可外推声明）/ INCONCLUSIVE（附缺哪条证据）");

    ObjectNode sourceAnchors = root.putObject("sourceAnchors");
    putAnchorSha(
        sourceAnchors, "displacementEvidenceAnchor", "near-dup-displacement-evidence-v1.json");
    putAnchorSha(sourceAnchors, "fusionImpactAnchor", "near-dup-fusion-impact-v1.json");

    root.put("baselineHead", ABLATION_BASELINE_HEAD);
    root.put(
        "zeroExternalCallBasis",
        "未构造真实 provider：embed 只从锚 embedCache 取（未命中即抛），embedImage/vision/chat 触发即抛；"
            + "A/B 两跑 embedImage/vision 计数恒 0（StubEmbeddingProvider 结构保证）；"
            + "B 跑仅经 mockStatic 替换 downWeightNearDuplicates 一个方法");
    root.put("externalCalls", 0);

    ObjectNode constants = root.putObject("productionConstants");
    constants.put("threshold", ImageSimilarityGate.DEFAULT_THRESHOLD);
    constants.put("nearDuplicateFloor", ImageSimilarityGate.NEAR_DUPLICATE_FLOOR);
    constants.put("bandReferenceFloor", ImageSimilarityGate.BAND_REFERENCE_FLOOR);
    constants.put("weightHigh", ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH);
    constants.put("weightMid", ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID);
    constants.put("weightLow", ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW);
    constants.put("textRouteWeight", positiveControl.textRouteWeight());
    constants.put("imageRouteWeight", positiveControl.imageRouteWeight());
    constants.put(
        "windowHigh", IMAGE_ROUTE_WEIGHT * (1D - ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH));
    constants.put(
        "windowMid", IMAGE_ROUTE_WEIGHT * (1D - ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID));
    constants.put(
        "windowLow", IMAGE_ROUTE_WEIGHT * (1D - ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW));
    constants.put(
        "windowFormula",
        "bound(w) = imageRouteWeight × (1 − w)（与 near-dup-fusion-impact-v1 bounds 同式）");

    ObjectNode design = root.putObject("ablationDesign");
    design.put(
        "passA",
        "生产降权：mockStatic(CALLS_REAL_METHODS) 打开，仅对 downWeightNearDuplicates 做"
            + "「捕获入参→callRealMethod（真实生产方法）→捕获返回」旁路观测，行为与生产一致");
    design.put("passB", "恒等替换：同一框架仅把该方法替换为原样返回入参，其余静态方法走真实实现；消融面唯一差异点");
    design.put(
        "rebuildBasis",
        "两跑各自从冻结锚 corpusMaterial 全新装配两集合与服务（坑 4：不许把 A 的结果喂给 B）；"
            + "逐查询独立 mockStatic 作用域并在关闭前 verify 恰 1 次");
    design.put(
        "downWeightCallSite",
        "KnowledgeRetrievalServiceImpl#retrieveFromKnowledgeBases 唯一调用点（图像路重排之后、fuseRoutes 之前）");

    ObjectNode positive = root.putObject("positiveControl");
    positive.put("flipped", positiveControl.flipped());
    positive.put("nearHighBand", positiveControl.nearHighBand());
    positive.put("nearHighWeight", positiveControl.nearHighWeight());
    positive.put("nearHighInputScore", positiveControl.nearHighInputScore());
    positive.put("nonNearImageInputScore", positiveControl.nonNearImageInputScore());
    positive.put("textInputScore", positiveControl.textInputScore());
    positive.put("fusedNearHighWithDownWeight", positiveControl.fusedNearHighWithDownWeight());
    positive.put(
        "fusedNearHighWithoutDownWeight", positiveControl.fusedNearHighWithoutDownWeight());
    positive.put(
        "fusedNonNearImageWithDownWeight", positiveControl.fusedNonNearImageWithDownWeight());
    positive.put(
        "fusedNonNearImageWithoutDownWeight", positiveControl.fusedNonNearImageWithoutDownWeight());
    positive.put("fusedTextWithDownWeight", positiveControl.fusedTextWithDownWeight());
    positive.put("fusedTextWithoutDownWeight", positiveControl.fusedTextWithoutDownWeight());
    positive.put("rankNearHighWithDownWeight", positiveControl.rankNearHighWithDownWeight());
    positive.put("rankNearHighWithoutDownWeight", positiveControl.rankNearHighWithoutDownWeight());
    positive.put(
        "rankNonNearImageWithDownWeight", positiveControl.rankNonNearImageWithDownWeight());
    positive.put(
        "rankNonNearImageWithoutDownWeight", positiveControl.rankNonNearImageWithoutDownWeight());
    positive.put(
        "source",
        "NearDupAblationPositiveControlTest#runPositiveControl 同一实现（surefire 正对照与消融锚结论位共用）");

    ObjectNode denominators = root.putObject("denominators");
    denominators.put("childRows", material.imageRecords().size() + material.textRecords().size());
    denominators.put(
        "childRowsBasis",
        "实测 = 冻结锚重建的 imageRecords（图片切片行）"
            + material.imageRecords().size()
            + " + textRecords（文本块行）"
            + material.textRecords().size()
            + "，逐条 chunkId 列出；禁任何 ×常数推导");
    ArrayNode childRowIds = denominators.putArray("childRowChunkIds");
    for (StoredRecord record : material.imageRecords()) {
      childRowIds.add(record.chunkId());
    }
    for (StoredRecord record : material.textRecords()) {
      childRowIds.add(record.chunkId());
    }
    denominators.put("embedInvocations", passA.embedInvocations());
    denominators.put(
        "embedInvocationsBasis",
        "A 跑重放链真实到达 provider 的 embed 请求实计（EmbeddingService 无缓存，每次到达即一次未命中请求，真跑时即一次外呼调用点）；"
            + "B 跑实测同值（两跑相等已断言）；摄取侧嵌入在写锚时已发生，重放由锚 embedCache 供给，不计入");
    denominators.put(
        "childRowsVsEmbedInvocationsReason",
        "两数口径不同所以不等：childRows 是语料子块行面（摄取侧），embedInvocations 是单轮重放检索链查询侧嵌入请求；两数均为实测，无推导");

    root.put("queryCount", results.queryCount());
    ArrayNode queryArray = root.putArray("queries");
    results.queryNodes().forEach(queryArray::add);
    ArrayNode candidateArray = root.putArray("candidates");
    results.candidateNodes().forEach(candidateArray::add);
    root.put("imageCandidateCount", results.imageCandidateCount());
    root.put("downWeightedCount", results.downWeightedCount());
    root.put("maxImageScoreDelta", results.maxImageScoreDelta());
    root.put("maxTextScoreDelta", results.maxTextScoreDelta());
    root.put("imageDeltaSum", results.imageDeltaSum());
    root.put("verdict", results.verdict());
    root.put("verdictReason", results.verdictReason());

    ObjectNode windowCheck = root.putObject("windowCheck");
    windowCheck.put(
        "note",
        "逐条被降权候选的窗口算术：gap = 该候选 fusedB（未降权跑融合分）− 同查询下位竞争者最大融合分；"
            + "window = 0.30×(1−w)（imageRouteWeight × (1 − w)），w 取 A 跑出参 BAND_WEIGHT_METADATA_KEY 实测值；"
            + "NO-RANK-CHANGE 必附本节；RANK-CHANGE 时本节仍为实测上下文，verdict 由 R6 机械推导，不受本节影响");
    ArrayNode windowArray = windowCheck.putArray("entries");
    results.windowNodes().forEach(windowArray::add);

    root.put(
        "nonExtrapolationNote",
        "本锚结论只对本冻结语料（3 份真实 PDF、7 条查询、TOP_K=5）与本装配成立，不可外推为「生产无位移」；"
            + "要真分布须另立卡（S1 预注册见 proposal §6，本卡不执行）");

    ObjectNode isolation = root.putObject("isolationEvidence");
    isolation.put("downWeightCapturedCallsA", passA.downWeightInputs().size());
    isolation.put("downWeightCapturedCallsB", passB.downWeightInputs().size());
    isolation.put(
        "bandWeightMarkerObservedOnAOutput",
        "A 跑出参带 BAND_WEIGHT_METADATA_KEY 的条数=" + results.downWeightedCount());
    isolation.put(
        "nonImageFusedByteEqualAcrossRuns", "maxTextScoreDelta=" + results.maxTextScoreDelta());
    isolation.put("inputIdentityVerified", "两跑降权入参逐条 fusionKey + 分数 Double.compare 相等（重放确定性结构证明）");
    isolation.put(
        "mockEvidence",
        "静态 mock 实际生效证据：逐查询 verify(times(1))；A 出参被降权者分数=入参×w 且带 BAND_WEIGHT_METADATA_KEY；"
            + "B 返回与入参逐条 Double.compare==0；非 IMAGE 来源两跑融合分逐条相等；embed 请求数两跑相等");
    return root;
  }

  /**
   * 消融专用 embed 计数桩：只统计真实到达 provider 的 embed 请求次数（embedInvocations 分母实测，按未命中计—— EmbeddingService
   * 无缓存，每次到达即一次未命中请求）；其余方法原样转发给锚缓存桩（锚缓存未命中/embedImage/vision 触发即抛， 零外呼结构性保持）。
   */
  private static ModelProvider embedCountingProvider(
      ModelProvider delegate, java.util.concurrent.atomic.AtomicInteger embedCounter) {
    Answer<Object> forwarding = AdditionalAnswers.delegatesTo(delegate);
    return Mockito.mock(
        ModelProvider.class,
        Mockito.withSettings()
            .name("near-dup-ablation-embed-counter")
            .defaultAnswer(
                invocation -> {
                  if ("embed".equals(invocation.getMethod().getName())) {
                    embedCounter.incrementAndGet();
                  }
                  return forwarding.answer(invocation);
                }));
  }

  /** callRealMethod 返回值还原为候选列表（静态 mock 旁路观测专用）。 */
  @SuppressWarnings("unchecked")
  private static List<RetrievalCandidate> castCandidates(Object value) {
    return (List<RetrievalCandidate>) value;
  }

  /** 候选命中 → 最终 sources() 口径的 chunkId（单一真相 = SourceReferenceFactory.from 的判别）。 */
  private static String sourceKeyOf(VectorSearchHit hit) {
    return SourceReferenceFactory.from(hit, 0D).chunkId();
  }

  /** metadata 值转字符串（null 保持 null，tier/band 缺省语义）。 */
  private static String stringOf(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /** 按 queryIndex 取重放证据；缺即抛（装配错）。 */
  private static QueryEvidence evidenceByIndex(List<QueryEvidence> evidences, int index) {
    for (QueryEvidence evidence : evidences) {
      if (evidence.index() == index) {
        return evidence;
      }
    }
    throw new IllegalStateException("缺 queryIndex=" + index + " 的重放证据");
  }

  /** 按 sources() 口径 chunkId 取单条来源引用；缺返回 null（另一跑也未含）。 */
  private static SourceRef sourceByKey(List<SourceRef> sources, String chunkId) {
    for (SourceRef source : sources) {
      if (source.chunkId().equals(chunkId)) {
        return source;
      }
    }
    return null;
  }
}
