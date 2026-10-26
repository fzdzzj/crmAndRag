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
import com.slz.crm.knowledge.document.DocumentService;
import com.slz.crm.knowledge.document.ImageSliceMetadata;
import com.slz.crm.knowledge.document.ImageVectorCleanupRegistry;
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
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.AdditionalAnswers;
import org.mockito.ArgumentMatchers;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.stubbing.Answer;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.StringUtils;

/**
 * 腿 A 带内入口几何扫描（near-dup-band-entry-and-tier-fill，failsafe IT，A 档真跑）：{@code
 * docs/rag-quality/near-dup-band-entry-v1.json} 的<b>唯一写入口</b>。
 *
 * <p><b>测什么</b>：卡 31 证质量型变形进不了降权档（jpeg-95/jpeg-85/scale-95 全落 SAME_IMAGE），S0 探针实测 {@code crop-04}
 * cos=0.9934 ∈ [0.982, 0.9944) 是全仓唯一带内入口。本 IT 用<b>纯几何</b>边距扫描（2%/4%/6% 裁切，{@link
 * NearDupS1CropCorpus}） 再撞一次带内，真走「合成 PDF → 生产提取 → VLM 描述 → 质量闸门 → 落盘 → 描述子块行 embed + 视觉向量
 * embedImage」，再以 A 跑（生产降权）/ B 跑（{@code mockStatic} 恒等替换）双跑同批查询向量，回答「撞上了降权搬不搬名次」；腿 B 的档位填充盘点与 0.90
 * 议题选项由 {@link NearDupTierFillAnalysisTest} 纯静态算子随锚一并落盘（零外呼）。
 *
 * <p><b>三模式</b>（与卡 31 同装置）：
 *
 * <ol>
 *   <li><b>未 opt-in</b>（默认 CI/verify）：整类 {@link Assumptions} 跳过、零外呼、不落文件；
 *   <li><b>opt-in + {@code -Dnear.dup.band.entry.write=true}</b>：全量真跑（目标 30 外呼，硬帽 36 由 provider
 *       触帽即抛）， <b>逐图 fail-fast</b>——顺序循环先跑第一张变体并校验完 R2 等式（提取=1 ∧ describe=1 ∧ 过闸=1 ∧ 切片≥1 ∧
 *       图像embed=1 ∧ 文本embed=实测子块行数）才继续下一张（第一笔钱花完就能判断参数有没有希望），不成立立即 abort 落锚；
 *   <li><b>opt-in 无 write（默认复跑）</b>：本地重建语料（确定性断言与锚冻结面逐字段一致）→ 从锚 corpusMaterial 重建两集合 →
 *       重放同一条生产检索链（A/B）→ 重算全部派生字段 → 剔除顶层 runtime 块后与磁盘锚<b>逐字节比对</b>——零外呼（桩 provider 结构保证）。
 * </ol>
 *
 * <p><b>板规 26 作为判据本体（R3）</b>：{@code verdict} 与 {@code downWeightedCount} 强制联动——{@code
 * downWeightedCount == 0} ⇒ 只能 {@code INCONCLUSIVE}（附 {@code missingEvidence =
 * NO-IN-BAND-CANDIDATE}），禁写「无位移」； {@code > 0} 且名次/集合有变化 ⇒ {@code RANK-CHANGE}（附逐事件 gap）；{@code > 0}
 * 且无变化 ⇒ {@code NO-RANK-CHANGE}（附 windowCheck 与不可外推声明）。
 *
 * <p><b>分母纪律（卡 27 COUNTER-SUPPRESSED）</b>：文本 embed 一律对账<b>实测子块行数</b>，禁「过闸图数×常数」推导量；图片切片行 {@code
 * rowIndex} 恒 {@code null}。装置件（TrackingProvider/StubEmbeddingProvider/StoredRecord 等）复用卡 31 {@link
 * NearDupIngestS1IT} 的冻结嵌套类，只读不改。
 */
@DisplayName("腿 A 带内入口几何扫描：裁切变体真 ingest + A/B 检索（RAG_NEAR_DUP_INGEST_REAL 显式 opt-in）")
class NearDupBandEntryIT {

  /** 真跑 opt-in 键（与卡 31 共用门控变量名，冻结）。 */
  static final String OPT_IN_ENV = "RAG_NEAR_DUP_INGEST_REAL";

  /** 锚写入门控属性（冻结）。 */
  static final String WRITE_PROP = "near.dup.band.entry.write";

  /** 本卡锚落盘路径（写集内唯一 JSON 产物）。 */
  static final Path ANCHOR_PATH = Path.of("docs", "rag-quality", "near-dup-band-entry-v1.json");

  /** 腿 A 外呼硬帽（Owner 2026-10-26 授权 36；触帽即抛，不吞）。 */
  static final int BAND_CALL_CAP = 36;

  /** 腿 A 外呼目标（18 入库 + 12 查询；写锚模式对账用）。 */
  static final int BAND_CALL_TARGET = 30;

  /** 评测上下文用户/知识库（独立取值，不与既有真跑 IT 共享）。 */
  private static final long EVAL_USER_ID = 99081L;

  private static final String KB_ID = "9908";

  private static final long KB_ID_LONG = 9908L;

  /** 子块行主键起点（测试侧合成 DB 主键）。 */
  private static final long FIRST_CHUNK_ID = 9820L;

  /** 召回截断（与既有套件同口径）。 */
  private static final int TOP_K = ImageCrossModalSuite.TOP_K;

  /** 图片切片类目（生产切片链的类目过滤键，本卡不自造）。 */
  private static final String IMAGE_CATEGORY = ImageCrossModalFixture.IMAGE_CATEGORY;

  /** 视觉 embedding 的 MIME（生产 PdfImageSliceIngestService.IMAGE_MIME_TYPE 同值）。 */
  private static final String IMAGE_MIME = "image/png";

  /** 图像路融合权重（RetrievalConfigResolver 字面值 0.30；卡 28/31 消融同款口径）。 */
  static final double IMAGE_ROUTE_WEIGHT = 0.30D;

  /** embedImage 调用节奏（毫秒）：定标卡实测 2 QPS 连打中途 429，压到 ~0.9 QPS。 */
  private static final long PACE_MILLIS = 1100L;

  /** 429 有界退避重试次数（仅限流；其余失败一律整体失败，不落半成品锚）。 */
  private static final int THROTTLE_RETRIES = 3;

  private static final long THROTTLE_BACKOFF_MILLIS = 5000L;

  /** compatible-mode 端点（与既有真跑 IT 相同装配）。 */
  private static final String COMPATIBLE_BASE_URL =
      "https://dashscope.aliyuncs.com/compatible-mode/v1";

  /** 视觉模型覆盖属性（与既有真跑 IT 同枚）。 */
  private static final String VISION_MODEL_PROP = "rag.pdf.image.vision-model";

  /** 视觉模型冻结投影常量（锚 constants.visionModel 只落此值；运行时活值见顶层 runtime 块）。 */
  static final String DEFAULT_VISION_MODEL = NearDupIngestGateProbeIT.DEFAULT_VISION_MODEL;

  /** 本卡起点基线（master @ 6cdfac6）。 */
  private static final String BASELINE_HEAD = "6cdfac6649d308ffaa6527ddbcbf450a90498cbd";

  /** 授权记录（R8：authorization 字段落锚原样取此值）。 */
  private static final String AUTHORIZATION_NOTE = "Owner 2026-10-26 授权（含更强变形）";

  /** 描述摘要长度（锚 descriptionHead 截断）。 */
  private static final int DESCRIPTION_HEAD_CHARS = 160;

  /** 序列化显式锁 LF（.gitattributes 对 *.json 强制 eol=lf；与卡 31 同款缩进器）。 */
  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .enable(SerializationFeature.INDENT_OUTPUT)
          .setDefaultPrettyPrinter(
              new com.fasterxml.jackson.core.util.DefaultPrettyPrinter()
                  .withObjectIndenter(
                      new com.fasterxml.jackson.core.util.DefaultIndenter("  ", "\n")));

  /** 本卡消融判据产物（板规 26 联动 + missingEvidence 面比卡 31 多一字段）。 */
  record BandResults(
      List<ObjectNode> queryNodes,
      List<ObjectNode> candidateNodes,
      List<ObjectNode> windowNodes,
      List<ObjectNode> bandSideNodes,
      int imageCandidateCount,
      int downWeightedCount,
      double maxImageScoreDelta,
      double maxTextScoreDelta,
      double imageDeltaSum,
      boolean allChunkIdSetsEqual,
      boolean anyImageRankChange,
      boolean anyImageScoreChange,
      int embedInvocationsA,
      int embedInvocationsB,
      List<String> inBandVariantKeys,
      String constructionFailure,
      String verdict,
      String verdictReason,
      String missingEvidence) {}

  @Test
  @DisplayName("腿 A：裁切变体真 ingest 30 外呼 + A/B 检索落锚（写锚 / 默认复跑逐字节校验）")
  void runBandEntryScan() throws Exception {
    if (!"1".equals(System.getenv(OPT_IN_ENV))) {
      Assumptions.assumeTrue(false, "未显式 opt-in（" + OPT_IN_ENV + "=1）：跳过带内入口真跑（默认 CI 零外呼）");
    }
    if (isWriteMode()) {
      String apiKey = NearDupIngestS1IT.resolveApiKey();
      Assumptions.assumeTrue(
          StringUtils.hasText(apiKey), "DASHSCOPE_API_KEY 未配置（env 或仓库根 .env）：跳过写锚真跑");
      runRealAndWrite(apiKey);
    } else {
      replayAndVerifyByteIdentity();
    }
  }

  /** 是否写锚模式（opt-in 且显式 {@code -Dnear.dup.band.entry.write=true}）。 */
  private static boolean isWriteMode() {
    return "true".equalsIgnoreCase(System.getProperty(WRITE_PROP));
  }

  // ==================== 写锚模式：全量真跑 ====================

