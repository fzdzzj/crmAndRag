package com.slz.crm.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异图硬阈值定标口径（multimodal-gray-and-eval-threshold 任务 2.1 / 2.2）。
 *
 * <p><b>为什么需要定标而不是继续用 0.90</b>：0.90 来自程序化语料（同图 1.0 / 异图 0.5~0.8），而真实语料里「两个不同文档的首页版式图」 的视觉余弦可以到
 * 0.9887（{@code production-gray-cost-v1.json} 实测）——<b>0.90 会把这类异图判成同图</b>。定标要做的事：
 * 用「同图再编码」测出<b>同图下限</b>、用「同类不同图 + 跨类图」测出<b>异图上限</b>，若两者可分则给出落在区间内的判别阈值。
 *
 * <p><b>纯函数、零外呼</b>：本类只吃余弦样本（{@link Pair}），不碰网络、不碰 Spring——真跑 IT 负责把真 embedding 的余弦喂进来， 单测用 fake
 * 向量手算验证边界（含不可分退化）。
 *
 * <p><b>三层档位口径</b>：{@code same-image}（同字节再编码）= 同图下限；{@code same-class}（同图型不同实例）+ {@code
 * cross-class}（不同图型）合并成「异图」——任何<b>不同</b>的图都必须被拒答，所以异图上限取这两档的最大值，不能只看跨类。
 */
final class ImageThresholdCalibrator {

  /** 定标版本号（与既有 baseline 各版本号并列，互不覆盖）。 */
  static final String CALIBRATION_VERSION = "IMAGE-THRESHOLD-CALIBRATION-1.0";

  /** 同图档（同字节再编码）。 */
  static final String SAME_IMAGE = "same-image";

  /** 同类档（同图型不同实例）。 */
  static final String SAME_CLASS = "same-class";

  /** 跨类档（不同图型）。 */
  static final String CROSS_CLASS = "cross-class";

  private static final ObjectMapper MAPPER =
      new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

  private ImageThresholdCalibrator() {}

  /**
   * 一次编码（同一张图编码两次，A/B 两向量用于「同图再编码」观测）。
   *
   * @param corpus 语料标签（{@code programmatic} / {@code real-pdf}）
   * @param key 图键（语料内唯一）
   * @param klass 图型族（同类判定的唯一依据；真实语料用出版物键）
   * @param vectorA 第一次编码向量
   * @param vectorB 第二次编码向量（同一字节再编码）
   */
  record Encoding(String corpus, String key, String klass, float[] vectorA, float[] vectorB) {}

  /**
   * 一对样本的余弦观测。
   *
   * @param corpus 语料标签
   * @param leftKey 左图键
   * @param rightKey 右图键
   * @param stratum 档位（{@link #SAME_IMAGE} / {@link #SAME_CLASS} / {@link #CROSS_CLASS}）
   * @param cosine 余弦
   */
  record Pair(String corpus, String leftKey, String rightKey, String stratum, double cosine) {}

  /**
   * 单档统计。
   *
   * @param name 档位名
   * @param count 样本对数
   * @param min 最小值
   * @param mean 均值
   * @param max 最大值
   */
  record Stratum(String name, int count, double min, double mean, double max) {}

  /**
   * 单语料的定标结论。
   *
   * @param corpus 语料标签
   * @param sameImage 同图档统计
   * @param sameClass 同类档统计
   * @param crossClass 跨类档统计
   * @param sameImageFloor 同图下限（同图档最小值）
   * @param crossImageCeiling 异图上限（同类 + 跨类的最大值）
   * @param separable 可分（异图上限 &lt; 同图下限）
   * @param recommendedThreshold 判别阈值（可分时 = 区间中点；不可分为 {@code null}）
   */
  record Calibration(
      String corpus,
      Stratum sameImage,
      Stratum sameClass,
      Stratum crossClass,
      double sameImageFloor,
      double crossImageCeiling,
      boolean separable,
      Double recommendedThreshold) {}

