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
import com.slz.crm.knowledge.retrieval.ConstraintQuerySplitter;
import com.slz.crm.knowledge.retrieval.ImageSimilarityGate;
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
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import java.nio.ByteBuffer;
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
 * S1 入库 + 权重消融（near-dup-ingest-cooccurrence 任务组 C，failsafe IT，A 档真跑）： {@code
 * docs/rag-quality/near-dup-ingest-cooccurrence-v1.json} 的<b>唯一写入口</b>。
 *
 * <p><b>测什么</b>：48 图（带内 7 对全部参与者 + 非带内变体补齐）分 3 份合成 PDF 走<b>真实 ingest 路径</b>入同一 KB，与既有真实 PDF
 * 的文本切片同库共存；逐图自查询跑两遍同一生产检索链——A 生产降权（三档 0.75/0.6/0.5）、B 消融（乘数全 1.0）。逐候选落 {@code (图像路分, 文本路分, 融合分,
 * tier, band, rankA, rankB)}，用权重消融把口径四的反事实 68.5% 变成实测（决策 D2）。
 *
 * <p><b>消融 B 的实现（零 {@code src/main} diff，决策 D3）</b>：三档乘数是 {@code ImageSimilarityGate} 的编译期常量，
 * 唯一无侵入的「全 1.0」通路 = 把图像阈值键 {@code rag.retrieval.image-similarity-threshold} 设为准入下限 0.982——此时 {@code
 * NEAR_DUPLICATE} 档为空集（floor == threshold），降权分支对全部候选不触发，而<b>准入面逐位不变</b> （{@code
 * effectiveFloor(0.982)=0.982} 与生产 {@code effectiveFloor(0.9944)=0.982} 相同，store 层 minScore 同值）。
 * 除乘数路径外两条链完全同构，R3（两次 chunkId 集合逐条相同）与 R5（文本路分两次相等）由此成为有咬合力的断言。
 *
 * <p><b>文本竞争者（零外呼）</b>：页面文本向量复用 {@code near-dup-displacement-evidence-v1.json} 冻结的 36 条 {@code
 * sourceType=pdf} 真实 embedding（同一 3 份真实 PDF、同一抽取规则，锚只读）；只重映射 metadata 的 knowledgeBaseId 到本卡
 * KB（装配动作，不改向量与文本）。
 *
 * <p><b>查询侧零额外外呼</b>：查询文本 = 该图自己的 VLM 描述（ingest 时已 embed，经 provider 内嵌缓存命中，零外呼）； 查询向量 = ingest
 * 暂存的视觉向量原值（不重编码）。拆句器衍生的子查询文本在检索前预热 embed 并计入 externalCalls （英文转写通常零拆分；有拆分时硬帽保护会先抛）。
 *
 * <p><b>三模式</b>（与 {@code NearDupDisplacementEvidenceIT} 同款）：未 opt-in 整类跳过；opt-in + write 全量真跑落锚；
 * opt-in 无 write 从锚冻结物本地重建两集合、重放 T/A/B 三次生产检索链、重算全部判据字段后与磁盘锚<b>逐字节比对</b> （embed
 * 由冻结缓存供给，embedImage/vision 桩抛错自证零外呼）。
 */
@DisplayName("S1 入库共现与权重消融：48 图真 ingest + A/B 两遍生产检索（RAG_NEAR_DUP_INGEST_REAL 显式 opt-in）")
class NearDupIngestCooccurrenceIT {

  /** 真跑 opt-in 键（本卡门控变量名冻结）。 */
  static final String OPT_IN_ENV = "RAG_NEAR_DUP_INGEST_REAL";

  /** 锚写入门控属性（冻结）。 */
  static final String WRITE_PROP = "near.dup.ingest.cooccurrence.write";

  /** S1 锚落盘路径（写集内唯一 JSON 产物）。 */
  static final Path ANCHOR_PATH =
      Path.of("docs", "rag-quality", "near-dup-ingest-cooccurrence-v1.json");

  /** v2 定标锚（只读：带内对选取依据）。 */
  private static final Path CALIBRATION_ANCHOR =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  /** 位移证据锚（只读：页面文本竞争者的冻结向量来源）。 */
  private static final Path DISPLACEMENT_ANCHOR =
      Path.of("docs", "rag-quality", "near-dup-displacement-evidence-v1.json");

  /** 序列化显式锁 LF（.gitattributes 对 *.json 强制 eol=lf）。 */
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

  /** 图片切片类目（生产切片链的类目过滤键）。 */
  private static final String IMAGE_CATEGORY = ImageCrossModalFixture.IMAGE_CATEGORY;

  /** S1 上下文知识库 id（独立取值：9904=位移卡、9905=S0 探针，本卡 9906）。 */
  private static final String EVAL_KB_ID = "9906";

  private static final long EVAL_KB_ID_LONG = 9906L;

  /** 切片行主键起点（避开位移锚页面文本的 9400 段，防跨源 id 视觉混淆）。 */
  private static final long FIRST_CHUNK_ID = 9800L;

  /** 召回截断：取「不裁候选面」的大 top-k（候选池 ≤ 文本全集 + image 全集），保证 A/B 候选集合差 = 纯融合效应。 */
  static final int TOP_K = 200;

  /** S1 外呼硬帽（Owner 授权 144；真调用点累加，触帽即抛）。 */
  static final int S1_CALL_CAP = 144;

  /** B 消融模式的图像阈值（= 生产准入下限；NEAR_DUP 档空 ⇒ 乘数全 1.0，准入面与 A 逐位相同）。 */
  static final double ABLATION_THRESHOLD = 0.982D;

  /** 带内区间（定标锚判读口径，冻结）。 */
  static final double BAND_LOW = 0.9887D;

  static final double BAND_HIGH = 0.9944D;

  /** 语料图最短边下限（生产 PdfImageExtractor.MIN_EDGE_PX 同值：低于它的图过不了真实提取，禁选）。 */
  private static final int MIN_EDGE_PX = 64;

  /** 非带内变体对照的最低张数（判据 R4）。 */
  static final int MIN_NON_BAND_VARIANTS = 6;

  /** 选取总图数（判据 R4 冻结值）。 */
  static final int SELECTED_IMAGES = 48;

  /** embedImage 调用节奏（毫秒）：定标卡实测 2 QPS 中途 429，压到 ~0.9 QPS。 */
  private static final long PACE_MILLIS = 1100L;

  /** 429 有界退避重试次数。 */
  private static final int THROTTLE_RETRIES = 3;

  private static final long THROTTLE_BACKOFF_MILLIS = 5000L;

  /** 视觉模型覆盖属性。 */
  private static final String VISION_MODEL_PROP = "rag.pdf.image.vision-model";

  private static final String DEFAULT_VISION_MODEL = "qwen-vl-plus";

  /** 融合闭式校验容差（消融纯度的数值咬合，远大于 double 噪声、远小于最小位移分差）。 */
  private static final double FUSION_EPSILON = 1e-9;

