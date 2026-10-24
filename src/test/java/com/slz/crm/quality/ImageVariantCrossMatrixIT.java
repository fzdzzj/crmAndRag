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
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.model.ModelProviderImpl;
import com.slz.crm.platform.model.ModelProviderProperties;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.StringUtils;

/**
 * 近重复位移证据 G2 真跑（near-dup-missing-evidence 任务组 2，failsafe IT，A 档真跑）： {@code
 * docs/rag-quality/image-variant-cross-matrix-v1.json} 的<b>唯一写入口</b>。
 *
 * <p><b>观测缺口是什么</b>：上一卡 R4 与口径三负例的「竞争者分数」全部以 base 对为代理（锚内无变体↔第三图对）， 代理是否成立未验。本 IT 复用 {@link
 * NearDuplicateCorpus#generate()}（确定性，不改生成逻辑、不新增语料）， 每图<b>单次</b>真视觉编码（144 次外呼，定标卡是每图两次的 288），建全
 * 144×144 对称余弦矩阵。
 *
 * <p><b>三模式</b>：
 *
 * <ol>
 *   <li><b>未 opt-in</b>（默认 CI/verify）：整类 {@link Assumptions} 跳过、零外呼、不落文件；
 *   <li><b>opt-in + {@code -Dimage.cross.matrix.write=true}</b>：144 次真编码 → 矩阵 → M2 偏差对账 → 写锚；
 *   <li><b>opt-in 无 write（默认复跑）</b>：从锚内冻结的 144 条向量本地重算矩阵、对账与全部分位，重算值逐值断言相等后
 *       整体序列化与磁盘锚<b>逐字节比对</b>，零外呼。跨次编码不可保证逐字节确定（M2 的 1e-3 容差本身即排除），
 *       逐字节一致唯一可辩护的实现就是把输入向量冻结在锚里、复跑只重放确定性本地算术。
 * </ol>
 *
 * <p><b>锋利判据（跑之前冻结，tasks.md M1–M4）</b>：M2 与 v2 定标锚已登记的 near-duplicate 120 对 + same-class 100 对 +
 * cross-class 176 对<b>逐对</b>比较，{@code maxAbsDeviation ≤ 1e-3} 才允许发布；超阈值 ⇒ 停 G2 上报（编码非确定或模型已变），
 * <b>禁止</b>把新矩阵当真相、禁止「取较小偏差的那批」。对角自余弦实测落 {@code selfCosines}（单编码下即余弦函数对同向量的自算值， 不硬写 1.0）。
 */
@DisplayName("近重复位移证据 G2：变体↔第三图全矩阵（RAG_IMAGE_CROSS_MATRIX_REAL 显式 opt-in，144 次单编码）")
class ImageVariantCrossMatrixIT {

  /** 真跑 opt-in 键（本卡门控变量名冻结，任务卡硬边界 7）。 */
  static final String OPT_IN_ENV = "RAG_IMAGE_CROSS_MATRIX_REAL";

  /** 锚写入门控属性（冻结）。 */
  static final String WRITE_PROP = "image.cross.matrix.write";

  /** G2 锚落盘路径（写集内唯一 JSON 产物）。 */
  static final Path ANCHOR_PATH =
      Path.of("docs", "rag-quality", "image-variant-cross-matrix-v1.json");

  /** v2 定标锚（只读：M2 偏差对账的基准 + 语料规模交叉核对）。 */
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

  /** 视觉 embedding 的 MIME（生产同值）。 */
  private static final String IMAGE_MIME = "image/png";

  /** embedImage 调用节奏（毫秒）：定标卡实测 2 QPS 连打中途 429，压到 ~0.9 QPS。 */
  private static final long PACE_MILLIS = 1100L;

  /** M2 偏差阈值（冻结；超阈值停 G2）。 */
  static final double DEVIATION_LIMIT = 1e-3D;

  /** 近似同图下限（ImageSimilarityGate.NEAR_DUPLICATE_FLOOR 同值；M3 判据用）。 */
  static final double NEAR_DUPLICATE_FLOOR = 0.9887D;

  /** 本卡起点基线（master @ 5519c43，任务 0.5 记录项）。 */
  private static final String BASELINE_HEAD = "5519c43bd61c886eb2b129a7498e796d20e43d8d";

