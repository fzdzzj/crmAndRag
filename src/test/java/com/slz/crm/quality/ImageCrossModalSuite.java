package com.slz.crm.quality;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 图文跨模态评测独立套件（image-cross-modal-eval 任务 1.1，{@code IMAGE-SUITE-1.0}）。
 *
 * <p><b>独立套件的理由</b>（对齐 {@code CustomerRetrievalBenchmarkIT} 的 {@code CUSTOMER-SUITE-2.0} 先例）：主套件
 * {@code RagBenchmarkSuite}（{@code SUITE_VERSION="6.0"}，72 例，其中 {@code Category.IMAGE} 6
 * 例）评测的是"图片理解问答走文本描述切片召回" ——零真视觉向量召回位点。本套件用<b>独立编号 IM-01~IM-N + 独立版本号</b>承载 query-by-image
 * 判分，<b>不并入主套件、不改 {@code SUITE_VERSION}、不改既有 {@code Category.IMAGE} 6 例语义</b>；历史基线 baseline-v1~v6
 * 因此保持可比。
 *
 * <p><b>一条用例 = 图三元组</b>：查询图（query-by-image 的输入）+ 同图（应召回，余弦高）+ 异图（不应召回，余弦低，见 fixture 的
 * "描述文本同骨架、视觉跨图型"设计）。判分口径见 {@link ImageCrossModalEvaluator}：同图余弦 ≥ {@link
 * #SAME_IMAGE_COSINE_THRESHOLD} 计命中、异图余弦 &lt; {@link #SAME_IMAGE_COSINE_THRESHOLD} 计正确拒答（阈值口径对齐
 * {@code VisionEmbeddingRealIT} 的 0.9）。
 *
 * <p><b>与 {@link RagBenchmarkCase} 的关系</b>：{@link #benchmarkCases()} 把每条用例投影成主套件同型用例（{@code
 * Category.IMAGE}、黄金 id = 同图切片 id），从而复用 {@link RagQualityEvaluator} 的 recall/MRR/hit
 * 判卷；余弦判别这类跨模态专属指标走 {@link ImageCrossModalEvaluator}，两者并列落盘。
 */
final class ImageCrossModalSuite {

  /** 独立套件版本号（不并入 {@code RagBenchmarkSuite.SUITE_VERSION}）。 */
  static final String SUITE_VERSION = "IMAGE-SUITE-1.0";

  /** 同图命中阈值（对齐 VisionEmbeddingRealIT：同图余弦 &gt; 0.9）。 */
  static final double SAME_IMAGE_COSINE_THRESHOLD = 0.90D;

  /** 异图拒答阈值（对齐 VisionEmbeddingRealIT：异图余弦 &lt; 0.9）。 */
  static final double CROSS_IMAGE_COSINE_THRESHOLD = 0.90D;

  /** 召回截断排名（与主基准、customer 基准同口径）。 */
  static final int TOP_K = 5;

  private ImageCrossModalSuite() {}

  /**
   * 单条图文跨模态用例。
   *
   * @param id 用例编号（IM-01…）
   * @param queryImageKey 查询图键（query-by-image 输入）
   * @param sameImageKey 同图键（应召回；与 query 同视觉内容）
   * @param crossImageKey 异图键（不应召回；描述文本同骨架、视觉跨图型）
   * @param queryText 查询附带的文本线索（生产里是用户随图提问；本套件用该图描述文本）
   * @param expectedChunkIds 黄金切片 id（= 同图切片 id，供 {@link RagQualityEvaluator} 判 recall）
   */
  record ImageCase(
      String id,
      String queryImageKey,
      String sameImageKey,
      String crossImageKey,
      String queryText,
      Set<String> expectedChunkIds) {}

  /**
   * 套件用例（5 条，IM-01~IM-05）。
   *
   * <p>异图选取在跑之前固定（不按结果调参），口径统一为"描述文本同骨架、视觉跨图型"：IM-01/IM-02 为蓝流程图 ↔ 蓝柱状图、IM-03 为橙流程图 ↔
   * 蓝柱状图（描述文本仅配色与句尾词之差）、IM-04 为扫描表格 ↔ 卡片看板（同为"台账/工单"语域）、IM-05 为卡片看板 ↔ 纯白底图（易负例地板对照）。
   *
   * <p>口径边界（实测留痕）：<b>仅配色不同、图型相同</b>的两张图（如蓝/橙流程图）真视觉余弦约 0.98——跨模态 embedding 主要编码结构而非配色，
   * 这类负例属"视觉几乎同图"，不作本套件的异图（否则是拿判据去要求它做不到的事）。纯文本降维路对同一批负例的余弦更高，这正是对照的意义。
   *
   * @return 用例表（不可变）
   */
  static List<ImageCase> cases() {
    List<ImageCase> cases = new ArrayList<>();
    cases.add(caseOf("IM-01", "flow-blue", "flow-blue", "bars-blue"));
    cases.add(caseOf("IM-02", "bars-blue", "bars-blue", "flow-blue"));
    cases.add(caseOf("IM-03", "flow-orange", "flow-orange", "bars-blue"));
    cases.add(caseOf("IM-04", "table-scan", "table-scan", "cards-green"));
    cases.add(caseOf("IM-05", "cards-green", "cards-green", "blank-white"));
    return List.copyOf(cases);
  }

  /** 单条用例构造：queryText 取查询图的描述文本，黄金 id 取同图切片 id。 */
  private static ImageCase caseOf(
      String id, String queryImageKey, String sameImageKey, String crossImageKey) {
    String queryText = ImageCrossModalFixture.image(queryImageKey).caption();
    return new ImageCase(
        id,
        queryImageKey,
        sameImageKey,
        crossImageKey,
        queryText,
        Set.of(ImageCrossModalFixture.sourceChunkId(sameImageKey)));
  }

  /**
   * 投影成主套件同型用例（复用 {@link RagQualityEvaluator} 判卷口径）。
   *
   * <p>纯检索判分：无答案要点（{@code expectedAnswerPoints} 空、{@code answerPointCoverage} 由调用方给中立值），评估指标只看
   * recall/precision/MRR/hit。
   *
   * @return {@link RagBenchmarkCase} 列表（{@code Category.IMAGE}）
   */
  static List<RagBenchmarkCase> benchmarkCases() {
    return cases().stream()
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

  /**
   * 按 id 取用例。
   *
   * @param id 用例编号
   * @return 用例
   * @throws IllegalArgumentException 编号不存在
   */
  static ImageCase byId(String id) {
    return cases().stream()
        .filter(imageCase -> imageCase.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知图文用例编号: " + id));
  }
}