  @Test
  @DisplayName("S1：48 图真 ingest + T/A/B 三遍检索落消融证据锚（写锚 / 默认复跑逐字节校验）")
  void runCooccurrence() throws Exception {
    if (!"1".equals(System.getenv(OPT_IN_ENV))) {
      Assumptions.assumeTrue(false, "未显式 opt-in（" + OPT_IN_ENV + "=1）：跳过 S1 消融真跑（默认 CI 零外呼）");
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

  /** 是否写锚模式（opt-in 且显式 {@code -Dnear.dup.ingest.cooccurrence.write=true}）。 */
  private static boolean isWriteMode() {
    return "true".equalsIgnoreCase(System.getProperty(WRITE_PROP));
  }

  // ==================== 选取规则（跑前冻结，从定标锚实读） ====================

  /** 一张入选图（base 或变体）。 */
  record SelectedImage(
      String assetKey,
      String kind,
      boolean variant,
      String baseKey,
      byte[] png,
      int width,
      int height) {}

  /** 一条带内对（参与者的入库覆盖对账单位）。 */
  record BandPair(double cosine, String baseKey, String variantKey, String kind) {}

  /** 选取结果。 */
  record Selection(List<SelectedImage> images, List<BandPair> bandPairs, int nonBandVariantCount) {}

  /** 守卫复验入口（包内可见；零外呼重放选取规则）。 */
  static Selection selectImagesInGuard(NearDuplicateCorpus.Corpus corpus) throws Exception {
    return selectImages(corpus);
  }

  /**
   * 选取规则（冻结，判据 R4）：带内对 = {@code stratum=near-duplicate ∧ cos∈[0.9887,0.9944)}（锚实读，断言 7 条），取全部 14
   * 个参与者；再按锚 pairs 序补同档非带内变体（最短边 ≥ 64px，低于即跳过续补）至恰 48 张。凑不齐 ⇒ 抛错停手（不换语料）。
   */
  static Selection selectImages(NearDuplicateCorpus.Corpus corpus) throws Exception {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(CALIBRATION_ANCHOR));
    List<BandPair> bandPairs = new ArrayList<>();
    List<JsonNode> nearDupPairs = new ArrayList<>();
    for (JsonNode pair : root.path("pairs")) {
      if ("near-duplicate".equals(pair.path("stratum").asText())) {
        nearDupPairs.add(pair);
        double cosine = pair.path("cosine").asDouble();
        if (cosine >= BAND_LOW && cosine < BAND_HIGH) {
          bandPairs.add(
              new BandPair(
                  cosine,
                  pair.path("leftKey").asText(),
                  pair.path("rightKey").asText(),
                  pair.path("kind").asText()));
        }
      }
    }
    assertEquals(7, bandPairs.size(), "定标锚带内对必须 7 条，实测 " + bandPairs.size());

    Map<String, NearDuplicateCorpus.Base> bases = new LinkedHashMap<>();
    for (NearDuplicateCorpus.Base base : corpus.bases()) {
      bases.put(base.key(), base);
    }
    Map<String, NearDuplicateCorpus.Variant> variants = new LinkedHashMap<>();
    for (NearDuplicateCorpus.Variant variant : corpus.variants()) {
      variants.put(variant.key(), variant);
    }

    LinkedHashSet<String> chosen = new LinkedHashSet<>();
    List<SelectedImage> images = new ArrayList<>();
    for (BandPair pair : bandPairs) {
      NearDuplicateCorpus.Base base = bases.get(pair.baseKey());
      NearDuplicateCorpus.Variant variant = variants.get(pair.variantKey());
      assertNotNull(base, "带内 base 缺失于语料: " + pair.baseKey());
      assertNotNull(variant, "带内变体缺失于语料: " + pair.variantKey());
      // 带内参与者无条件入选（判据 R4 必选集，不换语料）；短边 < 64px 的实例会被生产提取器丢弃，
      // 属「带内候选未入库」的被测现象，由入库三态记录并走预注册 INSUFFICIENT-COVERAGE，不在选取期停手
      if (chosen.add(pair.baseKey())) {
        images.add(toSelected(base));
      }
      if (chosen.add(pair.variantKey())) {
        images.add(toSelected(variant));
      }
    }
    int participantCount = images.size();
    assertEquals(14, participantCount, "带内参与者必须 14（7 base + 7 variant），实测 " + participantCount);

    int nonBandVariantCount = 0;
    for (JsonNode pair : nearDupPairs) {
      if (images.size() >= SELECTED_IMAGES) {
        break;
      }
      String variantKey = pair.path("rightKey").asText();
      if (!chosen.add(variantKey)) {
        continue;
      }
      NearDuplicateCorpus.Variant variant = variants.get(variantKey);
      if (variant == null) {
        chosen.remove(variantKey);
        continue;
      }
      if (Math.min(variant.width(), variant.height()) < MIN_EDGE_PX) {
        chosen.remove(variantKey);
        continue;
      }
      images.add(toSelected(variant));
      nonBandVariantCount++;
    }
    assertEquals(
        SELECTED_IMAGES,
        images.size(),
        "同档补齐后必须恰 " + SELECTED_IMAGES + " 图（凑不齐即停，不许换语料），实测 " + images.size());
    assertTrue(
        nonBandVariantCount >= MIN_NON_BAND_VARIANTS,
        "非带内变体必须 ≥" + MIN_NON_BAND_VARIANTS + " 张（判据 R4），实测 " + nonBandVariantCount);
    return new Selection(List.copyOf(images), List.copyOf(bandPairs), nonBandVariantCount);
  }

  private static SelectedImage toSelected(NearDuplicateCorpus.Base base) {
    return new SelectedImage(
        base.key(), null, false, base.key(), base.png(), base.width(), base.height());
  }

  private static SelectedImage toSelected(NearDuplicateCorpus.Variant variant) {
    return new SelectedImage(
        variant.key(),
        variant.kind(),
        true,
        variant.baseKey(),
        variant.png(),
        variant.width(),
        variant.height());
  }

  // ==================== 写锚模式：全量真跑 ====================

  private void runRealAndWrite(String apiKey) throws Exception {
    NearDuplicateCorpus.Corpus corpus = NearDuplicateCorpus.generate();
    Selection selection = selectImages(corpus);

    CachedBudgetTrackingProvider provider =
        new CachedBudgetTrackingProvider(realProvider(properties(apiKey), apiKey), S1_CALL_CAP);
    EmbeddingService embeddingService = new EmbeddingService(provider, properties(apiKey));

    // 1) 48 图分 3 份合成 PDF（20/20/8）走真实 ingest 路径
    IngestBuild build = ingestRealPdfs(provider, embeddingService, selection);
    // ingest 阶段实测 == 派生 对账：vision 每张被生产提取器看到的图恰 1 次；过闸门图各耗 1 次 embedImage；
    // 同 base 变体的 VLM 描述可能逐字相同 ⇒ 同文本 embed 由缓存供给（零边际外呼），embed 计数按去重描述文本对账
    assertEquals(build.extractedCount(), provider.visionCalls.get(), "vision 外呼必须等于被提取图数（真计量点）");
    assertEquals(
        build.imageProbes().size(), provider.embedImageCalls.get(), "embedImage 外呼必须等于过闸门图数");
    int distinctDescriptions =
        Set.copyOf(build.imageProbes().stream().map(ImageProbe::description).toList()).size();
    assertEquals(
        distinctDescriptions,
        provider.embedCalls.get(),
        "ingest 阶段文本 embed 外呼必须等于去重后的描述文本数（重复描述缓存命中不计）");

    // 2) 检索前预热拆句子查询（同文本 embed 走 provider 内嵌缓存零外呼；新文本计 1 次并受硬帽保护）
    int splitRoutePreembeds = prewarmSplitRoutes(provider, embeddingService, build);
    assertEquals(
        distinctDescriptions + splitRoutePreembeds,
        provider.embedCalls.get(),
        "预热后 embed 外呼必须等于去重描述数 + 拆分子查询预热数");

    // 3) T/A/B 三遍检索 + 判据计算
    ComputedResults results =
        computeResults(build, properties(apiKey), embeddingService, splitRoutePreembeds);

    ObjectNode root =
        buildAnchor(
            build,
            results,
            provider.visionCalls.get(),
            provider.embedImageCalls.get(),
            provider.embedCalls.get(),
            splitRoutePreembeds,
            provider.embedCache());
    Files.createDirectories(ANCHOR_PATH.toAbsolutePath().getParent());
    Files.writeString(ANCHOR_PATH, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
    System.out.println(
        "[near-dup-ingest-cooccurrence] 锚已落盘: "
            + ANCHOR_PATH.toAbsolutePath()
            + "（N_pass="
            + build.imageProbes().size()
            + " externalCalls="
            + provider.totalCalls()
            + " verdict="
            + results.verdict()
            + "）");
  }

  /** 一条入库图片的逐图结果。 */
  record ImageProbe(
      SelectedImage image,
      boolean gatePass,
      long chunkId,
      String description,
      float[] visualVector,
      float[] descriptionVector,
      String storageKey) {}

  /** 语料装配产物（冻结进锚 corpusMaterial 的全部原料）。 */
  record IngestBuild(
      Selection selection,
      List<Integer> pdfPageCounts,
      int extractedCount,
      List<ImageProbe> imageProbes,
      List<StoredRecord> imageRecords,
      List<StoredRecord> textRecords,
      List<StoredRecord> pageCompetitors,
      List<ImageState> imageStates) {}

  IngestBuild ingestRealPdfs(
      CachedBudgetTrackingProvider provider, EmbeddingService embeddingService, Selection selection)
      throws Exception {
    List<byte[]> pdfs =
        NearDupIngestPdfBuilder.buildPdfs(
            selection.images().stream().map(SelectedImage::png).toList());
    List<Integer> pageCounts = new ArrayList<>();
    for (int index = 0; index < pdfs.size(); index++) {
      int expected =
          Math.min(
              NearDupIngestPdfBuilder.MAX_IMAGES_PER_PDF,
              selection.images().size() - index * NearDupIngestPdfBuilder.MAX_IMAGES_PER_PDF);
      pageCounts.add(expected);
    }
    assertEquals(3, pdfs.size(), "48 图必须装 3 份 PDF（20/20/8），实测 " + pdfs.size());

    ObjectProvider<DynamicConfigService> configProvider =
        RagBenchmarkPipelineFactory.provider(RagBenchmarkPipelineFactory.dynamicConfig(configA()));
    List<ImageProbe> imageProbes = new ArrayList<>();
    List<StoredRecord> imageRecords = new ArrayList<>();
    List<StoredRecord> textRecords = new ArrayList<>();
    List<ImageState> imageStates = new ArrayList<>();
    int extractedTotal = 0;
    long nextChunkId = FIRST_CHUNK_ID;

    UserContextHolder.set(
        new UserContext(99061L, 5L, 101L, DataScopeLevel.ALL, "near-dup-ingest-cooccurrence"));
    try {
      for (int pdfIndex = 0; pdfIndex < pdfs.size(); pdfIndex++) {
        byte[] pdf = pdfs.get(pdfIndex);
        String filename = "near-dup-ingest-cooc-p" + (pdfIndex + 1) + ".pdf";
        // 提取一次并复用：页号集合 = 生产提取器真实看到的图（<64px 实例在此被丢，是被测现象不是构造缺陷）
        List<RealPdfAssets.Extracted> extracted =
            RealPdfAssets.extract(pdf, NearDupIngestPdfBuilder.MAX_IMAGES_PER_PDF);
        Set<Integer> extractedPages = new LinkedHashSet<>();
        for (RealPdfAssets.Extracted item : extracted) {
          extractedPages.add(item.pageNo());
        }
        extractedTotal += extracted.size();

        PdfIngestImageFixture.InMemoryImageStorage storage =
            PdfIngestImageFixture.inMemoryStorage();
        PdfImageSliceIngestService ingestService =
            new PdfImageSliceIngestService(
                provider,
                configProvider,
                provider((com.slz.crm.platform.contract.TokenUsageRecorder) null),
                provider(storage));
        List<DocumentChunk> slices =
            ingestService.buildSlices(pdf, filename, IMAGE_CATEGORY, EVAL_KB_ID);
        Map<String, float[]> staged = RealPdfAssets.stagedVectors();

        List<DocumentVectorChunkEntity> children = new ArrayList<>(slices.size());
        List<Long> chunkIds = new ArrayList<>(slices.size());
        for (int index = 0; index < slices.size(); index++) {
          long chunkId = nextChunkId++;
          chunkIds.add(chunkId);
          children.add(
              row(
                  chunkId,
                  index,
                  slices.get(index),
                  filename,
                  "near-dup-ingest-cooc-p" + (pdfIndex + 1)));
        }
        UploadedFileEntity file = file(filename, "near-dup-ingest-cooc-p" + (pdfIndex + 1));
        ImageVectorStore imageStore = new ImageVectorStore(new CapturingInMemoryStore());
        ImageVectorFlushSupport.flush(file, children, slices, imageStore);
        // flush 后 image 集合里已有的记录即本 PDF 的 image 记录；暂存向量按 storageKey 回查
        Map<String, float[]> stagedCopy = new LinkedHashMap<>(staged);

        // 入库三态逐图记录：slice.pageNo()（页号 = PDF 内图序）回指入选图下标
        Map<Integer, Long> passedPages = new LinkedHashMap<>();
        for (int index = 0; index < slices.size(); index++) {
          DocumentChunk slice = slices.get(index);
          long chunkId = chunkIds.get(index);
          int imageOrdinal =
              pdfIndex * NearDupIngestPdfBuilder.MAX_IMAGES_PER_PDF + slice.pageNo() - 1;
          SelectedImage selected = selection.images().get(imageOrdinal);
          String storageKey = ImageSliceMetadata.storageKeyOf(slice.extraMetadata());
          float[] visualVector = stagedCopy.get(storageKey);
          assertNotNull(visualVector, selected.assetKey() + " 缺视觉向量（视觉路未生效）");
          float[] descriptionVector = embeddingService.embed(slice.text());
          imageProbes.add(
              new ImageProbe(
                  selected,
                  true,
                  chunkId,
                  slice.text(),
                  visualVector,
                  descriptionVector,
                  storageKey));
          passedPages.put(slice.pageNo(), chunkId);
          Map<String, Object> metadata = descriptionMetadata(filename, chunkId, slice, storageKey);
          textRecords.add(
              new StoredRecord(
                  "neardup-ingest-txt-" + chunkId,
                  file.getDocumentId(),
                  String.valueOf(chunkId),
                  index,
                  slice.text(),
                  descriptionVector,
                  metadata));
        }
        imageRecords.addAll(((CapturingInMemoryStore) backingOf(imageStore)).captured());

        int first = pdfIndex * NearDupIngestPdfBuilder.MAX_IMAGES_PER_PDF;
        int last =
            Math.min(first + NearDupIngestPdfBuilder.MAX_IMAGES_PER_PDF, selection.images().size());
        for (int ordinal = first; ordinal < last; ordinal++) {
          int pageInPdf = ordinal - first + 1;
          SelectedImage selected = selection.images().get(ordinal);
          if (!extractedPages.contains(pageInPdf)) {
            imageStates.add(
                new ImageState(selected.assetKey(), selected.kind(), "EXTRACTION-DROPPED", null));
          } else if (passedPages.containsKey(pageInPdf)) {
            imageStates.add(
                new ImageState(
                    selected.assetKey(),
                    selected.kind(),
                    "GATE-PASSED",
                    String.valueOf(passedPages.get(pageInPdf))));
          } else {
            imageStates.add(
                new ImageState(selected.assetKey(), selected.kind(), "GATE-REJECTED", null));
          }
        }
      }
    } finally {
      UserContextHolder.clear();
    }
    assertTrue(
        extractedTotal <= selection.images().size() && extractedTotal > 0,
        "提取总量必须落在 (0, 入选数]：实测 " + extractedTotal);
    assertEquals(selection.images().size(), imageStates.size(), "逐图三态必须覆盖全部入选图");
    List<StoredRecord> pageCompetitors = loadPageCompetitors();
    return new IngestBuild(
        selection,
        pageCounts,
        extractedTotal,
        List.copyOf(imageProbes),
        List.copyOf(imageRecords),
        List.copyOf(textRecords),
        pageCompetitors,
        List.copyOf(imageStates));
  }

  /** 一张入选图的入库终态（EXTRACTION-DROPPED / GATE-REJECTED / GATE-PASSED）。 */
  record ImageState(String assetKey, String kind, String status, String chunkId) {}

  private static CrmVectorStore backingOf(ImageVectorStore store) {
    Object backing = ReflectionTestUtils.getField(store, "backing");
    assertNotNull(backing, "ImageVectorStore.backing 缺失");
    return (CrmVectorStore) backing;
  }

  /** 描述文本记录 metadata（生产 createVectorRecord 同形：sourceType=IMAGE + 资产键）。 */
  private static Map<String, Object> descriptionMetadata(
      String filename, long chunkId, DocumentChunk slice, String storageKey) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", EVAL_KB_ID);
    metadata.put("category", "");
    metadata.put("filename", filename);
    metadata.put("fileType", "pdf");
    metadata.put("pageNo", (long) slice.pageNo());
    metadata.put("rowIndex", 0L);
    metadata.put("chunkId", String.valueOf(chunkId));
    metadata.put(ImageSliceMetadata.KEY_SOURCE_TYPE, ImageSliceMetadata.SOURCE_TYPE_IMAGE);
    metadata.put(ImageSliceMetadata.KEY_IMAGE_ASSET_ID, String.valueOf(chunkId));
    metadata.put(ImageSliceMetadata.KEY_IMAGE_STORAGE_KEY, storageKey);
    return metadata;
  }

  /**
   * 页面文本竞争者：复用位移锚冻结的 36 条 {@code sourceType=pdf} 记录（同一 3 份真实 PDF、同一抽取规则的真实 embedding）。 只重映射
   * metadata.knowledgeBaseId 到本卡 KB（装配动作），向量/文本/documentId 原样保留。
   */
  private static List<StoredRecord> loadPageCompetitors() throws Exception {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(DISPLACEMENT_ANCHOR));
    List<StoredRecord> pageRecords = new ArrayList<>();
    for (JsonNode node : root.path("corpusMaterial").path("textRecords")) {
      if (node.path("metadata").has(ImageSliceMetadata.KEY_IMAGE_ASSET_ID)) {
        continue;
      }
      StoredRecord record = StoredRecord.fromJson(node);
      Map<String, Object> metadata = new LinkedHashMap<>(record.metadata());
      metadata.put("knowledgeBaseId", EVAL_KB_ID);
      pageRecords.add(
          new StoredRecord(
              record.id(),
              record.documentId(),
              record.chunkId(),
              record.chunkIndex(),
              record.text(),
              record.vector(),
              metadata));
    }
    assertTrue(pageRecords.size() >= MIN_NON_BAND_VARIANTS, "位移锚页面文本竞争者不足: " + pageRecords.size());
    return pageRecords;
  }

