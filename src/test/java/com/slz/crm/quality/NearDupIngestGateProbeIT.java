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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.knowledge.document.DocumentChunk;
import com.slz.crm.knowledge.document.ImageSliceMetadata;
import com.slz.crm.knowledge.document.ImageVectorFlushSupport;
import com.slz.crm.knowledge.document.PdfImageSliceIngestService;
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
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import com.slz.crm.platform.contract.VectorRecord;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.platform.contract.VectorSearchRequest;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.StringUtils;
import org.springframework.web.client.HttpClientErrorException;
import reactor.core.publisher.Flux;

/**
 * S0 闸门探针（near-dup-ingest-cooccurrence 任务组 B，failsafe IT，A 档真跑）： {@code
 * docs/rag-quality/near-dup-ingest-gate-probe-v1.json} 的<b>唯一写入口</b>。
 *
 * <p><b>测什么</b>：6 图（3 base + 3 个最差变体 {@code crop-12}/{@code crop-04}/{@code scale-070}）包成合成 PDF
 * 走<b>真实 ingest 路径</b>（{@code buildSlices}：VLM 描述 → 生产质量闸门 → 落盘 → 描述文本 embed + 视觉向量 embedImage），逐图落
 * {@code (assetKey, kind, vlmTokens, gatePass, failReason)} 与 {@code gatePassRate}。核心风险是 proposal
 * §3.2 的耦合： 闸门拒图连同图像向量一起丢——本探针把 {@code gatePassRate} 作为第一等输出，<b>不修这个顺序</b>（决策 D3）。
 *
 * <p><b>三模式</b>（与 {@code NearDupDisplacementEvidenceIT} 同款）：
 *
 * <ol>
 *   <li><b>未 opt-in</b>：整类 {@link Assumptions} 跳过、零外呼、不落文件；
 *   <li><b>opt-in + write</b>：全量真跑（真 VLM + 真 embedding），逐图证据冻结进锚；计量计数同时做「实测 == 派生」对账
 *       （embedImage/embed 恰等于过闸门图数，多出的计数即隐藏调用，直接红）；
 *   <li><b>opt-in 无 write（默认复跑）</b>：重新生成语料与合成 PDF、重过生产提取器（R0 复验），从锚冻结的逐图记录重算全部派生字段 （failReason /
 *       gatePassRate / gateVerdict / externalCalls），剔除顶层 runtime 块（活值投影）后与磁盘锚<b>逐字节比对</b>。全程不构造
 *       {@code ModelProvider}——复跑零外呼由结构保证。
 * </ol>
 *
 * <p><b>影子分类声明</b>：生产闸门 {@code VisionIngestSupport#passesQualityGate} 在 {@code src/main}（本卡只读）， 拒图的
 * VLM 输出被生产路径丢弃；本类在真调用点（provider 包装层）捕获输出做<b>影子分类</b>（同一阈值：紧凑长度 ≥40、 「（截图不清）」占汉字比
 * ≤0.4），写锚时逐图断言影子结论与生产闸门一致——分类只是诊断面，闸门判定以生产实现为准。
 */
@DisplayName("S0 闸门探针：6 图真 ingest 逐图闸门证据（RAG_NEAR_DUP_INGEST_REAL 显式 opt-in）")
class NearDupIngestGateProbeIT {

  /** 真跑 opt-in 键（本卡门控变量名冻结）。 */
  static final String OPT_IN_ENV = "RAG_NEAR_DUP_INGEST_REAL";

  /** 锚写入门控属性（冻结）。 */
  static final String WRITE_PROP = "near.dup.ingest.gate.write";

  /** S0 锚落盘路径（写集内唯一 JSON 产物）。 */
  static final Path ANCHOR_PATH =
      Path.of("docs", "rag-quality", "near-dup-ingest-gate-probe-v1.json");

  /** v2 定标锚（只读：带内对选取依据）。 */
  private static final Path CALIBRATION_ANCHOR =
      Path.of("docs", "rag-quality", "image-threshold-calibration-v2.json");

  /** 序列化显式锁 LF（.gitattributes 对 *.json 强制 eol=lf；Windows 默认缩进器落 \r\n 会让锚 SHA 漂移）。 */
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

  /** 图片切片类目（生产切片链的类目过滤键，本卡不自造）。 */
  private static final String IMAGE_CATEGORY = ImageCrossModalFixture.IMAGE_CATEGORY;

  /** 探针上下文知识库 id（独立取值，不与既有真跑 IT 共享）。 */
  private static final String EVAL_KB_ID = "9905";

  /** 切片行主键起点（测试侧合成 DB 主键）。 */
  private static final long FIRST_CHUNK_ID = 9700L;

  /** S0 外呼硬帽（Owner 授权：6 图 ≤18 次；真调用点累加，触帽即抛，不超支）。 */
  static final int S0_CALL_CAP = 18;

  /** S0 判据：gatePassRate ≥ 4/6 才能进 S1（tasks §0 停步阈值）。 */
  static final int GATE_PASS_MIN = 4;

  /** embedImage 调用节奏（毫秒）：定标卡实测 2 QPS 连打中途 429，压到 ~0.9 QPS。 */
  private static final long PACE_MILLIS = 1100L;

  /** 429 有界退避重试次数（仅限流；其余失败一律整体失败，不落半成品锚）。 */
  private static final int THROTTLE_RETRIES = 3;

  private static final long THROTTLE_BACKOFF_MILLIS = 5000L;

  /** 视觉模型覆盖属性（与既有真跑 IT 同枚）。 */
  private static final String VISION_MODEL_PROP = "rag.pdf.image.vision-model";

  /** 视觉模型冻结投影常量（锚 constants.visionModel 只落此值；运行时活值见顶层 runtime 块）。 */
  static final String DEFAULT_VISION_MODEL = "qwen-vl-plus";

