package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
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
import com.slz.crm.quality.ImageCrossModalEvaluator.Evaluation;
import com.slz.crm.quality.ImageCrossModalEvaluator.Probe;
import com.slz.crm.quality.ImageCrossModalSuite.ImageCase;
import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import com.slz.crm.quality.RagQualityReport.Report;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;

/**
 * PDF 摄取链图文召回质量真跑基准（pdf-ingest-image-eval 任务 2.1 / 2.2 / 3.2，failsafe IT，A 档真跑）。
 *
 * <p><b>门控</b>：{@code RAG_VISION_EMBED_REAL=1} 且存在 {@code DASHSCOPE_API_KEY}（env 或仓库根
 * .env）才执行；否则整类按假设跳过、 构建不失败——<b>默认 CI 与本地 verify 恒零外呼</b>。opt-in 键沿用 {@code VisionEmbeddingRealIT}
 * / {@code ImageCrossModalBenchmarkIT} 的同一枚（不新开键）。
 *
 * <p><b>为什么这条链值得单独评测</b>：上一卡（{@code image-cross-modal-eval}）用 6 张确定性 PNG 直接喂 {@code embedImage}
 * 证明"跨模态空间可分"；但那批向量是<b>旁路造出来的</b>，没经过生产摄取链。本卡从真 PDF 字节出发，逐段走生产实现：
 *
 * <ol>
 *   <li><b>真 PDF 解析</b>：{@link PdfImageSliceIngestService#buildSlices} 内部 {@code PdfImageExtractor}
 *       从页资源字典取 {@code PDImageXObject} → 解码 → PNG（三型语料各占一页，见 {@link PdfIngestImageFixture}）；
 *   <li><b>真视觉 embedding（opt-in）</b>：{@code ImageVisualVectorSupport#embedAndStage} 对同一张图调真 {@code
 *       embedImage} 暂存——与 VLM 描述切片（文本降维路）并行，两条路互不替代；
 *   <li><b>真落库口径</b>：{@link ImageVectorFlushSupport#flush} 把暂存向量按 storageKey 回填到切片行（{@code chunkId}
 *       = 生产切片行 主键），payload 走 {@code DocumentIngestionSupport#createVectorRecord} 单一实现 → image 集合；
 *   <li><b>query-by-image</b>：查询图重新真 {@code embedImage} 取查询向量，经生产 {@link
 *       KnowledgeRetrievalServiceImpl} 的图片视觉路 （{@code recallImage} → image 集合，过滤 {@code
 *       knowledgeBaseId} + {@code category=IMAGE}）召回；
 *   <li><b>判分</b>：复用上一卡 {@link ImageCrossModalEvaluator} 的余弦判据（同图余弦 ≥ 0.90 计命中、异图余弦 &lt; 0.90
 *       计拒答），并同时评一条<b>文本降维对照路</b>（同一批 VLM 描述文本 → 文本向量 → 文本集合），两路指标并列落盘 {@code
 *       docs/rag-quality/pdf-ingest-image-baseline-v1.json}。
 * </ol>
 *
 * <p><b>与既有基线的边界</b>：独立套件版本号 {@code PDF-IMAGE-SUITE-1.0}，独立落盘文件；<b>不碰</b> {@code
 * RagBenchmarkSuite}（72 例）、{@code SUITE_VERSION}、{@code Category.IMAGE}、{@code
 * image-baseline-v1.json} 与 baseline-v1~v6。
 *
 * <p><b>判据（跑之前固定）</b>：逐条「同图余弦 ≥ 阈值 ∧ 异图余弦 &lt; 阈值 ∧ 间隔 ≥ 0」必过；同图命中率与检索层同图召回率必须
 * 100%（查询图就是同一视觉内容的再次编码，做不到就是链路接错而不是模型能力）；视觉路的异图误召率不得高于文本降维路。
 */
@DisplayName("PDF 摄取链图文真跑（RAG_VISION_EMBED_REAL 显式 opt-in）")
class PdfIngestImageBenchmarkIT {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** compatible-mode 端点（与 {@code ImageCrossModalBenchmarkIT} 相同的装配方式）。 */
  private static final String COMPATIBLE_BASE_URL =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";

  /** 评测上下文用户 id（仅用于授权桩与 UserContextHolder，权限面不混入质量评测）。 */
  private static final long EVAL_USER_ID = 99004L;