  /** 拆句器衍生子查询预热（检索前集中外呼，硬帽保护）；返回预热次数。 */
  private int prewarmSplitRoutes(
      CachedBudgetTrackingProvider provider, EmbeddingService embeddingService, IngestBuild build) {
    int preembeds = 0;
    for (ImageProbe probe : build.imageProbes()) {
      for (String route : ConstraintQuerySplitter.split(probe.description())) {
        if (route.equals(probe.description())) {
          continue;
        }
        if (!provider.hasEmbed(route)) {
          embeddingService.embed(route);
          preembeds++;
        }
      }
    }
    return preembeds;
  }

  // ==================== T/A/B 检索与判据计算（write 与 replay 共用） ====================

  /** 一次判据计算的全部产物（锚 results 区的直接来源）。 */
  record ComputedResults(
      ArrayNode queryNodes,
      int bandPairsCovered,
      List<String> uncoveredPairs,
      int r5CheckedCandidates,
      double r5MaxAbsDeviation,
      int textSideNearDuplicates,
      String verdict,
      String verdictReason,
      ArrayNode displacedEvidence) {}

  ComputedResults computeResults(
      IngestBuild build,
      ModelProviderProperties runProperties,
      EmbeddingService embeddingService,
      int splitRoutePreembeds) {
    ModelProvider cachedProvider = embeddingProviderOf(embeddingService);
    KnowledgeRetrievalServiceImpl serviceA =
        buildService(build, runProperties, cachedProvider, configA());
    KnowledgeRetrievalServiceImpl serviceB =
        buildService(build, runProperties, cachedProvider, configB());

    // 图像集合成员（SourceReference 层 chunkId = IMAGE:{rowId}；纯文本 = 数字串）
    Set<String> imageMemberSourceIds = new LinkedHashSet<>();
    for (StoredRecord record : build.imageRecords()) {
      imageMemberSourceIds.add(SourceReferenceFactory.IMAGE_CHUNK_PREFIX + record.chunkId());
    }
    // 描述向量（R7 文本侧近重复用）
    Map<String, float[]> descriptionVectors = new LinkedHashMap<>();
    for (StoredRecord record : build.textRecords()) {
      descriptionVectors.put(record.chunkId(), record.vector());
    }

    List<ObjectNode> queryNodes = new ArrayList<>();
    ArrayNode displacedEvidence = MAPPER.createArrayNode();
    int r5Checked = 0;
    double r5MaxDeviation = 0D;
    int textSideNearDuplicates = 0;

    UserContextHolder.set(
        new UserContext(99061L, 5L, 101L, DataScopeLevel.ALL, "near-dup-ingest-cooccurrence"));
    try {
      int queryIndex = 0;
      for (ImageProbe probe : build.imageProbes()) {
        KnowledgeRetrievalPort.RetrievalQuery base =
            new KnowledgeRetrievalPort.RetrievalQuery(
                probe.description(), 99061L, List.of(EVAL_KB_ID), TOP_K, null, null);
        KnowledgeRetrievalPort.RetrievalQuery withVector =
            new KnowledgeRetrievalPort.RetrievalQuery(
                probe.description(),
                99061L,
                List.of(EVAL_KB_ID),
                TOP_K,
                probe.visualVector(),
                null);
        List<SourceReference> textOnly = sources(serviceA.retrieve(base));
        List<SourceReference> production = sources(serviceA.retrieve(withVector));
        List<SourceReference> ablation = sources(serviceB.retrieve(withVector));

        // R3：两次候选 chunkId 集合逐条相同（消融唯一差异必须是乘数路径）
        Set<String> idsA = chunkIds(production);
        Set<String> idsB = chunkIds(ablation);
        assertEquals(idsA, idsB, "R3 红：A/B 候选 chunkId 集合不同（消融不纯）query=" + probe.image().assetKey());

        Map<String, Double> textScores = new LinkedHashMap<>();
        for (SourceReference source : textOnly) {
          textScores.put(source.chunkId(), source.relevanceScore());
        }

        ObjectNode queryNode = MAPPER.createObjectNode();
        queryNode.put("queryIndex", queryIndex);
        queryNode.put("assetKey", probe.image().assetKey());
        if (probe.image().kind() == null) {
          queryNode.putNull("kind");
        } else {
          queryNode.put("kind", probe.image().kind());
        }
        queryNode.put("chunkId", SourceReferenceFactory.IMAGE_CHUNK_PREFIX + probe.chunkId());
        queryNode.put("queryText", probe.description());
        queryNode.put("imageCandidateCount", countImageMembers(production, imageMemberSourceIds));
        queryNode.put(
            "textCandidateCount",
            production.size() - countImageMembers(production, imageMemberSourceIds));

        ArrayNode candidateNodes = queryNode.putArray("candidates");
        List<double[]> displacedCandidates = new ArrayList<>();
        int imageCandidateCount = 0;
        for (int rankA = 0; rankA < production.size(); rankA++) {
          SourceReference sourceA = production.get(rankA);
          int rankB = indexOfChunkId(ablation, sourceA.chunkId());
          Double textScore = textScores.get(sourceA.chunkId());
          boolean imageMember = imageMemberSourceIds.contains(sourceA.chunkId());
          Double fusedA = sourceA.relevanceScore();
          Double fusedB = ablation.get(rankB).relevanceScore();
          String tier = null;
          String band = null;
          Double weight = null;
          Double imageRouteScore = null;
          if (imageMember) {
            imageCandidateCount++;
            float[] candidateVector = imageVectorOf(build, sourceA.chunkId());
            double cosine = cosineExact(probe.visualVector(), candidateVector);
            tier = ImageSimilarityGate.tierOf(cosine, ImageSimilarityGate.DEFAULT_THRESHOLD);
            band = ImageSimilarityGate.bandOf(cosine, ImageSimilarityGate.DEFAULT_THRESHOLD);
            weight =
                ImageSimilarityGate.TIER_NEAR_DUPLICATE.equals(tier)
                    ? ImageSimilarityGate.weightOfBand(band)
                    : 1.0D;
            imageRouteScore =
                textScore == null ? fusedB / 0.3D : (fusedB - 0.7D * textScore) / 0.3D;
            // 消融闭式校验：fusedA == 0.7t + 0.3·img·w（NEAR_DUP）或 fusedA == fusedB（其余档）
            double predictedA =
                ImageSimilarityGate.TIER_NEAR_DUPLICATE.equals(tier)
                    ? (textScore == null
                        ? 0.3D * imageRouteScore * weight
                        : 0.7D * textScore + 0.3D * imageRouteScore * weight)
                    : fusedB;
            double deviation = Math.abs(fusedA - predictedA);
            r5Checked++;
            r5MaxDeviation = Math.max(r5MaxDeviation, deviation);
            assertTrue(
                deviation <= FUSION_EPSILON,
                "R5/消融闭式校验红（消融污染文本路或权重重建错）: query="
                    + probe.image().assetKey()
                    + " chunk="
                    + sourceA.chunkId()
                    + " deviation="
                    + deviation);
          } else {
            // 纯文本候选：两模式融合分必须同为 0.7·t（文本路零污染，R5 核心）
            if (textScore != null) {
              double deviationA = Math.abs(fusedA - 0.7D * textScore);
              double deviationB = Math.abs(fusedB - 0.7D * textScore);
              r5Checked += 2;
              r5MaxDeviation = Math.max(r5MaxDeviation, Math.max(deviationA, deviationB));
              assertTrue(
                  deviationA <= FUSION_EPSILON && deviationB <= FUSION_EPSILON,
                  "R5 红：纯文本候选两模式分不等（消融污染文本路）chunk=" + sourceA.chunkId());
            } else {
              // 纯文本候选必有 T 分（T 与 A/B 的文本召回同参同库，缺失 = 文本路不一致 = 消融不纯）
              assertTrue(false, "R3/R5 红：纯文本候选在 T 模式缺失（文本路两次召回不一致）chunk=" + sourceA.chunkId());
            }
          }
          ObjectNode node = candidateNodes.addObject();
          node.put("chunkId", sourceA.chunkId());
          node.put("sourceType", sourceA.sourceType());
          node.put("imageRouteMember", imageMember);
          if (textScore == null) {
            node.putNull("textRouteScore");
          } else {
            node.put("textRouteScore", textScore);
          }
          if (imageRouteScore == null) {
            node.putNull("imageRouteScore");
          } else {
            node.put("imageRouteScore", imageRouteScore);
          }
          node.put("fusedScoreA", fusedA);
          node.put("fusedScoreB", fusedB);
          if (tier == null) {
            node.putNull("tier");
            node.putNull("band");
            node.putNull("weightA");
          } else {
            node.put("tier", tier);
            if (band == null) {
              node.putNull("band");
            } else {
              node.put("band", band);
            }
            node.put("weightA", weight);
          }
          node.put("rankA", rankA);
          node.put("rankB", rankB);

          // R6 候选对：带内图像候选 × 纯文本竞争者，A 被压 / B 翻回（严格分差翻转，无并列歧义）
          if (imageMember && ImageSimilarityGate.TIER_NEAR_DUPLICATE.equals(tier)) {
            for (int rankC = 0; rankC < production.size(); rankC++) {
              SourceReference competitor = production.get(rankC);
              if (imageMemberSourceIds.contains(competitor.chunkId())) {
                continue;
              }
              if (fusedA < competitor.relevanceScore() && fusedB > competitor.relevanceScore()) {
                displacedCandidates.add(
                    new double[] {
                      rankA, rankC, rankB, indexOfChunkId(ablation, competitor.chunkId())
                    });
                ObjectNode evidence = displacedEvidence.addObject();
                evidence.put("queryIndex", queryIndex);
                evidence.put("queryAssetKey", probe.image().assetKey());
                evidence.put("imageChunkId", sourceA.chunkId());
                evidence.put("imageBand", band);
                evidence.put("imageWeightA", weight);
                evidence.put("textCompetitorChunkId", competitor.chunkId());
                evidence.put("rankA_image", rankA);
                evidence.put("rankA_competitor", rankC);
                evidence.put("rankB_image", rankB);
                evidence.put("rankB_competitor", indexOfChunkId(ablation, competitor.chunkId()));
                evidence.put("fusedA_image", fusedA);
                evidence.put("fusedA_competitor", competitor.relevanceScore());
                evidence.put("fusedB_image", fusedB);
                evidence.put("fusedB_competitor", competitor.relevanceScore());
              }
            }
          }
        }
        assertEquals(
            imageCandidateCount, countImageMembers(production, imageMemberSourceIds), "图像候选计数自洽");
        // R7：变体描述与其它描述的文本向量近重复（仅解释用）
        int textSideCount = 0;
        for (Map.Entry<String, float[]> entry : descriptionVectors.entrySet()) {
          if (entry.getKey().equals(String.valueOf(probe.chunkId()))) {
            continue;
          }
          if (cosineExact(probe.descriptionVector(), entry.getValue()) >= BAND_LOW) {
            textSideCount++;
          }
        }
        textSideNearDuplicates += textSideCount;
        queryNode.put("textSideNearDuplicateCount", textSideCount);
        queryNode.put("displacedPairCount", displacedCandidates.size());
        queryNodes.add(queryNode);
        queryIndex++;
      }
    } finally {
      UserContextHolder.clear();
    }

    // 带内覆盖：7 对参与者的入库覆盖（双参与者都过闸门才计）
    Set<String> ingestedKeys = new LinkedHashSet<>();
    for (ImageProbe probe : build.imageProbes()) {
      ingestedKeys.add(probe.image().assetKey());
    }
    List<String> uncovered = new ArrayList<>();
    for (BandPair pair : build.selection().bandPairs()) {
      if (!ingestedKeys.contains(pair.baseKey()) || !ingestedKeys.contains(pair.variantKey())) {
        uncovered.add(pair.baseKey() + "<->" + pair.variantKey());
      }
    }
    int bandPairsCovered = build.selection().bandPairs().size() - uncovered.size();

    String verdict;
    String verdictReason;
    if (!displacedEvidence.isEmpty()) {
      verdict = "DISPLACED-IN-FUSION";
      verdictReason =
          "R6：存在「A 模式带内图像候选被纯文本竞争者压过、B 模式（乘数全 1.0）翻回」的严格分差翻转对 "
              + displacedEvidence.size()
              + " 条（逐条见 displacedEvidence）——降权在本语料上真实造成跨路位移";
    } else if (bandPairsCovered == build.selection().bandPairs().size()
        && !build.imageProbes().isEmpty()) {
      verdict = "NOT-DISPLACED-IN-FUSION";
      verdictReason = "R6：全部查询两模式排名与融合分零翻转，且带内 7 对参与者全覆盖入库——降权在本语料上未造成跨路位移";
    } else {
      verdict = "INSUFFICIENT-COVERAGE";
      verdictReason =
          "R6：带内候选未入库/被闸门拒/覆盖不足（未覆盖对 " + uncovered.size() + " 条），无法定案——禁硬判 NOT-DISPLACED";
    }

    return new ComputedResults(
        toArrayNode(queryNodes),
        bandPairsCovered,
        uncovered,
        r5Checked,
        r5MaxDeviation,
        textSideNearDuplicates,
        verdict,
        verdictReason,
        displacedEvidence);
  }