  private void runRealAndWrite(String apiKey) throws Exception {
    NearDupS1CropCorpus.CropCorpus corpus = NearDupS1CropCorpus.generate();
    NearDupIngestS1IT.TrackingProvider provider =
        new NearDupIngestS1IT.TrackingProvider(
            realProvider(properties(apiKey), apiKey), BAND_CALL_CAP);
    EmbeddingService embeddingService = new EmbeddingService(provider, properties(apiKey));
    NearDupIngestS1IT.RecordingTokenRecorder recorder =
        new NearDupIngestS1IT.RecordingTokenRecorder();
    ObjectProvider<DynamicConfigService> configProvider =
        RagBenchmarkPipelineFactory.provider(RagBenchmarkPipelineFactory.dynamicConfig(config()));

    NearDupIngestS1IT.CapturingInMemoryStore textCapture =
        new NearDupIngestS1IT.CapturingInMemoryStore();
    NearDupIngestS1IT.CapturingInMemoryStore imageBacking =
        new NearDupIngestS1IT.CapturingInMemoryStore();
    ImageVectorStore imageStore = new ImageVectorStore(imageBacking);
    ChunkIdBackfillMapper chunkMapper = new ChunkIdBackfillMapper(FIRST_CHUNK_ID);

    // 阶段一（外呼期）：逐变体真 ingest（逐图 fail-fast，先跑第一张校验完 R2 再继续）+ 查询向量只嵌一次
    List<NearDupIngestS1IT.FrozenIngest> ingests = new ArrayList<>();
    Map<Integer, float[]> queryImageVectors;
    NearDupIngestS1IT.Material material;
    try {
      UserContextHolder.set(
          new UserContext(EVAL_USER_ID, 5L, 101L, DataScopeLevel.ALL, "near-dup-band"));
      ImageVectorCleanupRegistry.bind(imageStore);
      for (NearDupS1CropCorpus.CropVariant variant : corpus.variants()) {
        ingests.add(
            ingestVariant(
                provider,
                embeddingService,
                recorder,
                configProvider,
                textCapture,
                imageBacking,
                chunkMapper,
                variant));
      }
      queryImageVectors = new LinkedHashMap<>();
      for (NearDupS1CropCorpus.CropQuery query : corpus.queries()) {
        embeddingService.embed(query.text());
        queryImageVectors.put(
            query.index(),
            provider.embedImageTracked(baseOf(corpus, query).png(), IMAGE_MIME).vector());
      }
      material =
          new NearDupIngestS1IT.Material(
              imageBacking.captured(),
              textCapture.captured(),
              queryImageVectors,
              provider.embedCache(),
              List.copyOf(ingests));
      assertLedger(corpus, ingests, provider);
    } catch (Throwable failure) {
      writeAbortAnchor(corpus, provider, ingests, failure);
      throw failure;
    } finally {
      ImageVectorCleanupRegistry.unbind();
      UserContextHolder.clear();
    }

    // 阶段二（零外呼期）：A/B 双跑 + 锚落盘（此段失败为装置性错误，直接红，不落 abort 锚）
    BandResults results = runAblationPasses(corpus, material);
    ObjectNode root = buildAnchor(corpus, material, results);
    String json = MAPPER.writeValueAsString(root);
    assertFalse(json.contains("\r"), "本卡锚 JSON 必须纯 LF（DefaultIndenter 已锁行尾）");
    Files.createDirectories(ANCHOR_PATH.toAbsolutePath().getParent());
    Files.writeString(ANCHOR_PATH, json, StandardCharsets.UTF_8);
    System.out.println(
        "[near-dup-band] 锚已落盘: "
            + ANCHOR_PATH.toAbsolutePath()
            + "（externalCalls="
            + provider.totalCalls()
            + " verdict="
            + root.path("verdict").asText()
            + "）");
  }

  /**
   * 单张裁切变体真 ingest（七链逐步断言，任何静默丢弃即红；R2 逐图等式即 fail-fast 位——第一张红即止，不烧余下预算）。
   *
   * <p>链：合成单图 PDF → 生产提取器（提取=1、像素一致）→ 生产 buildSlices（describe=1、过闸=1、切片≥1、图像embed=1 暂存）→ 生产
   * chunkEmbedAndWrite（文本embed=实测子块行数、rowIndex 恒 null、视觉向量经 registry flush 落 image 集合）。
   */
  private NearDupIngestS1IT.FrozenIngest ingestVariant(
      NearDupIngestS1IT.TrackingProvider provider,
      EmbeddingService embeddingService,
      NearDupIngestS1IT.RecordingTokenRecorder recorder,
      ObjectProvider<DynamicConfigService> configProvider,
      NearDupIngestS1IT.CapturingInMemoryStore textCapture,
      NearDupIngestS1IT.CapturingInMemoryStore imageBacking,
      ChunkIdBackfillMapper chunkMapper,
      NearDupS1CropCorpus.CropVariant variant)
      throws Exception {
    String filename = "near-dup-band-" + variant.key() + ".pdf";
    byte[] pdf = NearDupIngestGateProbeIT.buildSingleImagePdf(variant.png());
    List<RealPdfAssets.Extracted> extracted =
        RealPdfAssets.extract(pdf, NearDupIngestGateProbeIT.MAX_IMAGES_PER_PDF);
    assertEquals(1, extracted.size(), variant.key() + " 提取=1（R2）");
    assertEquals(variant.width(), extracted.get(0).width(), variant.key() + " 提取宽度一致（R2）");
    assertEquals(variant.height(), extracted.get(0).height(), variant.key() + " 提取高度一致（R2）");

    PdfIngestImageFixture.InMemoryImageStorage storage = PdfIngestImageFixture.inMemoryStorage();
    PdfImageSliceIngestService ingestService =
        new PdfImageSliceIngestService(
            provider, configProvider, providerOf(recorder), providerOf(storage));
    int visionBefore = provider.visionCalls.get();
    int embedImageBefore = provider.embedImageCalls.get();
    int textRowsBefore = textCapture.captured().size();
    int imageRowsBefore = imageBacking.captured().size();
    List<DocumentChunk> slices = ingestService.buildSlices(pdf, filename, IMAGE_CATEGORY, KB_ID);
    assertEquals(visionBefore + 1, provider.visionCalls.get(), variant.key() + " describe=1（R2）");
    assertTrue(!slices.isEmpty(), variant.key() + " 过闸=1（被闸门拒即 abort，R2/D5）");
    Map<String, float[]> staged = RealPdfAssets.stagedVectors();
    assertEquals(
        slices.size(),
        staged.size(),
        variant.key() + " 图像embed=暂存数=" + slices.size() + "（R2：暂存须在 flush 前读）");
    assertEquals(
        slices.size(),
        provider.embedImageCalls.get() - embedImageBefore,
        variant.key() + " embedImage 实呼数=切片数（R2）");

    UploadedFileEntity file = fileOf(variant, filename);
    int embedBefore = provider.embedCalls.get();
    List<DocumentVectorChunkEntity> children =
        chunkEmbedAndWrite(
            file,
            slices,
            new EmbeddingService(provider, properties("ingest-embed")),
            textCapture,
            chunkMapper);
    // 分母纪律（卡 27）：文本 embed 对账实测子块行数——两侧都是实测（provider 计数 vs 生产返回行），无推导量
    assertEquals(
        children.size(),
        provider.embedCalls.get() - embedBefore,
        variant.key() + " 文本embed=实测子块行数=" + children.size() + "（R2）");
    assertEquals(
        children.size(),
        textCapture.captured().size() - textRowsBefore,
        variant.key() + " 文本集合新增行=子块行数（R2）");
    for (DocumentVectorChunkEntity child : children) {
      assertTrue(child.getRowIndex() == null, variant.key() + " 图片切片行 rowIndex 恒 null（R2/卡27 R4）");
    }
    assertEquals(
        slices.size(),
        imageBacking.captured().size() - imageRowsBefore,
        variant.key() + " 视觉向量落 image 集合=切片数（R2，flush 由生产尾段经 registry 消费暂存）");

    List<String> chunkIds = new ArrayList<>();
    for (DocumentVectorChunkEntity child : children) {
      chunkIds.add(String.valueOf(child.getId()));
    }
    TokenUsageRecord visionRecord = recorder.lastVisionRecord();
    DocumentChunk first = slices.get(0);
    return new NearDupIngestS1IT.FrozenIngest(
        variant.key(),
        variant.baseKey(),
        variant.kind(),
        filename,
        extracted.size(),
        provider.visionCalls.get() - visionBefore,
        true,
        slices.size(),
        children.size(),
        provider.embedCalls.get() - embedBefore,
        staged.size(),
        chunkIds,
        visionRecord == null ? null : visionRecord.promptTokens(),
        visionRecord == null ? null : visionRecord.completionTokens(),
        visionRecord == null ? null : visionRecord.totalTokens(),
        head(first.text(), DESCRIPTION_HEAD_CHARS));
  }

  /** 实测台账对账（R8）：三分项 == 派生口径（6 描述 + 6 摄取 embedImage + 6 查询 embedImage + 子块行 embed + 6 查询 embed）。 */
  private void assertLedger(
      NearDupS1CropCorpus.CropCorpus corpus,
      List<NearDupIngestS1IT.FrozenIngest> ingests,
      NearDupIngestS1IT.TrackingProvider provider) {
    int childRows = ingests.stream().mapToInt(NearDupIngestS1IT.FrozenIngest::childRows).sum();
    int expectedVision = ingests.size();
    int expectedEmbedImage =
        ingests.stream().mapToInt(NearDupIngestS1IT.FrozenIngest::stagedImageVectors).sum()
            + corpus.queries().size();
    int expectedEmbed = childRows + corpus.queries().size();
    assertEquals(expectedVision, provider.visionCalls.get(), "vision 实呼=变体数（每图恰 1 次）");
    assertEquals(expectedEmbedImage, provider.embedImageCalls.get(), "embedImage 实呼=摄取暂存+查询图像");
    assertEquals(expectedEmbed, provider.embedCalls.get(), "embed 实呼=子块行+查询文本");
    assertTrue(
        provider.totalCalls() <= BAND_CALL_CAP,
        "腿 A 外呼实测 "
            + provider.totalCalls()
            + " 超硬帽 "
            + BAND_CALL_CAP
            + "（目标 "
            + BAND_CALL_TARGET
            + "）");
  }