  /** 授权桩返回的知识库 id（与 payload 的 {@code knowledgeBaseId} 同值）。 */
  private static final long EVAL_KB_ID_LONG = Long.parseLong(PdfIngestImageFixture.EVAL_KB_ID);

  /** 评测文档 id（切片行与向量记录的 {@code documentId}）。 */
  private static final String DOCUMENT_ID = "pdf-ingest-image-eval-doc";

  /** 默认落盘路径（独立基线，不碰 baseline-v1~v6 / image-baseline-v1）。 */
  private static final Path DEFAULT_REPORT_PATH =
      Path.of("docs", "rag-quality", "pdf-ingest-image-baseline-v1.json");

  /** 落盘路径覆盖属性（与既有基准同款形态）。 */
  private static final String OUT_PROP = "rag.pdf.image.benchmark.out";

  /**
   * 视觉模型覆盖属性（默认取生产默认 platform.ai.model.vision-model）。
   *
   * <p>为什么可覆盖：VLM 描述是这条链的必经步骤（生产 PdfImageSliceIngestService 要描述过质量闸门才落切片），而模型可用性属环境事实—— 本机实测生产默认
   * qwen-vl-plus 在该 key 上返回 model_not_found（同机既有 VisionPdfRealPilotIT 同症状），改用该 key 可用的同族 VL
   * 模型即可真跑。跑哪只模型必须落盘留痕（pdfIngest.visionModel），否则基线不可辩护。
   */
  private static final String VISION_MODEL_PROP = "rag.pdf.image.vision-model";

  /** 生产默认视觉模型（{@value #VISION_MODEL_PROP} 未给时使用）。 */
  private static final String DEFAULT_VISION_MODEL = "qwen-vl-plus";

  /** 每文档图片数帽：语料三张图一张不漏。 */
  private static final int MAX_IMAGES = PdfIngestImageFixture.keys().size();

  /** 落盘留痕：opt-in 与链路口径（复核判据直接读这一句）。 */
  private static final String OPT_IN_NOTE =
      "RAG_VISION_EMBED_REAL=1 + DASHSCOPE_API_KEY 显式真跑；语料 = 程序化确定性 PDF（3 页各 1 图：结构图 / 异图柱状图 / 纯白易负例），"
          + "走生产 PdfImageSliceIngestService#buildSlices（imageVectorEnabled 开）→ 真 embedImage 暂存 → ImageVectorFlushSupport 落 image 集合"
          + "→ 生产 recallImage query-by-image；文本对照路 = 同一批图由真 VLM 产出的描述文本经真文本 embedding 查文本集合";