  private ModelProvider embeddingProviderOf(EmbeddingService embeddingService) {
    Object provider = ReflectionTestUtils.getField(embeddingService, "modelProvider");
    assertNotNull(provider, "EmbeddingService.modelProvider 缺失");
    return (ModelProvider) provider;
  }

  private KnowledgeRetrievalServiceImpl buildService(
      IngestBuild build,
      ModelProviderProperties runProperties,
      ModelProvider provider,
      Map<String, Object> configMatrix) {
    EmbeddingService embeddingService = new EmbeddingService(provider, runProperties);
    InMemoryVectorStore textStore = new InMemoryVectorStore();
    for (StoredRecord record : build.textRecords()) {
      textStore.upsert(record.toVectorRecord());
    }
    for (StoredRecord record : build.pageCompetitors()) {
      textStore.upsert(record.toVectorRecord());
    }
    ImageVectorStore imageStore = new ImageVectorStore(new InMemoryVectorStore());
    List<VectorRecord> imageVectorRecords = new ArrayList<>();
    for (StoredRecord record : build.imageRecords()) {
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
        RagBenchmarkPipelineFactory.provider(
            RagBenchmarkPipelineFactory.dynamicConfig(configMatrix));
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

  private static List<SourceReference> sources(KnowledgeRetrievalPort.RetrievalResult result) {
    return result.sources() == null ? List.of() : result.sources();
  }

  private static Set<String> chunkIds(List<SourceReference> sources) {
    Set<String> ids = new LinkedHashSet<>();
    for (SourceReference source : sources) {
      ids.add(source.chunkId());
    }
    return ids;
  }

  private static int indexOfChunkId(List<SourceReference> sources, String chunkId) {
    for (int index = 0; index < sources.size(); index++) {
      if (sources.get(index).chunkId().equals(chunkId)) {
        return index;
      }
    }
    throw new IllegalStateException("chunkId 不在候选中: " + chunkId);
  }

  private static int countImageMembers(
      List<SourceReference> sources, Set<String> imageMemberSourceIds) {
    int count = 0;
    for (SourceReference source : sources) {
      if (imageMemberSourceIds.contains(source.chunkId())) {
        count++;
      }
    }
    return count;
  }

  /** 由 SourceReference 层 chunkId（IMAGE:{rowId}）回查 image 集合向量。 */
  private float[] imageVectorOf(IngestBuild build, String sourceChunkId) {
    String rowId =
        sourceChunkId.startsWith(SourceReferenceFactory.IMAGE_CHUNK_PREFIX)
            ? sourceChunkId.substring(SourceReferenceFactory.IMAGE_CHUNK_PREFIX.length())
            : sourceChunkId;
    for (StoredRecord record : build.imageRecords()) {
      if (record.chunkId().equals(rowId)) {
        return record.vector();
      }
    }
    throw new IllegalStateException("图像候选缺向量记录: " + sourceChunkId);
  }

  /** 与 InMemoryVectorStore 逐位同款的余弦（tier/band 重建的前提是分母同源）。 */
  static double cosineExact(float[] left, float[] right) {
    if (left.length != right.length) {
      throw new IllegalArgumentException("向量维度不一致: " + left.length + " vs " + right.length);
    }
    double dot = 0;
    double leftNorm = 0;
    double rightNorm = 0;
    for (int index = 0; index < left.length; index++) {
      dot += (double) left[index] * right[index];
      leftNorm += (double) left[index] * left[index];
      rightNorm += (double) right[index] * right[index];
    }
    double result = 0;
    if (leftNorm != 0 && rightNorm != 0) {
      result = Math.max(0, Math.min(1, dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm))));
    }
    return result;
  }