  @Test
  @DisplayName("G2：144 图单编码建全矩阵，M2 逐对对账 ≤1e-3，M3 分位与 proxyVerdict（写锚 / 默认复跑逐字节校验）")
  void runVariantCrossMatrixEvidence() throws Exception {
    if (!"1".equals(System.getenv(OPT_IN_ENV))) {
      Assumptions.assumeTrue(false, "未显式 opt-in（" + OPT_IN_ENV + "=1）：跳过变体↔第三图全矩阵真跑（默认 CI 零外呼）");
    }
    if ("true".equalsIgnoreCase(System.getProperty(WRITE_PROP))) {
      String apiKey = resolveApiKey();
      Assumptions.assumeTrue(
          StringUtils.hasText(apiKey), "DASHSCOPE_API_KEY 未配置（env 或仓库根 .env）：跳过写锚真跑");
      runRealAndWrite(apiKey);
    } else {
      replayAndVerifyByteIdentity();
    }
  }

  // ==================== 写锚模式：144 次单编码 ====================

  private void runRealAndWrite(String apiKey) throws Exception {
    NearDuplicateCorpus.Corpus corpus = NearDuplicateCorpus.generate();
    assertTrue(corpus.skips().isEmpty(), "有变体被跳过（最短边 < 48px）：对数将不再等于 base×5，先停下上报");
    JsonNode calibration = readCalibration();
    assertEquals(
        calibration.path("corpus").path("bases").asInt(),
        corpus.bases().size(),
        "base 数与 v2 定标锚不一致（语料被改？）");
    assertEquals(
        calibration.path("corpus").path("variants").asInt(),
        corpus.variants().size(),
        "变体数与 v2 定标锚不一致（语料被改？）");

    // 每图单次编码（顺序 = base 先、变体后，与定标卡 jobs 同序）
    NearDupDisplacementEvidenceIT.TrackingProvider provider =
        new NearDupDisplacementEvidenceIT.TrackingProvider(realProvider(apiKey), PACE_MILLIS);
    Map<String, float[]> vectors = new LinkedHashMap<>();
    List<String> keys = new ArrayList<>();
    List<ImageJob> jobs = jobs(corpus);
    for (ImageJob job : jobs) {
      float[] vector = provider.embedImageTracked(job.png(), IMAGE_MIME, job.key()).vector();
      assertNotNull(vector, "视觉 embedding 返回空向量: " + job.key());
      assertTrue(vector.length > 0, "视觉 embedding 返回零维向量: " + job.key());
      vectors.put(job.key(), vector);
      keys.add(job.key());
    }
    int externalCalls =
        provider.visionCalls.get() + provider.embedImageCalls.get() + provider.embedCalls.get();
    assertEquals(corpus.imageCount(), provider.embedImageCalls.get(), "外呼次数必须等于图数（每图单次编码，禁止多编）");

    MatrixResult matrix = computeMatrix(corpus, keys, vectors, calibration);
    ObjectNode root =
        buildAnchor(
            corpus,
            keys,
            vectors,
            externalCalls,
            provider.visionCalls.get(),
            provider.embedImageCalls.get(),
            provider.embedCalls.get(),
            matrix);
    Files.createDirectories(ANCHOR_PATH.toAbsolutePath().getParent());
    Files.writeString(ANCHOR_PATH, MAPPER.writeValueAsString(root), StandardCharsets.UTF_8);
    System.out.println(
        "[image-cross-matrix] 锚已落盘: "
            + ANCHOR_PATH.toAbsolutePath()
            + "（images="
            + corpus.imageCount()
            + " externalCalls="
            + externalCalls
            + " maxAbsDeviation="
            + matrix.maxAbsDeviation()
            + " proxyVerdict="
            + matrix.proxyVerdict()
            + "）");
  }

  /** 一张待编码的图（与定标卡 ImageJob 同形）。 */
  private record ImageJob(String key, String docKey, byte[] png) {}

  /** 组装待编码图列表（顺序 = base 先、变体后，全确定）。 */
  private static List<ImageJob> jobs(NearDuplicateCorpus.Corpus corpus) {
    Map<String, String> docKeyOfBase = new LinkedHashMap<>();
    List<ImageJob> jobs = new ArrayList<>();
    for (NearDuplicateCorpus.Base base : corpus.bases()) {
      docKeyOfBase.put(base.key(), base.docKey());
      jobs.add(new ImageJob(base.key(), base.docKey(), base.png()));
    }
    for (NearDuplicateCorpus.Variant variant : corpus.variants()) {
      jobs.add(
          new ImageJob(
              variant.key(),
              docKeyOfBase.getOrDefault(variant.baseKey(), variant.baseKey()),
              variant.png()));
    }
    return List.copyOf(jobs);
  }

