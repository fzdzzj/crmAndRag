package com.slz.crm.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.embedding.EmbeddingService;
import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import com.slz.crm.knowledge.retrieval.KnowledgeRetrievalServiceImpl;
import com.slz.crm.knowledge.retrieval.RetrievalQueryRewriteService;
import com.slz.crm.knowledge.vector.ImageVectorStore;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.quality.ImageCrossModalEvaluator.Evaluation;
import com.slz.crm.quality.ImageCrossModalEvaluator.Probe;
import com.slz.crm.quality.ImageCrossModalSuite.ImageCase;
import com.slz.crm.quality.RagQualityReport.CaseOutcome;
import com.slz.crm.quality.RagQualityReport.Report;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 图文跨模态召回质量真跑基准（image-cross-modal-eval 任务 2.1 / 2.2 / 3.2，failsafe IT，A 档真跑）。
 *
 * <p><b>门控</b>：{@code RAG_VISION_EMBED_REAL=1} 且存在 {@code DASHSCOPE_API_KEY}（env 或仓库根
 * .env）才执行；否则整类按 假设跳过、构建不失败——<b>默认 CI 与本地 verify 恒零外呼</b>（口径沿用 {@code VisionEmbeddingRealIT} 的
 * opt-in 键，不新开键）。
 *
 * <p><b>执行内容</b>（每步都是真链路，不是桩）：
 *
 * <ol>
 *   <li><b>fixture 幂等入库</b>：{@code ImageCrossModalFixture} 的 6 张确定性图 → 真视觉 embedding（{@link
 *       ModelProvider#embedImage}）→ 独立 {@link ImageVectorStore}（image 集合口径，payload 对齐生产 {@code
 *       ImageVectorFlushSupport}）；同一批图的描述文本 → 真文本 embedding → 文本集合（文本降维对照路）。向量点 id 由图像键派生， 重复
 *       upsert 覆盖不叠加（幂等）。
 *   <li><b>query-by-image</b>：逐条用例对查询图重新 {@code embedImage} 取查询向量，经生产 {@link
 *       KnowledgeRetrievalServiceImpl}（装配 {@code imageVectorStore}）的图片视觉路召回（{@code recallImage} →
 *       image 集合， 过滤 {@code knowledgeBaseId} + {@code category=IMAGE}）。
 *   <li><b>余弦判别判分</b>：查询向量 vs 同图向量 / 异图向量的余弦，按 {@link ImageCrossModalEvaluator} 的阈值口径判「同图命中 /
 *       异图拒答」，并汇总同图召回率、异图误召率、判别间隔。
 *   <li><b>文本降维对照</b>：同一批图的描述文本向量在文本集合里做同一套查询与判分，两路指标并列写进同一份 JSON（增量价值的量化形式）。
 *   <li><b>落盘</b>：{@link Report}（{@code suiteVersion=IMAGE-SUITE-1.0}，独立版本号）写 {@code
 *       docs/rag-quality/image-baseline-v1.json}；可用 {@code -Drag.image.benchmark.out} 覆盖路径。绝不触碰
 *       baseline-v1~v6。
 * </ol>
 *
 * <p><b>语料与判据的可辩护性</b>：异图由"描述文本骨架相同、视觉跨图型"设计（见 fixture 类注释），在跑之前固定，不按结果调参；易负例（纯白底图）
 * 作地板对照，证明判据不是"把任何图都判异"。真视觉若退化为文本降维（或返回常量向量），同图/异图余弦会同时被拉平 → 逐条 {@code pass}
 * 与"判别间隔显著大于文本降维路"两条断言必红。
 */
@DisplayName("图文跨模态召回真跑（RAG_VISION_EMBED_REAL 显式 opt-in）")
class ImageCrossModalBenchmarkIT {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  /** 真跑 opt-in 键（沿用 multimodal-shared-space 的同一枚键，不新增）。 */
  static final String OPT_IN_ENV = "RAG_VISION_EMBED_REAL";

  /** compatible-mode 端点（与 {@code CustomerRetrievalBenchmarkIT} 相同的装配方式）。 */
  private static final String COMPATIBLE_BASE_URL =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";

  /** 评测上下文用户 id（仅用于授权桩与 UserContextHolder，权限面不混入质量评测）。 */
  private static final long EVAL_USER_ID = 99003L;

  /** 授权桩返回的知识库 id（与 fixture payload 的 {@code knowledgeBaseId} 同值）。 */
  private static final long EVAL_KB_ID_LONG = Long.parseLong(ImageCrossModalFixture.EVAL_KB_ID);

