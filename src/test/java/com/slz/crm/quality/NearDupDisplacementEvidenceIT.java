package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
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
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
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

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

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

    // 零外呼自证：桩 provider 对 embedImage/vision 一律抛错；embed 只从锚缓存取
    StubEmbeddingProvider stub = new StubEmbeddingProvider(embedCache);
    EmbeddingService embeddingService = new EmbeddingService(stub, properties("replay-no-key"));

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
            new RetrievalQueryRewriteService(stub, configProvider),
            new Bm25Scorer(),
            configProvider);
    ReflectionTestUtils.setField(service, "imageVectorStore", imageStore);

    // 逐查询重放检索链（输入向量全部来自锚）
    List<QueryEvidence> evidences = new ArrayList<>();
    UserContextHolder.set(
        new UserContext(
            EVAL_USER_ID, 5L, 101L, DataScopeLevel.ALL, "near-dup-displacement-evidence"));
    try {
      for (JsonNode storedQuery : root.path("queries")) {
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
    assertEquals(root.path("queries").size(), evidences.size(), "复跑查询数必须与锚一致");
    assertEquals(
        0, stub.embedImageCalls + stub.visionCalls, "默认复跑必须零外呼（embedImage/vision 桩被触发即失败）");

    // 重算全部判据字段（与写锚同一实现）并逐值断言
    CorpusBuild build =
        new CorpusBuild(List.of(), List.copyOf(imageRecords), List.copyOf(textRecords), List.of());
    AnchorInputs inputs =
        new AnchorInputs(
            build,
            evidences,
            root.path("callBreakdown").path("visionCalls").asInt(),
            root.path("callBreakdown").path("embedImageCalls").asInt(),
            root.path("callBreakdown").path("embedCalls").asInt(),
            embedCache,
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
}