  // ==================== 判据计算（M1–M3，跑前冻结） ====================

  /** 矩阵与全部派生判据（write 与 replay 共用同一实现）。 */
  private record MatrixResult(
      double[][] matrix,
      double[] selfCosines,
      int deviationPairs,
      double maxAbsDeviation,
      int overThresholdCount,
      double nearDupMaxDeviation,
      double sameClassMaxDeviation,
      double crossClassMaxDeviation,
      int variantVsThirdCount,
      double p50,
      double p90,
      double p99,
      double maxVariantVsThird,
      boolean hasCosAtOrAboveFloor,
      String proxyVerdict) {}

  private MatrixResult computeMatrix(
      NearDuplicateCorpus.Corpus corpus,
      List<String> keys,
      Map<String, float[]> vectors,
      JsonNode calibration) {
    int n = keys.size();
    assertEquals(corpus.imageCount(), n, "键数必须等于图数");
    double[][] matrix = new double[n][n];
    double[] selfCosines = new double[n];
    for (int i = 0; i < n; i++) {
      float[] left = vectors.get(keys.get(i));
      selfCosines[i] = ImageCrossModalEvaluator.cosine(left, left);
      matrix[i][i] = selfCosines[i];
      for (int j = i + 1; j < n; j++) {
        double cosine = ImageCrossModalEvaluator.cosine(left, vectors.get(keys.get(j)));
        matrix[i][j] = cosine;
        matrix[j][i] = cosine;
      }
    }

    // M2：与定标锚已登记的 396 对逐对比较（禁「取较小偏差的那批」）
    Map<String, Integer> index = new LinkedHashMap<>();
    for (int i = 0; i < n; i++) {
      index.put(keys.get(i), i);
    }
    int pairs = 0;
    double maxDeviation = 0D;
    int overThreshold = 0;
    double nearDupMax = 0D;
    double sameClassMax = 0D;
    double crossClassMax = 0D;
    for (JsonNode pair : calibration.path("pairs")) {
      String stratum = pair.path("stratum").asText();
      if (!"near-duplicate".equals(stratum)
          && !"same-class".equals(stratum)
          && !"cross-class".equals(stratum)) {
        continue;
      }
      Integer left = index.get(pair.path("leftKey").asText());
      Integer right = index.get(pair.path("rightKey").asText());
      assertNotNull(left, "定标锚 leftKey 不在本矩阵键集: " + pair.path("leftKey").asText());
      assertNotNull(right, "定标锚 rightKey 不在本矩阵键集: " + pair.path("rightKey").asText());
      double deviation = Math.abs(matrix[left][right] - pair.path("cosine").asDouble());
      pairs++;
      maxDeviation = Math.max(maxDeviation, deviation);
      if (deviation > DEVIATION_LIMIT) {
        overThreshold++;
      }
      if ("near-duplicate".equals(stratum)) {
        nearDupMax = Math.max(nearDupMax, deviation);
      } else if ("same-class".equals(stratum)) {
        sameClassMax = Math.max(sameClassMax, deviation);
      } else {
        crossClassMax = Math.max(crossClassMax, deviation);
      }
    }
    assertEquals(396, pairs, "对账对数必须 = 120 + 100 + 176（禁换分母）");
    assertTrue(
        maxDeviation <= DEVIATION_LIMIT,
        "M2 偏差对账超阈值：maxAbsDeviation="
            + maxDeviation
            + " > "
            + DEVIATION_LIMIT
            + "，超阈对数="
            + overThreshold
            + "（编码非确定或模型已变 ⇒ 停 G2 上报，禁止把新矩阵当真相）");

    // M3：variant ↔ 第三图（120×23，排除自身 base）分位与带内存在性
    List<Double> thirdCosines = new ArrayList<>();
    for (NearDuplicateCorpus.Variant variant : corpus.variants()) {
      Integer variantIndex = index.get(variant.key());
      for (NearDuplicateCorpus.Base base : corpus.bases()) {
        if (base.key().equals(variant.baseKey())) {
          continue;
        }
        thirdCosines.add(matrix[variantIndex][index.get(base.key())]);
      }
    }
    int thirdCount = corpus.variants().size() * (corpus.bases().size() - 1);
    assertEquals(thirdCount, thirdCosines.size(), "variant↔第三图取值数必须 = 120×23");
    double[] sorted = thirdCosines.stream().mapToDouble(Double::doubleValue).sorted().toArray();
    double p50 = nearestRank(sorted, 0.50D);
    double p90 = nearestRank(sorted, 0.90D);
    double p99 = nearestRank(sorted, 0.99D);
    double max = sorted[sorted.length - 1];
    boolean hasInBand = max >= NEAR_DUPLICATE_FLOOR;
    String proxyVerdict = hasInBand ? "PROXY-UNDERSTATES" : "PROXY-SUFFICIENT";
    return new MatrixResult(
        matrix,
        selfCosines,
        pairs,
        maxDeviation,
        overThreshold,
        nearDupMax,
        sameClassMax,
        crossClassMax,
        thirdCount,
        p50,
        p90,
        p99,
        max,
        hasInBand,
        proxyVerdict);
  }