  /**
   * 由编码表生成全部样本对（只生成一次，不在跑后调参）。
   *
   * <p>口径固定：同图档 = 同一 key 的 A vs B；同类档 = 同 klass 不同 key 的 A vs A；跨类档 = 不同 klass 的 A vs A。
   *
   * @param encodings 编码表（同一 corpus 内比较）
   * @return 样本对（顺序确定）
   */
  static List<Pair> pairs(List<Encoding> encodings) {
    List<Pair> pairs = new ArrayList<>();
    for (int left = 0; left < encodings.size(); left++) {
      Encoding first = encodings.get(left);
      pairs.add(
          new Pair(
              first.corpus(),
              first.key(),
              first.key(),
              SAME_IMAGE,
              ImageCrossModalEvaluator.cosine(first.vectorA(), first.vectorB())));
      for (int right = left + 1; right < encodings.size(); right++) {
        Encoding second = encodings.get(right);
        if (!first.corpus().equals(second.corpus())) {
          continue;
        }
        String stratum = first.klass().equals(second.klass()) ? SAME_CLASS : CROSS_CLASS;
        pairs.add(
            new Pair(
                first.corpus(),
                first.key(),
                second.key(),
                stratum,
                ImageCrossModalEvaluator.cosine(first.vectorA(), second.vectorA())));
      }
    }
    return List.copyOf(pairs);
  }

  /**
   * 单语料定标：同图下限 / 异图上限 / 是否可分 / 判别阈值。
   *
   * <p>阈值 = 同图下限与异图上限的中点——它同时满足「同图余弦 ≥ 阈值」（不误拒同图）与「异图余弦 &lt; 阈值」（不误召异图）。 不可分时<b>不给阈值</b>（返回 {@code
   * null}），而不是硬凑一个数：真实语料分不开就该如实说分不开。
   *
   * @param corpus 语料标签
   * @param pairs 该语料的样本对
   * @return 定标结论
   */
  static Calibration calibrate(String corpus, List<Pair> pairs) {
    List<Pair> sameImage = filter(pairs, SAME_IMAGE);
    List<Pair> sameClass = filter(pairs, SAME_CLASS);
    List<Pair> crossClass = filter(pairs, CROSS_CLASS);
    double sameImageFloor = minOf(sameImage);
    double crossImageCeiling = Math.max(maxOf(sameClass), maxOf(crossClass));
    boolean separable =
        !sameImage.isEmpty()
            && !(sameClass.isEmpty() && crossClass.isEmpty())
            && crossImageCeiling < sameImageFloor;
    Double threshold = separable ? (sameImageFloor + crossImageCeiling) / 2D : null;
    return new Calibration(
        corpus,
        summarize(SAME_IMAGE, sameImage),
        summarize(SAME_CLASS, sameClass),
        summarize(CROSS_CLASS, crossClass),
        sameImageFloor,
        crossImageCeiling,
        separable,
        threshold);
  }

  /**
   * 按语料分组定标（顺序按首次出现，落盘可比）。
   *
   * @param pairs 全部样本对
   * @return 语料标签 → 定标结论（不可变）
   */
  static Map<String, Calibration> calibrateByCorpus(List<Pair> pairs) {
    Map<String, List<Pair>> grouped = new LinkedHashMap<>();
    for (Pair pair : pairs) {
      grouped.computeIfAbsent(pair.corpus(), key -> new ArrayList<>()).add(pair);
    }
    Map<String, Calibration> result = new LinkedHashMap<>();
    grouped.forEach((corpus, corpusPairs) -> result.put(corpus, calibrate(corpus, corpusPairs)));
    return result;
  }

  private static List<Pair> filter(List<Pair> pairs, String stratum) {
    return pairs.stream().filter(pair -> pair.stratum().equals(stratum)).toList();
  }

  private static double minOf(List<Pair> pairs) {
    return pairs.stream().mapToDouble(Pair::cosine).min().orElse(0D);
  }

  private static double maxOf(List<Pair> pairs) {
    return pairs.stream().mapToDouble(Pair::cosine).max().orElse(0D);
  }

  private static Stratum summarize(String name, List<Pair> pairs) {
    if (pairs.isEmpty()) {
      return new Stratum(name, 0, 0D, 0D, 0D);
    }
    double min = Double.POSITIVE_INFINITY;
    double max = Double.NEGATIVE_INFINITY;
    double sum = 0D;
    for (Pair pair : pairs) {
      min = Math.min(min, pair.cosine());
      max = Math.max(max, pair.cosine());
      sum += pair.cosine();
    }
    return new Stratum(name, pairs.size(), min, sum / pairs.size(), max);
  }

