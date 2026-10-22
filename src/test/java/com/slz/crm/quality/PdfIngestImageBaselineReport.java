package com.slz.crm.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.slz.crm.quality.ImageCrossModalEvaluator.CaseJudgement;
import com.slz.crm.quality.ImageCrossModalEvaluator.Evaluation;
import com.slz.crm.quality.ImageCrossModalEvaluator.PathMetrics;
import com.slz.crm.quality.ImageCrossModalSuite.ImageCase;
import com.slz.crm.quality.RagQualityReport.Report;
import java.util.List;

/**
 * PDF 摄取链图文评测的基线 JSON 装配（pdf-ingest-image-eval 任务 2.1 / 2.2 的落盘物）。
 *
 * <p><b>为什么单独成类而不是改 {@code ImageCrossModalEvaluator#toJson}</b>：上一卡的装配器把 corpus 段硬绑在 {@code
 * ImageCrossModalFixture} 上、把 suiteVersion 绑在 {@code IMAGE-SUITE-1.0} 上。本卡的新基线是<b>另一条链路</b>（真 PDF →
 * 摄取 → image 集合），套件版本号、语料段、附加证据段都不同；改上一卡的装配器会让 image-baseline-v1 的落盘物随本卡漂移，违背"历史基线保持可比"。
 *
 * <p><b>结构与本卡口径</b>：与 {@code image-baseline-v1.json} 同段名（{@code vision} / {@code textBaseline} /
 * {@code comparison} / {@code report}），因此两卡基线可以逐字段对读；差异只有三处——{@code
 * suiteVersion=PDF-IMAGE-SUITE-1.0}、{@code corpus} 段来自 PDF 三型语料、每组用例额外携带<b>真实黄金 id</b>（生产切片行主键派生的
 * {@code IMAGE:{id}}，由调用方从落库行读回，不是合成键）。
 *
 * <p><b>为什么用例行要按位置配对</b>：{@link ImageCrossModalEvaluator#evaluate} 保证 {@code cases} 与 {@code
 * evaluation.cases()} 同序同长（单测 {@code ImageCrossModalSuiteTest} 已锁该口径）；黄金 id 只有调用方知道（来自摄取链），故这里按位置把
 * 调用方的 {@link ImageCase} 与判分结果拼成一行——配对长度不等直接抛错，不产出"看起来正常"的错位基线。
 */
final class PdfIngestImageBaselineReport {

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private PdfIngestImageBaselineReport() {}

  /**
   * 装配基线 JSON 根节点。
   *
   * @param report 独立版本号的评测报告（recall/MRR/hit 判卷产物，{@code suiteVersion=PDF-IMAGE-SUITE-1.0}）
   * @param vision 真视觉 query-by-image 路评估
   * @param textBaseline 文本降维对照路评估
   * @param cases 用例表（与两条 evaluation 同序同长）
   * @param goldenByKey 图键 → 生产黄金引用 id（取自摄取链落库行）
   * @param optInNote opt-in 与语料口径留痕
   * @return JSON 根节点
   * @throws IllegalStateException 用例表与评估结果长度不一致（错位基线的唯一防线）
   */
  static ObjectNode toJson(
      Report report,
      Evaluation vision,
      Evaluation textBaseline,
      List<ImageCase> cases,
      java.util.Map<String, String> goldenByKey,
      String optInNote) {
    if (vision.cases().size() != cases.size() || textBaseline.cases().size() != cases.size()) {
      throw new IllegalStateException(
          "用例表与评估结果长度不一致: cases="
              + cases.size()
              + " vision="
              + vision.cases().size()
              + " text="
              + textBaseline.cases().size());
    }
    ObjectNode root = MAPPER.createObjectNode();
    root.put("suiteVersion", PdfIngestImageFixture.SUITE_VERSION);
    root.put("generatedAt", report.generatedAt());
    root.put("optIn", optInNote);
    root.put("topK", ImageCrossModalSuite.TOP_K);
    root.put("sameImageCosineThreshold", ImageCrossModalSuite.SAME_IMAGE_COSINE_THRESHOLD);
    root.put("crossImageCosineThreshold", ImageCrossModalSuite.CROSS_IMAGE_COSINE_THRESHOLD);
    ObjectNode judgement = root.putObject("judgement");
    judgement.put("sameImageHit", "sameImageCosine >= sameImageCosineThreshold");
    judgement.put("crossImageRejected", "crossImageCosine < crossImageCosineThreshold");
    judgement.put(
        "casePass",
        "sameImageHit && crossImageRejected && (sameImageCosine - crossImageCosine) >= 0");
    judgement.put("口径来源", "ImageCrossModalEvaluator（上一卡 frame 复用，不新造判据）");
    root.set("corpus", corpusNode());
    root.set("vision", evaluationNode(vision, cases, goldenByKey));
    root.set("textBaseline", evaluationNode(textBaseline, cases, goldenByKey));
    root.set("comparison", comparisonNode(vision, textBaseline));
    root.set("report", MAPPER.valueToTree(report));
    return root;
  }