  // ==================== 锚构建（write 与 replay 共用，字段顺序即序列化顺序） ====================

  private ObjectNode buildAnchor(
      IngestBuild build,
      ComputedResults results,
      int visionCalls,
      int embedImageCalls,
      int embedCalls,
      int splitRoutePreembeds,
      Map<String, float[]> embedCache) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", "near-dup-ingest-cooccurrence");
    root.put("stage", "S1-COOCCURRENCE");
    root.put("decisionVersion", "NEAR-DUP-INGEST-COOCCURRENCE-1.0");
    root.put("decisionOwner", "OWNER");

    ObjectNode criteria = root.putObject("criteria");
    criteria.put(
        "R2",
        "入库完整性：N 入选图、image 集合向量数 = N_pass、描述 chunk 数 = N_pass、externalCalls 实测对账（闸门拒图只耗 1 次 vision）");
    criteria.put("R3", "消融对照：逐查询 A（生产降权）/ B（乘数全 1.0）两遍，断言两次候选 chunkId 集合逐条相同（不同即消融不纯，红）");
    criteria.put("R4", "带内覆盖：48 图含全部 7 条带内对参与者 + ≥6 张非带内变体（同档按锚序补，禁换语料）");
    criteria.put("R5", "归因隔离：逐候选文本路分两次必须相等（闭式校验 fusedA−图像贡献 == fusedB−图像贡献；纯文本候选两模式同分）");
    criteria.put(
        "R6", "结论三选一：DISPLACED-IN-FUSION / NOT-DISPLACED-IN-FUSION / INSUFFICIENT-COVERAGE，禁造第四种");
    criteria.put("R7", "副作用留痕：textSideNearDuplicates 计数仅解释用，不作为判据");