  // ==================== A/B 消融（write 与 replay 共用同一实现） ====================

  /** A 跑（生产降权，真实方法+旁路观测）→ B 跑（恒等替换）；两跑全新装配、复用同批冻结查询向量、零外呼。 */
  private BandResults runAblationPasses(
      NearDupS1CropCorpus.CropCorpus corpus, NearDupIngestS1IT.Material material) {
    NearDupAblationPositiveControlTest.PositiveControlOutcome positiveControl =
        NearDupAblationPositiveControlTest.runPositiveControl();
    NearDupIngestS1IT.PassEvidence passA = runPass(corpus, material, false);
    NearDupIngestS1IT.PassEvidence passB = runPass(corpus, material, true);
    assertEquals(
        passA.embedInvocations(),
        passB.embedInvocations(),
        "两跑 embed 请求数必须相等（消融只许替换 downWeightNearDuplicates 一个方法）");
    return computeResults(corpus, material, positiveControl, passA, passB);
  }

  /** 单次消融跑（冻结材料全新装配两集合与生产检索链；A/B 互不共享可变状态）。 */
  private NearDupIngestS1IT.PassEvidence runPass(
      NearDupS1CropCorpus.CropCorpus corpus,
      NearDupIngestS1IT.Material material,
      boolean identityStub) {
    AtomicInteger embedInvocations = new AtomicInteger();
    ModelProvider provider =
        embedCountingProvider(
            new NearDupIngestS1IT.StubEmbeddingProvider(material.embedCache()), embedInvocations);
    KnowledgeRetrievalServiceImpl service =
        assembleService(provider, material.imageRecords(), material.textRecords());
    Map<Integer, List<NearDupIngestS1IT.SourceRef>> sources = new LinkedHashMap<>();
    Map<Integer, List<RetrievalCandidate>> inputs = new LinkedHashMap<>();
    Map<Integer, List<RetrievalCandidate>> returns = new LinkedHashMap<>();
    UserContextHolder.set(
        new UserContext(EVAL_USER_ID, 5L, 101L, DataScopeLevel.ALL, "near-dup-band"));
    try {
      for (NearDupS1CropCorpus.CropQuery query : corpus.queries()) {
        float[] queryVector = material.queryImageVectors().get(query.index());
        assertNotNull(queryVector, "缺 queryIndex=" + query.index() + " 的查询图像向量");
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
                      query.text(), EVAL_USER_ID, List.of(KB_ID), TOP_K, queryVector, null));
          gate.verify(
              () -> ImageSimilarityGate.downWeightNearDuplicates(ArgumentMatchers.any()),
              Mockito.times(1));
        }
        inputs.put(query.index(), List.copyOf(capturedInput));
        returns.put(query.index(), List.copyOf(capturedReturn));
        List<NearDupIngestS1IT.SourceRef> refs = new ArrayList<>();
        for (SourceReference source :
            result.sources() == null ? List.<SourceReference>of() : result.sources()) {
          refs.add(
              new NearDupIngestS1IT.SourceRef(
                  source.sourceType(), source.chunkId(), source.relevanceScore()));
        }
        sources.put(query.index(), List.copyOf(refs));
      }
    } finally {
      UserContextHolder.clear();
    }
    return new NearDupIngestS1IT.PassEvidence(sources, inputs, returns, embedInvocations.get());
  }

  /** 装配生产检索服务（授权桩 + 两集合重建 + 生产配置矩阵；provider 由调用方给定）。 */
  private KnowledgeRetrievalServiceImpl assembleService(
      ModelProvider provider,
      List<NearDupIngestS1IT.StoredRecord> imageRecords,
      List<NearDupIngestS1IT.StoredRecord> textRecords) {
    EmbeddingService embeddingService = new EmbeddingService(provider, properties("replay-no-key"));
    InMemoryVectorStore textStore = new InMemoryVectorStore();
    for (NearDupIngestS1IT.StoredRecord record : textRecords) {
      textStore.upsert(record.toVectorRecord());
    }
    ImageVectorStore imageStore = new ImageVectorStore(new InMemoryVectorStore());
    List<VectorRecord> imageVectorRecords = new ArrayList<>();
    for (NearDupIngestS1IT.StoredRecord record : imageRecords) {
      imageVectorRecords.add(record.toVectorRecord());
    }
    imageStore.upsertAll(imageVectorRecords);
    KnowledgeBaseAuthorizationService authorization =
        Mockito.mock(KnowledgeBaseAuthorizationService.class);
    Mockito.when(
            authorization.authorizedKnowledgeBaseIds(
                ArgumentMatchers.any(), ArgumentMatchers.any()))
        .thenReturn(List.of(KB_ID_LONG));
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

  /** 消融判据机械推导（名次面+集合面判定、窗口算术、bandSide、板规 26 联动三支；禁自创第四种）。 */
  private BandResults computeResults(
      NearDupS1CropCorpus.CropCorpus corpus,
      NearDupIngestS1IT.Material material,
      NearDupAblationPositiveControlTest.PositiveControlOutcome positiveControl,
      NearDupIngestS1IT.PassEvidence passA,
      NearDupIngestS1IT.PassEvidence passB) {
    // 两跑降权入参恒等（重放确定性结构证明）
    assertEquals(passA.downWeightInputs().keySet(), passB.downWeightInputs().keySet());
    for (Map.Entry<Integer, List<RetrievalCandidate>> entry : passA.downWeightInputs().entrySet()) {
      List<RetrievalCandidate> inputA = entry.getValue();
      List<RetrievalCandidate> inputB = passB.downWeightInputs().get(entry.getKey());
      assertEquals(inputA.size(), inputB.size(), "queryIndex=" + entry.getKey() + " 入参长度一致");
      for (int index = 0; index < inputA.size(); index++) {
        assertEquals(
            inputA.get(index).fusionKey(),
            inputB.get(index).fusionKey(),
            "queryIndex=" + entry.getKey() + " 第 " + index + " 条入参候选一致");
        assertEquals(
            0,
            Double.compare(inputA.get(index).rerankScore(), inputB.get(index).rerankScore()),
            "queryIndex=" + entry.getKey() + " 第 " + index + " 条入参分数一致");
      }
    }
    // B 跑恒等回执（逐条 Double.compare==0）
    for (Map.Entry<Integer, List<RetrievalCandidate>> entry : passB.downWeightInputs().entrySet()) {
      List<RetrievalCandidate> input = entry.getValue();
      List<RetrievalCandidate> returned = passB.downWeightReturns().get(entry.getKey());
      assertEquals(input.size(), returned.size(), "B 跑恒等返回长度一致");
      for (int index = 0; index < input.size(); index++) {
        assertEquals(
            0,
            Double.compare(input.get(index).rerankScore(), returned.get(index).rerankScore()),
            "B 跑恒等返回分数被改动");
      }
    }

    // tier/band/cos/weight 建图（cos=图像路原始余弦 hit.score()；weight=A 跑出参 BAND_WEIGHT_METADATA_KEY 实测值）
    Map<Integer, Map<String, String>> tierByQuery = new LinkedHashMap<>();
    Map<Integer, Map<String, String>> bandByQuery = new LinkedHashMap<>();
    Map<Integer, Map<String, Double>> cosByQuery = new LinkedHashMap<>();
    Map<Integer, Map<String, Double>> weightByQuery = new LinkedHashMap<>();
    for (Map.Entry<Integer, List<RetrievalCandidate>> entry : passA.downWeightInputs().entrySet()) {
      Map<String, String> tiers = new LinkedHashMap<>();
      Map<String, String> bands = new LinkedHashMap<>();
      Map<String, Double> cosines = new LinkedHashMap<>();
      for (RetrievalCandidate candidate : entry.getValue()) {
        String key = sourceKeyOf(candidate.hit());
        tiers.putIfAbsent(
            key, stringOf(candidate.hit().metadata().get(ImageSimilarityGate.TIER_METADATA_KEY)));
        bands.putIfAbsent(
            key, stringOf(candidate.hit().metadata().get(ImageSimilarityGate.BAND_METADATA_KEY)));
        cosines.putIfAbsent(key, candidate.hit().score());
      }
      Map<String, Double> weights = new LinkedHashMap<>();
      for (RetrievalCandidate candidate : passA.downWeightReturns().get(entry.getKey())) {
        Map<String, Object> metadata = candidate.hit().metadata();
        if (metadata != null
            && metadata.containsKey(ImageSimilarityGate.BAND_WEIGHT_METADATA_KEY)) {
          weights.put(
              sourceKeyOf(candidate.hit()),
              ((Number) metadata.get(ImageSimilarityGate.BAND_WEIGHT_METADATA_KEY)).doubleValue());
        }
      }
      tierByQuery.put(entry.getKey(), tiers);
      bandByQuery.put(entry.getKey(), bands);
      cosByQuery.put(entry.getKey(), cosines);
      weightByQuery.put(entry.getKey(), weights);
    }
    int downWeightedCount = weightByQuery.values().stream().mapToInt(Map::size).sum();

    // 逐查询逐候选表（名次面/集合面/分数面）
    List<ObjectNode> queryNodes = new ArrayList<>();
    List<ObjectNode> candidateNodes = new ArrayList<>();
    Map<Integer, Map<String, Double>> fusedAByQuery = new LinkedHashMap<>();
    Map<Integer, Map<String, Double>> fusedBByQuery = new LinkedHashMap<>();
    Map<String, String> variantByChunkKey = variantByChunkKey(material);
    int imageCandidateCount = 0;
    boolean allChunkIdSetsEqual = true;
    boolean anyImageRankChange = false;
    boolean anyImageScoreChange = false;
    double maxImageScoreDelta = 0.0D;
    double maxTextScoreDelta = 0.0D;
    double imageDeltaSum = 0.0D;
    for (NearDupS1CropCorpus.CropQuery query : corpus.queries()) {
      int index = query.index();
      List<NearDupIngestS1IT.SourceRef> sourcesA = passA.sources().get(index);
      List<NearDupIngestS1IT.SourceRef> sourcesB = passB.sources().get(index);
      List<String> orderedA = sourcesA.stream().map(NearDupIngestS1IT.SourceRef::chunkId).toList();
      List<String> orderedB = sourcesB.stream().map(NearDupIngestS1IT.SourceRef::chunkId).toList();
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
      List<String> downWeightedKeys = new ArrayList<>();
      for (String chunkId : unionKeys) {
        NearDupIngestS1IT.SourceRef fromA = sourceByKey(sourcesA, chunkId);
        NearDupIngestS1IT.SourceRef fromB = sourceByKey(sourcesB, chunkId);
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
        boolean downWeighted = weightByQuery.get(index).containsKey(chunkId);
        if (downWeighted) {
          downWeightedKeys.add(chunkId);
        }
        if (image && fusedA != null && fusedB != null) {
          double delta = Math.abs(fusedA - fusedB);
          if (delta > maxImageScoreDelta) {
            maxImageScoreDelta = delta;
          }
          imageDeltaSum += delta;
          if (Double.compare(fusedA, fusedB) != 0) {
            anyImageScoreChange = true;
          }
        }
        if (image && rankA != null && rankB != null && !rankA.equals(rankB)) {
          anyImageRankChange = true;
        }
        if (!image && fusedA != null && fusedB != null) {
          assertEquals(
              0,
              Double.compare(fusedA, fusedB),
              "非 IMAGE 来源融合分两跑不一致（隔离被破坏）: query=" + index + " chunkId=" + chunkId);
          double delta = Math.abs(fusedA - fusedB);
          if (delta > maxTextScoreDelta) {
            maxTextScoreDelta = delta;
          }
        }
        ObjectNode node = MAPPER.createObjectNode();
        node.put("queryIndex", index);
        node.put("sourceType", sourceType);
        node.put("chunkId", chunkId);
        String variantKey = variantByChunkKey.get(chunkId);
        if (variantKey == null) {
          node.putNull("variantKey");
        } else {
          node.put("variantKey", variantKey);
        }
        String tier = tierByQuery.get(index).get(chunkId);
        String band = bandByQuery.get(index).get(chunkId);
        node.put("tier", tier);
        node.put("band", band);
        node.put("downWeighted", downWeighted);
        Double cos = cosByQuery.get(index).get(chunkId);
        node.put("cos", cos);
        node.put("fusedA", fusedA);
        node.put("fusedB", fusedB);
        node.put("rankA", rankA);
        node.put("rankB", rankB);
        candidateNodes.add(node);
      }
      fusedAByQuery.put(index, fusedAByKey);
      fusedBByQuery.put(index, fusedBByKey);
      imageCandidateCount += imageCount;
      ObjectNode queryNode = MAPPER.createObjectNode();
      queryNode.put("queryIndex", index);
      queryNode.put("baseKey", query.baseKey());
      queryNode.put("form", query.form());
      queryNode.put("chunkIdSetEqual", chunkIdSetEqual);
      queryNode.put("imageCandidateCount", imageCount);
      ArrayNode keys = queryNode.putArray("downWeightedKeys");
      downWeightedKeys.forEach(keys::add);
      queryNodes.add(queryNode);
    }

    // bandSide：逐变体 × 其 base 的 3 查询；边界读生产常量（tier 已由生产 gate 判定）
    List<ObjectNode> bandSideNodes = new ArrayList<>();
    List<String> inBandVariantKeys = new ArrayList<>();
    boolean anyInBand = false;
    boolean anyAbove = false;
    for (NearDupS1CropCorpus.CropVariant variant : corpus.variants()) {
      ObjectNode node = MAPPER.createObjectNode();
      node.put("variantKey", variant.key());
      ArrayNode rows = node.putArray("rows");
      boolean inBand = false;
      boolean above = false;
      boolean recalled = false;
      for (NearDupS1CropCorpus.CropQuery query : corpus.queries()) {
        if (!query.baseKey().equals(variant.baseKey())) {
          continue;
        }
        String chunkKey = chunkKeyOf(material, variant);
        String tier = tierByQuery.get(query.index()).get(chunkKey);
        Double cos = cosByQuery.get(query.index()).get(chunkKey);
        ObjectNode row = rows.addObject();
        row.put("queryIndex", query.index());
        row.put("form", query.form());
        row.put("recalled", tier != null);
        row.put("cos", cos);
        row.put("tier", tier);
        row.put("band", bandByQuery.get(query.index()).get(chunkKey));
        if (tier != null) {
          recalled = true;
        }
        if (ImageSimilarityGate.TIER_NEAR_DUPLICATE.equals(tier)) {
          inBand = true;
        }
        if (ImageSimilarityGate.TIER_SAME_IMAGE.equals(tier)) {
          above = true;
        }
      }
      // 变体级归类（冻结优先序）：带内 > 带上 > 带下；未召回=BELOW
      String bandSide;
      if (inBand) {
        bandSide = "IN-BAND";
        anyInBand = true;
        inBandVariantKeys.add(variant.key());
      } else if (above) {
        bandSide = "ABOVE";
        anyAbove = true;
      } else {
        bandSide = "BELOW";
      }
      node.put("recalled", recalled);
      node.put("bandSide", bandSide);
      bandSideNodes.add(node);
    }
    String constructionFailure = null;
    if (!anyInBand) {
      constructionFailure =
          anyAbove
              ? "全部变体落带外（至少一条 SAME_IMAGE 带上）：未构造出带内候选（D3，如实判 INCONCLUSIVE，不许追加调用换参数）"
              : "全部变体落带外（无一被图像路召回）：构造失效（D3，如实判 INCONCLUSIVE，不许追加调用换参数）";
    }

    // 窗口算术：逐降权事件 gap vs imageRouteWeight×(1−w)（RANK-CHANGE 必附逐事件；NO-RANK-CHANGE 必附本节）
    List<ObjectNode> windowNodes = new ArrayList<>();
    for (Map.Entry<Integer, List<RetrievalCandidate>> entry : passA.downWeightInputs().entrySet()) {
      int queryIndex = entry.getKey();
      Map<String, Double> weights = weightByQuery.get(queryIndex);
      Map<String, Double> fusedAByKey = fusedAByQuery.get(queryIndex);
      Map<String, Double> fusedBByKey = fusedBByQuery.get(queryIndex);
      for (Map.Entry<String, Double> weightEntry : weights.entrySet()) {
        String chunkId = weightEntry.getKey();
        double weight = weightEntry.getValue();
        ObjectNode node = MAPPER.createObjectNode();
        node.put("queryIndex", queryIndex);
        node.put("chunkId", chunkId);
        String variantKey = variantByChunkKey.get(chunkId);
        if (variantKey == null) {
          node.putNull("variantKey");
        } else {
          node.put("variantKey", variantKey);
        }
        node.put("band", bandByQuery.get(queryIndex).get(chunkId));
        node.put("weight", weight);
        node.put("window", IMAGE_ROUTE_WEIGHT * (1D - weight));
        node.put("cos", cosByQuery.get(queryIndex).get(chunkId));
        Double fusedB = fusedBByKey.get(chunkId);
        if (fusedB == null) {
          node.putNull("fusedA");
          node.putNull("fusedB");
          node.putNull("delta");
          node.putNull("strongestCompetitorBelowFusedB");
          node.putNull("gap");
          node.put("gapExceedsWindow", false);
          node.put("note", "该被降权候选未入本查询 topK，结果面无融合分可测（禁融合分÷w 反解）；其降权不可能改变已返回候选的相对顺序");
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

    // verdict 与 downWeightedCount 强制联动（板规 26 落地为判据本体 R3）：三支，禁自创第四种
    boolean rankOrSetTrigger = anyImageRankChange || !allChunkIdSetsEqual;
    String verdict;
    String verdictReason;
    String missingEvidence = null;
    if (!positiveControl.flipped()) {
      verdict = "INCONCLUSIVE";
      missingEvidence = "POSITIVE-CONTROL-NOT-FLIPPED";
      verdictReason = "正对照未翻转（装置无鉴别力），本锚任何「无位移/有位移」结论都不可采信；缺的证据：装置鉴别力证明";
    } else if (downWeightedCount == 0) {
      verdict = "INCONCLUSIVE";
      missingEvidence = "NO-IN-BAND-CANDIDATE";
      verdictReason =
          "板规 26（判据本体 R3）：downWeightedCount == 0 时「无位移」恒真，禁判 NO-RANK-CHANGE——"
              + (constructionFailure == null
                  ? "存在带内变体但其候选未触发降权（未入候选集或查询面未共现）"
                  : constructionFailure)
              + "；「撞上了会怎样」仍开放（不可外推、不许换参数再花一轮）";
    } else if (rankOrSetTrigger) {
      verdict = "RANK-CHANGE";
      verdictReason =
          "downWeightedCount="
              + downWeightedCount
              + " 且名次面或集合面发生变化——anyImageRankChange="
              + anyImageRankChange
              + " allChunkIdSetsEqual="
              + allChunkIdSetsEqual
              + "；逐事件 gap vs 0.30×(1−w) 见 windowCheck；分数面位移另见 scoreFace（maxImageScoreDelta="
              + maxImageScoreDelta
              + "），分数面不作判定面";
    } else {
      verdict = "NO-RANK-CHANGE";
      verdictReason =
          "downWeightedCount="
              + downWeightedCount
              + " 且名次面（anyImageRankChange=false）与集合面（全查询 chunkIdSetEqual=true）均无变化；"
              + "分数面位移单列于 scoreFace（maxImageScoreDelta="
              + maxImageScoreDelta
              + " imageDeltaSum="
              + imageDeltaSum
              + "）；窗口算术见 windowCheck（逐条被降权候选 gap vs 0.30×(1−w)）；带内变体 "
              + inBandVariantKeys
              + "；不可外推为「生产无位移」——结论只对本构造语料与本装配成立";
    }
    return new BandResults(
        queryNodes,
        candidateNodes,
        windowNodes,
        bandSideNodes,
        imageCandidateCount,
        downWeightedCount,
        maxImageScoreDelta,
        maxTextScoreDelta,
        imageDeltaSum,
        allChunkIdSetsEqual,
        anyImageRankChange,
        anyImageScoreChange,
        passA.embedInvocations(),
        passB.embedInvocations(),
        List.copyOf(inBandVariantKeys),
        constructionFailure,
        verdict,
        verdictReason,
        missingEvidence);
  }

  // ==================== 锚构建（write 与 replay 共用，字段顺序即序列化顺序） ====================

  private ObjectNode buildAnchor(
      NearDupS1CropCorpus.CropCorpus corpus,
      NearDupIngestS1IT.Material material,
      BandResults results) {
    NearDupAblationPositiveControlTest.PositiveControlOutcome positiveControl =
        NearDupAblationPositiveControlTest.runPositiveControl();
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", "near-dup-band-entry-and-tier-fill");
    root.put("stage", "BAND-ENTRY");
    root.put("bandEntryStatus", "COMPLETED");
    root.put("decisionVersion", "NEAR-DUP-BAND-ENTRY-1.0");
    root.put("decisionOwner", "OWNER");

    ObjectNode criteria = root.putObject("criteria");
    criteria.put(
        "R0",
        "预算硬帽 36（测试内触帽即抛）+ 逐图 fail-fast：顺序循环先跑第一张变体并校验完 R2 等式再继续"
            + "（第一笔钱花完就能判断参数有没有希望）；abort 时已花调用落锚，不许静默重试、不许换图重跑");
    criteria.put(
        "R1",
        "腿 A 语料只做几何编辑：nist-sp800-101r1 生成序前 2 个短边 ≥64px 且非纯色/低色数的 base，"
            + "每 base 3 个裁切变体（边距 2%/4%/6% 写死并落锚 corpusRule，看到分数后不得回改=挑样本判死）；"
            + "裁切后短边 ≥64px 用本地像素断言，不足换更小边距重造（不许弃图）；新类 NearDupS1CropCorpus，"
            + "NearDuplicateCorpus/NearDupS1Corpus 不得修改");
    criteria.put(
        "R2",
        "入库逐图对账（任何静默丢弃即红）：提取=1 ∧ describe=1 ∧ 过闸=1 ∧ 切片≥1 ∧ 图像embed=1 ∧ 文本embed=实测子块行数"
            + "（分母一律实测子块行数，禁过闸图数×常数；卡 27 COUNTER-SUPPRESSED）；图片切片 rowIndex 恒 null；"
            + "等式不成立立即 abort（D5）");
    criteria.put(
        "R3",
        "A/B 检索 6 查询 × 2 跑：查询=2 base × 3 形态（title/first-sentence/caption-keywords，文本取自该页已提取文本，"
            + "图像分量=base 位图）；A=生产降权，B=mockStatic(CALLS_REAL_METHODS) 恒等替换；查询向量只嵌一次、A/B 复用"
            + "（各嵌一遍=24 次 ⇒ 总 42 超帽）；逐查询落 候选集/cos/fusedA/fusedB/rankA/rankB/downWeightedKeys；"
            + "verdict 与 downWeightedCount 强制联动（板规 26 判据本体）：==0 ⇒ 只能 INCONCLUSIVE"
            + "（missingEvidence=NO-IN-BAND-CANDIDATE，禁写无位移）；>0 且有变化 ⇒ RANK-CHANGE（附逐事件 gap）；"
            + ">0 且无变化 ⇒ NO-RANK-CHANGE（附 windowCheck+不可外推）");
    criteria.put(
        "R4",
        "腿 B 档位填充盘点（零外呼只统计不推断）：编辑类型 × cos(min/median/max) × 命中档位 × 带内对数，"
            + "逐条可指回源锚字段（image-variant-cross-matrix-v1 / near-dup-exchange-rate-v1 / near-dup-ingest-gate-probe-v1）；"
            + "禁因果推断与阈值主张");
    criteria.put(
        "R5",
        "腿 B 0.90 议题只给选项与代价：维持 0.9887 / 降到 0.90 两口径下新增进带对数（标明分母）+ 已记录代价"
            + "（R 膝盖点、多压同文档图数）；不给推荐阈值、不改任何常量，NEAR_DUPLICATE_FLOOR 变更属另卡须 Owner 拍");
    criteria.put(
        "R6",
        "锚与守卫：本锚 LF、写模式 -Dnear.dup.band.entry.write=true、默认逐字节复算（剔除顶层 runtime 块）；"
            + "守卫 NearDupBandEntryGuardTest（零外呼）钉死台账等式、R3 联动三分支、R4 表逐数字复算、"
            + "腿 B 无推荐阈值（grep 断言）");
    criteria.put(
        "R7",
        "边界：src/main 零 diff；既有锚（docs/rag-quality/*.json 全量）零字节变动；"
            + "NearDuplicateCorpus/NearDupS1Corpus/NearDupIngestGateProbe*/NearDupIngestS1* 不得修改；"
            + "不改 pom.xml、不改 CI YAML");
    criteria.put(
        "R8",
        "成本台账：callLedger{vision,embedImage,embed,total,cap}，total=三分项之和且 ≤36；"
            + "authorization=Owner 2026-10-26 授权（含更强变形）");

    ObjectNode sourceAnchors = root.putObject("sourceAnchors");
    putAnchorSha(sourceAnchors, "gateProbeAnchor", "near-dup-ingest-gate-probe-v1.json");
    putAnchorSha(sourceAnchors, "s1Anchor", "near-dup-ingest-s1-v1.json");
    putAnchorSha(sourceAnchors, "crossMatrixAnchor", "image-variant-cross-matrix-v1.json");
    putAnchorSha(sourceAnchors, "exchangeRateAnchor", "near-dup-exchange-rate-v1.json");

    root.put("baselineHead", BASELINE_HEAD);

    ObjectNode corpusRule = root.putObject("corpusRule");
    corpusRule.put(
        "baseSelection",
        "nist-sp800-101r1 生成序（NearDuplicateCorpus.generate 提取序）前 2 个 min(width,height) ≥ 64px 的 base"
            + "（ExcludedBase 纯色/低色数排除口径已在 generate 内生效；与卡 31 NearDupS1Corpus 同序同口径 ⇒ 同两个 base）");
    ArrayNode kinds = corpusRule.putArray("variantKinds");
    NearDupS1CropCorpus.KINDS.forEach(kinds::add);
    ObjectNode params = corpusRule.putObject("params");
    params.put("crop02Margin", NearDupS1CropCorpus.MARGIN_02);
    params.put("crop04Margin", NearDupS1CropCorpus.MARGIN_04);
    params.put("crop06Margin", NearDupS1CropCorpus.MARGIN_06);
    params.put("marginStep", NearDupS1CropCorpus.MARGIN_STEP);
    corpusRule.put(
        "marginFallbackRule",
        "裁切落地短边 <64px 时按步进 0.01 向下换更小边距重造并如实记 param（只许少切不许多切）；" + "0.01 仍不足即停手上报（不许弃图凑数）");
    corpusRule.put(
        "queryRule",
        "2 base × 3 形态（复用卡 31 冻结形态规则 NearDupS1Corpus.formOf）：title=页文本首个非空行 / "
            + "first-sentence=正文首个句终结片段 / caption-keywords=首个 table|figure|fig.|exhibit 行前 8 词；"
            + "文本取自 base 所在页 PDFBox 提取文本（零外呼）；图像分量=base 位图 embedImage");
    corpusRule.put("minEdgePx", NearDupS1CropCorpus.MIN_EDGE_PX);
    corpusRule.put(
        "note",
        "D1：只做几何编辑——卡 31 已证 jpeg-95/jpeg-85/scale-95 全落 SAME_IMAGE（cos ≥ 0.997653），质量型重复即浪费预算；"
            + "全仓唯一实测带内入口 = S0 探针 crop-04 cos=0.9934 ∈ [0.982,0.9944)。"
            + "D2：三边距先钉死，看到分数后不得回改重跑（回改即挑样本判死）；追加调用须新授权新卡");

    ArrayNode baseNodes = root.putArray("bases");
    for (NearDupS1CropCorpus.CropBase base : corpus.bases()) {
      ObjectNode node = baseNodes.addObject();
      node.put("key", base.key());
      node.put("pageNo", base.pageNo());
      node.put("width", base.width());
      node.put("height", base.height());
      node.put("pageTextChars", base.pageTextChars());
    }
    ArrayNode variantNodes = root.putArray("variants");
    for (NearDupS1CropCorpus.CropVariant variant : corpus.variants()) {
      ObjectNode node = variantNodes.addObject();
      node.put("key", variant.key());
      node.put("baseKey", variant.baseKey());
      node.put("kind", variant.kind());
      node.put("width", variant.width());
      node.put("height", variant.height());
      node.put("param", variant.param());
    }
    ArrayNode ingestNodes = root.putArray("ingest");
    for (NearDupIngestS1IT.FrozenIngest ingest : material.ingests()) {
      ingestNodes.add(ingest.toJson());
    }
    ArrayNode queryNodes = root.putArray("queries");
    for (NearDupS1CropCorpus.CropQuery query : corpus.queries()) {
      ObjectNode node = queryNodes.addObject();
      node.put("index", query.index());
      node.put("baseKey", query.baseKey());
      node.put("form", query.form());
      node.put("text", query.text());
      node.put("textChars", query.text().length());
    }

    // 台账（write 模式已断言 provider 实测 == 本派生口径；replay 用同一派生保证字节恒等）
    int childRows =
        material.ingests().stream().mapToInt(NearDupIngestS1IT.FrozenIngest::childRows).sum();
    ObjectNode ledger = root.putObject("callLedger");
    ledger.put("vision", material.ingests().size());
    ledger.put(
        "embedImage",
        material.ingests().stream()
                .mapToInt(NearDupIngestS1IT.FrozenIngest::stagedImageVectors)
                .sum()
            + corpus.queries().size());
    ledger.put("embed", childRows + corpus.queries().size());
    ledger.put(
        "total",
        material.ingests().size()
            + material.ingests().stream()
                .mapToInt(NearDupIngestS1IT.FrozenIngest::stagedImageVectors)
                .sum()
            + corpus.queries().size()
            + childRows
            + corpus.queries().size());
    ledger.put("cap", BAND_CALL_CAP);
    ledger.put("target", BAND_CALL_TARGET);
    ledger.put("authorization", AUTHORIZATION_NOTE);
    ledger.put(
        "basis",
        "三计数由 TrackingProvider 在真调用点累加（vision=VLM 描述、embedImage=视觉编码、embed=文本编码），"
            + "非公式推导；写锚模式已断言实测计数与派生口径逐项相等（assertLedger）");

    ObjectNode runtime = root.putObject("runtime");
    String liveVisionModel = visionModel();
    runtime.put("visionModel", liveVisionModel);
    runtime.putArray("excludedFromReplay").add("visionModel");
    runtime.put(
        "overrideReason",
        DEFAULT_VISION_MODEL.equals(liveVisionModel)
            ? null
            : NearDupIngestGateProbeIT.VISION_MODEL_OVERRIDE_REASON);

    ObjectNode constants = root.putObject("constants");
    constants.put("threshold", ImageSimilarityGate.DEFAULT_THRESHOLD);
    constants.put("nearDuplicateFloor", ImageSimilarityGate.NEAR_DUPLICATE_FLOOR);
    constants.put("bandReferenceFloor", ImageSimilarityGate.BAND_REFERENCE_FLOOR);
    constants.put("weightHigh", ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH);
    constants.put("weightMid", ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID);
    constants.put("weightLow", ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW);
    constants.put("imageRouteWeight", IMAGE_ROUTE_WEIGHT);
    constants.put("textRouteWeight", 0.70D);
    constants.put("bandCallCap", BAND_CALL_CAP);
    constants.put("bandCallTarget", BAND_CALL_TARGET);
    constants.put("minEdgePx", NearDupS1CropCorpus.MIN_EDGE_PX);
    constants.put("topK", TOP_K);
    constants.put("maxImagesPerPdf", NearDupIngestGateProbeIT.MAX_IMAGES_PER_PDF);
    constants.put("visionModel", DEFAULT_VISION_MODEL);
    constants.put(
        "bandSideRule",
        "变体级归类（冻结优先序）：其 base 的 3 查询中任一 tier=NEAR_DUPLICATE ⇒ IN-BAND；否则任一 SAME_IMAGE ⇒ ABOVE；"
            + "否则 BELOW（含未被图像路召回者）。边界全部由生产 gate 现算（tierOf/bandOf），锚内不抄数字");

    ObjectNode design = root.putObject("ablationDesign");
    design.put(
        "passA",
        "生产降权：mockStatic(CALLS_REAL_METHODS) 打开，仅对 downWeightNearDuplicates 做"
            + "「捕获入参→callRealMethod（真实生产方法）→捕获返回」旁路观测，行为与生产一致");
    design.put("passB", "恒等替换：同一框架仅把该方法替换为原样返回入参，其余静态方法走真实实现；消融面唯一差异点");
    design.put(
        "rebuildBasis",
        "两跑各自从冻结材料全新装配两集合与服务（不许把 A 的结果喂给 B）；逐查询独立 mockStatic 作用域并在关闭前 "
            + "verify 恰 1 次；查询向量只嵌一次、A/B 复用（StubEmbeddingProvider 供缓存，零外呼）");
    design.put(
        "downWeightCallSite",
        "KnowledgeRetrievalServiceImpl#retrieveFromKnowledgeBases 唯一调用点（图像路重排之后、fuseRoutes 之前）");

    ObjectNode positive = root.putObject("positiveControl");
    positive.put("flipped", positiveControl.flipped());
    positive.put("nearHighWeight", positiveControl.nearHighWeight());
    positive.put("fusedNearHighWithDownWeight", positiveControl.fusedNearHighWithDownWeight());
    positive.put(
        "fusedNearHighWithoutDownWeight", positiveControl.fusedNearHighWithoutDownWeight());
    positive.put(
        "fusedNonNearImageWithDownWeight", positiveControl.fusedNonNearImageWithDownWeight());
    positive.put(
        "fusedNonNearImageWithoutDownWeight", positiveControl.fusedNonNearImageWithoutDownWeight());
    positive.put("rankNearHighWithDownWeight", positiveControl.rankNearHighWithDownWeight());
    positive.put("rankNearHighWithoutDownWeight", positiveControl.rankNearHighWithoutDownWeight());
    positive.put(
        "source", "NearDupAblationPositiveControlTest#runPositiveControl 同一实现（卡 28 先例；装置鉴别力前提）");

    ArrayNode bandSideArray = root.putArray("bandSides");
    results.bandSideNodes().forEach(bandSideArray::add);
    ArrayNode inBand = root.putArray("inBandVariantKeys");
    results.inBandVariantKeys().forEach(inBand::add);
    if (results.constructionFailure() == null) {
      root.putNull("constructionFailure");
    } else {
      root.put("constructionFailure", results.constructionFailure());
    }

    ArrayNode queryOutcomeArray = root.putArray("queriesOutcome");
    results.queryNodes().forEach(queryOutcomeArray::add);
    ArrayNode candidateArray = root.putArray("candidates");
    results.candidateNodes().forEach(candidateArray::add);
    root.put("imageCandidateCount", results.imageCandidateCount());
    root.put("downWeightedCount", results.downWeightedCount());
    root.put("maxImageScoreDelta", results.maxImageScoreDelta());
    root.put("maxTextScoreDelta", results.maxTextScoreDelta());
    root.put("imageDeltaSum", results.imageDeltaSum());

    ObjectNode scoreFace = root.putObject("scoreFace");
    scoreFace.put(
        "note", "分数面 = 各 IMAGE 候选降权前后融合分位移。w<1 时必然发生，故只作数据面、不作 verdict 判定面（卡 28 fix1 口径）");
    scoreFace.put("changed", results.anyImageScoreChange());
    scoreFace.put("maxImageScoreDelta", results.maxImageScoreDelta());
    scoreFace.put("imageDeltaSum", results.imageDeltaSum());
    scoreFace.put("imageCandidateCount", results.imageCandidateCount());
    scoreFace.put("downWeightedCount", results.downWeightedCount());

    root.put("verdict", results.verdict());
    root.put("verdictReason", results.verdictReason());
    if (results.missingEvidence() == null) {
      root.putNull("missingEvidence");
    } else {
      root.put("missingEvidence", results.missingEvidence());
    }
    ObjectNode reachability = root.putObject("verdictReachability");
    reachability.put(
        "RANK-CHANGE",
        "可达路径：downWeightedCount>0 且带内变体被降权后 fusedA 跌破同查询竞争者 fusedB"
            + "（存在 gap ≤ 0.30×(1−w) 的被降权候选即发生名次/集合翻转）");
    reachability.put(
        "NO-RANK-CHANGE",
        "可达路径：downWeightedCount>0 且全部被降权候选 gap > 0.30×(1−w)（窗口装不下位移）——名次面与集合面均无变化");
    reachability.put(
        "INCONCLUSIVE",
        "可达路径：downWeightedCount == 0（板规 26：带内候选缺失或未被降权，恒真面禁判无位移；本锚若落此值必附 "
            + "missingEvidence=NO-IN-BAND-CANDIDATE）或正对照未翻转（装置鉴别力缺失）或 R2 等式不成立（D5 abort 锚）");

    ObjectNode windowCheck = root.putObject("windowCheck");
    windowCheck.put(
        "note",
        "逐条被降权候选的窗口算术：gap = 该候选 fusedB（未降权跑融合分）− 同查询下位竞争者最大融合分；"
            + "window = 0.30×(1−w)（imageRouteWeight × (1 − w)），w 取 A 跑出参 BAND_WEIGHT_METADATA_KEY 实测值；"
            + "NO-RANK-CHANGE 必附本节；RANK-CHANGE 时本节仍为实测上下文");
    ArrayNode windowArray = windowCheck.putArray("entries");
    results.windowNodes().forEach(windowArray::add);

    root.put(
        "nonExtrapolationNote",
        "本锚结论只对本构造语料（2 base × 3 裁切变体 × 6 查询、TOP_K="
            + TOP_K
            + "）与本装配成立；若判 NO-RANK-CHANGE 且带内样本存在，则「降权在整个生产参数域内对名次无效」仅在与本构造同形的"
            + "窗口算术下成立，不可外推为「生产无位移」");

    ObjectNode isolation = root.putObject("isolationEvidence");
    isolation.put("embedInvocationsA", results.embedInvocationsA());
    isolation.put("embedInvocationsB", results.embedInvocationsB());
    isolation.put(
        "nonImageFusedByteEqualAcrossRuns", "maxTextScoreDelta=" + results.maxTextScoreDelta());
    isolation.put("inputIdentityVerified", "两跑降权入参逐条 fusionKey + 分数 Double.compare 相等（重放确定性结构证明）");
    isolation.put(
        "mockEvidence",
        "逐查询 verify(times(1))；A 出参被降权者分数=入参×w 且带 BAND_WEIGHT_METADATA_KEY；B 返回与入参逐条 "
            + "Double.compare==0；两跑 embed 请求数相等");

    // 腿 B（零外呼）：档位填充盘点 + 0.90 议题选项与代价（纯静态算子，与守卫共用同一实现）
    ObjectNode legB = root.putObject("legB");
    legB.put(
        "note",
        "腿 B 与腿 A 同锚不同域：纯冻结锚复算（image-variant-cross-matrix-v1 / near-dup-exchange-rate-v1 / "
            + "near-dup-ingest-gate-probe-v1），零外呼；无论腿 A 成败本节都交付（proposal §7）");
    legB.set("tierFill", NearDupTierFillAnalysisTest.computeTierFill());
    legB.set("thresholdOptions", NearDupTierFillAnalysisTest.computeThresholdOptions());

    ObjectNode materialNode = root.putObject("corpusMaterial");
    ArrayNode imageArray = materialNode.putArray("imageRecords");
    for (NearDupIngestS1IT.StoredRecord record : material.imageRecords()) {
      imageArray.add(record.toJson());
    }
    ArrayNode textArray = materialNode.putArray("textRecords");
    for (NearDupIngestS1IT.StoredRecord record : material.textRecords()) {
      textArray.add(record.toJson());
    }
    ArrayNode queryVectorArray = materialNode.putArray("queryImageVectors");
    for (NearDupS1CropCorpus.CropQuery query : corpus.queries()) {
      ObjectNode node = queryVectorArray.addObject();
      node.put("index", query.index());
      node.put("vector", base64(material.queryImageVectors().get(query.index())));
    }
    ArrayNode cacheArray = materialNode.putArray("embedCache");
    for (Map.Entry<String, float[]> entry : material.embedCache().entrySet()) {
      ObjectNode node = cacheArray.addObject();
      node.put("text", entry.getKey());
      node.put("vector", base64(entry.getValue()));
    }
    root.put(
        "byteIdentityBasis",
        "锚内 corpusMaterial 冻结两集合记录与查询向量；默认复跑本地重建语料（确定性断言与 bases/variants/queries 冻结面"
            + "逐字段一致）→ 从 corpusMaterial 重建两集合 → 重放生产检索链（A/B）→ 重算全部派生字段与腿 B 静态算子 → "
            + "剔除顶层 runtime 块后与磁盘锚逐字节比对；复跑不构造真实 provider，零外呼由结构保证");
    root.put(
        "paceNote",
        "embedImage 按 "
            + PACE_MILLIS
            + "ms 主动降速（定标卡实测 2 QPS 中途 429），429 有界退避 "
            + THROTTLE_RETRIES
            + " 次；节奏只影响墙钟，不改判据");
    return root;
  }

  /** D5 abort 锚：已花调用 + 缺口原样落盘（与完成锚不同 schema，bandEntryStatus=ABORTED）。 */
  private void writeAbortAnchor(
      NearDupS1CropCorpus.CropCorpus corpus,
      NearDupIngestS1IT.TrackingProvider provider,
      List<NearDupIngestS1IT.FrozenIngest> completed,
      Throwable failure)
      throws Exception {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", "near-dup-band-entry-and-tier-fill");
    root.put("stage", "BAND-ENTRY");
    root.put("bandEntryStatus", "ABORTED");
    root.put("decisionVersion", "NEAR-DUP-BAND-ENTRY-1.0");
    root.put("decisionOwner", "OWNER");
    root.put("verdict", "INCONCLUSIVE");
    root.put("missingEvidence", "R2-EQUATION-BROKEN");
    root.put(
        "verdictReason",
        "R2 逐图等式不成立或链路失败，立即 abort（D5）："
            + failure.getClass().getSimpleName()
            + ": "
            + head(String.valueOf(failure.getMessage()), 300));
    ObjectNode ledger = root.putObject("callLedger");
    ledger.put("vision", provider.visionCalls.get());
    ledger.put("embedImage", provider.embedImageCalls.get());
    ledger.put("embed", provider.embedCalls.get());
    ledger.put("total", provider.totalCalls());
    ledger.put("cap", BAND_CALL_CAP);
    ledger.put("target", BAND_CALL_TARGET);
    ledger.put("authorization", AUTHORIZATION_NOTE);
    ledger.put("basis", "abort 时 TrackingProvider 实测已花调用（外呼上限仍受硬帽约束）");
    root.put("completedVariants", completed.size());
    ArrayNode nodes = root.putArray("completedIngest");
    for (NearDupIngestS1IT.FrozenIngest ingest : completed) {
      nodes.add(ingest.toJson());
    }
    root.put("baselineHead", BASELINE_HEAD);
    String json = MAPPER.writeValueAsString(root);
    if (!json.contains("\r")) {
      Files.createDirectories(ANCHOR_PATH.toAbsolutePath().getParent());
      Files.writeString(ANCHOR_PATH, json, StandardCharsets.UTF_8);
      System.out.println(
          "[near-dup-band] ABORT 锚已落盘（已花外呼=" + provider.totalCalls() + "）：" + failure.getMessage());
    }
  }

  // ==================== 默认复跑：语料重建 + A/B 重放 + 逐字节比对（零外呼） ====================

  private void replayAndVerifyByteIdentity() throws Exception {
    assertTrue(Files.exists(ANCHOR_PATH), "锚不存在：默认复跑必须先有写锚产物（跳过≠复跑）: " + ANCHOR_PATH);
    String stored = Files.readString(ANCHOR_PATH, StandardCharsets.UTF_8);
    JsonNode root = MAPPER.readTree(stored);
    if ("ABORTED".equals(root.path("bandEntryStatus").asText())) {
      assertTrue(root.path("verdict").asText().equals("INCONCLUSIVE"), "abort 锚必须判 INCONCLUSIVE");
      JsonNode ledger = root.path("callLedger");
      assertEquals(
          ledger.path("vision").asInt()
              + ledger.path("embedImage").asInt()
              + ledger.path("embed").asInt(),
          ledger.path("total").asInt(),
          "abort 锚 callLedger.total 必须等于三分项之和");
      assertTrue(ledger.path("total").asInt() <= BAND_CALL_CAP, "abort 锚已花调用也必须不超硬帽");
      System.out.println("[near-dup-band] abort 锚自洽复跑通过（无 A/B 面可重放，按 D5 口径）");
      return;
    }

    // 1) 语料确定性：重建后与锚冻结面逐字段一致
    NearDupS1CropCorpus.CropCorpus corpus = NearDupS1CropCorpus.generate();
    assertCorpusMatchesAnchor(corpus, root);

    // 2) 材料重建（两集合 + 查询向量 + embed 缓存 + 入库冻结行）
    JsonNode materialNode = root.path("corpusMaterial");
    List<NearDupIngestS1IT.StoredRecord> imageRecords = new ArrayList<>();
    for (JsonNode node : materialNode.path("imageRecords")) {
      imageRecords.add(NearDupIngestS1IT.StoredRecord.fromJson(node));
    }
    List<NearDupIngestS1IT.StoredRecord> textRecords = new ArrayList<>();
    for (JsonNode node : materialNode.path("textRecords")) {
      textRecords.add(NearDupIngestS1IT.StoredRecord.fromJson(node));
    }
    Map<Integer, float[]> queryImageVectors = new LinkedHashMap<>();
    for (JsonNode node : materialNode.path("queryImageVectors")) {
      queryImageVectors.put(node.path("index").asInt(), fromBase64(node.path("vector").asText()));
    }
    Map<String, float[]> embedCache = new LinkedHashMap<>();
    for (JsonNode node : materialNode.path("embedCache")) {
      embedCache.put(node.path("text").asText(), fromBase64(node.path("vector").asText()));
    }
    List<NearDupIngestS1IT.FrozenIngest> ingests = new ArrayList<>();
    for (JsonNode node : root.path("ingest")) {
      ingests.add(NearDupIngestS1IT.FrozenIngest.fromJson(node));
    }
    NearDupIngestS1IT.Material material =
        new NearDupIngestS1IT.Material(
            imageRecords, textRecords, queryImageVectors, embedCache, ingests);
    assertTrue(!imageRecords.isEmpty(), "锚缺 imageRecords");
    assertTrue(!textRecords.isEmpty(), "锚缺 textRecords");

    // 3) A/B 重放（StubEmbeddingProvider：embed 只从锚缓存取；embedImage/vision 触发即抛——零外呼结构保证）
    BandResults recomputed = runAblationPasses(corpus, material);

    // 4) 重建锚并逐字节比对（剔除顶层 runtime 块——活值投影不参与字节恒等，卡 26 NF-1 先例）
    ObjectNode rebuilt = buildAnchor(corpus, material, recomputed);
    JsonNode storedRuntime = root.path("runtime");
    assertTrue(storedRuntime.isObject(), "磁盘锚缺顶层 runtime 块");
    assertVisionModelWhitelist(storedRuntime);
    assertVisionModelWhitelist(rebuilt.path("runtime"));
    ((ObjectNode) root).remove("runtime");
    ((ObjectNode) rebuilt).remove("runtime");
    assertEquals(
        MAPPER.writeValueAsString(root),
        MAPPER.writeValueAsString(rebuilt),
        "默认复跑序列化与磁盘锚不一致（剔除 runtime 后；锚被手改或重放算术漂移）——逐字节比对失败");
    System.out.println(
        "[near-dup-band] 默认复跑逐字节一致（verdict="
            + root.path("verdict").asText()
            + " externalCalls="
            + root.path("callLedger").path("total").asInt()
            + " 零外呼）");
  }

  /** runtime 白名单锁死：excludedFromReplay 恰等于 [visionModel]。 */
  private static void assertVisionModelWhitelist(JsonNode runtime) {
    JsonNode excluded = runtime.path("excludedFromReplay");
    assertTrue(excluded.isArray() && excluded.size() == 1, "runtime.excludedFromReplay 必须恰 1 项");
    assertEquals("visionModel", excluded.get(0).asText(), "白名单只许 visionModel");
  }

  /** 语料重建与锚冻结面逐字段一致（bases/variants/queries 全量；漂移即红——写锚后语料规则被改过的唯一暴露面）。 */
  private static void assertCorpusMatchesAnchor(
      NearDupS1CropCorpus.CropCorpus corpus, JsonNode root) {
    JsonNode bases = root.path("bases");
    assertEquals(corpus.bases().size(), bases.size(), "base 数与锚不一致（语料漂移）");
    for (int index = 0; index < corpus.bases().size(); index++) {
      NearDupS1CropCorpus.CropBase base = corpus.bases().get(index);
      JsonNode node = bases.get(index);
      assertEquals(base.key(), node.path("key").asText(), "base 键漂移 @" + index);
      assertEquals(base.pageNo(), node.path("pageNo").asInt(), "base 页码漂移 @" + index);
      assertEquals(base.width(), node.path("width").asInt(), "base 宽漂移 @" + index);
      assertEquals(base.height(), node.path("height").asInt(), "base 高漂移 @" + index);
      assertEquals(base.pageTextChars(), node.path("pageTextChars").asInt(), "页文本长度漂移 @" + index);
    }
    JsonNode variants = root.path("variants");
    assertEquals(corpus.variants().size(), variants.size(), "变体数与锚不一致（语料漂移）");
    for (int index = 0; index < corpus.variants().size(); index++) {
      NearDupS1CropCorpus.CropVariant variant = corpus.variants().get(index);
      JsonNode node = variants.get(index);
      assertEquals(variant.key(), node.path("key").asText(), "变体键漂移 @" + index);
      assertEquals(variant.kind(), node.path("kind").asText(), "变体类型漂移 @" + index);
      assertEquals(variant.width(), node.path("width").asInt(), "变体宽漂移 @" + index);
      assertEquals(variant.height(), node.path("height").asInt(), "变体高漂移 @" + index);
      assertEquals(variant.param(), node.path("param").asText(), "变体参数漂移 @" + index);
    }
    JsonNode queries = root.path("queries");
    assertEquals(corpus.queries().size(), queries.size(), "查询数与锚不一致（语料漂移）");
    for (int index = 0; index < corpus.queries().size(); index++) {
      NearDupS1CropCorpus.CropQuery query = corpus.queries().get(index);
      JsonNode node = queries.get(index);
      assertEquals(query.index(), node.path("index").asInt(), "查询序漂移 @" + index);
      assertEquals(query.baseKey(), node.path("baseKey").asText(), "查询 base 漂移 @" + index);
      assertEquals(query.form(), node.path("form").asText(), "查询形态漂移 @" + index);
      assertEquals(query.text(), node.path("text").asText(), "查询文本漂移 @" + index);
    }
  }

  // ==================== 装配与辅助 ====================

  /** 生产配置矩阵（与卡 31 同款：切片总开关 + 视觉路开 + 图片数帽 + 关查询改写）。 */
  private static Map<String, Object> config() {
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put(PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, Boolean.TRUE);
    matrix.put(
        PdfImageSliceIngestService.MAX_IMAGES_KEY, NearDupIngestGateProbeIT.MAX_IMAGES_PER_PDF);
    matrix.put("rag.retrieval.query-rewrite.enabled", Boolean.FALSE);
    return matrix;
  }

  private static String visionModel() {
    String override = System.getProperty(VISION_MODEL_PROP);
    return override == null || override.isBlank() ? DEFAULT_VISION_MODEL : override.strip();
  }

  private static ModelProviderProperties properties(String apiKey) {
    ModelProviderProperties properties = new ModelProviderProperties();
    properties.setApiKey(apiKey);
    properties.setBaseUrl(COMPATIBLE_BASE_URL);
    properties.setVisionModel(visionModel());
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

  /** 反射调包私有 {@code DocumentIngestionSupport#chunkEmbedAndWrite}（生产入库装配尾段本体；卡 27 同款反射桥）。 */
  @SuppressWarnings("unchecked")
  private static List<DocumentVectorChunkEntity> chunkEmbedAndWrite(
      UploadedFileEntity file,
      List<DocumentChunk> chunks,
      EmbeddingService embeddingService,
      CrmVectorStore textStore,
      ChunkIdBackfillMapper chunkMapper)
      throws Exception {
    Method method =
        Class.forName("com.slz.crm.knowledge.document.DocumentIngestionSupport")
            .getDeclaredMethod(
                "chunkEmbedAndWrite",
                UploadedFileEntity.class,
                List.class,
                EmbeddingService.class,
                CrmVectorStore.class,
                DocumentVectorChunkMapper.class,
                DocumentService.class);
    method.setAccessible(true);
    return (List<DocumentVectorChunkEntity>)
        method.invoke(
            null,
            file,
            chunks,
            embeddingService,
            textStore,
            chunkMapper.asMapper(),
            new DocumentService());
  }

  /** 子块行主键回填 mapper（insertBatch 按行序发号；persistBatch 语义要求生成键立即可用）。 */
  private static final class ChunkIdBackfillMapper {

    private long next;

    ChunkIdBackfillMapper(long seed) {
      this.next = seed;
    }

    DocumentVectorChunkMapper asMapper() {
      return (DocumentVectorChunkMapper)
          Proxy.newProxyInstance(
              DocumentVectorChunkMapper.class.getClassLoader(),
              new Class<?>[] {DocumentVectorChunkMapper.class},
              (proxy, method, args) -> {
                if ("insertBatch".equals(method.getName())) {
                  List<?> rows = (List<?>) args[0];
                  for (Object row : rows) {
                    ((DocumentVectorChunkEntity) row).setId(next++);
                  }
                  return rows.size();
                }
                Class<?> returnType = method.getReturnType();
                if (!returnType.isPrimitive()) {
                  return null;
                }
                return returnType == boolean.class ? Boolean.FALSE : 0;
              });
    }
  }

  /** 文档实体（payload 口径与生产 createVectorRecord 对齐）。 */
  private static UploadedFileEntity fileOf(
      NearDupS1CropCorpus.CropVariant variant, String filename) {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setDocumentId("near-dup-band-" + variant.key());
    file.setOriginalFilename(filename);
    file.setFilename(filename);
    file.setFileType("pdf");
    file.setKnowledgeBase(KB_ID);
    file.setStorageKey("near-dup-band/" + filename);
    return file;
  }

  private static NearDupS1CropCorpus.CropBase baseOf(
      NearDupS1CropCorpus.CropCorpus corpus, NearDupS1CropCorpus.CropQuery query) {
    NearDupS1CropCorpus.CropBase base = corpus.baseByKey(query.baseKey());
    assertNotNull(base, "查询回指的 base 缺失: " + query.baseKey());
    return base;
  }

  /** 变体 → 其描述子块行的最终 sources() 口径键（IMAGE:{chunkId}；由入库冻结行的 chunkIds 推出）。 */
  private static Map<String, String> variantByChunkKey(NearDupIngestS1IT.Material material) {
    Map<String, String> byChunk = new LinkedHashMap<>();
    for (NearDupIngestS1IT.FrozenIngest ingest : material.ingests()) {
      for (String chunkId : ingest.chunkIds()) {
        byChunk.put(SourceReferenceFactory.IMAGE_CHUNK_PREFIX + chunkId, ingest.variantKey());
      }
    }
    return byChunk;
  }

  /** 变体在其 base 查询里的图像路候选键（变体单图入库 ⇒ 其首个子块行即描述切片行）。 */
  private static String chunkKeyOf(
      NearDupIngestS1IT.Material material, NearDupS1CropCorpus.CropVariant variant) {
    for (NearDupIngestS1IT.FrozenIngest ingest : material.ingests()) {
      if (ingest.variantKey().equals(variant.key()) && !ingest.chunkIds().isEmpty()) {
        return SourceReferenceFactory.IMAGE_CHUNK_PREFIX + ingest.chunkIds().get(0);
      }
    }
    throw new IllegalStateException("变体缺入库冻结行: " + variant.key());
  }

  /** 候选命中 → 最终 sources() 口径的 chunkId（SourceReferenceFactory 唯一判别位；卡 28 同款）。 */
  private static String sourceKeyOf(VectorSearchHit hit) {
    return SourceReferenceFactory.from(hit, 0D).chunkId();
  }

  /** metadata 值转字符串（null 保持 null）。 */
  private static String stringOf(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /** 按 chunkId 取单条来源引用；缺返回 null。 */
  private static NearDupIngestS1IT.SourceRef sourceByKey(
      List<NearDupIngestS1IT.SourceRef> sources, String chunkId) {
    for (NearDupIngestS1IT.SourceRef source : sources) {
      if (source.chunkId().equals(chunkId)) {
        return source;
      }
    }
    return null;
  }

  /** callRealMethod 返回值还原（静态 mock 旁路观测专用）。 */
  @SuppressWarnings("unchecked")
  private static List<RetrievalCandidate> castCandidates(Object value) {
    return (List<RetrievalCandidate>) value;
  }

  /** 消融专用 embed 计数桩：只统计真实到达 provider 的 embed 请求（其余转发给锚缓存桩；卡 28 同款）。 */
  private static ModelProvider embedCountingProvider(
      ModelProvider delegate, AtomicInteger embedCounter) {
    Answer<Object> forwarding = AdditionalAnswers.delegatesTo(delegate);
    return Mockito.mock(
        ModelProvider.class,
        Mockito.withSettings()
            .name("near-dup-band-embed-counter")
            .defaultAnswer(
                invocation -> {
                  if ("embed".equals(invocation.getMethod().getName())) {
                    embedCounter.incrementAndGet();
                  }
                  return forwarding.answer(invocation);
                }));
  }

  private static String head(String text, int limit) {
    if (text == null) {
      return null;
    }
    return text.length() <= limit ? text : text.substring(0, limit) + "…";
  }

  private static void putAnchorSha(ObjectNode parent, String field, String fileName) {
    Path path = Path.of("docs", "rag-quality", fileName);
    try {
      parent.put(field, "docs/rag-quality/" + fileName);
      parent.put(field + "Sha256", RealPdfAssets.sha256(Files.readAllBytes(path)));
    } catch (Exception exception) {
      throw new IllegalStateException("上游锚读取失败（只读）: " + path, exception);
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
    for (int index = 0; index < vector.length; index++) {
      vector[index] = buffer.getFloat();
    }
    return vector;
  }
}