  /** 活值≠常量时的覆盖原因（与锚 .md 头部 model_not_found 记载一致；replay 无覆盖时为 null）。 */
  static final String VISION_MODEL_OVERRIDE_REASON =
      "生产默认 qwen-vl-plus 在本 key 返回 model_not_found，写锚运行覆盖为 qwen3-vl-plus（灰卡同款覆盖）";

  /** 每份 PDF 图片数帽（= 生产 PdfImageSliceIngestService.HARD_MAX_IMAGES 同值，不引用生产常量）。 */
  static final int MAX_IMAGES_PER_PDF = 20;

  /** 带内区间（定标锚判读口径：近重复带 [0.9887, 0.9944)，冻结）。 */
  static final double BAND_LOW = 0.9887D;

  static final double BAND_HIGH = 0.9944D;

  /** 生产闸门阈值（VisionIngestSupport 同值，影子分类用；闸门判定以生产实现为准）。 */
  private static final int MIN_COMPACT_CHARS = 40;

  /** 生产提取器最短边下限（PdfImageExtractor.MIN_EDGE_PX 同值：低于它的图过不了真实提取，探针禁选）。 */
  private static final int MIN_EDGE_PX = 64;

  private static final String UNCLEAR_MARK = "（截图不清）";

  private static final double UNCLEAR_HAN_RATIO_LIMIT = 0.4;