  /** 最近秩分位（升序第 ⌈q·n⌉ 个；写死避免浮点分位歧义，落锚 quantileMethod）。 */
  private static double nearestRank(double[] sorted, double q) {
    int rank = (int) Math.ceil(q * sorted.length);
    rank = Math.min(Math.max(rank, 1), sorted.length);
    return sorted[rank - 1];
  }

  // ==================== 锚构建（write 与 replay 共用） ====================

  private ObjectNode buildAnchor(
      NearDuplicateCorpus.Corpus corpus,
      List<String> keys,
      Map<String, float[]> vectors,
      int externalCalls,
      int visionCalls,
      int embedImageCalls,
      int embedCalls,
      MatrixResult matrix) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("card", "near-dup-missing-evidence");
    root.put("group", "G2");
    root.put("decisionVersion", "IMAGE-VARIANT-CROSS-MATRIX-1.0");
    root.put("decisionOwner", "OWNER");

    ObjectNode criteria = root.putObject("criteria");
    criteria.put("M1", "144×144 对称余弦（24 base + 120 variant），每图单次编码；对角实测自余弦落 selfCosines，不硬写 1.0");
    criteria.put(
        "M2",
        "与 v2 定标锚 near-duplicate 120 对 + same-class 100 对 + cross-class 176 对逐对比较，"
            + "maxAbsDeviation ≤ 1e-3 才允许发布；超阈值停 G2 上报，禁把新矩阵当真相、禁「取较小偏差的那批」");
    criteria.put(
        "M3",
        "variant↔第三图（120×23，排除自身 base）分位 p50/p90/p99/max，并回答是否存在 cos ≥ 0.9887："
            + "有 ⇒ PROXY-UNDERSTATES（base 对代理低估带内竞争者）；无 ⇒ PROXY-SUFFICIENT（附 max 与到 floor 的距离）");
    criteria.put(
        "M4",
        "image-near-dup-truth-v1.json 的 deviations（负例以 base 对为代理）与融合锚 R4 的代理缺口，按 M3 结论逐条回填 md");

    ObjectNode sourceAnchors = root.putObject("sourceAnchors");
    putAnchorSha(sourceAnchors, "corpusAnchor", "image-threshold-calibration-v2.json");
    putAnchorSha(sourceAnchors, "fusionImpactAnchor", "near-dup-fusion-impact-v1.json");
    putAnchorSha(sourceAnchors, "exchangeRateAnchor", "near-dup-exchange-rate-v1.json");
    putAnchorSha(sourceAnchors, "truthAnchor", "image-near-dup-truth-v1.json");

    ObjectNode constants = root.putObject("productionConstants");
    constants.put("threshold", 0.9944D);
    constants.put("nearDuplicateFloor", NEAR_DUPLICATE_FLOOR);
    constants.put("weightHigh", 0.75D);
    constants.put("weightMid", 0.6D);
    constants.put("weightLow", 0.5D);
    constants.put("textRouteWeight", 0.7D);
    constants.put("imageRouteWeight", 0.3D);
    constants.put("deviationLimit", DEVIATION_LIMIT);
    root.put("baselineHead", BASELINE_HEAD);