    ObjectNode sourceAnchors = root.putObject("sourceAnchors");
    sourceAnchors.put("calibrationAnchor", "docs/rag-quality/image-threshold-calibration-v2.json");
    sourceAnchors.put("calibrationAnchorSha256", sha256OfFile(CALIBRATION_ANCHOR));
    sourceAnchors.put(
        "competitorAnchor", "docs/rag-quality/near-dup-displacement-evidence-v1.json");
    sourceAnchors.put("competitorAnchorSha256", sha256OfFile(DISPLACEMENT_ANCHOR));

    ObjectNode constants = root.putObject("productionConstants");
    constants.put("threshold", ImageSimilarityGate.DEFAULT_THRESHOLD);
    constants.put("ablationThreshold", ABLATION_THRESHOLD);
    constants.put("nearDuplicateFloor", ImageSimilarityGate.NEAR_DUPLICATE_FLOOR);
    constants.put("bandReferenceFloor", ImageSimilarityGate.BAND_REFERENCE_FLOOR);
    constants.put("weightHigh", ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_HIGH);
    constants.put("weightMid", ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_MID);
    constants.put("weightLow", ImageSimilarityGate.NEAR_DUPLICATE_WEIGHT_LOW);
    constants.put("textRouteWeight", 0.7D);
    constants.put("imageRouteWeight", 0.3D);
    constants.put("topK", TOP_K);
    constants.put("s1CallCap", S1_CALL_CAP);
    constants.put("cumulativeCallCap", 162);
    constants.put("visionModel", visionModel());
    root.put("baselineHead", "cdf3382");

    root.put(
        "ablationBasis",
        "B 模式 = 同一生产检索链 + rag.retrieval.image-similarity-threshold=0.982：NEAR_DUPLICATE 档为空集（floor==threshold）"
            + " ⇒ 降权分支对全部候选不触发（乘数全 1.0），准入面逐位不变（effectiveFloor(0.982)=0.982 == effectiveFloor(0.9944)，"
            + "store 层 minScore 同值）。三档乘数是 ImageSimilarityGate 编译期常量，此通路是零 src/main diff 的唯一全 1.0 形态。"
            + "R3（两次 chunkId 集合逐条相同）与 R5 闭式校验共同构成消融纯度断言");

    root.put(
        "selectionRule",
        "带内对 = stratum=near-duplicate ∧ cos∈[0.9887,0.9944)（定标锚实读，断言 7 条），取全部 14 参与者；"
            + "再按锚 pairs 序补同档非带内变体（最短边 ≥64px，低于跳过续补）至恰 48 张。凑不齐即停，不换语料");

    ObjectNode corpusNode = root.putObject("corpus");
    corpusNode.put("selectedCount", build.selection().images().size());
    ArrayNode pdfCounts = corpusNode.putArray("pdfPageCounts");
    build.pdfPageCounts().forEach(pdfCounts::add);
    corpusNode.put("extractedCount", build.extractedCount());
    corpusNode.put("bandPairCount", build.selection().bandPairs().size());
    corpusNode.put("bandParticipantCount", 14);
    corpusNode.put("nonBandVariantCount", build.selection().nonBandVariantCount());

    ObjectNode ingestion = root.putObject("ingestion");
    int extractionDropped =
        (int)
            build.imageStates().stream()
                .filter(state -> "EXTRACTION-DROPPED".equals(state.status()))
                .count();
    int gateRejected =
        (int)
            build.imageStates().stream()
                .filter(state -> "GATE-REJECTED".equals(state.status()))
                .count();
    ingestion.put("N", build.selection().images().size());
    ingestion.put("N_extracted", build.extractedCount());
    ingestion.put("extractionDropped", extractionDropped);
    ingestion.put("gateRejected", gateRejected);
    ingestion.put("N_pass", build.imageProbes().size());
    ingestion.put("imageVectorCount", build.imageRecords().size());
    ingestion.put("descriptionChunkCount", build.textRecords().size());
    ingestion.put("pageCompetitorCount", build.pageCompetitors().size());
    ingestion.put(
        "stateSemantics",
        "EXTRACTION-DROPPED = 短边 <64px 被生产提取器丢弃（未耗任何外呼）；GATE-REJECTED = 被生产质量闸门拒"
            + "（耗 1 次 vision，无下游两路）；GATE-PASSED = 全链路入库（3 次外呼）。三态逐图冻结于 corpusMaterial.imageStates");
    ingestion.put(
        "pageCompetitorSource",
        "near-dup-displacement-evidence-v1.json corpusMaterial.textRecords（sourceType=pdf，向量冻结复用，仅重映射 knowledgeBaseId）");

    root.put("externalCalls", visionCalls + embedImageCalls + embedCalls);
    ObjectNode calls = root.putObject("callBreakdown");
    calls.put("visionCalls", visionCalls);
    calls.put("embedImageCalls", embedImageCalls);
    calls.put("embedCalls", embedCalls);
    calls.put("splitRoutePreembeds", splitRoutePreembeds);
    calls.put(
        "r2Accounting",
        "externalCalls = N_extracted(vision) + N_pass(embedImage) + (去重描述文本数 + splitRoutePreembeds)(embed)；"
            + "提取丢弃不耗外呼、闸门拒图只耗 1 次 vision、重复描述缓存命中零边际外呼（同 base 变体的 VLM 描述可能逐字相同）。"
            + "== 3×N 当且仅当零提取丢弃、N_pass==N、描述全去重且无拆分子查询预热。"
            + "查询文本 = 该图自己的描述（ingest 已 embed，缓存命中零外呼）；查询向量 = ingest 暂存视觉向量原值（不重编码）");
    calls.put("semantics", "三计数由 CachedBudgetTrackingProvider 在真调用点累加（写 0 判假绿），触硬帽即抛");

    ObjectNode coverage = root.putObject("coverage");
    coverage.put("bandPairsTotal", build.selection().bandPairs().size());
    coverage.put("bandPairsCovered", results.bandPairsCovered());
    ArrayNode uncoveredNode = coverage.putArray("uncoveredPairs");
    results.uncoveredPairs().forEach(uncoveredNode::add);
    coverage.put("nonBandVariantCount", build.selection().nonBandVariantCount());

    ArrayNode queryArray = root.putArray("queries");
    results.queryNodes().forEach(queryArray::add);

    ObjectNode r5 = root.putObject("r5");
    r5.put("consistent", true);
    r5.put("checkedCandidates", results.r5CheckedCandidates());
    r5.put("maxAbsDeviation", results.r5MaxAbsDeviation());
    r5.put("epsilon", FUSION_EPSILON);
    r5.put("note", "consistent 由计算过程断言保证（任何越 ε 残差即抛错终止，不可能带红值落锚）；maxAbsDeviation 为实测最大残差");

    ObjectNode r7 = root.putObject("r7");
    r7.put("textSideNearDuplicates", results.textSideNearDuplicates());
    r7.put("note", "变体描述与其它描述的文本向量近重复对计数（文本向量余弦 ≥ 0.9887），仅解释 R6 用，不作为判据");

    root.put("verdict", results.verdict());
    root.put("verdictReason", results.verdictReason());
    root.set("displacedEvidence", results.displacedEvidence());