  @Test
  @DisplayName("S0：6 图真 ingest 落逐图闸门证据锚（写锚 / 默认复跑逐字节校验）")
  void runGateProbe() throws Exception {
    if (!"1".equals(System.getenv(OPT_IN_ENV))) {
      Assumptions.assumeTrue(false, "未显式 opt-in（" + OPT_IN_ENV + "=1）：跳过 S0 闸门探针真跑（默认 CI 零外呼）");
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

  /** 是否写锚模式（opt-in 且显式 {@code -Dnear.dup.ingest.gate.write=true}）。 */
  private static boolean isWriteMode() {
    return "true".equalsIgnoreCase(System.getProperty(WRITE_PROP));
  }

  // ==================== 选取规则（跑前冻结，从定标锚实读） ====================

  /** 一张探针图（base 或变体；kind 仅变体有值）。 */
  private record ProbeImage(
      String assetKey,
      String kind,
      boolean variant,
      String baseKey,
      byte[] png,
      int width,
      int height) {}

  /**
   * 选取规则（冻结）：crop-04 与 scale-070 各取<b>带内第一对且变体过生产提取器下限</b>（锚 pairs 序；变体最短边 &lt; {@value
   * #MIN_EDGE_PX}px 的实例会被生产提取器丢弃，顺延到下一个合规实例，无合规带内实例时回落语料生成序第一个合规变体）； crop-12 无带内对，取语料生成序第一个「base
   * 不与已选 base 重合且过下限」的 crop-12 变体及其 base。产出 6 张互不相同的图。
   */
  static List<ProbeImage> selectProbeImages(NearDuplicateCorpus.Corpus corpus) throws Exception {
    JsonNode root = MAPPER.readTree(Files.readAllBytes(CALIBRATION_ANCHOR));
    List<JsonNode> bandPairs = new ArrayList<>();
    for (JsonNode pair : root.path("pairs")) {
      if ("near-duplicate".equals(pair.path("stratum").asText())) {
        double cosine = pair.path("cosine").asDouble();
        if (cosine >= BAND_LOW && cosine < BAND_HIGH) {
          bandPairs.add(pair);
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

    // 三种 kind 顺序选取，全程 base 去重（3 base 必须互不相同）；带内对优先，无合规带内实例时回落语料生成序
    java.util.Set<String> chosenBases = new java.util.LinkedHashSet<>();

    NearDuplicateCorpus.Variant crop04Variant =
        pickVariant(corpus, bandPairs, bases, NearDuplicateCorpus.KIND_CROP_04, chosenBases);
    ProbeImage crop04Base = baseImage(bases.get(crop04Variant.baseKey()));
    ProbeImage crop04VariantImage = variantImage(crop04Variant);
    chosenBases.add(crop04Variant.baseKey());

    NearDuplicateCorpus.Variant scale070Variant =
        pickVariant(corpus, bandPairs, bases, NearDuplicateCorpus.KIND_SCALE_070, chosenBases);
    ProbeImage scale070Base = baseImage(bases.get(scale070Variant.baseKey()));
    ProbeImage scale070VariantImage = variantImage(scale070Variant);
    chosenBases.add(scale070Variant.baseKey());

    NearDuplicateCorpus.Variant crop12 =
        corpus.variants().stream()
            .filter(variant -> NearDuplicateCorpus.KIND_CROP_12.equals(variant.kind()))
            .filter(variant -> Math.min(variant.width(), variant.height()) >= MIN_EDGE_PX)
            .filter(variant -> !chosenBases.contains(variant.baseKey()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("语料无合规且 base 不重合的 crop-12 变体（语料漂移？）"));
    ProbeImage crop12Base = baseImage(bases.get(crop12.baseKey()));
    ProbeImage crop12Variant = variantImage(crop12);
    chosenBases.add(crop12.baseKey());

    List<ProbeImage> images = new ArrayList<>();
    images.add(crop04Base);
    images.add(crop04VariantImage);
    images.add(scale070Base);
    images.add(scale070VariantImage);
    images.add(crop12Base);
    images.add(crop12Variant);
    assertEquals(
        6,
        images.stream().map(ProbeImage::assetKey).distinct().count(),
        "探针 6 图必须互不相同（3 base + 3 最差变体）");
    return List.copyOf(images);
  }

  private static ProbeImage baseImage(NearDuplicateCorpus.Base base) {
    assertNotNull(base, "带内 base 缺失于语料（语料与锚漂移？）");
    return new ProbeImage(
        base.key(), null, false, base.key(), base.png(), base.width(), base.height());
  }

  private static ProbeImage variantImage(NearDuplicateCorpus.Variant variant) {
    assertNotNull(variant, "带内变体缺失于语料（语料与锚漂移？）");
    return new ProbeImage(
        variant.key(),
        variant.kind(),
        true,
        variant.baseKey(),
        variant.png(),
        variant.width(),
        variant.height());
  }

  /** 单 kind 选取：优先「带内第一对且变体合规且 base 未占用」（锚 pairs 序），否则回落语料生成序第一个 「合规且 base 未占用」的同 kind 变体。 */
  private static NearDuplicateCorpus.Variant pickVariant(
      NearDuplicateCorpus.Corpus corpus,
      List<JsonNode> bandPairs,
      Map<String, NearDuplicateCorpus.Base> bases,
      String kind,
      java.util.Set<String> chosenBases) {
    for (JsonNode pair : bandPairs) {
      if (!kind.equals(pair.path("kind").asText())) {
        continue;
      }
      NearDuplicateCorpus.Variant variant = variantByKey(corpus, pair.path("rightKey").asText());
      if (variant == null
          || Math.min(variant.width(), variant.height()) < MIN_EDGE_PX
          || chosenBases.contains(variant.baseKey())) {
        continue;
      }
      assertNotNull(
          bases.get(pair.path("leftKey").asText()),
          "带内 base 缺失于语料: " + pair.path("leftKey").asText());
      return variant;
    }
    return corpus.variants().stream()
        .filter(variant -> kind.equals(variant.kind()))
        .filter(variant -> Math.min(variant.width(), variant.height()) >= MIN_EDGE_PX)
        .filter(variant -> !chosenBases.contains(variant.baseKey()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("语料无合规且 base 未占用的 " + kind + " 变体（语料漂移？）"));
  }

  private static NearDuplicateCorpus.Variant variantByKey(
      NearDuplicateCorpus.Corpus corpus, String key) {
    return corpus.variants().stream()
        .filter(variant -> variant.key().equals(key))
        .findFirst()
        .orElse(null);
  }

  // ==================== 写锚模式：全量真跑 ====================

  private void runRealAndWrite(String apiKey) throws Exception {
    NearDuplicateCorpus.Corpus corpus = NearDuplicateCorpus.generate();
    List<ProbeImage> images = selectProbeImages(corpus);

    BudgetedTrackingProvider provider =
        new BudgetedTrackingProvider(realProvider(properties(apiKey), apiKey), S0_CALL_CAP);
    EmbeddingService embeddingService = new EmbeddingService(provider, properties(apiKey));
    RecordingTokenRecorder recorder = new RecordingTokenRecorder();
    ObjectProvider<DynamicConfigService> configProvider =
        RagBenchmarkPipelineFactory.provider(RagBenchmarkPipelineFactory.dynamicConfig(config()));

    List<FrozenProbe> probes = new ArrayList<>();
    long nextChunkId = FIRST_CHUNK_ID;
    UserContextHolder.set(
        new UserContext(99051L, 5L, 101L, DataScopeLevel.ALL, "near-dup-ingest-gate-probe"));
    try {
      for (int index = 0; index < images.size(); index++) {
        probes.add(
            probeOne(
                provider,
                embeddingService,
                recorder,
                configProvider,
                images.get(index),
                index + 1,
                nextChunkId));
        if (probes.get(probes.size() - 1).gatePass()) {
          nextChunkId++;
        }
      }
    } finally {
      UserContextHolder.clear();
    }

    int gatePassCount = (int) probes.stream().filter(FrozenProbe::gatePass).count();
    // 实测 == 派生 对账：vision 每图恰 1 次；过闸门图各耗 1 次 embedImage + 1 次 embed，多出的计数即隐藏调用
    assertEquals(images.size(), provider.visionCalls.get(), "vision 外呼必须每图恰 1 次（真计量点）");
    assertEquals(gatePassCount, provider.embedImageCalls.get(), "embedImage 外呼必须等于过闸门图数");
    assertEquals(gatePassCount, provider.embedCalls.get(), "文本 embed 外呼必须等于过闸门图数");
    assertTrue(
        provider.totalCalls() <= S0_CALL_CAP,
        "S0 外呼实测 " + provider.totalCalls() + " 超硬帽 " + S0_CALL_CAP);

    ObjectNode root =
        buildAnchor(
            probes,
            provider.visionCalls.get(),
            provider.embedImageCalls.get(),
            provider.embedCalls.get());
    Files.createDirectories(ANCHOR_PATH.toAbsolutePath().getParent());
    Files.writeString(ANCHOR_PATH, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
    System.out.println(
        "[near-dup-ingest-gate-probe] 锚已落盘: "
            + ANCHOR_PATH.toAbsolutePath()
            + "（gatePassRate="
            + gatePassCount
            + "/6 externalCalls="
            + provider.totalCalls()
            + " verdict="
            + root.path("gateVerdict").asText()
            + "）");
  }

  /** 单图真 ingest：合成 PDF → 生产 buildSlices → 逐图证据（冻结表示）。 */
  private FrozenProbe probeOne(
      BudgetedTrackingProvider provider,
      EmbeddingService embeddingService,
      RecordingTokenRecorder recorder,
      ObjectProvider<DynamicConfigService> configProvider,
      ProbeImage image,
      int expectedVisionSequence,
      long chunkIdSeed)
      throws Exception {
    byte[] pdf = buildSingleImagePdf(image.png());
    List<RealPdfAssets.Extracted> extracted = RealPdfAssets.extract(pdf, MAX_IMAGES_PER_PDF);
    assertEquals(1, extracted.size(), image.assetKey() + " 合成 PDF 必须恰好抠出 1 图（R0 保真）");
    assertEquals(image.width(), extracted.get(0).width(), image.assetKey() + " 抠图宽度必须与输入一致（R0）");
    assertEquals(image.height(), extracted.get(0).height(), image.assetKey() + " 抠图高度必须与输入一致（R0）");

    PdfIngestImageFixture.InMemoryImageStorage storage = PdfIngestImageFixture.inMemoryStorage();
    PdfImageSliceIngestService ingestService =
        new PdfImageSliceIngestService(
            provider, configProvider, provider(recorder), provider(storage));
    String filename = "near-dup-ingest-probe-" + image.assetKey() + ".pdf";
    List<DocumentChunk> slices =
        ingestService.buildSlices(pdf, filename, IMAGE_CATEGORY, EVAL_KB_ID);
    // 暂存表必须在 flush 之前读：flush 消费后清 ThreadLocal
    Map<String, float[]> staged = RealPdfAssets.stagedVectors();
    boolean gatePass = !slices.isEmpty();
    // 闸门拒图 ⇒ 文本 chunk 与图像向量一起丢（proposal §3.2，被测现象）；过闸门 ⇒ 恰 1 切片、1 暂存向量
    if (gatePass) {
      assertEquals(1, slices.size(), image.assetKey() + " 过闸门后应恰 1 条切片");
      assertEquals(1, staged.size(), image.assetKey() + " 过闸门后应恰 1 个暂存视觉向量（embedImage 真发生）");
    } else {
      assertEquals(0, slices.size(), image.assetKey() + " 被闸门拒后不得有切片");
      assertEquals(0, staged.size(), image.assetKey() + " 被闸门拒后不得有视觉向量（连带丢弃是被测现象）");
    }

    String description = null;
    if (gatePass) {
      DocumentChunk slice = slices.get(0);
      description = slice.text();
      DocumentVectorChunkEntity row = new DocumentVectorChunkEntity();
      row.setId(chunkIdSeed);
      row.setDocumentId("near-dup-ingest-probe-" + image.baseKey());
      row.setChunkIndex(0);
      row.setChunkText(slice.text());
      row.setCategory(slice.category());
      row.setFilename(filename);
      row.setPageNo(slice.pageNo());
      row.setRowIndex(slice.rowIndex());
      row.setChunkRole("CHILD");
      row.setExtraMetadataJson(ImageSliceMetadata.toJson(slice.extraMetadata()));
      UploadedFileEntity file = new UploadedFileEntity();
      file.setDocumentId("near-dup-ingest-probe-" + image.baseKey());
      file.setOriginalFilename(filename);
      file.setFilename(filename);
      file.setFileType("pdf");
      file.setKnowledgeBase(EVAL_KB_ID);
      file.setStorageKey("near-dup-ingest-probe/" + filename);
      CapturingProbeStore captureStore = new CapturingProbeStore();
      int flushed = ImageVectorFlushSupport.flush(file, List.of(row), slices, captureStore);
      assertEquals(1, flushed, image.assetKey() + " 视觉向量必须落 image 集合（真视觉路生效）");
      // 描述文本 → 既有 chunk/embed 链路（生产口径：描述块由调用方 embed）——真实计量点第 3 次外呼
      embeddingService.embed(slice.text());
    }

    BudgetedTrackingProvider.VisionCapture capture = provider.lastVisionCapture();
    assertNotNull(capture, image.assetKey() + " 必须有一次 VISION 调用捕获（真计量点）");
    assertEquals(
        expectedVisionSequence, capture.sequence(), image.assetKey() + " VISION 捕获序必须与图序对齐");
    int compactChars = compactLength(capture.content());
    int unclearCount = countOccurrences(capture.content(), UNCLEAR_MARK);
    int hanCount = countHan(capture.content());
    String shadowFailReason =
        classify(capture.errorMessage(), compactChars, unclearCount, hanCount);
    assertEquals(
        gatePass,
        shadowFailReason == null,
        image.assetKey() + " 影子分类必须与生产闸门一致（捕获失真即红）：shadow=" + shadowFailReason);

    TokenUsageRecord visionRecord = recorder.visionRecords.get(recorder.visionRecords.size() - 1);
    return new FrozenProbe(
        image.assetKey(),
        image.kind(),
        image.variant(),
        image.baseKey(),
        image.width(),
        image.height(),
        gatePass,
        visionRecord.promptTokens(),
        visionRecord.completionTokens(),
        visionRecord.totalTokens(),
        capture.content() == null ? 0 : capture.content().length(),
        compactChars,
        unclearCount,
        hanCount,
        head(capture.content(), 160),
        capture.errorMessage(),
        description,
        gatePass ? String.valueOf(chunkIdSeed) : null);
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

  // ==================== 锚构建（write 与 replay 共用单实现，字段顺序即序列化顺序） ====================

  private ObjectNode buildAnchor(
      List<FrozenProbe> probes, int visionCalls, int embedImageCalls, int embedCalls) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", "near-dup-ingest-cooccurrence");
    root.put("stage", "S0-GATE-PROBE");
    root.put("decisionVersion", "NEAR-DUP-INGEST-GATE-PROBE-1.0");
    root.put("decisionOwner", "OWNER");

    ObjectNode criteria = root.putObject("criteria");
    criteria.put(
        "R0", "合成 PDF 可行性（零外呼先做）：PDFBox 造 PDF → 生产 PdfImageExtractor 抠图，图数与像素尺寸逐一对上；不通过即停");
    criteria.put(
        "R1",
        "gatePassRate ≥ 4/6 ⇒ gateVerdict=PROCEED-S1；< 4/6 ⇒ gateVerdict=STOP-GATE-REJECTION（停手上报，不进 S1）。"
            + "闸门拒图连带丢图像向量（proposal §3.2）是被测现象，禁修顺序");

    ObjectNode sourceAnchors = root.putObject("sourceAnchors");
    sourceAnchors.put("calibrationAnchor", "docs/rag-quality/image-threshold-calibration-v2.json");
    sourceAnchors.put("calibrationAnchorSha256", sha256OfFile(CALIBRATION_ANCHOR));

    ObjectNode constants = root.putObject("constants");
    constants.put("minCompactChars", MIN_COMPACT_CHARS);
    constants.put("unclearHanRatioLimit", UNCLEAR_HAN_RATIO_LIMIT);
    constants.put("hardMaxImagesPerDocument", MAX_IMAGES_PER_PDF);
    constants.put(
        "gateImplNote",
        "闸门实现 = 生产 VisionIngestSupport.passesQualityGate（src/main 只读）；failReason 为测试侧影子分类（同阈值），"
            + "写锚时逐图断言影子结论与生产闸门一致");
    constants.put("s0CallCap", S0_CALL_CAP);
    constants.put("cumulativeCallCap", 162);
    constants.put(
        "meteringNote",
        "embedding token 不可得时显式 null（端点限制，docs/token-metering.md §1），禁按字符数估算；VISION token 由 "
            + "TokenUsageRecorder 真实计量点落账");
    // NF-1 冻结投影：constants 只落编译期常量（默认参数下可复算），不读属性；运行时活值落 runtime 块
    constants.put("visionModel", DEFAULT_VISION_MODEL);
    ObjectNode runtime = root.putObject("runtime");
    String liveVisionModel = visionModel();
    runtime.put("visionModel", liveVisionModel);
    runtime.putArray("excludedFromReplay").add("visionModel");
    runtime.put(
        "overrideReason",
        DEFAULT_VISION_MODEL.equals(liveVisionModel) ? null : VISION_MODEL_OVERRIDE_REASON);
    root.put("baselineHead", "cdf3382");

    root.put(
        "selectionRule",
        "crop-04 与 scale-070 各取带内第一对且变体过生产提取器下限 64px（锚 pairs 序；不合规实例顺延，无合规带内实例时回落"
            + "语料生成序第一个合规变体）；crop-12 无带内对，取语料生成序第一个 base 不与已选 base 重合且过下限的 crop-12 变体"
            + "及其 base（保证 3 base 互不相同）。6 图互不相同，顺序=[crop-04 base, crop-04 variant, scale-070 base, "
            + "scale-070 variant, crop-12 base, crop-12 variant]");

    int gatePassCount = 0;
    var array = root.putArray("images");
    for (FrozenProbe probe : probes) {
      ObjectNode node = array.addObject();
      node.put("assetKey", probe.assetKey());
      if (probe.kind() == null) {
        node.putNull("kind");
      } else {
        node.put("kind", probe.kind());
      }
      node.put("role", probe.variant() ? "variant" : "base");
      node.put("baseKey", probe.baseKey());
      node.put("width", probe.width());
      node.put("height", probe.height());
      node.put("gatePass", probe.gatePass());
      node.put("failReason", classify(probe));
      node.put("vlmPromptTokens", probe.vlmPromptTokens());
      node.put("vlmCompletionTokens", probe.vlmCompletionTokens());
      node.put("vlmTotalTokens", probe.vlmTotalTokens());
      node.put("vlmOutputChars", probe.vlmOutputChars());
      ObjectNode metrics = node.putObject("vlmMetrics");
      metrics.put("compactChars", probe.compactChars());
      metrics.put("unclearMarkCount", probe.unclearCount());
      metrics.put("hanCount", probe.hanCount());
      node.put("vlmOutputHead", probe.vlmOutputHead());
      node.put("visionErrorMessage", probe.visionErrorMessage());
      if (probe.description() == null) {
        node.putNull("description");
      } else {
        node.put("description", probe.description());
      }
      if (probe.chunkId() == null) {
        node.putNull("chunkId");
      } else {
        node.put("chunkId", probe.chunkId());
      }
      if (probe.gatePass()) {
        gatePassCount++;
      }
    }

    root.put("gatePassCount", gatePassCount);
    root.put("gatePassRate", gatePassCount / (double) probes.size());
    root.put("gateVerdict", gatePassCount >= GATE_PASS_MIN ? "PROCEED-S1" : "STOP-GATE-REJECTION");
    root.put(
        "gateVerdictReason",
        gatePassCount >= GATE_PASS_MIN
            ? "R1：gatePassRate=" + gatePassCount + "/6 ≥ 4/6，允许进入 S1"
            : "R1：gatePassRate=" + gatePassCount + "/6 < 4/6，停手上报（闸门系统性丢弃目标图，另立「解耦图像向量与描述」卡）");

    root.put("externalCalls", visionCalls + embedImageCalls + embedCalls);
    ObjectNode calls = root.putObject("callBreakdown");
    calls.put("visionCalls", visionCalls);
    calls.put("embedImageCalls", embedImageCalls);
    calls.put("embedCalls", embedCalls);
    calls.put("embeddingCallCount", embedImageCalls + embedCalls);
    calls.put("visionCallCount", visionCalls);
    calls.put(
        "semantics",
        "三计数由 BudgetedTrackingProvider 在真调用点累加（vision=VLM 描述、embedImage=视觉编码、embed=文本编码），非公式推导；"
            + "闸门拒图只耗 1 次 vision 外呼（下游两路不发生），embedImage/embedCalls 应等于 gatePassCount（写锚模式已断言）");
    root.put(
        "embeddingTokenUnavailableNote",
        "文本/视觉 embedding 端点不回流 usage（token-metering §1），相关 token 记 null");

    ObjectNode material = root.putObject("corpusMaterial");
    var frozen = material.putArray("images");
    for (FrozenProbe probe : probes) {
      frozen.add(toFrozenNode(probe));
    }
    root.put(
        "byteIdentityBasis",
        "锚内 corpusMaterial.images 冻结逐图证据（闸门结论/token/影子指标/描述文本）；默认复跑重新生成语料与合成 PDF、"
            + "重过生产提取器（R0 复验），从冻结子集重算 failReason/gatePassRate/gateVerdict/externalCalls 后逐字节比对"
            + "（比对前整体剔除顶层 runtime 块——活值投影不参与字节恒等）；复跑不构造 ModelProvider，零外呼由结构保证");
    return root;
  }

  /** 冻结记录 → JSON 节点（write 与 replay 共用，保证字节同构）。 */
  private ObjectNode toFrozenNode(FrozenProbe probe) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("assetKey", probe.assetKey());
    if (probe.kind() == null) {
      node.putNull("kind");
    } else {
      node.put("kind", probe.kind());
    }
    node.put("role", probe.variant() ? "variant" : "base");
    node.put("baseKey", probe.baseKey());
    node.put("width", probe.width());
    node.put("height", probe.height());
    node.put("gatePass", probe.gatePass());
    node.put("vlmPromptTokens", probe.vlmPromptTokens());
    node.put("vlmCompletionTokens", probe.vlmCompletionTokens());
    node.put("vlmTotalTokens", probe.vlmTotalTokens());
    ObjectNode metrics = node.putObject("vlmMetrics");
    metrics.put("compactChars", probe.compactChars());
    metrics.put("unclearMarkCount", probe.unclearCount());
    metrics.put("hanCount", probe.hanCount());
    node.put("vlmOutputHead", probe.vlmOutputHead());
    node.put("visionErrorMessage", probe.visionErrorMessage());
    if (probe.description() == null) {
      node.putNull("description");
    } else {
      node.put("description", probe.description());
    }
    if (probe.chunkId() == null) {
      node.putNull("chunkId");
    } else {
      node.put("chunkId", probe.chunkId());
    }
    node.put("vlmOutputChars", probe.vlmOutputChars());
    return node;
  }

  // ==================== 默认复跑：语料重建 + 派生重算 + 逐字节比对（零外呼） ====================

  private void replayAndVerifyByteIdentity() throws Exception {
    assertTrue(Files.exists(ANCHOR_PATH), "锚不存在：默认复跑必须先有写锚产物（跳过≠复跑）: " + ANCHOR_PATH);
    String stored = Files.readString(ANCHOR_PATH, StandardCharsets.UTF_8);
    JsonNode root = MAPPER.readTree(stored);
    JsonNode material = root.path("corpusMaterial");
    assertTrue(material.path("images").size() > 0, "锚缺 corpusMaterial.images");

    NearDuplicateCorpus.Corpus corpus = NearDuplicateCorpus.generate();
    List<ProbeImage> images = selectProbeImages(corpus);
    JsonNode frozenImages = material.path("images");
    assertEquals(6, frozenImages.size(), "冻结逐图记录必须 6 条");
    for (int index = 0; index < images.size(); index++) {
      ProbeImage image = images.get(index);
      JsonNode frozen = frozenImages.get(index);
      assertEquals(image.assetKey(), frozen.path("assetKey").asText(), "语料重建图键漂移 @" + index);
      assertEquals(image.width(), frozen.path("width").asInt(), "语料重建宽度漂移 @" + index);
      assertEquals(image.height(), frozen.path("height").asInt(), "语料重建高度漂移 @" + index);
    }

    // R0 复验（零外呼）：合成 PDF 重新过生产提取器，图数与像素尺寸逐一对上
    for (int index = 0; index < images.size(); index++) {
      byte[] pdf = buildSingleImagePdf(images.get(index).png());
      List<RealPdfAssets.Extracted> extracted = RealPdfAssets.extract(pdf, MAX_IMAGES_PER_PDF);
      assertEquals(1, extracted.size(), "复跑 R0：PDF @" + index + " 必须恰好抠出 1 图");
      assertEquals(images.get(index).width(), extracted.get(0).width(), "复跑 R0：宽度漂移 @" + index);
      assertEquals(images.get(index).height(), extracted.get(0).height(), "复跑 R0：高度漂移 @" + index);
    }

    List<FrozenProbe> probes = new ArrayList<>();
    for (JsonNode frozen : frozenImages) {
      probes.add(FrozenProbe.fromJson(frozen));
    }
    int gatePassCount = (int) probes.stream().filter(FrozenProbe::gatePass).count();
    ObjectNode rebuilt = buildAnchor(probes, probes.size(), gatePassCount, gatePassCount);
    // runtime 块是活值投影（写锚时实际所用视觉模型）：白名单锁死后整体剔除，再逐字节比对（NF-1）
    JsonNode storedRuntime = root.path("runtime");
    assertTrue(storedRuntime.isObject(), "磁盘锚缺顶层 runtime 块（冻结投影迁移未做？）");
    assertVisionModelWhitelist(storedRuntime);
    assertVisionModelWhitelist(rebuilt.path("runtime"));
    ((ObjectNode) root).remove("runtime");
    ((ObjectNode) rebuilt).remove("runtime");
    String rebuiltJson = MAPPER.writeValueAsString(rebuilt);
    assertEquals(
        MAPPER.writeValueAsString(root),
        rebuiltJson,
        "默认复跑序列化与磁盘锚不一致（剔除 runtime 后；锚被手改或派生算术漂移）——逐字节比对失败");
    System.out.println(
        "[near-dup-ingest-gate-probe] 默认复跑逐字节一致（gatePassRate="
            + root.path("gatePassRate").asDouble()
            + " verdict="
            + root.path("gateVerdict").asText()
            + " 零外呼）");
  }

  /** runtime 白名单锁死：excludedFromReplay 恰等于 [visionModel]（多、少、换名都红）。 */
  private static void assertVisionModelWhitelist(JsonNode runtime) {
    JsonNode excluded = runtime.path("excludedFromReplay");
    assertTrue(excluded.isArray() && excluded.size() == 1, "runtime.excludedFromReplay 必须恰 1 项");
    assertEquals(
        "visionModel", excluded.get(0).asText(), "runtime.excludedFromReplay 白名单只许 visionModel");
  }

  // ==================== 合成 PDF 装配（原 NearDupIngestPdfBuilder 内联） ====================
  // S1 装置拆除（Owner 裁定 A′）后 S0 仍依赖合成 PDF（write 与 replay 的 R0 复验都走它），原类全文留 d7abc37。

  /**
   * 单图合成 PDF：一页，页尺寸 = 图片尺寸，图片满页无缩放内嵌（S0 探针用，逐图隔离闸门判定）。
   *
   * @param png 变体/base 的 PNG 字节
   * @return PDF 字节
   */
  static byte[] buildSingleImagePdf(byte[] png) {
    return buildMultiImagePdf(List.of(png));
  }

  /**
   * 批量图 → 合成 PDF 序列：按输入顺序每 {@value #MAX_IMAGES_PER_PDF} 图切一份，每份一图一页（守卫边界测试用）。
   *
   * @param pngImages 保序的 PNG 字节序列（非空）
   * @return PDF 字节序列（每份 ≤ {@value #MAX_IMAGES_PER_PDF} 图）
   */
  static List<byte[]> buildPdfs(List<byte[]> pngImages) {
    if (pngImages == null || pngImages.isEmpty()) {
      throw new IllegalArgumentException("合成 PDF 需要至少一张图");
    }
    List<byte[]> pdfs = new ArrayList<>();
    for (int from = 0; from < pngImages.size(); from += MAX_IMAGES_PER_PDF) {
      int to = Math.min(from + MAX_IMAGES_PER_PDF, pngImages.size());
      pdfs.add(buildMultiImagePdf(pngImages.subList(from, to)));
    }
    return List.copyOf(pdfs);
  }

  /** 一份多页 PDF：每页一图（页尺寸 = 图尺寸，lossless 内嵌），页序 = 输入序；无时间戳，同一输入字节一致。 */
  private static byte[] buildMultiImagePdf(List<byte[]> pngImages) {
    try (PDDocument document = new PDDocument()) {
      for (byte[] png : pngImages) {
        BufferedImage image = readPng(png);
        PDPage page = new PDPage(new PDRectangle(image.getWidth(), image.getHeight()));
        document.addPage(page);
        PDImageXObject xobject = LosslessFactory.createFromImage(document, image);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          content.drawImage(xobject, 0F, 0F, image.getWidth(), image.getHeight());
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("合成 PDF 生成失败", exception);
    }
  }

  /** 解码 PNG（失败即抛，不吞；与语料侧同一纪律）。 */
  private static BufferedImage readPng(byte[] png) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
      if (image == null) {
        throw new IllegalStateException("PNG 解码失败（无可用解码器？）");
      }
      return image;
    } catch (IOException exception) {
      throw new IllegalStateException("PNG 解码失败", exception);
    }
  }

  // ==================== 装配与辅助 ====================

  private static Map<String, Object> config() {
    Map<String, Object> matrix = new LinkedHashMap<>();
    matrix.put(PdfImageSliceIngestService.ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY, Boolean.TRUE);
    matrix.put(PdfImageSliceIngestService.MAX_IMAGES_KEY, MAX_IMAGES_PER_PDF);
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
        provider(nativeChat), provider(embeddingModel), properties, environment);
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

  private static String head(String text, int limit) {
    if (text == null) {
      return null;
    }
    return text.length() <= limit ? text : text.substring(0, limit) + "…";
  }

  /** 紧凑长度（去全部空白；与生产闸门同口径）。 */
  private static int compactLength(String text) {
    return text == null ? 0 : text.replaceAll("\\s+", "").length();
  }

  private static int countOccurrences(String text, String needle) {
    int count = 0;
    if (text != null && needle != null && !needle.isEmpty()) {
      int from = 0;
      while (true) {
        int at = text.indexOf(needle, from);
        if (at < 0) {
          break;
        }
        count++;
        from = at + needle.length();
      }
    }
    return count;
  }

  private static int countHan(String text) {
    int count = 0;
    if (text != null && !text.isEmpty()) {
      java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\p{IsHan}").matcher(text);
      while (matcher.find()) {
        count++;
      }
    }
    return count;
  }

  /** 阈值段影子分类：null = 应过闸门；否则失败原因（与生产闸门同分支序）。 */
  static String classify(int compactChars, int unclearCount, int hanCount) {
    if (compactChars < MIN_COMPACT_CHARS) {
      return "TOO-SHORT";
    }
    if (unclearCount > 0 && hanCount == 0) {
      return "UNCLEAR-NO-HAN";
    }
    if (hanCount > 0 && ((double) unclearCount / (double) hanCount) > UNCLEAR_HAN_RATIO_LIMIT) {
      return "UNCLEAR-RATIO";
    }
    return null;
  }

  /** 影子分类完整入口：错误 > 空输出 > 阈值段（write 与 replay 单一实现）。 */
  static String classify(
      String visionErrorMessage, int compactChars, int unclearCount, int hanCount) {
    if (visionErrorMessage != null) {
      return "VISION-ERROR";
    }
    if (compactChars == 0) {
      return "BLANK-OUTPUT";
    }
    return classify(compactChars, unclearCount, hanCount);
  }

  /** 影子分类（冻结记录入口）。 */
  static String classify(FrozenProbe probe) {
    return classify(
        probe.visionErrorMessage(), probe.compactChars(), probe.unclearCount(), probe.hanCount());
  }

  // ==================== 数据载体 ====================

  /** 锚冻结的逐图记录（write 产出与 replay 重建的唯一表示）。 */
  record FrozenProbe(
      String assetKey,
      String kind,
      boolean variant,
      String baseKey,
      int width,
      int height,
      boolean gatePass,
      Long vlmPromptTokens,
      Long vlmCompletionTokens,
      Long vlmTotalTokens,
      int vlmOutputChars,
      int compactChars,
      int unclearCount,
      int hanCount,
      String vlmOutputHead,
      String visionErrorMessage,
      String description,
      String chunkId) {

    static FrozenProbe fromJson(JsonNode node) {
      JsonNode kind = node.path("kind");
      JsonNode description = node.path("description");
      JsonNode chunkId = node.path("chunkId");
      JsonNode head = node.path("vlmOutputHead");
      JsonNode visionError = node.path("visionErrorMessage");
      return new FrozenProbe(
          node.path("assetKey").asText(),
          kind.isMissingNode() || kind.isNull() ? null : kind.asText(),
          "variant".equals(node.path("role").asText()),
          node.path("baseKey").asText(),
          node.path("width").asInt(),
          node.path("height").asInt(),
          node.path("gatePass").asBoolean(),
          nullableLong(node.path("vlmPromptTokens")),
          nullableLong(node.path("vlmCompletionTokens")),
          nullableLong(node.path("vlmTotalTokens")),
          node.path("vlmOutputChars").asInt(0),
          node.path("vlmMetrics").path("compactChars").asInt(0),
          node.path("vlmMetrics").path("unclearMarkCount").asInt(0),
          node.path("vlmMetrics").path("hanCount").asInt(0),
          head.isMissingNode() || head.isNull() ? null : head.asText(),
          visionError.isMissingNode() || visionError.isNull() ? null : visionError.asText(),
          description.isMissingNode() || description.isNull() ? null : description.asText(),
          chunkId.isMissingNode() || chunkId.isNull() ? null : chunkId.asText());
    }

    private static Long nullableLong(JsonNode node) {
      return node.isMissingNode() || node.isNull() ? null : node.asLong();
    }
  }

  /**
   * 真调用点计数装饰器 + VLM 输出捕获：vision/embedImage/embed 在真调用点累加（externalCalls 必须来自这里，写 0 判假绿），
   * 触硬帽即抛（外呼上限是硬边界）；vision 捕获输出文本供影子分类（生产路径会丢弃拒图输出）；embedImage 先降速（定标卡 实测 2 QPS 中途 429）；vision 429
   * 有界退避，其余失败捕获后整体失败（不落半成品锚）。
   */
  static final class BudgetedTrackingProvider implements ModelProvider {

    private final ModelProvider delegate;

    private final int callCap;

    final AtomicInteger visionCalls = new AtomicInteger();

    final AtomicInteger embedImageCalls = new AtomicInteger();

    final AtomicInteger embedCalls = new AtomicInteger();

    private final List<VisionCapture> visionCaptures = new ArrayList<>();

    BudgetedTrackingProvider(ModelProvider delegate, int callCap) {
      this.delegate = delegate;
      this.callCap = callCap;
    }

    /** 单次 VISION 捕获（内容或错误二选一；sequence 从 1 起，与图序对齐）。 */
    record VisionCapture(int sequence, String content, String errorMessage) {}

    synchronized VisionCapture lastVisionCapture() {
      if (visionCaptures.isEmpty()) {
        throw new IllegalStateException("无 VISION 捕获（vision 未被调用？）");
      }
      return visionCaptures.get(visionCaptures.size() - 1);
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
      guardBudget();
      embedCalls.incrementAndGet();
      return delegate.embed(request);
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
          System.out.println("[near-dup-ingest-gate-probe] embedImage 429 退避 " + wait + " ms 后重试");
          pause(wait);
        }
      }
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt) {
      guardBudget();
      visionCalls.incrementAndGet();
      return captureVision(() -> delegate.vision(prompt));
    }

    @Override
    public ModelCallResult<String> vision(Prompt prompt, ModelCallOptions options) {
      guardBudget();
      visionCalls.incrementAndGet();
      return captureVision(() -> delegate.vision(prompt, options));
    }

    private ModelCallResult<String> captureVision(
        java.util.function.Supplier<ModelCallResult<String>> call) {
      for (int attempt = 0; ; attempt++) {
        try {
          ModelCallResult<String> result = call.get();
          visionCaptures.add(
              new VisionCapture(
                  visionCaptures.size() + 1, result == null ? null : result.content(), null));
          return result;
        } catch (HttpClientErrorException exception) {
          if (exception.getStatusCode().value() != 429 || attempt >= THROTTLE_RETRIES) {
            throw exception;
          }
          long wait = THROTTLE_BACKOFF_MILLIS << attempt;
          System.out.println("[near-dup-ingest-gate-probe] vision 429 退避 " + wait + " ms 后重试");
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

  /** 计量记录收集器（生产 VisionIngestSupport 的 TokenUsageRecorder 计量点，逐条留序）。 */
  static final class RecordingTokenRecorder implements TokenUsageRecorder {

    final List<TokenUsageRecord> visionRecords = new ArrayList<>();

    final List<TokenUsageRecord> allRecords = new ArrayList<>();

    @Override
    public void record(TokenUsageRecord record) {
      allRecords.add(record);
      if (record.type() == TokenUsageType.VISION) {
        visionRecords.add(record);
      }
    }
  }

  /** 捕获型内存向量库（image 集合 flush 截获；S0 只对账条数）。 */
  static final class CapturingProbeStore implements CrmVectorStore {

    private final List<VectorRecord> captured = new ArrayList<>();

    List<VectorRecord> captured() {
      return List.copyOf(captured);
    }

    @Override
    public void upsert(VectorRecord record) {
      captured.add(record);
    }

    @Override
    public void upsertAll(List<VectorRecord> records) {
      captured.addAll(records);
    }

    @Override
    public List<VectorSearchHit> search(VectorSearchRequest request) {
      throw new IllegalStateException("S0 探针不检索");
    }

    @Override
    public void deleteByDocumentId(String documentId) {
      throw new IllegalStateException("S0 探针不删除");
    }
  }
}
