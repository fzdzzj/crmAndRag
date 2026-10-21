package com.slz.crm.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.quality.ImageCrossModalSuite.ImageCase;
import com.slz.crm.quality.RagQualityReport.Report;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 图文跨模态判分口径（image-cross-modal-eval 任务 3.1 / 3.2）：余弦判别 + 同图召回率 / 异图误召率 + 基线 JSON 装配。
 *
 * <p><b>为什么判分口径独立成类</b>：{@link RagQualityEvaluator} 判的是 chunkId 召回（recall/MRR/hit），无法表达"余弦有多高"这类跨模态
 * 专属信号。本类把"同图余弦 ≥ 阈值 → 命中 / 异图余弦 &lt; 阈值 → 正确拒答"的判别口径做成纯函数（零外呼、零状态）， 因而单测可用 fake 向量手算验证阈值边界，真跑 IT
 * 只负责把真 embedding 的余弦喂进来。
 *
 * <p><b>判据（Owner 拍板，2026-10-21）</b>：同/异图余弦判别——同图余弦高、异图低。逐条 {@code pass} = 同图命中 ∧ 异图拒答 ∧ 同图余弦 &gt;
 * 异图余弦（"显著高于"的否定式：不低于）；阈值默认 0.90（对齐 {@code VisionEmbeddingRealIT}）。
 *
 * <p><b>两条路同口径并列</b>：同一个 {@link #evaluate} 同时用于真视觉向量路与"描述文本→文本向量"降维路，两者指标可直接相减（{@link #toJson} 里的
 * {@code comparison} 段）；这是"真视觉是否带来增量"的可比较证据，而不是各说各话。
 */
final class ImageCrossModalEvaluator {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private ImageCrossModalEvaluator() {}

  /**
   * 单条用例的两路观测值（由调用方注入——真跑给真 embedding 余弦，单测给 fake）。
   *
   * @param sameImageCosine 查询向量 vs 同图向量的余弦
   * @param crossImageCosine 查询向量 vs 异图向量的余弦
   * @param sameImageRetrieved 同图切片是否进了检索 topK（检索层观测，与余弦判别互为佐证）
   * @param crossImageRetrieved 异图切片是否进了检索 topK（检索层误召观测）
   */
  record Probe(
      double sameImageCosine,
      double crossImageCosine,
      boolean sameImageRetrieved,
      boolean crossImageRetrieved) {}

  /**
   * 单条用例判分结果。
   *
   * @param id 用例编号
   * @param queryImageKey 查询图键
   * @param sameImageChunkId 同图黄金切片 id
   * @param crossImageChunkId 异图切片 id
   * @param sameImageCosine 同图余弦
   * @param crossImageCosine 异图余弦
   * @param discriminationMargin 判别间隔 = 同图余弦 − 异图余弦（越大越能分开）
   * @param sameImageHit 同图命中（余弦 ≥ 阈值）
   * @param crossImageRejected 异图正确拒答（余弦 &lt; 阈值）
   * @param sameImageRetrieved 同图切片进了 topK
   * @param crossImageRetrieved 异图切片进了 topK
   * @param pass 本条判据全过（命中 ∧ 拒答 ∧ 间隔 ≥ 0）
   */
  record CaseJudgement(
      String id,
      String queryImageKey,
      String sameImageChunkId,
      String crossImageChunkId,
      double sameImageCosine,
      double crossImageCosine,
      double discriminationMargin,
      boolean sameImageHit,
      boolean crossImageRejected,
      boolean sameImageRetrieved,
      boolean crossImageRetrieved,
      boolean pass) {}

  /**
   * 一条路的聚合指标（真视觉路与文本降维路同型，可逐项相减）。
   *
   * @param caseCount 用例数
   * @param sameImageHitRate 同图召回率（余弦判据，任务组 3 主指标）
   * @param crossImageRejectionRate 异图正确拒答率
   * @param crossImageFalsePositiveRate 异图误召率（= 1 − 拒答率，任务组 3 主指标）
   * @param meanSameImageCosine 同图余弦均值
   * @param meanCrossImageCosine 异图余弦均值
   * @param meanDiscriminationMargin 判别间隔均值（同图 − 异图）
   * @param retrievalRecallAtK 检索层同图召回率（同图切片进 topK）
   * @param retrievalCrossImageRate 检索层异图误召率
   * @param passRate 逐条判据全过率
   */
  record PathMetrics(
      int caseCount,
      double sameImageHitRate,
      double crossImageRejectionRate,
      double crossImageFalsePositiveRate,
      double meanSameImageCosine,
      double meanCrossImageCosine,
      double meanDiscriminationMargin,
      double retrievalRecallAtK,
      double retrievalCrossImageRate,
      double passRate) {}

  /**
   * 一条路的完整评估结果。
   *
   * @param path 路径标签（{@code vision-query-by-image} / {@code text-caption-baseline}）
   * @param cases 逐条判分
   * @param metrics 聚合指标
   */
  record Evaluation(String path, List<CaseJudgement> cases, PathMetrics metrics) {}

  /**
   * 余弦相似度（跨模态比较口径，与 {@code VisionEmbeddingRealIT} / {@code InMemoryVectorStore} 同式）。
   *
   * <p>维度不等直接抛错而不是截断比较：视觉向量与文本向量维度本就不同（image collection 独立维度），把不同空间的向量拿来算余弦是
   * 接线错误，静默截断会产出"看起来正常"的假指标。
   *
   * @param left 左向量
   * @param right 右向量
   * @return 余弦（零范数返回 0，避免 NaN 假红/假绿）
   * @throws IllegalArgumentException 维度不一致或向量为空
   */
  static double cosine(float[] left, float[] right) {
    if (left == null || right == null || left.length == 0 || right.length == 0) {
      throw new IllegalArgumentException("余弦比较的向量不能为空");
    }
    if (left.length != right.length) {
      throw new IllegalArgumentException("余弦比较的向量维度不一致: " + left.length + " vs " + right.length);
    }
    double dot = 0D;
    double leftNorm = 0D;
    double rightNorm = 0D;
    for (int index = 0; index < left.length; index++) {
      dot += (double) left[index] * right[index];
      leftNorm += (double) left[index] * left[index];
      rightNorm += (double) right[index] * right[index];
    }
    return leftNorm <= 0D || rightNorm <= 0D
        ? 0D
        : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
  }

  /**
   * 按余弦判据评估一条路。
   *
   * @param path 路径标签
   * @param cases 用例
   * @param probe 观测值提供函数（真跑 = 真 embedding；单测 = fake）
   * @return 逐条判分 + 聚合指标
   */
  static Evaluation evaluate(String path, List<ImageCase> cases, Function<ImageCase, Probe> probe) {
    List<CaseJudgement> judgements = new ArrayList<>();
    for (ImageCase imageCase : cases) {
      Probe observation = probe.apply(imageCase);
      double margin = observation.sameImageCosine() - observation.crossImageCosine();
      boolean hit =
          observation.sameImageCosine() >= ImageCrossModalSuite.SAME_IMAGE_COSINE_THRESHOLD;
      boolean rejected =
          observation.crossImageCosine() < ImageCrossModalSuite.CROSS_IMAGE_COSINE_THRESHOLD;
      judgements.add(
          new CaseJudgement(
              imageCase.id(),
              imageCase.queryImageKey(),
              ImageCrossModalFixture.sourceChunkId(imageCase.sameImageKey()),
              ImageCrossModalFixture.sourceChunkId(imageCase.crossImageKey()),
              observation.sameImageCosine(),
              observation.crossImageCosine(),
              margin,
              hit,
              rejected,
              observation.sameImageRetrieved(),
              observation.crossImageRetrieved(),
              hit && rejected && margin >= 0D));
    }
    return new Evaluation(path, List.copyOf(judgements), metricsOf(judgements));
  }

  private static PathMetrics metricsOf(List<CaseJudgement> judgements) {
    int count = judgements.size();
    if (count == 0) {
      return new PathMetrics(0, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D);
    }
    int hits = 0;
    int rejected = 0;
    int retrieved = 0;
    int crossRetrieved = 0;
    int passes = 0;
    double sameSum = 0D;
    double crossSum = 0D;
    double marginSum = 0D;
    for (CaseJudgement judgement : judgements) {
      if (judgement.sameImageHit()) {
        hits++;
      }
      if (judgement.crossImageRejected()) {
        rejected++;
      }
      if (judgement.sameImageRetrieved()) {
        retrieved++;
      }
      if (judgement.crossImageRetrieved()) {
        crossRetrieved++;
      }
      if (judgement.pass()) {
        passes++;
      }
      sameSum += judgement.sameImageCosine();
      crossSum += judgement.crossImageCosine();
      marginSum += judgement.discriminationMargin();
    }
    double rejectionRate = (double) rejected / count;
    return new PathMetrics(
        count,
        (double) hits / count,
        rejectionRate,
        1D - rejectionRate,
        sameSum / count,
        crossSum / count,
        marginSum / count,
        (double) retrieved / count,
        (double) crossRetrieved / count,
        (double) passes / count);
  }

  /**
   * 基线 JSON 装配（{@code docs/rag-quality/image-baseline-v1.json} 的唯一写入口）。
   *
   * <p>结构：套件版本/生成时间/阈值/语料 → 真视觉路评估 → 文本降维对照路评估 → 两路 comparison → {@link Report} 原样节点（证明落盘物里带的就是
   * {@code suiteVersion=IMAGE-SUITE-1.0} 的评测报告，而非重拼一份）。
   *
   * @param report 独立版本号的评测报告（recall/MRR/hit 判卷产物）
   * @param vision 真视觉 query-by-image 路评估
   * @param textBaseline 文本降维对照路评估
   * @param optInNote opt-in 与语料说明（落盘留痕，供复核判据读）
   * @return JSON 根节点
   */
  static ObjectNode toJson(
      Report report, Evaluation vision, Evaluation textBaseline, String optInNote) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("suiteVersion", ImageCrossModalSuite.SUITE_VERSION);
    root.put("generatedAt", report.generatedAt());
    root.put("optIn", optInNote);
    root.put("topK", ImageCrossModalSuite.TOP_K);
    root.put("sameImageCosineThreshold", ImageCrossModalSuite.SAME_IMAGE_COSINE_THRESHOLD);
    root.put("crossImageCosineThreshold", ImageCrossModalSuite.CROSS_IMAGE_COSINE_THRESHOLD);
    ObjectNode thresholds = root.putObject("judgement");
    thresholds.put("sameImageHit", "sameImageCosine >= sameImageCosineThreshold");
    thresholds.put("crossImageRejected", "crossImageCosine < crossImageCosineThreshold");
    thresholds.put(
        "casePass",
        "sameImageHit && crossImageRejected && (sameImageCosine - crossImageCosine) >= 0");
    root.set("corpus", corpusNode());
    root.set("vision", evaluationNode(vision));
    root.set("textBaseline", evaluationNode(textBaseline));
    root.set("comparison", comparisonNode(vision, textBaseline));
    root.set("report", MAPPER.valueToTree(report));
    return root;
  }

  private static ArrayNode corpusNode() {
    ArrayNode corpus = MAPPER.createArrayNode();
    for (ImageCrossModalFixture.FixtureImage image : ImageCrossModalFixture.images()) {
      ObjectNode node = corpus.addObject();
      node.put("key", image.key());
      node.put("caption", image.caption());
      node.put("chunkId", image.chunkId());
    }
    return corpus;
  }

  private static ObjectNode evaluationNode(Evaluation evaluation) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("path", evaluation.path());
    node.set("metrics", metricsNode(evaluation.metrics()));
    ArrayNode cases = node.putArray("cases");
    for (CaseJudgement judgement : evaluation.cases()) {
      ObjectNode item = cases.addObject();
      item.put("id", judgement.id());
      item.put("queryImageKey", judgement.queryImageKey());
      item.put("sameImageChunkId", judgement.sameImageChunkId());
      item.put("crossImageChunkId", judgement.crossImageChunkId());
      item.put("sameImageCosine", judgement.sameImageCosine());
      item.put("crossImageCosine", judgement.crossImageCosine());
      item.put("discriminationMargin", judgement.discriminationMargin());
      item.put("sameImageHit", judgement.sameImageHit());
      item.put("crossImageRejected", judgement.crossImageRejected());
      item.put("sameImageRetrieved", judgement.sameImageRetrieved());
      item.put("crossImageRetrieved", judgement.crossImageRetrieved());
      item.put("pass", judgement.pass());
    }
    return node;
  }

  private static ObjectNode metricsNode(PathMetrics metrics) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("caseCount", metrics.caseCount());
    node.put("sameImageHitRate", metrics.sameImageHitRate());
    node.put("crossImageRejectionRate", metrics.crossImageRejectionRate());
    node.put("crossImageFalsePositiveRate", metrics.crossImageFalsePositiveRate());
    node.put("meanSameImageCosine", metrics.meanSameImageCosine());
    node.put("meanCrossImageCosine", metrics.meanCrossImageCosine());
    node.put("meanDiscriminationMargin", metrics.meanDiscriminationMargin());
    node.put("retrievalRecallAtK", metrics.retrievalRecallAtK());
    node.put("retrievalCrossImageRate", metrics.retrievalCrossImageRate());
    node.put("passRate", metrics.passRate());
    return node;
  }

  /** 两路差值（真视觉 − 文本降维）：正 = 真视觉更好，是"增量价值"的量化形式。 */
  private static ObjectNode comparisonNode(Evaluation vision, Evaluation textBaseline) {
    ObjectNode node = MAPPER.createObjectNode();
    PathMetrics visionMetrics = vision.metrics();
    PathMetrics textMetrics = textBaseline.metrics();
    node.put(
        "discriminationMarginDelta",
        visionMetrics.meanDiscriminationMargin() - textMetrics.meanDiscriminationMargin());
    node.put(
        "crossImageFalsePositiveRateDelta",
        visionMetrics.crossImageFalsePositiveRate() - textMetrics.crossImageFalsePositiveRate());
    node.put(
        "sameImageHitRateDelta", visionMetrics.sameImageHitRate() - textMetrics.sameImageHitRate());
    node.put(
        "retrievalRecallAtKDelta",
        visionMetrics.retrievalRecallAtK() - textMetrics.retrievalRecallAtK());
    node.put(
        "note", "差值为正表示真视觉路优于文本降维路；文本降维路把图编码为描述文本，" + "描述文本骨架相同的异图在其空间中近乎同一向量（误召），这正是本套件要量化的增量位点");
    return node;
  }
}