    ArrayNode missingEvidence = root.putArray("missingEvidence");
    if (!results.uncoveredPairs().isEmpty()) {
      missingEvidence.add("带内对未全覆盖入库（闸门拒图连带丢弃，proposal §3.2 被测现象）：解耦图像向量与描述需另立卡（改生产行为，本卡禁改）");
    }
    if (results.displacedEvidence().isEmpty()
        && !"NOT-DISPLACED-IN-FUSION".equals(results.verdict())) {
      missingEvidence.add("无位移翻转对且覆盖不足：跨路位移问题在更真实分布上是否发生仍开放，须另立卡扩样");
    }

    ObjectNode material = root.putObject("corpusMaterial");
    ArrayNode imageArray = material.putArray("imageRecords");
    for (StoredRecord record : build.imageRecords()) {
      imageArray.add(record.toJson());
    }
    ArrayNode textArray = material.putArray("textRecords");
    for (StoredRecord record : build.textRecords()) {
      textArray.add(record.toJson());
    }
    ArrayNode pageArray = material.putArray("pageCompetitorRecords");
    for (StoredRecord record : build.pageCompetitors()) {
      pageArray.add(record.toJson());
    }
    ArrayNode stateArray = material.putArray("imageStates");
    for (ImageState state : build.imageStates()) {
      ObjectNode node = stateArray.addObject();
      node.put("assetKey", state.assetKey());
      if (state.kind() == null) {
        node.putNull("kind");
      } else {
        node.put("kind", state.kind());
      }
      node.put("status", state.status());
      if (state.chunkId() == null) {
        node.putNull("chunkId");
      } else {
        node.put("chunkId", state.chunkId());
      }
    }
    ArrayNode cacheArray = material.putArray("embedCache");
    for (Map.Entry<String, float[]> entry : embedCache.entrySet()) {
      ObjectNode node = cacheArray.addObject();
      node.put("text", entry.getKey());
      node.put("vector", base64(entry.getValue()));
    }
    ArrayNode queryVectors = material.putArray("queryVectors");
    for (ImageProbe probe : build.imageProbes()) {
      ObjectNode node = queryVectors.addObject();
      node.put("queryIndex", build.imageProbes().indexOf(probe));
      node.put("assetKey", probe.image().assetKey());
      if (probe.image().kind() == null) {
        node.putNull("kind");
      } else {
        node.put("kind", probe.image().kind());
      }
      node.put("chunkId", String.valueOf(probe.chunkId()));
      node.put("queryText", probe.description());
      node.put("vector", base64(probe.visualVector()));
    }
    root.put(
        "byteIdentityBasis",
        "锚内 corpusMaterial 冻结全部输入向量与文本；默认复跑从锚本地重建两集合、重放 T/A/B 三遍生产检索链、重算全部判据字段逐值断言后"
            + "整体序列化与磁盘锚逐字节比对（embed 由冻结缓存供给，embedImage/vision 桩抛错自证零外呼）");
    return root;
  }

  // ==================== 默认复跑：锚重建 + 重放 + 逐字节比对（零外呼） ====================

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
    List<StoredRecord> pageRecords = new ArrayList<>();
    for (JsonNode node : material.path("pageCompetitorRecords")) {
      pageRecords.add(StoredRecord.fromJson(node));
    }
    assertTrue(imageRecords.size() > 0, "锚缺 imageRecords");
    assertTrue(textRecords.size() > 0, "锚缺 textRecords");
    assertEquals(
        root.path("ingestion").path("imageVectorCount").asInt(-1),
        imageRecords.size(),
        "imageVectorCount 与冻结记录数不一致");
    assertEquals(
        root.path("ingestion").path("descriptionChunkCount").asInt(-1),
        textRecords.size(),
        "descriptionChunkCount 与冻结记录数不一致");
    assertEquals(
        root.path("ingestion").path("pageCompetitorCount").asInt(-1),
        pageRecords.size(),
        "pageCompetitorCount 与冻结记录数不一致");

    // 选取规则复验（零外呼）：重新生成语料 + 选取，断言与锚 corpus 区自洽
    NearDuplicateCorpus.Corpus corpus = NearDuplicateCorpus.generate();
    Selection selection = selectImages(corpus);
    assertEquals(
        root.path("corpus").path("selectedCount").asInt(-1),
        selection.images().size(),
        "选取规则复验：入选图数漂移");
    assertEquals(
        root.path("corpus").path("nonBandVariantCount").asInt(-1),
        selection.nonBandVariantCount(),
        "选取规则复验：非带内变体数漂移");

    // 重建 ImageProbe（查询向量从锚冻结，逐字段对齐）
    List<ImageProbe> probes = new ArrayList<>();
    Map<String, StoredRecord> imageRecordByChunk = new LinkedHashMap<>();
    for (StoredRecord record : imageRecords) {
      imageRecordByChunk.put(record.chunkId(), record);
    }
    Map<String, SelectedImage> selectedByKey = new LinkedHashMap<>();
    for (SelectedImage image : selection.images()) {
      selectedByKey.put(image.assetKey(), image);
    }
    Map<String, float[]> descriptionVectorByChunk = new LinkedHashMap<>();
    for (StoredRecord record : textRecords) {
      descriptionVectorByChunk.put(record.chunkId(), record.vector());
    }
    for (JsonNode node : material.path("queryVectors")) {
      String assetKey = node.path("assetKey").asText();
      String rowId = node.path("chunkId").asText();
      StoredRecord imageRecord = imageRecordByChunk.get(rowId);
      assertNotNull(imageRecord, "查询向量回指的 image 记录缺失: " + rowId);
      SelectedImage selected = selectedByKey.get(assetKey);
      assertNotNull(selected, "查询 assetKey 不在复验选取集: " + assetKey);
      float[] descriptionVector = descriptionVectorByChunk.get(rowId);
      assertNotNull(descriptionVector, "描述向量缺失: " + rowId);
      probes.add(
          new ImageProbe(
              selected,
              true,
              Long.parseLong(rowId),
              node.path("queryText").asText(),
              fromBase64(node.path("vector").asText()),
              descriptionVector,
              null));
    }
    assertEquals(
        root.path("ingestion").path("N_pass").asInt(-1), probes.size(), "N_pass 与冻结查询数不一致");

    // 重建逐图三态（从锚冻结）与 embedCache（零外呼自证缓存面）
    List<ImageState> imageStates = new ArrayList<>();
    for (JsonNode node : material.path("imageStates")) {
      JsonNode kind = node.path("kind");
      JsonNode chunkId = node.path("chunkId");
      imageStates.add(
          new ImageState(
              node.path("assetKey").asText(),
              kind.isMissingNode() || kind.isNull() ? null : kind.asText(),
              node.path("status").asText(),
              chunkId.isMissingNode() || chunkId.isNull() ? null : chunkId.asText()));
    }
    assertEquals(
        root.path("ingestion").path("N").asInt(-1), imageStates.size(), "imageStates 数与入选图数不一致");
    Map<String, float[]> embedCache = new LinkedHashMap<>();
    for (JsonNode node : material.path("embedCache")) {
      embedCache.put(node.path("text").asText(), fromBase64(node.path("vector").asText()));
    }
    assertTrue(embedCache.size() > 0, "锚缺 embedCache");

    // 重建 bandPairs（来自定标锚实读，与 selection 同源）
    IngestBuild build =
        new IngestBuild(
            selection,
            pdfPageCountsOf(root),
            root.path("corpus").path("extractedCount").asInt(-1),
            List.copyOf(probes),
            List.copyOf(imageRecords),
            List.copyOf(textRecords),
            pageRecords,
            List.copyOf(imageStates));

    // 零外呼自证：桩 provider 对 embedImage/vision 一律抛错；embed 只从锚缓存取
    StubEmbeddingProvider stub = new StubEmbeddingProvider(embedCache);
    ComputedResults results =
        computeResults(
            build,
            properties("replay-no-key"),
            new EmbeddingService(stub, properties("replay-no-key")),
            0);
    assertEquals(
        0, stub.embedImageAttempts.get() + stub.visionAttempts.get(), "默认复跑必须零外呼（桩被触发即失败）");

    int gatePassCount = probes.size();
    ObjectNode rebuilt =
        buildAnchor(
            build,
            results,
            root.path("callBreakdown").path("visionCalls").asInt(-1),
            root.path("callBreakdown").path("embedImageCalls").asInt(-1),
            root.path("callBreakdown").path("embedCalls").asInt(-1),
            root.path("callBreakdown").path("splitRoutePreembeds").asInt(-1),
            embedCache);
    String rebuiltJson = MAPPER.writeValueAsString(rebuilt);
    assertEquals(stored, rebuiltJson, "默认复跑序列化与磁盘锚不一致（锚被手改或重放算术漂移）——逐字节比对失败");
    System.out.println(
        "[near-dup-ingest-cooccurrence] 默认复跑逐字节一致（N_pass="
            + gatePassCount
            + " verdict="
            + results.verdict()
            + " 零外呼）");
  }

  private static List<Integer> pdfPageCountsOf(JsonNode root) {
    List<Integer> counts = new ArrayList<>();
    for (JsonNode node : root.path("corpus").path("pdfPageCounts")) {
      counts.add(node.asInt());
    }
    return counts;
  }

  // ==================== 装配与辅助 ====================

  /** A 生产配置矩阵（阈值键不设 = 默认 0.9944）。 */
  private static Map<String, Object> configA() {
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put(PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, Boolean.TRUE);
    matrix.put(
        PdfImageSliceIngestService.MAX_IMAGES_KEY, NearDupIngestPdfBuilder.MAX_IMAGES_PER_PDF);
    matrix.put("rag.retrieval.query-rewrite.enabled", Boolean.FALSE);
    return matrix;
  }

  /** B 消融配置矩阵：唯一差异 = 图像阈值设为准入下限（NEAR_DUP 档空 ⇒ 乘数全 1.0）。 */
  private static Map<String, Object> configB() {
    Map<String, Object> matrix = new LinkedHashMap<>(configA());
    matrix.put(ImageSimilarityGate.THRESHOLD_KEY, ABLATION_THRESHOLD);
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
        provider(nativeChat), provider(embeddingModel), properties, environment);
  }

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

  private static String sha256OfFile(Path path) {
    try {
      return RealPdfAssets.sha256(Files.readAllBytes(path));
    } catch (Exception exception) {
      throw new IllegalStateException("上游锚读取失败（只读）: " + path, exception);
    }
  }

  private static ArrayNode toArrayNode(List<ObjectNode> nodes) {
    ArrayNode array = MAPPER.createArrayNode();
    nodes.forEach(array::add);
    return array;
  }

  private static String base64(float[] vector) {
    ByteBuffer buffer = ByteBuffer.allocate(vector.length * 4);
    for (float value : vector) {
      buffer.putFloat(value);
    }
    return Base64.getEncoder().encodeToString(buffer.array());
  }

  private static float[] fromBase64(String encoded) {
    byte[] bytes = Base64.getDecoder().decode(encoded);
    ByteBuffer buffer = ByteBuffer.wrap(bytes);
    float[] vector = new float[bytes.length / 4];
    for (int i = 0; i < vector.length; i++) {
      vector[i] = buffer.getFloat();
    }
    return vector;
  }

  /** 切片行实体（生产口径「自增主键即 chunkId」）。 */
  private static DocumentVectorChunkEntity row(
      long chunkId, int index, DocumentChunk chunk, String filename, String documentId) {
    DocumentVectorChunkEntity entity = new DocumentVectorChunkEntity();
    entity.setId(chunkId);
    entity.setDocumentId(documentId);
    entity.setChunkIndex(index);
    entity.setChunkText(chunk.text());
    entity.setCategory(chunk.category());
    entity.setFilename(filename);
    entity.setPageNo(chunk.pageNo());
    entity.setRowIndex(chunk.rowIndex());
    entity.setChunkRole("CHILD");
    entity.setExtraMetadataJson(ImageSliceMetadata.toJson(chunk.extraMetadata()));
    return entity;
  }

  /** 文档实体（payload 口径）。 */
  private static UploadedFileEntity file(String filename, String documentId) {
    UploadedFileEntity file = new UploadedFileEntity();
    file.setDocumentId(documentId);
    file.setOriginalFilename(filename);
    file.setFilename(filename);
    file.setFileType("pdf");
    file.setKnowledgeBase(EVAL_KB_ID);
    file.setStorageKey("near-dup-ingest-cooc/" + filename);
    return file;
  }

  // ==================== 数据载体 ====================

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
   * 捕获型内存向量库：转发给真 {@link InMemoryVectorStore}，同时截获写入记录（image 集合记录由生产 createVectorRecord 生成、point id
   * 是随机 UUID，必须截获落锚 replay 才能原样重建）。
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
   * 真调用点计数装饰器（带同文本 embed 缓存）：vision/embedImage/embed 在真调用点累加并触硬帽；embed 对「整请求全部文本已缓存」
   * 的调用直接供给缓存向量（零外呼零计数）——这是查询侧零额外外呼的机制本体（查询文本 = ingest 已 embed 的描述）。
   */
  static final class CachedBudgetTrackingProvider implements ModelProvider {

    private final ModelProvider delegate;

    private final int callCap;

    final AtomicInteger visionCalls = new AtomicInteger();

    final AtomicInteger embedImageCalls = new AtomicInteger();

    final AtomicInteger embedCalls = new AtomicInteger();

    private final Map<String, float[]> embedCache = new LinkedHashMap<>();

    CachedBudgetTrackingProvider(ModelProvider delegate, int callCap) {
      this.delegate = delegate;
      this.callCap = callCap;
    }

    boolean hasEmbed(String text) {
      return embedCache.containsKey(text);
    }

    Map<String, float[]> embedCache() {
      return embedCache;
    }

    int totalCalls() {
      return visionCalls.get() + embedImageCalls.get() + embedCalls.get();
    }

    private void guardBudget() {
      if (totalCalls() >= callCap) {
        throw new IllegalStateException("外呼已达本阶段硬帽 " + callCap + "（Owner 授权上限，硬边界）：拒绝继续外呼");
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
      List<String> instructions = request.getInstructions();
      if (instructions.size() == 1 && embedCache.containsKey(instructions.get(0))) {
        return ModelCallResult.ofVector(
            embedCache.get(instructions.get(0)), "ingest-embed-cache", null, null);
      }
      guardBudget();
      embedCalls.incrementAndGet();
      ModelCallResult<float[]> result = delegate.embed(request);
      if (result != null && result.vector() != null) {
        for (String text : instructions) {
          embedCache.putIfAbsent(text, result.vector());
        }
      }
      return result;
    }

    @Override
    public ModelCallResult<float[]> embedImage(byte[] imageBytes, String mimeType) {
      guardBudget();
      pause(PACE_MILLIS);
      embedImageCalls.incrementAndGet();
      for (int attempt = 0; ; attempt++) {
        try {
          return delegate.embedImage(imageBytes, mimeType);
        } catch (HttpClientErrorException exception) {
          if (exception.getStatusCode().value() != 429 || attempt >= THROTTLE_RETRIES) {
            throw exception;
          }
          long wait = THROTTLE_BACKOFF_MILLIS << attempt;
          System.out.println(
              "[near-dup-ingest-cooccurrence] embedImage 429 退避 " + wait + " ms 后重试");
          pause(wait);
        }
      }
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      guardBudget();
      visionCalls.incrementAndGet();
      return delegate.vision(prompt);
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt, ModelCallOptions options) {
      guardBudget();
      visionCalls.incrementAndGet();
      return callVisionWithRetry(() -> delegate.vision(prompt, options));
    }

    private ModelCallResult<String> callVisionWithRetry(
        java.util.function.Supplier<ModelCallResult<String>> call) {
      for (int attempt = 0; ; attempt++) {
        try {
          return call.get();
        } catch (HttpClientErrorException exception) {
          if (exception.getStatusCode().value() != 429 || attempt >= THROTTLE_RETRIES) {
            throw exception;
          }
          long wait = THROTTLE_BACKOFF_MILLIS << attempt;
          System.out.println("[near-dup-ingest-cooccurrence] vision 429 退避 " + wait + " ms 后重试");
          pause(wait);
        }
      }
    }

    private static void pause(long millis) {
      try {
        Thread.sleep(millis);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("真跑被中断（不落半成品锚）", exception);
      }
    }
  }

  /**
   * replay 桩 provider：embed 只从锚缓存取（未命中即抛——缓存面就是写锚时链路真实嵌入过的全部文本）； embedImage/vision/chat 一律抛错并计数（计数
   * > 0 即复跑发生了外呼）。
   */
  static final class StubEmbeddingProvider implements ModelProvider {

    private final Map<String, float[]> embedCache;

    final AtomicInteger embedImageAttempts = new AtomicInteger();

    final AtomicInteger visionAttempts = new AtomicInteger();

    StubEmbeddingProvider(Map<String, float[]> embedCache) {
      this.embedCache = embedCache;
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
      embedImageAttempts.incrementAndGet();
      throw new IllegalStateException("默认复跑必须零外呼：embedImage 桩被触发");
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      visionAttempts.incrementAndGet();
      throw new IllegalStateException("默认复跑必须零外呼：vision 桩被触发");
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt, ModelCallOptions options) {
      visionAttempts.incrementAndGet();
      throw new IllegalStateException("默认复跑必须零外呼：vision 桩被触发");
    }

    private static String prefix(String text) {
      return text.length() <= 60 ? text : text.substring(0, 60) + "…";
    }
  }
}