  /**
   * 定标锚 JSON 装配（{@code docs/rag-quality/image-threshold-calibration-v1.json} 的唯一写入口）。
   *
   * <p>结构：版本/生成时间/opt-in 留痕 → 逐语料定标块 → 全量样本对（可逐对复核，禁止手改结果的「黑盒锚」）→ 判别口径 → 判据说明。真实语料块（{@code
   * real-pdf}）是阈值锚的唯一来源；程序化块（{@code programmatic}）只作对照，不参与锚取值。
   *
   * @param calibrations 语料 → 定标结论
   * @param pairs 全量样本对
   * @param optInNote opt-in 与语料说明
   * @param generatedAt 生成时间（ISO-8601，由调用方给，保持落盘可比）
   * @return JSON 根节点
   */
  static ObjectNode toJson(
      Map<String, Calibration> calibrations,
      List<Pair> pairs,
      String optInNote,
      String generatedAt) {
    ObjectNode root = MAPPER.createObjectNode();
    root.put("calibrationVersion", CALIBRATION_VERSION);
    root.put("generatedAt", generatedAt);
    root.put("optIn", optInNote);
    ObjectNode corpora = root.putObject("corpora");
    calibrations.forEach(
        (corpus, calibration) -> corpora.set(corpus, calibrationNode(calibration)));
    Calibration anchor = calibrations.get("real-pdf");
    ObjectNode threshold = root.putObject("threshold");
    if (anchor == null || anchor.recommendedThreshold() == null) {
      threshold.put("source", anchor == null ? "none" : anchor.corpus());
      threshold.put("recommendedThreshold", (Double) null);
      threshold.put("decision", "真实语料不可分：不给硬阈值（同图下限未高于异图上限），按「异图余弦 < 同图余弦」的顺序判据执行");
    } else {
      threshold.put("source", anchor.corpus());
      threshold.put("sameImageFloor", anchor.sameImageFloor());
      threshold.put("crossImageCeiling", anchor.crossImageCeiling());
      threshold.put("recommendedThreshold", anchor.recommendedThreshold());
      threshold.put("decision", "真实语料可分：判别阈值 = (同图下限 + 异图上限) / 2，落在严格区间 (异图上限, 同图下限] 内");
    }
    ObjectNode judgement = root.putObject("judgement");
    judgement.put("sameImageHit", "cosine >= recommendedThreshold");
    judgement.put("crossImageRejected", "cosine < recommendedThreshold");
    judgement.put(
        "contrast",
        "程序化套件 ImageCrossModalSuite 的 0.90 保持不变（其语料是程序化 6 图，改它会动 image-baseline-v1 可比性）；本锚只服务真实语料");
    ArrayNode pairNodes = root.putArray("pairs");
    for (Pair pair : pairs) {
      ObjectNode node = pairNodes.addObject();
      node.put("corpus", pair.corpus());
      node.put("leftKey", pair.leftKey());
      node.put("rightKey", pair.rightKey());
      node.put("stratum", pair.stratum());
      node.put("cosine", pair.cosine());
    }
    root.put(
        "note",
        "同图下限 = same-image 档最小值（同字节再编码）；异图上限 = same-class ∪ cross-class 的最大值；"
            + "阈值只由 real-pdf 语料决定，programmatic 块为对照");
    return root;
  }

  private static ObjectNode calibrationNode(Calibration calibration) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("corpus", calibration.corpus());
    node.set("sameImage", stratumNode(calibration.sameImage()));
    node.set("sameClass", stratumNode(calibration.sameClass()));
    node.set("crossClass", stratumNode(calibration.crossClass()));
    node.put("sameImageFloor", calibration.sameImageFloor());
    node.put("crossImageCeiling", calibration.crossImageCeiling());
    node.put("separable", calibration.separable());
    node.put("recommendedThreshold", calibration.recommendedThreshold());
    return node;
  }

  private static ObjectNode stratumNode(Stratum stratum) {
    ObjectNode node = MAPPER.createObjectNode();
    node.put("name", stratum.name());
    node.put("count", stratum.count());
    node.put("min", stratum.min());
    node.put("mean", stratum.mean());
    node.put("max", stratum.max());
    return node;
  }
}
