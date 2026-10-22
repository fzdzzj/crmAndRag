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
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
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
import reactor.core.publisher.Flux;

/**
 * 真共享空间生产灰度真跑（production-gray-real-run 任务 2.1 / 2.2 / 3.1，failsafe IT，A 档真跑）。
 *
 * <p><b>与前四张卡的差别只有一处，但那一处就是本卡全部价值</b>：语料从"程序化确定性 PDF"（{@code pdfBytes()} 现场生成）换成
 * <b>真实公有领域政府出版物</b>（{@code src/test/resources/rag-quality/real-pdf/}，NIST/DOE，来源与授权登记在 {@code
 * sources.json}）。 链路其余部分逐段走生产实现，一行不改：
 *
 * <ol>
 *   <li><b>真 PDF → 真切片</b>：{@link PdfImageSliceIngestService#buildSlices}（总开关 + 视觉路开）内部 {@code
 *       PdfImageExtractor} 从页资源字典取 {@code PDImageXObject} → 解码 PNG → 真 {@code vision} 描述 → 质量闸门 →
 *       落资产 → {@code embedImage} 真视觉向量暂存；
 *   <li><b>真落库口径</b>：{@link ImageVectorFlushSupport#flush} 按 storageKey 回填到切片行（{@code chunkId} =
 *       生产切片行主键），payload 走 {@code DocumentIngestionSupport#createVectorRecord} 单一实现 → image 集合；
 *   <li><b>文本集合</b>：同一批 VLM 描述文本经真文本 embedding → 文本集合（图文双集合并存，互不替代）；
 *   <li><b>query-by-image</b>：查询图 = <b>落盘的那张</b> PNG 字节重新真 {@code embedImage}，经生产 {@link
 *       KnowledgeRetrievalServiceImpl} 图片视觉路召回 image 集合，黄金 id 走生产口径 {@code IMAGE:{行主键}}；
 *   <li><b>成本基线</b>：{@link CountingProvider} 在真调用点累加调用次数、token 与耗时，落盘 {@code
 *       docs/rag-quality/production-gray-cost-v1.json}（本卡唯一成本真相源）。
 * </ol>
 *
 * <p><b>门控</b>：沿用 {@code RAG_VISION_EMBED_REAL=1} + {@code DASHSCOPE_API_KEY}（env 或仓库根 {@code
 * .env}），<b>不新开键</b>；任一不满足整类 Assume 跳过、构建不失败——<b>默认 CI 与本地 verify 恒零外呼</b>（复用 {@code
 * ImageCrossModalBenchmarkIT#requireRealRun} 的同一枚门控）。
 *
 * <p><b>判据（跑之前固定，不按结果调参）</b>：① 每份资产至少出 1 个切片、全批 ≥3 个（少于 3 说明真实 PDF 的图没走通链路）；② 每条 query-by-image
 * 都召回自己的切片（同图余弦 ≥ 0.90，阈值复用 {@code ImageCrossModalSuite}）；③ 异图余弦必须低于同图余弦（真视觉把不同图分得开，
 * 但不设硬阈值——真实语料的异图关系不是程序化语料那种"摆好的"关系）；④ 文本集合那条对照路同样命中自己的切片。
 *
 * <p><b>边界</b>：本类只读真实 PDF，不回写资产、不碰既有 baseline（v1~v7 / image / pdf 锚）、不改任何生产开关默认值。
 */
@DisplayName("真共享空间生产灰度真跑（真实 PDF 资产，RAG_VISION_EMBED_REAL 显式 opt-in）")
class ProductionGrayRealRunIT {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** compatible-mode 端点（与既有真跑 IT 相同的装配方式）。 */
  private static final String COMPATIBLE_BASE_URL =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";

  /** 评测上下文用户 id（只用于授权桩与 UserContextHolder，权限面不混入质量评测）。 */
  private static final long EVAL_USER_ID = 99005L;

  /** 评测知识库 id（字符串口径 = 生产 payload；长整型 = 授权桩）。 */
  private static final String EVAL_KB_ID = "9903";

  private static final long EVAL_KB_ID_LONG = 9903L;

  /** 图片切片类目（既有 image 集合的分类过滤键，本卡不自造类目）。 */
  private static final String IMAGE_CATEGORY = ImageCrossModalFixture.IMAGE_CATEGORY;

  /** 视觉 embedding 的 MIME（生产 {@code PdfImageSliceIngestService.IMAGE_MIME_TYPE} 同值）。 */
  private static final String IMAGE_MIME = "image/png";

  /** 切片行主键起点（测试侧合成的 DB 主键，与生产"自增主键作 chunkId"同构；跨文档连续分配，全局唯一）。 */
  private static final long FIRST_CHUNK_ID = 9300L;

  /** 召回截断排名（与既有套件同口径）。 */
  private static final int TOP_K = ImageCrossModalSuite.TOP_K;

  /** 默认落盘路径（本卡独立成本基线，不碰既有 baseline）。 */
  private static final Path DEFAULT_OUT_PATH =
      Path.of("docs", "rag-quality", "production-gray-cost-v1.json");