  /** 默认落盘路径（独立基线，不碰 baseline-v1~v6）。 */
  private static final Path DEFAULT_REPORT_PATH =
      Path.of("docs", "rag-quality", "image-baseline-v1.json");

  /** 落盘路径覆盖属性（与 customer 基准同款形态）。 */
  private static final String OUT_PROP = "rag.image.benchmark.out";

  /** 落盘留痕：opt-in 与语料口径（复核判据直接读这一句）。 */
  private static final String OPT_IN_NOTE =
      "RAG_VISION_EMBED_REAL=1 + DASHSCOPE_API_KEY 显式真跑；语料 = 6 张确定性程序化图像（4 内容图 + 1 看板 + 1 纯白易负例），"
          + "查询图经真视觉 embedding（multimodal-embedding-v1）query-by-image 查独立 image 集合；"
          + "文本对照路 = 同一批图的描述文本（VLM 描述的确定性替身）经真文本 embedding 查文本集合";

  @Test
  @DisplayName("真跑：同图余弦命中 / 异图余弦拒答，并量化真视觉 vs 文本降维的增量")
  void runImageCrossModalBenchmarkAndWriteBaseline() throws Exception {
    requireRealRun(System.getenv(), resolveApiKey());
    String apiKey = resolveApiKey();
    List<ImageCase> cases = ImageCrossModalSuite.cases();

    ModelProviderProperties properties = new ModelProviderProperties();
    properties.setApiKey(apiKey);
    properties.setBaseUrl(COMPATIBLE_BASE_URL);
    properties.setTimeoutSeconds(60);
    properties.setConnectTimeoutSeconds(10);
    ModelProvider provider = provider(properties, apiKey);
    EmbeddingService embeddingService = new EmbeddingService(provider, properties);

    // 1) fixture 幂等入库：真视觉向量进 image 集合，描述文本真文本向量进文本集合
    ImageVectorStore imageStore = new ImageVectorStore(new InMemoryVectorStore());
    InMemoryVectorStore textStore = new InMemoryVectorStore();
    Map<String, float[]> imageVectors = new LinkedHashMap<>();
    Map<String, float[]> textVectors = new LinkedHashMap<>();
    long visionTokens = 0L;
    long textTokens = 0L;
    for (ImageCrossModalFixture.FixtureImage image : ImageCrossModalFixture.images()) {
      ModelCallResult<float[]> visual =
          provider.embedImage(image.png(), ImageCrossModalFixture.IMAGE_MIME);
      imageStore.upsert(ImageCrossModalFixture.imageRecord(image, visual.vector()));
      imageVectors.put(image.key(), visual.vector());
      visionTokens += tokenOf(visual);
      ModelCallResult<float[]> textual = embeddingService.embedWithUsage(image.caption());
      textStore.upsert(ImageCrossModalFixture.textRecord(image, textual.vector()));
      textVectors.put(image.key(), textual.vector());
      textTokens += tokenOf(textual);
    }
    System.out.println(
        "[image-benchmark] fixture 入库完成：images="
            + ImageCrossModalFixture.images().size()
            + " visionTokens="
            + visionTokens
            + " textTokens="
            + textTokens);

    // 2) 生产检索链装配：视觉路装 imageVectorStore、文本集合空（只取图片路候选）；文本对照路只有文本集合
    KnowledgeBaseAuthorizationService authorization =
        Mockito.mock(KnowledgeBaseAuthorizationService.class);
    Mockito.when(
            authorization.authorizedKnowledgeBaseIds(
                ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(List.of(EVAL_KB_ID_LONG));
    DynamicConfigService config =
        RagBenchmarkPipelineFactory.dynamicConfig(
            Map.of("rag.retrieval.query-rewrite.enabled", false));
    ObjectProvider<DynamicConfigService> configProvider =
        RagBenchmarkPipelineFactory.provider(config);
    InMemoryVectorStore emptyTextSpace = new InMemoryVectorStore();
    KnowledgeRetrievalPort visionRetrieval =
        retrievalService(
            embeddingService, emptyTextSpace, imageStore, authorization, provider, configProvider);
    KnowledgeRetrievalPort textRetrieval =
        retrievalService(
            embeddingService, textStore, null, authorization, provider, configProvider);

    UserContext evaluatorContext =
        new UserContext(EVAL_USER_ID, 5L, 101L, DataScopeLevel.ALL, "image-cross-modal-benchmark");
    UserContextHolder.set(evaluatorContext);
    Map<String, Probe> visionProbes = new LinkedHashMap<>();
    Map<String, Probe> textProbes = new LinkedHashMap<>();
    Map<String, List<String>> visionRetrieved = new LinkedHashMap<>();
    Map<String, Long> latencies = new LinkedHashMap<>();
    try {
      for (ImageCase imageCase : cases) {
        String queryKey = imageCase.queryImageKey();
        // query-by-image：查询图重新编码取查询向量（生产同口径：查询侧只送图，不拼文本）
        float[] queryVector =
            provider
                .embedImage(
                    ImageCrossModalFixture.image(queryKey).png(), ImageCrossModalFixture.IMAGE_MIME)
                .vector();
        long startedAt = System.currentTimeMillis();
        List<String> retrieved = retrieveByImage(visionRetrieval, imageCase, queryVector);
        latencies.put(imageCase.id(), System.currentTimeMillis() - startedAt);
        visionRetrieved.put(imageCase.id(), retrieved);
        // 黄金 id 用生产引用口径 IMAGE:{资产 id}（SourceReferenceFactory），与检索回传的 sources 逐字节可比
        String sameChunkId = ImageCrossModalFixture.sourceChunkId(imageCase.sameImageKey());
        String crossChunkId = ImageCrossModalFixture.sourceChunkId(imageCase.crossImageKey());
        visionProbes.put(
            imageCase.id(),
            new Probe(
                ImageCrossModalEvaluator.cosine(
                    queryVector, imageVectors.get(imageCase.sameImageKey())),
                ImageCrossModalEvaluator.cosine(
                    queryVector, imageVectors.get(imageCase.crossImageKey())),
                retrieved.contains(sameChunkId),
                retrieved.contains(crossChunkId)));
        System.out.println(
            "[image-benchmark] "
                + imageCase.id()
                + " visionRetrieved="
                + retrieved
                + " sameCos="
                + visionProbes.get(imageCase.id()).sameImageCosine()
                + " crossCos="
                + visionProbes.get(imageCase.id()).crossImageCosine());

        // 文本降维对照路：查询文本 = 该图描述文本（同一空间里的同型查询与判分）
        float[] textQueryVector = embeddingService.embed(imageCase.queryText());
        List<String> textRetrieved = retrieveByText(textRetrieval, imageCase);
        textProbes.put(
            imageCase.id(),
            new Probe(
                ImageCrossModalEvaluator.cosine(
                    textQueryVector, textVectors.get(imageCase.sameImageKey())),
                ImageCrossModalEvaluator.cosine(
                    textQueryVector, textVectors.get(imageCase.crossImageKey())),
                textRetrieved.contains(sameChunkId),
                textRetrieved.contains(crossChunkId)));
      }
    } finally {
      UserContextHolder.clear();
    }

    // 3) 判分：真视觉路 + 文本降维路同口径，再复用 RagQualityEvaluator 出独立版本的 Report
    Evaluation vision =
        ImageCrossModalEvaluator.evaluate(
            "vision-query-by-image", cases, imageCase -> visionProbes.get(imageCase.id()));
    Evaluation textBaseline =
        ImageCrossModalEvaluator.evaluate(
            "text-caption-baseline", cases, imageCase -> textProbes.get(imageCase.id()));
    Report rawReport =
        RagQualityEvaluator.evaluate(
            ImageCrossModalSuite.benchmarkCases(),
            benchmarkCase -> caseOutcome(benchmarkCase, visionRetrieved, latencies),
            ImageCrossModalSuite.TOP_K);
    Report report =
        new Report(
            ImageCrossModalSuite.SUITE_VERSION,
            rawReport.generatedAt(),
            rawReport.metrics(),
            rawReport.cases());
    assertEquals(cases.size(), report.metrics().caseCount(), "每条图文用例都必须有评分");
    assertTrue(report.metrics().hitRate() > 0D, "整轮零命中说明视觉路召回或 image 集合装配有问题");

    System.out.println("[image-benchmark] vision metrics=" + vision.metrics());
    System.out.println("[image-benchmark] text-baseline metrics=" + textBaseline.metrics());

    // 4) 判据断言（A 档真跑判据）：逐条同图命中 + 异图拒答 + 间隔非负；整体判别力不低于文本降维路
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
    assertTrue(
        vision.metrics().meanDiscriminationMargin()
            >= textBaseline.metrics().meanDiscriminationMargin(),
        "真视觉路的判别间隔低于文本降维路（同图/异图分不开），增量价值不成立");

    // 5) 独立基线落盘（suiteVersion=IMAGE-SUITE-1.0；不碰 baseline-v1~v6）
    Path outPath = resolveOutPath();
    ObjectNode root = ImageCrossModalEvaluator.toJson(report, vision, textBaseline, OPT_IN_NOTE);
    root.put("corpusSize", ImageCrossModalFixture.images().size());
    root.put("visionEmbeddingTotalTokens", visionTokens);
    root.put("textEmbeddingTotalTokens", textTokens);
    // 外呼口径留痕：显式外呼次数可数（语料入库 + 逐条查询），检索链内部的文本 embed 由生产实现自己发，不计入本计数
    root.put("visionEmbeddingCalls", ImageCrossModalFixture.images().size() + cases.size());
    root.put(
        "textEmbeddingCalls", ImageCrossModalFixture.images().size() + cases.size() + cases.size());
    root.put(
        "usageNote",
        "视觉/文本 embedding 端点均不回流 token usage，故 totalTokens 恒 0（同 VisionEmbeddingRealIT 实测）；成本以调用次数留痕");
    root.put("measuredAt", Instant.now().toString());
    Files.createDirectories(outPath.toAbsolutePath().getParent());
    Files.writeString(outPath, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
    System.out.println("[image-benchmark] image-baseline 已落盘: " + outPath.toAbsolutePath());
  }

  /**
   * 门控：未 opt-in 或未配 key 时整类跳过（默认零外呼）。
   *
   * <p>抽成静态方法是为了让单测能直接验"无 key 跳过"而不触发任何外呼（任务 4.1）。
   *
   * @param env 环境变量视图（真跑传 {@code System.getenv()}）
   * @param apiKey 解析后的 key（env 或 .env）
   */
  static void requireRealRun(Map<String, String> env, String apiKey) {
    Assumptions.assumeTrue(
        "1".equals(env.get(OPT_IN_ENV)), "未显式 opt-in（" + OPT_IN_ENV + "=1）：跳过图文跨模态真跑基线（默认 CI 零外呼）");
    Assumptions.assumeTrue(
        apiKey != null && !apiKey.isBlank(), "DASHSCOPE_API_KEY 未配置（env 或仓库根 .env）：跳过图文跨模态真跑基线");
  }

  private static long tokenOf(ModelCallResult<float[]> result) {
    if (result.totalTokens() != null) {
      return result.totalTokens();
    }
    return result.promptTokens() == null ? 0L : result.promptTokens();
  }

  /** 图片视觉路召回（生产 {@code KnowledgeRetrievalServiceImpl#recallImage}）：按图查 image 集合。 */
  private List<String> retrieveByImage(
      KnowledgeRetrievalPort retrieval, ImageCase imageCase, float[] queryVector) {
    KnowledgeRetrievalPort.RetrievalResult result =
        retrieval.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(
                imageCase.queryText(),
                EVAL_USER_ID,
                List.of(ImageCrossModalFixture.EVAL_KB_ID),
                ImageCrossModalSuite.TOP_K,
                queryVector,
                ImageCrossModalFixture.IMAGE_CATEGORY));
    return chunkIdsOf(result);
  }

  /** 文本降维对照路召回：同一问句走文本集合（无图片向量）。 */
  private List<String> retrieveByText(KnowledgeRetrievalPort retrieval, ImageCase imageCase) {
    KnowledgeRetrievalPort.RetrievalResult result =
        retrieval.retrieve(
            new KnowledgeRetrievalPort.RetrievalQuery(
                imageCase.queryText(),
                EVAL_USER_ID,
                List.of(ImageCrossModalFixture.EVAL_KB_ID),
                ImageCrossModalSuite.TOP_K,
                null,
                null));
    return chunkIdsOf(result);
  }

  private static List<String> chunkIdsOf(KnowledgeRetrievalPort.RetrievalResult result) {
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
   * 装配真实 Provider：DashScope 原生（chat/embed）+ compatible-mode（stream），同 CustomerRetrievalBenchmarkIT。
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

  private <T> ObjectProvider<T> provider(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }
    };
  }

  private Path resolveOutPath() {
    String override = System.getProperty(OUT_PROP);
    return override == null || override.isBlank() ? DEFAULT_REPORT_PATH : Path.of(override);
  }

  /** 读 key：优先环境变量，其次仓库根 .env（与 RagRealRetrievalBenchmarkIT / CustomerRetrievalBenchmarkIT 同序）。 */
  static String resolveApiKey() {
    String fromEnv = System.getenv("DASHSCOPE_API_KEY");
    if (fromEnv != null && !fromEnv.isBlank()) {
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
}