    ObjectNode corpusNode = root.putObject("corpus");
    corpusNode.put("bases", corpus.bases().size());
    corpusNode.put("variants", corpus.variants().size());
    corpusNode.put("images", corpus.imageCount());
    corpusNode.put("skips", corpus.skips().size());
    corpusNode.put("source", "NearDuplicateCorpus.generate()（确定性，未改生成逻辑、未新增语料）");
    ArrayNode documents = corpusNode.putArray("documents");
    for (NearDuplicateCorpus.DocumentCount document : corpus.documents()) {
      ObjectNode node = documents.addObject();
      node.put("docKey", document.docKey());
      node.put("requested", document.requested());
      node.put("taken", document.taken());
    }

    ArrayNode keyArray = root.putArray("keys");
    keys.forEach(keyArray::add);

    root.put("externalCalls", externalCalls);
    ObjectNode calls = root.putObject("callBreakdown");
    calls.put("visionCalls", visionCalls);
    calls.put("embedImageCalls", embedImageCalls);
    calls.put("embedCalls", embedCalls);
    calls.put("semantics", "embedImage 次数 = 图数（每图单次编码）；计数由 TrackingProvider 在真调用点累加，非公式推导");

    ObjectNode matrixNode = root.putObject("matrix");
    matrixNode.put("dimension", keys.size());
    matrixNode.put("symmetric", true);
    ArrayNode rows = matrixNode.putArray("rows");
    for (int i = 0; i < keys.size(); i++) {
      ArrayNode row = rows.addArray();
      for (int j = 0; j < keys.size(); j++) {
        row.add(matrix.matrix()[i][j]);
      }
    }
    ArrayNode selfArray = root.putArray("selfCosines");
    for (double selfCosine : matrix.selfCosines()) {
      selfArray.add(selfCosine);
    }

    ObjectNode deviation = root.putObject("deviationReconciliation");
    deviation.put("anchorStrata", "near-duplicate 120 + same-class 100 + cross-class 176");
    deviation.put("pairs", matrix.deviationPairs());
    deviation.put("maxAbsDeviation", matrix.maxAbsDeviation());
    deviation.put("overThresholdCount", matrix.overThresholdCount());
    deviation.put("limit", DEVIATION_LIMIT);
    deviation.put("nearDuplicateMaxDeviation", matrix.nearDupMaxDeviation());
    deviation.put("sameClassMaxDeviation", matrix.sameClassMaxDeviation());
    deviation.put("crossClassMaxDeviation", matrix.crossClassMaxDeviation());
    deviation.put("verdict", matrix.maxAbsDeviation() <= DEVIATION_LIMIT ? "PASS" : "STOP-G2");

    ObjectNode variantVsThird = root.putObject("variantVsThirdImage");
    variantVsThird.put("count", matrix.variantVsThirdCount());
    variantVsThird.put(
        "shape", corpus.variants().size() + "×" + (corpus.bases().size() - 1) + "（排除自身 base）");
    variantVsThird.put("quantileMethod", "nearest-rank（升序第 ⌈q·n⌉ 个）");
    variantVsThird.put("p50", matrix.p50());
    variantVsThird.put("p90", matrix.p90());
    variantVsThird.put("p99", matrix.p99());
    variantVsThird.put("maxVariantVsThird", matrix.maxVariantVsThird());
    variantVsThird.put("hasCosAtOrAboveFloor", matrix.hasCosAtOrAboveFloor());
    if (!matrix.hasCosAtOrAboveFloor()) {
      variantVsThird.put("distanceToFloor", NEAR_DUPLICATE_FLOOR - matrix.maxVariantVsThird());
    } else {
      variantVsThird.putNull("distanceToFloor");
    }
    root.put("proxyVerdict", matrix.proxyVerdict());
    root.put(
        "proxyVerdictReason",
        matrix.hasCosAtOrAboveFloor()
            ? "M3：存在 cos ≥ 0.9887 的变体↔第三图对——base 对代理低估带内竞争者，R4 与口径三的进入带样本数偏少"
            : "M3：variant↔第三图全部低于 0.9887（max=" + matrix.maxVariantVsThird() + "）——base 对代理在本语料成立");