  /** 落盘路径覆盖属性（与既有基准同款形态；仅测试侧系统属性，不是生产开关）。 */
  private static final String OUT_PROP = "rag.production-gray.cost.out";

  /**
   * 视觉模型覆盖属性（沿用 {@code PdfIngestImageBenchmarkIT} 的同一枚，不新开概念）。
   *
   * <p>为什么可覆盖：VLM 描述是这条链的必经步骤（生产要过描述质量闸门才落切片），模型可用性属环境事实——上一卡本机实测生产默认 {@code qwen-vl-plus} 在该 key
   * 上返回 {@code model_not_found}，改用该 key 可用的同族 VL 模型即可真跑。跑哪只模型必须落盘留痕 （{@code
   * visionModel}），否则成本基线不可辩护。
   */
  private static final String VISION_MODEL_PROP = "rag.pdf.image.vision-model";

  /** 生产默认视觉模型（{@value #VISION_MODEL_PROP} 未给时使用）。 */
  private static final String DEFAULT_VISION_MODEL = "qwen-vl-plus";

  /** 程序化 PDF 对照基线（只读：用于"真实 vs 程序化"成本差异观察，只读不写）。 */
  private static final Path PROGRAMMATIC_REFERENCE =
      Path.of("docs", "rag-quality", "pdf-ingest-image-baseline-v1.json");

  /** 落盘留痕：opt-in 与链路口径（复核判据直接读这一句）。 */
  private static final String OPT_IN_NOTE =
      "RAG_VISION_EMBED_REAL=1 + DASHSCOPE_API_KEY 显式真跑；语料 = 真实公有领域政府出版物 PDF（NIST SP 800-101r1 / NIST SP 800-183 / DOE "
          + "Renewable Energy Framework，来源与授权登记于 "
          + "src/test/resources/rag-quality/real-pdf/sources.json），走生产 PdfImageSliceIngestService#buildSlices（总开关 + 视觉路开）"
          + "→ 真 VLM 描述 → 质量闸门 → 真 embedImage 暂存 → ImageVectorFlushSupport 落 image 集合 → 生产 recallImage "
          + "query-by-image；文本对照集合 = 同一批 VLM 描述文本经真文本 embedding 落文本集合";