  /** 语料段：图键 / 预期视觉内容 / 黄金 id / 所在页（页序即提取顺序）。 */
  private static ArrayNode corpusNode() {
    ArrayNode corpus = MAPPER.createArrayNode();
    List<String> keys = PdfIngestImageFixture.keys();
    for (int index = 0; index < keys.size(); index++) {
      String key = keys.get(index);
      ObjectNode node = corpus.addObject();
      node.put("key", key);
      node.put("pageNo", index + 1);
      node.put("expectedContent", PdfIngestImageFixture.image(key).caption());
      node.put("goldenChunkId", PdfIngestImageFixture.goldenId(key));
      node.put("chunkIdDerivation", "IMAGE:{生产切片行主键}");
    }
    return corpus;
  }

  /** 一条路的评估段：阈值口径下的逐条判分（带真实黄金 id）。 */
  private static ObjectNode evaluationNode(
      Evaluation evaluation, List<ImageCase> cases, java.util.Map<String, String> goldenByKey) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("path", evaluation.path());
    node.set("metrics", metricsNode(evaluation.metrics()));
    ArrayNode rows = node.putArray("cases");
    for (int index = 0; index < cases.size(); index++) {
      ImageCase imageCase = cases.get(index);
      CaseJudgement judgement = evaluation.cases().get(index);
      ObjectNode row = rows.addObject();
      row.put("id", imageCase.id());
      row.put("queryImageKey", imageCase.queryImageKey());
      row.put("sameImageKey", imageCase.sameImageKey());
      row.put("crossImageKey", imageCase.crossImageKey());
      row.put("sameImageChunkId", goldenByKey.get(imageCase.sameImageKey()));
      row.put("crossImageChunkId", goldenByKey.get(imageCase.crossImageKey()));
      row.put("sameImageCosine", judgement.sameImageCosine());
      row.put("crossImageCosine", judgement.crossImageCosine());
      row.put("discriminationMargin", judgement.discriminationMargin());
      row.put("sameImageHit", judgement.sameImageHit());
      row.put("crossImageRejected", judgement.crossImageRejected());
      row.put("sameImageRetrieved", judgement.sameImageRetrieved());
      row.put("crossImageRetrieved", judgement.crossImageRetrieved());
      row.put("pass", judgement.pass());
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

  /** 两路差值（真视觉 − 文本降维）：正 = 真视觉更好，即"PDF 摄取链上真视觉带来的增量"。 */
  private static ObjectNode comparisonNode(Evaluation vision, Evaluation textBaseline) {
    ObjectNode node = MAPPER.copy().createObjectNode();
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
        "note",
        "差值为正表示真视觉路优于文本降维路；文本降维路把图编码成 VLM 描述文本，同骨架异图在其空间里会被拉近（误召），" + "本卡在真 PDF 摄取链上量化这一位点");
    return node;
  }
}