    root.put("scopeNote", "本矩阵回答的是「base 对代理是否成立」（存在性），不是生产检索分布；结论只对同一确定性语料与当前 embedding 模型有效");
    root.put(
        "byteIdentityBasis",
        "锚内 vectors 冻结全部 144 条单编码向量；默认复跑（opt-in 无 write）从锚向量本地重算矩阵、对账与全部分位，"
            + "重算值逐值断言后整体序列化与磁盘锚逐字节比对，零外呼");
    root.put(
        "paceNote",
        "embedImage 按 " + PACE_MILLIS + "ms 主动降速（定标卡实测 2 QPS 中途 429），429 有界退避；节奏只影响墙钟，不改判据");

    ObjectNode material = root.putObject("vectors");
    for (String key : keys) {
      material.put(key, base64(vectors.get(key)));
    }
    return root;
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

  private static JsonNode readCalibration() {
    try {
      return MAPPER.readTree(Files.readAllBytes(CALIBRATION_ANCHOR));
    } catch (Exception exception) {
      throw new IllegalStateException("定标锚读取失败（上游锚只读）: " + CALIBRATION_ANCHOR, exception);
    }
  }

  // ==================== 默认复跑：从锚向量重算 + 逐字节比对（零外呼） ====================

  private void replayAndVerifyByteIdentity() throws Exception {
    assertTrue(Files.exists(ANCHOR_PATH), "锚不存在：默认复跑必须先有写锚产物（跳过≠复跑）: " + ANCHOR_PATH);
    String stored = Files.readString(ANCHOR_PATH, StandardCharsets.UTF_8);
    JsonNode root = MAPPER.readTree(stored);

    List<String> keys = new ArrayList<>();
    root.path("keys").forEach(key -> keys.add(key.asText()));
    Map<String, float[]> vectors = new LinkedHashMap<>();
    JsonNode material = root.path("vectors");
    for (String key : keys) {
      assertTrue(material.has(key), "锚缺 key 的向量: " + key);
      vectors.put(key, fromBase64(material.path(key).asText()));
    }

    NearDuplicateCorpus.Corpus corpus = NearDuplicateCorpus.generate();
    MatrixResult recomputed = computeMatrix(corpus, keys, vectors, readCalibration());

    ObjectNode rebuilt =
        buildAnchor(
            corpus,
            keys,
            vectors,
            root.path("externalCalls").asInt(),
            root.path("callBreakdown").path("visionCalls").asInt(),
            root.path("callBreakdown").path("embedImageCalls").asInt(),
            root.path("callBreakdown").path("embedCalls").asInt(),
            recomputed);
    String rebuiltJson = MAPPER.writeValueAsString(rebuilt);
    assertEquals(stored, rebuiltJson, "默认复跑序列化与磁盘锚不一致（锚被手改或重放算术漂移）——逐字节比对失败");
    System.out.println(
        "[image-cross-matrix] 默认复跑逐字节一致（images="
            + keys.size()
            + " proxyVerdict="
            + recomputed.proxyVerdict()
            + " maxAbsDeviation="
            + recomputed.maxAbsDeviation()
            + " 零外呼）");
  }

  // ==================== 装配与辅助 ====================

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

  private static ModelProvider realProvider(String apiKey) {
    ModelProviderProperties properties = new ModelProviderProperties();
    properties.setApiKey(apiKey);
    properties.setBaseUrl(COMPATIBLE_BASE_URL);
    // 超时口径按定标卡实测放宽：JDK HttpClient 进程内首次连接实测出现过 >10s 挂起
    properties.setTimeoutSeconds(120);
    properties.setConnectTimeoutSeconds(60);
    DashScopeApi nativeApi = DashScopeApi.builder().apiKey(apiKey).build();
    ChatModel nativeChat = DashScopeChatModel.builder().dashScopeApi(nativeApi).build();
    EmbeddingModel embeddingModel = new DashScopeEmbeddingModel(nativeApi, MetadataMode.EMBED);
    Environment environment =
        new MockEnvironment()
            .withProperty("spring.ai.dashscope.api-key", apiKey)
            .withProperty("spring.ai.dashscope.base-url", COMPATIBLE_BASE_URL);
    return new ModelProviderImpl(
        providerOf(nativeChat), providerOf(embeddingModel), properties, environment);
  }

  private static <T> ObjectProvider<T> providerOf(T value) {
    return new ObjectProvider<>() {
      @Override
      public T getObject() {
        return value;
      }
    };
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
}