  @Test
  @DisplayName("真跑：真 PDF 解析 → 真视觉 embedImage → image 集合 → query-by-image，量化两路增量")
  void runPdfIngestImageBenchmarkAndWriteBaseline() throws Exception {
    String apiKey = ImageCrossModalBenchmarkIT.resolveApiKey();
    ImageCrossModalBenchmarkIT.requireRealRun(System.getenv(), apiKey);

    ModelProviderProperties properties = new ModelProviderProperties();
    properties.setApiKey(apiKey);
    properties.setBaseUrl(COMPATIBLE_BASE_URL);
    properties.setVisionModel(visionModel());
    properties.setTimeoutSeconds(60);
    properties.setConnectTimeoutSeconds(10);
    // 计数装饰器包真 Provider：三计数（vision / embedImage / embed）在真实调用点累加，不做公式推导
    CountingProvider provider = new CountingProvider(provider(properties, apiKey));
    EmbeddingService embeddingService = new EmbeddingService(provider, properties);

    // 1) 真 PDF → buildSlices（真视觉路开、真 VLM 描述开、真 embedImage）→ 暂存视觉向量
    byte[] pdf = PdfIngestImageFixture.pdfBytes();
    PdfIngestImageFixture.InMemoryImageStorage storage = PdfIngestImageFixture.inMemoryStorage();
    PdfImageSliceIngestService ingestService =
        new PdfImageSliceIngestService(
            provider, provider(config()), provider((TokenUsageRecorder) null), provider(storage));
    List<DocumentChunk> slices =
        ingestService.buildSlices(
            pdf,
            PdfIngestImageFixture.FILENAME,
            PdfIngestImageFixture.IMAGE_CATEGORY,
            PdfIngestImageFixture.EVAL_KB_ID);
    assertTrue(
        slices.size() >= 2, "结构图与柱状图必须都进 image 集合（少于两张说明解析/暂存/落盘某段掉了图）；实测 " + slices.size() + " 张");
    // 页序 → 图键：pageNo 从 1 起，语料顺序即页序；被描述闸门剔除的图不在切片里，故按页号映射而不是按下标
    Map<String, DocumentChunk> sliceByKey = new LinkedHashMap<>();
    for (DocumentChunk slice : slices) {
      sliceByKey.put(PdfIngestImageFixture.keys().get(slice.pageNo() - 1), slice);
      assertTrue(
          ImageSliceMetadata.isImageSlice(slice.extraMetadata()),
          "图片描述切片必须带资产 storageKey（生产 flush 靠它回填视觉向量）");
    }
    Map<String, float[]> stagedByStorageKey = stagedVisionVectors();
    Map<String, float[]> imageVectors = bindVectorsByImageKey(slices, stagedByStorageKey);
    // 摄取段真实外呼：VLM 描述（闸门之前，每张提取图各一次）与 embedImage（仅过闸图各一次）
    int ingestVisionCalls = provider.visionCalls();
    int ingestImageEmbeds = provider.embedImageCalls();
    int extractedImages = extractedImageCount(pdf);
    assertEquals(extractedImages, ingestVisionCalls, "每张提取图都应真调一次 VLM 描述（描述调用先于质量闸门，被剔除的图也计数）");
    assertEquals(sliceByKey.size(), ingestImageEmbeds, "embedImage 只对过闸（进 image 集合）的图调用，次数应等于切片数");
    assertEquals(
        sliceByKey.size(),
        imageVectors.size(),
        "每张图都必须拿到真视觉向量（staged 为空说明 imageVectorEnabled 未生效）");

    // 2) 真落库口径：flush 把暂存向量回填到切片行 → image 集合
    List<DocumentVectorChunkEntity> children = childrenOf(slices);
    ImageVectorStore imageStore = new ImageVectorStore(new InMemoryVectorStore());
    int flushed = ImageVectorFlushSupport.flush(file(), children, slices, imageStore);
    assertEquals(children.size(), flushed, "切片行数与 image 集合写入条数必须一一对应");

    Map<String, String> goldenByKey = new LinkedHashMap<>();
    Map<String, String> storageKeyByKey = new LinkedHashMap<>();
    for (int index = 0; index < children.size(); index++) {
      String key = PdfIngestImageFixture.keys().get(slices.get(index).pageNo() - 1);
      assertEquals(
          PdfIngestImageFixture.chunkIdOf(key),
          String.valueOf(children.get(index).getId()),
          "黄金 id 必须由生产切片行主键派生（IMAGE:{行主键}），合成键会让命中判成零召回");
      goldenByKey.put(key, PdfIngestImageFixture.goldenId(key));
      storageKeyByKey.put(key, ImageSliceMetadata.storageKeyOf(slices.get(index).extraMetadata()));
    }
    System.out.println(
        "[pdf-image-benchmark] 摄取完成：pages="
            + PdfIngestImageFixture.keys().size()
            + " slices="
            + slices.size()
            + " flushed="
            + flushed
            + " objects="
            + storage.size());

    // 3) 文本降维对照路语料：同一批 VLM 描述文本 → 真文本向量 → 文本集合（payload 与 image 集合同 chunkId，黄金 id 才可比）
    InMemoryVectorStore textStore = new InMemoryVectorStore();
    Map<String, float[]> textVectors = new LinkedHashMap<>();
    Map<String, String> descriptionByKey = new LinkedHashMap<>();
    for (int index = 0; index < slices.size(); index++) {
      String key = PdfIngestImageFixture.keys().get(slices.get(index).pageNo() - 1);
      String description = slices.get(index).text();
      descriptionByKey.put(key, description);
      ModelCallResult<float[]> textual = embeddingService.embedWithUsage(description);
      textStore.upsert(
          textRecord(
              key, children.get(index), storageKeyByKey.get(key), description, textual.vector()));
      textVectors.put(key, textual.vector());
    }
    // 文本降维路语料段：真实文本 embedding 次数（逐条描述各一次）
    int descriptionTextEmbeds = provider.embedCalls();
    int describedImages = descriptionByKey.size();

    // 4) 生产检索链装配：视觉路装 image 集合（文本集合空，只取图片路候选）；文本对照路只有文本集合
    KnowledgeBaseAuthorizationService authorization =
        Mockito.mock(KnowledgeBaseAuthorizationService.class);
    Mockito.when(
            authorization.authorizedKnowledgeBaseIds(
                ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(List.of(EVAL_KB_ID_LONG));
    ObjectProvider<DynamicConfigService> configProvider = provider(config());
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

    // 5) 逐条 query-by-image + 文本对照查询（查询图/查询文本都是"同一视觉内容的再次编码"）
    // 只对"两图都真进了 image 集合"的用例判分：被描述质量闸门剔除的图在真链路上不产生向量，
    // 拿它当异图探针等于自造向量；被剔除的用例连同原因落盘留痕，不静默丢
    List<ImageCase> allCases = PdfIngestImageFixture.cases(descriptionByKey);
    List<ImageCase> cases =
        allCases.stream()
            .filter(
                imageCase ->
                    sliceByKey.containsKey(imageCase.sameImageKey())
                        && sliceByKey.containsKey(imageCase.crossImageKey()))
            .toList();
    assertTrue(cases.size() >= 2, "结构图 ↔ 柱状图 两条用例必须可判（少于 2 条说明链路掉了图）");
    UserContext evaluatorContext =
        new UserContext(EVAL_USER_ID, 5L, 101L, DataScopeLevel.ALL, "pdf-ingest-image-benchmark");
    UserContextHolder.set(evaluatorContext);
    Map<String, Probe> visionProbes = new LinkedHashMap<>();
    Map<String, Probe> textProbes = new LinkedHashMap<>();
    Map<String, List<String>> visionRetrieved = new LinkedHashMap<>();
    Map<String, Long> latencies = new LinkedHashMap<>();
    try {
      for (ImageCase imageCase : cases) {
        String queryKey = imageCase.queryImageKey();
        float[] queryVector =
            provider
                .embedImage(
                    PdfIngestImageFixture.image(queryKey).png(), PdfIngestImageFixture.IMAGE_MIME)
                .vector();
        long startedAt = System.currentTimeMillis();
        List<String> retrieved =
            retrieve(
                visionRetrieval,
                queryVector,
                descriptionByKey.get(queryKey),
                PdfIngestImageFixture.IMAGE_CATEGORY);
        latencies.put(imageCase.id(), System.currentTimeMillis() - startedAt);
        visionRetrieved.put(imageCase.id(), retrieved);
        String sameId = goldenByKey.get(imageCase.sameImageKey());
        String crossId = goldenByKey.get(imageCase.crossImageKey());
        visionProbes.put(
            imageCase.id(),
            new Probe(
                ImageCrossModalEvaluator.cosine(
                    queryVector, imageVectors.get(imageCase.sameImageKey())),
                ImageCrossModalEvaluator.cosine(
                    queryVector, imageVectors.get(imageCase.crossImageKey())),
                retrieved.contains(sameId),
                retrieved.contains(crossId)));

        float[] textQueryVector = embeddingService.embed(descriptionByKey.get(queryKey));
        List<String> textRetrieved =
            retrieve(textRetrieval, null, descriptionByKey.get(queryKey), null);
        textProbes.put(
            imageCase.id(),
            new Probe(
                ImageCrossModalEvaluator.cosine(
                    textQueryVector, textVectors.get(imageCase.sameImageKey())),
                ImageCrossModalEvaluator.cosine(
                    textQueryVector, textVectors.get(imageCase.crossImageKey())),
                textRetrieved.contains(sameId),
                textRetrieved.contains(crossId)));
        System.out.println(
            "[pdf-image-benchmark] "
                + imageCase.id()
                + " visionRetrieved="
                + retrieved
                + " sameCos="
                + visionProbes.get(imageCase.id()).sameImageCosine()
                + " crossCos="
                + visionProbes.get(imageCase.id()).crossImageCosine()
                + " textSameCos="
                + textProbes.get(imageCase.id()).sameImageCosine()
                + " textCrossCos="
                + textProbes.get(imageCase.id()).crossImageCosine());
      }
    } finally {
      UserContextHolder.clear();
    }
    // 查询段真实外呼：query-by-image 每条用例一次；文本查询含检索链内部再编码（实测，不推导）
    int queryImageEmbeds = provider.embedImageCalls() - ingestImageEmbeds;
    int queryTextEmbeds = provider.embedCalls() - descriptionTextEmbeds;

    // 6) 判分：视觉路 + 文本降维路同口径（复用 ImageCrossModalEvaluator），再出独立版本的 Report
    Evaluation vision =
        ImageCrossModalEvaluator.evaluate(
            "pdf-vision-query-by-image", cases, imageCase -> visionProbes.get(imageCase.id()));
    Evaluation textBaseline =
        ImageCrossModalEvaluator.evaluate(
            "pdf-text-caption-baseline", cases, imageCase -> textProbes.get(imageCase.id()));
    Report rawReport =
        RagQualityEvaluator.evaluate(
            benchmarkCases(cases),
            benchmarkCase -> caseOutcome(benchmarkCase, visionRetrieved, latencies),
            ImageCrossModalSuite.TOP_K);
    Report report =
        new Report(
            PdfIngestImageFixture.SUITE_VERSION,
            rawReport.generatedAt(),
            rawReport.metrics(),
            rawReport.cases());
    assertEquals(cases.size(), report.metrics().caseCount(), "每条用例都必须有评分");
    System.out.println("[pdf-image-benchmark] vision metrics=" + vision.metrics());
    System.out.println("[pdf-image-benchmark] text-baseline metrics=" + textBaseline.metrics());

    // 7) 判据断言（跑之前固定，不按结果调参）
    for (ImageCrossModalEvaluator.CaseJudgement judgement : vision.cases()) {
      assertTrue(
          judgement.pass(),
          "真视觉判据未过: "
              + judgement.id()
              + " sameCosine="
              + judgement.sameImageCosine()
              + " crossCosine="
              + judgement.crossImageCosine());
    }
    assertEquals(1D, vision.metrics().sameImageHitRate(), "查询图就是同一视觉内容的再次编码，同图命中率必须是 100%");
    assertEquals(1D, vision.metrics().retrievalRecallAtK(), "同图切片必须全部进 topK（image 集合装配或过滤键有问题）");
    assertEquals(1D, vision.metrics().passRate(), "逐条判据必须全过（任何一条不过都是链路/语料问题）");
    assertTrue(
        vision.metrics().crossImageFalsePositiveRate()
            <= textBaseline.metrics().crossImageFalsePositiveRate(),
        "真视觉路的异图误召率高于文本降维路：真视觉把这个语料分得比描述文本还差，增量价值不成立");

    // 8) 独立基线落盘（suiteVersion=PDF-IMAGE-SUITE-1.0；不碰 baseline-v1~v6 与 image-baseline-v1）
    ObjectNode root =
        PdfIngestImageBaselineReport.toJson(
            report, vision, textBaseline, cases, goldenByKey, OPT_IN_NOTE);
    root.putObject("pdfIngest")
        .put("filename", PdfIngestImageFixture.FILENAME)
        .put("pdfBytes", pdf.length)
        .put("pages", PdfIngestImageFixture.keys().size())
        .put("extractedImages", extractedImages)
        .put("slicedImages", slices.size())
        .put("gateRejectedImages", extractedImages - slices.size())
        .put("stagedVisionVectors", stagedByStorageKey.size())
        .put("ingestImageEmbeds", ingestImageEmbeds)
        .put("queryImageEmbeds", queryImageEmbeds)
        .put("descriptionTextEmbeds", descriptionTextEmbeds)
        .put("queryTextEmbeds", queryTextEmbeds)
        .put("flushedImageVectors", flushed)
        .put("imageObjectsStored", storage.size())
        .put("corpusFingerprint", PdfIngestImageFixture.corpusFingerprint())
        .put(
            "chain",
            "PdfImageExtractor → PdfImageSliceIngestService#buildSlices(imageVectorEnabled) → "
                + "ImageVisualVectorSupport#embedAndStage → ImageVectorFlushSupport#flush → "
                + "image 集合 → KnowledgeRetrievalServiceImpl#recallImage");
    ObjectNode ingestNode = (ObjectNode) root.get("pdfIngest");
    ingestNode.put("ingestedImageKeys", String.join(",", descriptionByKey.keySet()));
    ingestNode.put("visionModel", visionModel());
    ArrayNode skippedCases = root.putArray("skippedCases");
    for (ImageCase imageCase : allCases) {
      if (!cases.contains(imageCase)) {
        ObjectNode skipped = skippedCases.addObject();
        skipped.put("id", imageCase.id());
        skipped.put("queryImageKey", imageCase.queryImageKey());
        skipped.put("crossImageKey", imageCase.crossImageKey());
        skipped.put(
            "reason",
            "该图未进 image 集合（生产描述质量闸门剔除：描述不足 40 字或未过闸门），真链路上没有它的向量，" + "拿它当异图探针等于自造向量，故本条不判分");
      }
    }
    root.put("visionEmbeddingCalls", provider.embedImageCalls());
    root.put("textEmbeddingCalls", provider.embedCalls());
    root.put("visionCallCalls", provider.visionCalls());
    root.put(
        "callCountSemantics",
        "三计数由 CountingProvider 在真调用点累加，均非公式推导：visionCallCalls = VLM 描述外呼次数（含被质量闸门剔除的图——闸门在调用之后，"
            + "纯白图也真调一次）；visionEmbeddingCalls = embedImage 次数 = 摄取侧暂存 "
            + ingestImageEmbeds
            + " + 查询侧 query-by-image "
            + queryImageEmbeds
            + "；textEmbeddingCalls = 文本 embed 次数 = 描述编码 "
            + descriptionTextEmbeds
            + " + 文本查询 "
            + queryTextEmbeds
            + "（含检索链内部再编码）");
    root.put(
        "usageNote",
        "视觉 embedding 与 VLM 描述端点均不回流 token usage，故 totalTokens 恒 0（同 VisionEmbeddingRealIT 实测）；成本以调用次数留痕");
    root.put("measuredAt", Instant.now().toString());
    Path outPath = resolveOutPath();
    Files.createDirectories(outPath.toAbsolutePath().getParent());
    Files.writeString(outPath, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
    System.out.println(
        "[pdf-image-benchmark] pdf-ingest-image-baseline 已落盘: " + outPath.toAbsolutePath());
  }

  /**
   * 读取摄取链暂存视觉向量（{@code ImageVisualVectorSupport#staged()}，存取侧包内可见故用反射）。
   *
   * <p>为什么必须读暂存而不是重算：flush 消费后才把向量写进 image 集合，重算会引入"第二次编码"的额外外呼，且拿到的不是<b>落库那条</b> 向量——评测要判的就是落库那条。
   *
   * @return storageKey → 真视觉向量（按摄取顺序）
   */
  @SuppressWarnings("unchecked")
  private static Map<String, float[]> stagedVisionVectors() {
    try {
      Class<?> support = Class.forName("com.slz.crm.knowledge.document.ImageVisualVectorSupport");
      Method staged = support.getDeclaredMethod("staged");
      staged.setAccessible(true);
      return new LinkedHashMap<>((Map<String, float[]>) staged.invoke(null));
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("读取摄取链暂存视觉向量失败（内部契约变动？）", exception);
    }
  }

  /**
   * 生产提取器真实提取到的图数（{@code PdfImageExtractor#extract} 包内可见，故反射调用一次）。
   *
   * <p>为什么单独测一次：切片数 ≠ 提取图数（描述闸门会剔除图），只有提取器返回列表的长度才是"提取图数"。这次调用纯本地解码，零外呼， 不影响已完成的摄取与暂存向量。
   *
   * @param pdf 评测 PDF 字节
   * @return 提取图数
   */
  private static int extractedImageCount(byte[] pdf) {
    try {
      Class<?> extractor = Class.forName("com.slz.crm.knowledge.document.PdfImageExtractor");
      Method extract = extractor.getDeclaredMethod("extract", byte[].class, int.class);
      extract.setAccessible(true);
      return ((List<?>) extract.invoke(null, pdf, MAX_IMAGES)).size();
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("读取生产提取图数失败（内部契约变动？）", exception);
    }
  }

  /** storageKey → 图键：按切片顺序把暂存向量重新贴回语料键。 */
  private static Map<String, float[]> bindVectorsByImageKey(
      List<DocumentChunk> slices, Map<String, float[]> stagedByStorageKey) {
    Map<String, float[]> vectors = new LinkedHashMap<>();
    for (int index = 0; index < slices.size(); index++) {
      String storageKey = ImageSliceMetadata.storageKeyOf(slices.get(index).extraMetadata());
      float[] vector = stagedByStorageKey.get(storageKey);
      if (vector != null && vector.length > 0) {
        vectors.put(PdfIngestImageFixture.keys().get(slices.get(index).pageNo() - 1), vector);
      }
    }
    return vectors;
  }

  /** 切片行替身：主键取自 fixture（生产口径"自增主键即 chunkId"），其余字段与生产 createChunkEntity 同源。 */
  private static List<DocumentVectorChunkEntity> childrenOf(List<DocumentChunk> slices) {
    List<DocumentVectorChunkEntity> children = new ArrayList<>(slices.size());
    for (int index = 0; index < slices.size(); index++) {
      DocumentChunk chunk = slices.get(index);
      DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
      entity.setId(
          Long.valueOf(
              PdfIngestImageFixture.chunkIdOf(
                  PdfIngestImageFixture.keys().get(slices.get(index).pageNo() - 1))));
      entity.setDocumentId(DOCUMENT_ID);
      entity.setChunkIndex(index);
      entity.setChunkText(chunk.text());
      entity.setCategory(chunk.category());
      entity.setFilename(PdfIngestImageFixture.FILENAME);
      entity.setPageNo(chunk.pageNo());
      entity.setRowIndex(chunk.rowIndex());
      entity.setChunkRole("CHILD");
      entity.setExtraMetadataJson(ImageSliceMetadata.toJson(chunk.extraMetadata()));
      children.add(entity);
    }
    return children;
  }

  /** 评测文档实体（payload 口径：knowledgeBaseId / 文件名 / 类型）。 */
  private static UploadedFileEntity file() {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setDocumentId(DOCUMENT_ID);
    file.setOriginalFilename(PdfIngestImageFixture.FILENAME);
    file.setFilename(PdfIngestImageFixture.FILENAME);
    file.setFileType("pdf");
    file.setKnowledgeBase(PdfIngestImageFixture.EVAL_KB_ID);
    file.setStorageKey("pdf-source/" + PdfIngestImageFixture.FILENAME);
    return file;
  }

  /**
   * 文本降维对照集合记录：与 image 集合共享 {@code chunkId}/{@code imageAssetId}，引用 id 同为 {@code IMAGE:{行主键}}。
   *
   * <p>为什么测试侧手工装配而不是调生产 API：生产里这条记录由 {@code DocumentIngestionSupport#createVectorRecord}
   * 生成，那是摄取文本切片的实现，本卡的对照路只是"把同一批描述文本重新编码一次"，不承担生产写入语义。
   */
  private static VectorRecord textRecord(
      String key,
      DocumentVectorChunkEntity child,
      String storageKey,
      String description,
      float[] vector) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", PdfIngestImageFixture.EVAL_KB_ID);
    metadata.put("category", "");
    metadata.put("filename", PdfIngestImageFixture.FILENAME);
    metadata.put("fileType", "pdf");
    metadata.put("pageNo", child.getPageNo() == null ? 0L : child.getPageNo().longValue());
    metadata.put("rowIndex", 0L);
    metadata.put("chunkId", String.valueOf(child.getId()));
    metadata.put("sourceType", ImageSliceMetadata.SOURCE_TYPE_IMAGE);
    metadata.put("imageAssetId", String.valueOf(child.getId()));
    metadata.put("imageStorageKey", storageKey);
    return new VectorRecord(
        "pdf-txtvec-" + key,
        DOCUMENT_ID,
        String.valueOf(child.getId()),
        child.getChunkIndex(),
        description,
        vector,
        metadata);
  }

  /** 摄取配置矩阵：图片切片总开关 + 真视觉路开关 + 图片数帽（关掉查询改写，评测只看召回）。 */
  private static DynamicConfigService config() {
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put(PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.MAX_IMAGES_KEY, MAX_IMAGES);
    matrix.put("rag.retrieval.query-rewrite.enabled", Boolean.FALSE);
    return RagBenchmarkPipelineFactory.dynamicConfig(matrix);
  }

  /** 图片视觉路 / 文本路召回（生产 {@link KnowledgeRetrievalPort} 口径）。 */
  private List<String> retrieve(
      KnowledgeRetrievalPort retrieval,
      float[] queryVector,
      String queryText,
      String intentCategory) {
    KnowledgeRetrievalPort.RetrievalResult result =
        retrieval.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(
                queryText,
                EVAL_USER_ID,
                List.of(PdfIngestImageFixture.EVAL_KB_ID),
                ImageCrossModalSuite.TOP_K,
                queryVector,
                intentCategory));
    return result.sources() == null
        ? List.of()
        : result.sources().stream().map(SourceReference::chunkId).toList();
  }

  /** 单条用例转评测产出（纯检索判分：无引用、中立答案覆盖度）。 */
  private static CaseOutcome caseOutcome(
      RagBenchmarkCase benchmarkCase,
      Map<String, List<String>> retrievedByCase,
      Map<String, Long> latencies) {
    List<String> retrieved = retrievedByCase.getOrDefault(benchmarkCase.id(), List.of());
    long elapsed = latencies.getOrDefault(benchmarkCase.id(), 0L);
    return new CaseOutcome(retrieved, List.of(), 1.0D, elapsed, elapsed, 0, true);
  }

  /** 用例投影成主套件同型用例（复用 {@link RagQualityEvaluator} 的 recall/MRR/hit 判卷口径）。 */
  private static List<RagBenchmarkCase> benchmarkCases(List<ImageCase> cases) {
    return cases.stream()
        .map(
            imageCase ->
                new RagBenchmarkCase(
                    imageCase.id(),
                    RagBenchmarkCase.Category.IMAGE,
                    imageCase.queryText(),
                    imageCase.expectedChunkIds(),
                    List.of(),
                    true))
        .toList();
  }

  /** 生产检索链装配（6 参兼容构造 = 纯向量单路）；视觉路额外反射装上 image 集合。 */
  private KnowledgeRetrievalPort retrievalService(
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

  /**
   * 装配真实 Provider：DashScope 原生（chat/embed）+ compatible-mode（stream），同 ImageCrossModalBenchmarkIT。
   */
  private ModelProvider provider(ModelProviderProperties properties, String apiKey) {
    DashScopeApi nativeApi = DashScopeApi.builder().apiKey(apiKey).build();
    ChatModel nativeChat = DashScopeChatModel.builder().dashScopeApi(nativeApi).build();
    EmbeddingModel embeddingModel = new DashScopeEmbeddingModel(nativeApi, MetadataMode.EMBED);
    MockEnvironment environment = new MockEnvironment();
    environment.setProperty("spring.ai.dashscope.api-key", apiKey);
    environment.setProperty("spring.ai.dashscope.base-url", COMPATIBLE_BASE_URL);
    return new ModelProviderImpl(
        provider(nativeChat), provider(embeddingModel), properties, environment);
  }

  /** 只读 ObjectProvider 包装单值（null 值也照原样返回，供"未装配计量/存储"的摄取构造用）。 */
  private static <T> ObjectProvider<T> provider(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        if (value == null) {
          throw new NoSuchBeanDefinitionException("评测替身未提供该 bean");
        }
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

  /** 本次真跑实际使用的视觉模型（生产默认或 {@value #VISION_MODEL_PROP} 覆盖值）。 */
  private static String visionModel() {
    String override = System.getProperty(VISION_MODEL_PROP);
    return override == null || override.isBlank() ? DEFAULT_VISION_MODEL : override.strip();
  }

  private Path resolveOutPath() {
    String override = System.getProperty(OUT_PROP);
    return override == null || override.isBlank() ? DEFAULT_REPORT_PATH : Path.of(override);
  }

  /**
   * 真 Provider 的调用计数装饰器（复核必修②）：只转发 + 计数，不改请求/响应，不影响判分结论。
   *
   * <p><b>为什么必须在这里计数而不是按语料/用例推导</b>：VLM 描述调用发生在质量闸门<b>之前</b>——纯白图同样真调一次，只是结果被闸门 丢掉（故
   * visionCallCalls 可能大于切片数）；embedImage 只在过闸图与每条查询图各调一次；文本查询还会被检索链内部再编码一次。 次数只能在调用点取，任何 keys/cases
   * 公式都会错位。
   */
  private static final class CountingProvider implements ModelProvider {

    private final ModelProvider delegate;

    private final AtomicInteger visionCalls = new AtomicInteger();

    private final AtomicInteger embedImageCalls = new AtomicInteger();

    private final AtomicInteger embedCalls = new AtomicInteger();

    CountingProvider(ModelProvider delegate) {
      this.delegate = delegate;
    }

    int visionCalls() {
      return visionCalls.get();
    }

    int embedImageCalls() {
      return embedImageCalls.get();
    }

    int embedCalls() {
      return embedCalls.get();
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
      return delegate.embed(request);
    }

    @Override
    public ModelCallResult<float[]> embedImage(byte[] imageBytes, String mimeType) {
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
}