  @Test
  @DisplayName("真跑：真实 PDF → 真 VLM + 真视觉/文本 embedding → 双集合 → query-by-image，落盘成本基线")
  void runRealPdfThroughProductionGrayChainAndWriteCostBaseline() throws Exception {
    String apiKey = ImageCrossModalBenchmarkIT.resolveApiKey();
    ImageCrossModalBenchmarkIT.requireRealRun(System.getenv(), apiKey);

    ModelProviderProperties properties = properties(apiKey);
    CountingProvider provider = new CountingProvider(provider(properties, apiKey));
    EmbeddingService embeddingService = new EmbeddingService(provider, properties);
    ImageVectorStore imageStore = new ImageVectorStore(new InMemoryVectorStore());
    InMemoryVectorStore textStore = new InMemoryVectorStore();
    ObjectProvider<DynamicConfigService> configProvider = provider(config());

    List<RealPdfAssets.Asset> assets = RealPdfAssets.assets();
    assertTrue(assets.size() >= 2, "真实 PDF 语料至少 2 份（owner 口径 2~3 份），实测 " + assets.size());

    // 1) 逐份真 PDF → 生产切片（真 VLM 描述 + 真视觉向量）→ 真落库口径写 image 集合
    long nextChunkId = FIRST_CHUNK_ID;
    List<SliceRow> rows = new ArrayList<>();
    List<ObjectNode> documentNodes = new ArrayList<>();
    long ingestStarted = System.currentTimeMillis();
    for (RealPdfAssets.Asset asset : assets) {
      byte[] pdf = asset.pdf();
      int visionBefore = provider.vision.calls();
      int imageEmbedsBefore = provider.embedImage.calls();
      long docStarted = System.currentTimeMillis();

      PdfIngestImageFixture.InMemoryImageStorage storage = PdfIngestImageFixture.inMemoryStorage();
      PdfImageSliceIngestService ingestService =
          new PdfImageSliceIngestService(
              provider, provider(config()), provider((TokenUsageRecorder) null), provider(storage));
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
      int flushed = ImageVectorFlushSupport.flush(file(asset), children, slices, imageStore);

      List<SliceRow> documentRows = new ArrayList<>(slices.size());
      for (int index = 0; index < slices.size(); index++) {
        DocumentChunk slice = slices.get(index);
        String storageKey = ImageSliceMetadata.storageKeyOf(slice.extraMetadata());
        float[] vector = staged.get(storageKey);
        assertNotNull(
            vector,
            asset.key()
                + " 第 "
                + slice.pageNo()
                + " 页图缺视觉向量（imageVectorEnabled 未生效或该图 embedImage 失败）");
        documentRows.add(
            new SliceRow(
                asset.key(),
                asset.file(),
                chunkIds.get(index),
                storageKey,
                slice.text(),
                vector,
                storage.open(storageKey).readAllBytes(),
                slice.pageNo()));
      }
      rows.addAll(documentRows);
      int extracted = RealPdfAssets.extract(pdf, RealPdfAssets.MAX_IMAGES_PER_DOCUMENT).size();

      ObjectNode document = MAPPER.createObjectNode();
      document.put("key", asset.key());
      document.put("file", asset.file());
      document.put("agency", asset.agency());
      document.put("sourceUrl", asset.sourceUrl());
      document.put("license", asset.license());
      document.put("pdfBytes", pdf.length);
      document.put("pages", asset.pages());
      document.put("extractedImages", extracted);
      document.put("slicedImages", slices.size());
      document.put("gateRejectedImages", extracted - slices.size());
      document.put("flushedImageVectors", flushed);
      document.put("visionCalls", provider.vision.calls() - visionBefore);
      document.put("ingestImageEmbeds", provider.embedImage.calls() - imageEmbedsBefore);
      document.put("stagedVisionVectors", staged.size());
      document.put("elapsedMs", System.currentTimeMillis() - docStarted);
      document.put("imageRows", documentRows.size());
      documentNodes.add(document);
      System.out.println(
          "[production-gray] "
              + asset.key()
              + " pages="
              + asset.pages()
              + " extracted="
              + extracted
              + " sliced="
              + slices.size()
              + " flushed="
              + flushed);
    }
    long ingestElapsed = System.currentTimeMillis() - ingestStarted;
    assertTrue(rows.size() >= 3, "整批真实 PDF 至少 3 个图片切片（少于 3 说明真实资产的图没走通摄取链），实测 " + rows.size());
    for (ObjectNode document : documentNodes) {
      assertTrue(
          document.path("imageRows").asInt() >= 1,
          document.path("key").asText() + " 一份资产都没产切片：该 PDF 的图可能全被描述闸门剔除");
    }
    int flushedTotal =
        documentNodes.stream().mapToInt(node -> node.path("flushedImageVectors").asInt()).sum();
    assertEquals(rows.size(), flushedTotal, "切片行数与 image 集合写入条数必须一一对应");

    // 2) 文本集合：同一批 VLM 描述文本 → 真文本 embedding → 文本集合（payload 与 image 集合同 chunkId，黄金 id 才可比）
    long descriptionStarted = System.currentTimeMillis();
    Map<Long, float[]> textVectors = new LinkedHashMap<>();
    for (SliceRow row : rows) {
      ModelCallResult<float[]> textual = embeddingService.embedWithUsage(row.description());
      textVectors.put(row.chunkId(), textual.vector());
      textStore.upsert(textRecord(row, textual.vector()));
    }
    long descriptionElapsed = System.currentTimeMillis() - descriptionStarted;

    // 3) 生产检索链装配：视觉路装 image 集合，文本对照路装文本集合
    KnowledgeBaseAuthorizationService authorization =
        Mockito.mock(KnowledgeBaseAuthorizationService.class);
    Mockito.when(
            authorization.authorizedKnowledgeBaseIds(
                ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(List.of(EVAL_KB_ID_LONG));
    KnowledgeRetrievalPort visionRetrieval =
        retrievalService(
            embeddingService,
            new InMemoryVectorStore(),
            imageStore,
            authorization,
            provider,
            configProvider);
    KnowledgeRetrievalPort textRetrieval =
        retrievalService(
            embeddingService, textStore, null, authorization, provider, configProvider);

    // 4) 逐张 query-by-image（查询图 = 落盘的那张 PNG）+ 文本对照查询（查询文本 = 该图描述）
    List<QueryOutcome> outcomes = new ArrayList<>(rows.size());
    long queryStarted = System.currentTimeMillis();
    UserContext evaluatorContext =
        new UserContext(EVAL_USER_ID, 5L, 101L, DataScopeLevel.ALL, "production-gray-real-run");
    UserContextHolder.set(evaluatorContext);
    try {
      for (SliceRow row : rows) {
        float[] queryVector = provider.embedImage(row.png(), IMAGE_MIME).vector();
        long started = System.currentTimeMillis();
        List<String> retrieved =
            retrieve(visionRetrieval, row.description(), queryVector, IMAGE_CATEGORY);
        long latency = System.currentTimeMillis() - started;
        double sameCosine = cosine(queryVector, row.imageVector());
        double maxCrossCosine =
            rows.stream()
                .filter(other -> other.chunkId() != row.chunkId())
                .mapToDouble(other -> cosine(queryVector, other.imageVector()))
                .max()
                .orElse(0D);
        float[] textQueryVector = embeddingService.embed(row.description());
        List<String> textRetrieved = retrieve(textRetrieval, row.description(), null, null);
        outcomes.add(
            new QueryOutcome(
                row,
                retrieved,
                textRetrieved,
                sameCosine,
                maxCrossCosine,
                cosine(textQueryVector, textVectors.get(row.chunkId())),
                latency));
      }
    } finally {
      UserContextHolder.clear();
    }
    long queryElapsed = System.currentTimeMillis() - queryStarted;

    // 5) 判据（跑之前固定）：同图命中 + 同图余弦 ≥ 阈值 + 异图余弦低于同图 + 文本对照路命中
    long visionHits = outcomes.stream().filter(QueryOutcome::visionHit).count();
    long textHits = outcomes.stream().filter(QueryOutcome::textHit).count();
    double minSameCosine = outcomes.stream().mapToDouble(QueryOutcome::sameCosine).min().orElse(0D);
    double maxCrossCosine =
        outcomes.stream().mapToDouble(QueryOutcome::maxCrossCosine).max().orElse(0D);
    for (QueryOutcome outcome : outcomes) {
      assertTrue(
          outcome.sameCosine() >= ImageCrossModalSuite.SAME_IMAGE_COSINE_THRESHOLD,
          outcome.row().assetKey()
              + " 第 "
              + outcome.row().pageNo()
              + " 页图同图余弦 "
              + outcome.sameCosine()
              + " < "
              + ImageCrossModalSuite.SAME_IMAGE_COSINE_THRESHOLD
              + "（查询图就是落盘那张的再次编码，做不到就是链路接错）");
      assertTrue(
          outcome.maxCrossCosine() < outcome.sameCosine(),
          outcome.row().assetKey()
              + " 第 "
              + outcome.row().pageNo()
              + " 页图异图余弦 "
              + outcome.maxCrossCosine()
              + " 不低于同图余弦 "
              + outcome.sameCosine()
              + "（真视觉分不开不同图，增量价值不成立）");
    }
    // 「每条必召回」隐含依赖图像路近似同图下限 0.9887 低于真实同图余弦；重标定抬锚后此处会先炸成
    // 「装配有问题」的误导信息，排查顺序：先看 ImageSimilarityGate.NEAR_DUPLICATE_FLOOR，再看 image 集合装配。
    assertEquals(rows.size(), visionHits, "每条 query-by-image 都必须召回自己那张切片（image 集合装配/过滤键有问题？）");
    assertEquals(rows.size(), textHits, "文本对照路必须同样命中自己的切片（文本集合装配有问题？）");
    System.out.println(
        "[production-gray] rows="
            + rows.size()
            + " visionHits="
            + visionHits
            + " textHits="
            + textHits
            + " minSameCos="
            + minSameCosine
            + " maxCrossCos="
            + maxCrossCosine);

    // 6) 成本基线落盘（独立文件；不碰 baseline-v1~v7 / image-baseline-v1 / pdf-ingest-image-baseline-v1）
    ObjectNode root = MAPPER.createObjectNode();
    root.put("suiteVersion", "PRODUCTION-GRAY-COST-1.0");
    root.put("optIn", OPT_IN_NOTE);
    root.put("measuredAt", Instant.now().toString());
    root.put("visionModel", visionModel());
    root.put("embeddingModel", properties.getEmbeddingModel());
    root.put("imageEmbeddingModel", properties.getImageEmbeddingModel());
    root.put("knowledgeBaseId", EVAL_KB_ID);
    root.put(
        "chain",
        "PdfImageExtractor → PdfImageSliceIngestService#buildSlices(imageVectorEnabled=true) → 真 vision 描述 + 质量闸门 → "
            + "ImageVisualVectorSupport#embedAndStage → ImageVectorFlushSupport#flush → image 集合；"
            + "描述文本 → EmbeddingService#embedWithUsage → 文本集合；query-by-image → KnowledgeRetrievalServiceImpl#recallImage");

    RealPdfAssets.Asset first = assets.get(0);
    JsonNode manifest = RealPdfAssets.manifest();
    ObjectNode corpus = root.putObject("corpus");
    corpus.put("pdfCount", assets.size());
    corpus.put(
        "totalPdfBytes",
        assets.stream().mapToLong(asset -> RealPdfAssets.bytes(asset).length).sum());
    corpus.put("maxImagesPerDocument", RealPdfAssets.MAX_IMAGES_PER_DOCUMENT);
    corpus.put("sourceManifest", RealPdfAssets.DIR.resolve(RealPdfAssets.MANIFEST_FILE).toString());
    corpus.put("licenseBasisSummary", manifest.path("licenseBasisSummary").asText());
    corpus.put("licenseCaveat", manifest.path("licenseCaveat").asText());
    ArrayNode assetNodes = corpus.putArray("assets");
    for (ObjectNode document : documentNodes) {
      assetNodes.add(document);
    }
    corpus.put("firstAssetTitle", first.title());

    ObjectNode calls = root.putObject("callCounts");
    calls.put("visionCallCalls", provider.vision.calls());
    calls.put("visionEmbeddingCalls", provider.embedImage.calls());
    calls.put("textEmbeddingCalls", provider.embed.calls());
    calls.put(
        "totalCalls",
        provider.vision.calls() + provider.embedImage.calls() + provider.embed.calls());
    calls.put(
        "semantics",
        "三计数由 CountingProvider 在真调用点累加，均非公式推导：visionCallCalls = VLM 描述外呼次数（描述先于质量闸门，被闸门剔除的图同样计数）；"
            + "visionEmbeddingCalls = embedImage 次数 = 摄取侧暂存 + 查询侧 query-by-image；textEmbeddingCalls = 文本 embed 次数 = 描述编码 + 文本查询");

    ObjectNode tokens = root.putObject("tokenUsage");
    putTokens(tokens, "vision", provider.vision);
    putTokens(tokens, "imageEmbedding", provider.embedImage);
    putTokens(tokens, "textEmbedding", provider.embed);
    tokens.put("totalTokens", provider.totalTokens());
    tokens.put(
        "usageNote",
        "token 数取自 ModelCallResult 的 usage（DashScope 回流才计数，未回流记 null 并以 callsWithUsage 标注样本量）；"
            + "与上一卡「embedding/vision 端点恒不回流 usage」的观察不同时，以本字段为准——本卡如实记录实测，不做任何折算");

    ObjectNode latency = root.putObject("latency");
    latency.put("ingestElapsedMs", ingestElapsed);
    latency.put("descriptionTextEmbedElapsedMs", descriptionElapsed);
    latency.put("queryElapsedMs", queryElapsed);
    latency.put("totalElapsedMs", provider.elapsedMs());
    latency.put("visionCallAvgMs", provider.vision.averageMs());
    latency.put("visionCallMaxMs", provider.vision.maxMs());
    latency.put("imageEmbeddingAvgMs", provider.embedImage.averageMs());
    latency.put("textEmbeddingAvgMs", provider.embed.averageMs());
    latency.put(
        "semantics",
        "各段耗时由 System.currentTimeMillis 在真调用点取差值；totalElapsedMs = 三类模型外呼耗时之和（不含测试装配与判分）");

    ObjectNode retrieval = root.putObject("retrieval");
    retrieval.put("cases", rows.size());
    retrieval.put("topK", TOP_K);
    retrieval.put("visionHitRate", ratio(visionHits, rows.size()));
    retrieval.put("textPathHitRate", ratio(textHits, rows.size()));
    retrieval.put("minSameImageCosine", minSameCosine);
    retrieval.put("maxCrossImageCosine", maxCrossCosine);
    retrieval.put("sameImageCosineThreshold", ImageCrossModalSuite.SAME_IMAGE_COSINE_THRESHOLD);
    ArrayNode caseNodes = retrieval.putArray("cases_detail");
    for (QueryOutcome outcome : outcomes) {
      ObjectNode node = caseNodes.addObject();
      node.put("assetKey", outcome.row().assetKey());
      node.put("pageNo", outcome.row().pageNo());
      node.put("chunkId", outcome.row().chunkId());
      node.put("goldenId", goldenIdOf(outcome.row().chunkId()));
      node.put("visionRetrieved", String.join(",", outcome.visionRetrieved()));
      node.put("textPathRetrieved", String.join(",", outcome.textRetrieved()));
      node.put("sameImageCosine", outcome.sameCosine());
      node.put("maxCrossImageCosine", outcome.maxCrossCosine());
      node.put("textSameImageCosine", outcome.textSameImageCosine());
      node.put("retrievalLatencyMs", outcome.retrievalLatencyMs());
      node.put("descriptionChars", outcome.row().description().length());
    }

    ObjectNode perImage = root.putObject("costPerImage");
    perImage.put("images", rows.size());
    perImage.put("visionCallsPerImage", ratio(provider.vision.calls(), rows.size()));
    perImage.put("imageEmbedsPerImage", ratio(provider.embedImage.calls(), rows.size()));
    perImage.put("textEmbedsPerImage", ratio(provider.embed.calls(), rows.size()));
    perImage.put(
        "elapsedMsPerImage", ratio(ingestElapsed + descriptionElapsed + queryElapsed, rows.size()));
    perImage.put(
        "semantics", "每图成本按「三类外呼总耗时 / 入库图片数」摊分（含查询侧与文本集合侧，因为生产灰度一打开，这三类外呼会同时发生）；" + "不是单看摄取段的估算值");

    root.set("programmaticReference", programmaticReference());
    root.put(
        "realVsProgrammaticObservation",
        "真实 PDF 与程序化 PDF 的成本结构差异主要在三点：① 提取密度——程序化语料一页一图、每页都有图，真实文档内嵌图分布稀疏且混着 <64px 装饰图，"
            + "实际入库图数由描述质量闸门决定（本次见 corpus.assets 的 extractedImages vs slicedImages）；"
            + "② 描述长度——真实截图/图表里的文字多，VLM 输出显著长于程序化语料的确定性图形描述，直接影响 vision 出 token 与描述文本 embedding 的入 token；"
            + "③ 失败/剔除概率——真实图更可能撞上闸门（信息量太低或转写不清），表现为 gateRejectedImages > 0，成本不会白花但切片会少。"
            + "结论口径：按「每张真正入库的图」摊成本，而不是按「每份 PDF」或「每个提取图」摊");

    Path outPath = resolveOutPath();
    Files.createDirectories(outPath.toAbsolutePath().getParent());
    Files.writeString(outPath, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
    System.out.println(
        "[production-gray] production-gray-cost-v1 已落盘: " + outPath.toAbsolutePath());
  }

  /**
   * 黄金引用 id（生产口径 {@code IMAGE:{行主键}}）。
   *
   * @param chunkId 切片行主键
   * @return 黄金引用 id
   */
  static String goldenIdOf(long chunkId) {
    return SourceReferenceFactory.IMAGE_CHUNK_PREFIX + chunkId;
  }

  // ---------- 数据载体 ----------

  /**
   * 一条入库图片切片（本轮真跑的最小见证）。
   *
   * @param assetKey 所属资产键
   * @param filename 所属文件名
   * @param chunkId 切片行主键（黄金引用 id 的来源）
   * @param storageKey 图片资产存储键（视觉向量的暂存键）
   * @param description VLM 真产出的描述（即切片正文）
   * @param imageVector 真视觉向量（落 image 集合那条）
   * @param png 落盘 PNG 字节（query-by-image 的查询图）
   * @param pageNo 页码
   */
  private record SliceRow(
      String assetKey,
      String filename,
      long chunkId,
      String storageKey,
      String description,
      float[] imageVector,
      byte[] png,
      int pageNo) {}

  /**
   * 一条 query-by-image 判分记录。
   *
   * @param row 目标切片
   * @param visionRetrieved 视觉路召回 id
   * @param textRetrieved 文本对照路召回 id
   * @param sameCosine 同图余弦（查询向量 vs 落库向量）
   * @param maxCrossCosine 异图余弦最大值（查询向量 vs 其它所有入库图）
   * @param textSameImageCosine 文本对照路同图余弦
   * @param retrievalLatencyMs 检索段耗时
   */
  private record QueryOutcome(
      SliceRow row,
      List<String> visionRetrieved,
      List<String> textRetrieved,
      double sameCosine,
      double maxCrossCosine,
      double textSameImageCosine,
      long retrievalLatencyMs) {

    boolean visionHit() {
      return containsChunk(visionRetrieved, row.chunkId());
    }

    boolean textHit() {
      return containsChunk(textRetrieved, row.chunkId());
    }
  }

  /**
   * 召回列表里是否含目标切片（兼容 {@code IMAGE:{id}} 与裸 {@code id} 两种口径）。
   *
   * <p>为什么容错：视觉路走 {@code SourceReferenceFactory} 加 {@code IMAGE:} 前缀，文本路的历史 payload 口径可能给裸
   * id；判"命中"时两者都算命中， 否则会把"引用前缀差异"误判成"零召回"。
   */
  private static boolean containsChunk(List<String> retrieved, long chunkId) {
    String golden = goldenIdOf(chunkId);
    String bare = String.valueOf(chunkId);
    return retrieved.contains(golden) || retrieved.contains(bare);
  }

  // ---------- 装配 ----------

  /** 本次真跑实际使用的视觉模型（生产默认或 {@value #VISION_MODEL_PROP} 覆盖值）。 */
  private static String visionModel() {
    String override = System.getProperty(VISION_MODEL_PROP);
    return override == null || override.isBlank() ? DEFAULT_VISION_MODEL : override.strip();
  }

  private static ModelProviderProperties properties(String apiKey) {
    ModelProviderProperties properties = new ModelProviderProperties();
    properties.setApiKey(apiKey);
    properties.setBaseUrl(COMPATIBLE_BASE_URL);
    properties.setVisionModel(visionModel());
    properties.setTimeoutSeconds(60);
    properties.setConnectTimeoutSeconds(10);
    return properties;
  }

  /** 摄取配置矩阵：图片切片总开关 + 真视觉路开关 + 图片数帽（关掉查询改写，评测只看召回）。 */
  private static DynamicConfigService config() {
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put(PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.MAX_IMAGES_KEY, RealPdfAssets.MAX_IMAGES_PER_DOCUMENT);
    matrix.put("rag.retrieval.query-rewrite.enabled", Boolean.FALSE);
    return RagBenchmarkPipelineFactory.dynamicConfig(matrix);
  }

  /** 切片行替身：主键由调用方顺序分配（生产口径"自增主键即 chunkId"），其余字段与生产 createChunkEntity 同源。 */
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
    file.setStorageKey("real-pdf-source/" + asset.file());
    return file;
  }

  private static String documentIdOf(RealPdfAssets.Asset asset) {
    return "production-gray-" + asset.key();
  }

  /**
   * 文本集合记录：与 image 集合共享 {@code chunkId}/{@code imageAssetId}，引用 id 同为 {@code IMAGE:{行主键}}。
   *
   * <p>为什么测试侧手工装配而不是调生产 API：生产里这条记录由 {@code DocumentIngestionSupport#createVectorRecord}
   * 生成，那是文本切片的摄取实现； 本卡文本集合只是"把同一批描述文本重新编码一次"的对照路，不承担生产写入语义。
   */
  private static VectorRecord textRecord(SliceRow row, float[] vector) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", EVAL_KB_ID);
    metadata.put("category", "");
    metadata.put("filename", row.filename());
    metadata.put("fileType", "pdf");
    metadata.put("pageNo", (long) row.pageNo());
    metadata.put("rowIndex", 0L);
    metadata.put("chunkId", String.valueOf(row.chunkId()));
    metadata.put("sourceType", ImageSliceMetadata.SOURCE_TYPE_IMAGE);
    metadata.put("imageAssetId", String.valueOf(row.chunkId()));
    metadata.put("imageStorageKey", row.storageKey());
    return new VectorRecord(
        "production-gray-txtvec-" + row.chunkId(),
        "production-gray-" + row.assetKey(),
        String.valueOf(row.chunkId()),
        (int) row.chunkId(),
        row.description(),
        vector,
        metadata);
  }

  /** 生产检索链装配（6 参兼容构造 = 纯向量单路）；视觉路额外反射装上 image 集合。 */
  private static KnowledgeRetrievalPort retrievalService(
      EmbeddingService embeddingService,
      CrmVectorStore store,
      ImageVectorStore imageStore,
      KnowledgeBaseAuthorizationService authorization,
      ModelProvider provider,
      ObjectProvider<DynamicConfigService> configProvider) {
    KnowledgeRetrievalServiceImpl service =
        new KnowledgeRetrievalServiceImpl(
            authorization,
            embeddingService,
            store,
            new RetrievalQueryRewriteService(provider, configProvider),
            new Bm25Scorer(),
            configProvider);
    if (imageStore != null) {
      ReflectionTestUtils.setField(service, "imageVectorStore", imageStore);
    }
    return service;
  }

  /** 图片视觉路 / 文本路召回（生产 {@link KnowledgeRetrievalPort} 口径）。 */
  private static List<String> retrieve(
      KnowledgeRetrievalPort retrieval,
      String queryText,
      float[] queryVector,
      String intentCategory) {
    KnowledgeRetrievalPort.RetrievalResult result =
        retrieval.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(
                queryText, EVAL_USER_ID, List.of(EVAL_KB_ID), TOP_K, queryVector, intentCategory));
    return result.sources() == null
        ? List.of()
        : result.sources().stream().map(SourceReference::chunkId).toList();
  }

  /** 装配真实 Provider：DashScope 原生（chat/embed）+ compatible-mode（stream），同既有真跑 IT。 */
  private static ModelProvider provider(ModelProviderProperties properties, String apiKey) {
    DashScopeApi nativeApi = DashScopeApi.builder().apiKey(apiKey).build();
    ChatModel nativeChat = DashScopeChatModel.builder().dashScopeApi(nativeApi).build();
    EmbeddingModel embeddingModel = new DashScopeEmbeddingModel(nativeApi, MetadataMode.EMBED);
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.ai.dashscope.api-key", apiKey);
    environment.setProperty("spring.ai.dashscope.base-url", COMPATIBLE_BASE_URL);
    return new ModelProviderImpl(
        provider(nativeChat), provider(embeddingModel), properties, environment);
  }

  /** 只读 ObjectProvider 包装单值（null 值也照原样返回，供"未装配计量"的摄取构造用）。 */
  private static <T> ObjectProvider<T> provider(T value) {
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

  private static Path resolveOutPath() {
    String override = System.getProperty(OUT_PROP);
    return override == null || override.isBlank() ? DEFAULT_OUT_PATH : Path.of(override);
  }

  /** 余弦（与 {@code ImageCrossModalEvaluator} 同一实现，避免评测侧出现两套口径）。 */
  private static double cosine(float[] left, float[] right) {
    return ImageCrossModalEvaluator.cosine(left, right);
  }

  private static double ratio(long numerator, long denominator) {
    return denominator == 0 ? 0D : (double) numerator / (double) denominator;
  }

  /** token 段（每类外呼一组；未回流以 null 记，样本量见 callsWithUsage）。 */
  private static void putTokens(ObjectNode parent, String name, CallStats stats) {
    ObjectNode node = parent.putObject(name);
    node.put("calls", stats.calls.get());
    node.put("callsWithUsage", stats.usageCalls.get());
    node.put("promptTokens", stats.promptTokens());
    node.put("completionTokens", stats.completionTokens());
    node.put("totalTokens", stats.totalTokens());
  }

  /**
   * 程序化 PDF 对照基线（只读 {@code pdf-ingest-image-baseline-v1.json}，用于"真实 vs 程序化"成本差异观察）。
   *
   * <p>文件缺失时返回空节点（不失败）：对照是观察项，不是本卡判据——真实 PDF 成本基线的成立不依赖上一卡的落盘物在不在。
   */
  private static ObjectNode programmaticReference() {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("source", PROGRAMMATIC_REFERENCE.toString());
    if (!Files.exists(PROGRAMMATIC_REFERENCE)) {
      node.put("available", false);
      node.put("note", "上一卡基线文件不存在：跳过对照（不影响本卡基线成立）");
      return node;
    }
    try {
      JsonNode reference = MAPPER.readTree(Files.readString(PROGRAMMATIC_REFERENCE));
      JsonNode ingest = reference.path("pdfIngest");
      node.put("available", true);
      node.put("pdfBytes", ingest.path("pdfBytes").asInt());
      node.put("pages", ingest.path("pages").asInt());
      node.put("extractedImages", ingest.path("extractedImages").asInt());
      node.put("slicedImages", ingest.path("slicedImages").asInt());
      node.put("gateRejectedImages", ingest.path("gateRejectedImages").asInt());
      node.put("visionCallCalls", reference.path("visionCallCalls").asInt());
      node.put("visionEmbeddingCalls", reference.path("visionEmbeddingCalls").asInt());
      node.put("textEmbeddingCalls", reference.path("textEmbeddingCalls").asInt());
      node.put(
          "comparisonBasis",
          "同为「PDF 摄取链 → 真视觉/文本 embedding」真跑，唯一变量是语料：程序化 3 页 3 图（一页一图、lossless 位图）vs 真实政府出版物"
              + "（多页、内嵌图稀疏、含 <64px 装饰图与描述闸门剔除）");
    } catch (Exception exception) {
      node.put("available", false);
      node.put("note", "上一卡基线读取失败（不影响本卡基线成立）: " + exception.getClass().getSimpleName());
    }
    return node;
  }

  // ---------- 真调用点计数（对齐 PdfIngestImageBenchmarkIT 的 CountingProvider 形态） ----------

  /**
   * 真 Provider 的调用计数装饰器：只转发 + 计数（次数 / token / 耗时），不改请求与响应，不影响判分结论。
   *
   * <p><b>为什么必须在调用点计数</b>：VLM 描述发生在描述质量闸门<b>之前</b>（被剔除的图同样真调一次）；{@code embedImage} 只在摄取与每条查询各调一次；
   * 文本查询还会被检索链内部再编码一次。任何按 keys/cases 推导的公式都会错位，次数只能取在调用点。
   */
  private static final class CountingProvider implements ModelProvider {

    private final ModelProvider delegate;

    private final CallStats vision = new CallStats();

    private final CallStats embedImage = new CallStats();

    private final CallStats embed = new CallStats();

    CountingProvider(ModelProvider delegate) {
      this.delegate = delegate;
    }

    long totalTokens() {
      return vision.totalTokens() + embedImage.totalTokens() + embed.totalTokens();
    }

    long elapsedMs() {
      return vision.millis.get() + embedImage.millis.get() + embed.millis.get();
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
      long started = System.currentTimeMillis();
      try {
        ModelCallResult<float[]> result = delegate.embed(request);
        embed.add(result, System.currentTimeMillis() - started);
        return result;
      } catch (RuntimeException | Error failure) {
        embed.addFailure(System.currentTimeMillis() - started);
        throw failure;
      }
    }

    @Override
    public ModelCallResult<float[]> embedImage(byte[] imageBytes, String mimeType) {
      long started = System.currentTimeMillis();
      try {
        ModelCallResult<float[]> result = delegate.embedImage(imageBytes, mimeType);
        embedImage.add(result, System.currentTimeMillis() - started);
        return result;
      } catch (RuntimeException | Error failure) {
        embedImage.addFailure(System.currentTimeMillis() - started);
        throw failure;
      }
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      long started = System.currentTimeMillis();
      try {
        ModelCallResult<String> result = delegate.vision(prompt);
        vision.add(result, System.currentTimeMillis() - started);
        return result;
      } catch (RuntimeException | Error failure) {
        vision.addFailure(System.currentTimeMillis() - started);
        throw failure;
      }
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt, ModelCallOptions options) {
      long started = System.currentTimeMillis();
      try {
        ModelCallResult<String> result = delegate.vision(prompt, options);
        vision.add(result, System.currentTimeMillis() - started);
        return result;
      } catch (RuntimeException | Error failure) {
        vision.addFailure(System.currentTimeMillis() - started);
        throw failure;
      }
    }
  }

  /** 单类外呼的计数与计量（成功记录 token/耗时，失败只记次数与耗时，不掩盖异常）。 */
  private static final class CallStats {

    private final AtomicInteger calls = new AtomicInteger();

    private final AtomicInteger usageCalls = new AtomicInteger();

    private final AtomicLong promptTokens = new AtomicLong();

    private final AtomicLong completionTokens = new AtomicLong();

    private final AtomicLong totalTokens = new AtomicLong();

    private final AtomicLong millis = new AtomicLong();

    private final AtomicLong maxMillis = new AtomicLong();

    int calls() {
      return calls.get();
    }

    long promptTokens() {
      return promptTokens.get();
    }

    long completionTokens() {
      return completionTokens.get();
    }

    long totalTokens() {
      return totalTokens.get();
    }

    double averageMs() {
      return calls.get() == 0 ? 0D : (double) millis.get() / (double) calls.get();
    }

    long maxMs() {
      return maxMillis.get();
    }

    void add(ModelCallResult<?> result, long elapsed) {
      calls.incrementAndGet();
      millis.addAndGet(elapsed);
      maxMillis.accumulateAndGet(elapsed, Math::max);
      if (result != null && result.hasUsage()) {
        usageCalls.incrementAndGet();
      }
      if (result != null) {
        if (result.promptTokens() != null) {
          promptTokens.addAndGet(result.promptTokens());
        }
        if (result.completionTokens() != null) {
          completionTokens.addAndGet(result.completionTokens());
        }
        if (result.totalTokens() != null) {
          totalTokens.addAndGet(result.totalTokens());
        }
      }
    }

    void addFailure(long elapsed) {
      calls.incrementAndGet();
      millis.addAndGet(elapsed);
      maxMillis.accumulateAndGet(elapsed, Math::max);
    }
  }
}
