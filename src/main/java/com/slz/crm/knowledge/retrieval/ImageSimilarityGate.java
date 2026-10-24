package com.slz.crm.knowledge.retrieval;

import com.slz.crm.platform.contract.VectorSearchHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图像路同图判别闸门（image-retrieval-threshold-gate + image-near-duplicate-band-weight）：把定标锚 {@code
 * docs/rag-quality/image-threshold-calibration-v1.json} 的判别阈值落到检索侧，并解决「近重复图被单阈值误杀」。
 *
 * <p>三档 tier（{@code cosine} 为图像路命中分，与文本路 {@code rag.retrieval.minScore=0.20} 解耦）： {@code cosine >=
 * threshold}（默认 0.9944）→ {@link #TIER_SAME_IMAGE} 正常召回、不降权； {@code floor <= cosine <
 * threshold}（{@code floor} = {@link #NEAR_DUPLICATE_FLOOR} = 生产准入下限 0.982，Owner 2026-10-24 签收下探）→
 * {@link #TIER_NEAR_DUPLICATE} 召回并打标、重排后按 {@code imageSimilarityBand} 三档降权（{@link
 * #NEAR_DUPLICATE_WEIGHT_HIGH} / {@link #NEAR_DUPLICATE_WEIGHT_MID} / {@link
 * #NEAR_DUPLICATE_WEIGHT_LOW}）； {@code cosine < floor} → {@link #TIER_CROSS_IMAGE} 过滤。
 *
 * <p>近似带按 {@code [bandReferenceFloor(threshold), threshold]} 三等分，边界见 {@link
 * #lowBandUpperBound(double)} / {@link #highBandLowerBound(double)}：内边界以 {@link
 * #BAND_REFERENCE_FLOOR}（定标锚异图上限的 4 位小数 0.9887，
 * 冻结）为基准推导，<b>不随生产准入下限缩放</b>（否则已在带内的候选被无声改档）；三档权重需人工复核（见 {@code
 * docs/rag-quality/image-near-duplicate-bands-v1.md}）。准入下限的签收依据与降权置于重排之后（默认重排器按 {@code hit.score()}
 * 做 min-max 归一化）的理由见 {@link #gate(List, double)} 与 {@code docs/dynamic-config-keys.md}。
 */
public final class ImageSimilarityGate {

  /** 独立图像阈值键（Owner 2026-10-22 拍板：图像路不再复用文本 {@code rag.retrieval.minScore}）。 */
  public static final String THRESHOLD_KEY = "rag.retrieval.image-similarity-threshold";

  /** 默认判别阈值 = 定标锚 {@code threshold.recommendedThreshold} 0.9943679700394117 的展示口径（0.9944）。 */
  public static final double DEFAULT_THRESHOLD = 0.9944D;

  /**
   * 生产准入下限（Owner 2026-10-24 签收「下探 0.982」，决议锚 {@code
   * docs/rag-quality/near-dup-floor-signoff-v1.json}）： {@code cosine} 低于该值按异图过滤。它与 {@link
   * #BAND_REFERENCE_FLOOR} 是<b>故意的不相等</b>——生产准入面已下探到 证据档之下，三档内边界不随它移动（见 {@link
   * #bandReferenceFloor(double)}）。
   */
  public static final double NEAR_DUPLICATE_FLOOR = 0.982D;

  /**
   * 三档细分带的内边界基准 = 定标锚 {@code threshold.crossImageCeiling} 0.9887359400788237（4 位小数取 0.9887）。冻结：
   * 生产准入下限变动时内边界不得跟着动，否则已在带内的候选被无声改档、降权变轻（破坏「越远离阈值 ⇒ 降权越重」的单调语义）。
   */
  public static final double BAND_REFERENCE_FLOOR = 0.9887D;

  /** 近似同图高档权重（间隔带上 1/3，最贴近阈值）：降权最轻，几乎等同真同图。 */
  public static final double NEAR_DUPLICATE_WEIGHT_HIGH = 0.75D;

  /** 近似同图中档权重（间隔带中 1/3）。 */
  public static final double NEAR_DUPLICATE_WEIGHT_MID = 0.6D;

  /** 近似同图低档权重（间隔带下 1/3，勉强过下限）：保持现状 0.5，不放松。 */
  public static final double NEAR_DUPLICATE_WEIGHT_LOW = 0.5D;

  /** 旧统一降权系数 0.5，保留为 {@link #NEAR_DUPLICATE_WEIGHT_LOW} 的别名（= 最低档）以兼容旧引用；新代码请用三档常量。 */
  public static final double NEAR_DUPLICATE_DOWN_WEIGHT = NEAR_DUPLICATE_WEIGHT_LOW;

  /** 档位标记键（internal，只落在图像路候选的 {@code hit.metadata()}，不进 SourceReference）。 */
  public static final String TIER_METADATA_KEY = "imageSimilarityTier";

  public static final String TIER_SAME_IMAGE = "SAME_IMAGE";

  public static final String TIER_NEAR_DUPLICATE = "NEAR_DUPLICATE";

  public static final String TIER_CROSS_IMAGE = "CROSS_IMAGE";

  /** 近似带细分标记键（internal，只有近似同图档候选携带；缺失时降权回落最低档）。 */
  public static final String BAND_METADATA_KEY = "imageSimilarityBand";

  public static final String BAND_NEAR_HIGH = "NEAR_HIGH";

  public static final String BAND_NEAR_MID = "NEAR_MID";

  public static final String BAND_NEAR_LOW = "NEAR_LOW";

  /** 已施加降权的标记键（internal，值为本次乘过的权重）：重复调用不再叠加（幂等）。 */
  public static final String BAND_WEIGHT_METADATA_KEY = "imageSimilarityBandWeightApplied";

  private ImageSimilarityGate() {}

  /**
   * 生效准入下限：阈值的配置越界/运营把阈值调到下限以下时，下限随阈值收敛，区间退化为「无近似档」而非反向区间。
   *
   * @param threshold 生效阈值
   * @return 近似档下限
   */
  public static double effectiveFloor(double threshold) {
    return Math.min(NEAR_DUPLICATE_FLOOR, threshold);
  }

  /**
   * 带内基准下限：三档内边界一律由它推导（{@link #lowBandUpperBound(double)} / {@link #highBandLowerBound(double)}），
   * 不随 {@link #NEAR_DUPLICATE_FLOOR} 变动；阈值跌破基准时随阈值收敛（退化语义与 {@link #effectiveFloor(double)} 同构）。
   *
   * @param threshold 生效阈值
   * @return 带内基准下限
   */
  public static double bandReferenceFloor(double threshold) {
    return Math.min(BAND_REFERENCE_FLOOR, threshold);
  }

  /**
   * 低/中档边界 = {@code bandReferenceFloor + Δ/3}（含）：由 {@code [bandReferenceFloor(threshold),
   * threshold]} 推导，内边界只随带内基准（锚的异图上限展示口径）缩放，不随生产准入下限移动。
   *
   * @param threshold 生效阈值
   * @return 低档上界（= 中档下界）
   */
  public static double lowBandUpperBound(double threshold) {
    double floor = bandReferenceFloor(threshold);
    return floor + (threshold - floor) / 3.0D;
  }

  /**
   * 中/高档边界 = {@code bandReferenceFloor + 2Δ/3}（含）：同样只随带内基准缩放，不新增手工同步常量。
   *
   * @param threshold 生效阈值
   * @return 高档下界（= 中档上界）
   */
  public static double highBandLowerBound(double threshold) {
    double floor = bandReferenceFloor(threshold);
    return floor + 2.0D * (threshold - floor) / 3.0D;
  }

  /**
   * 单条命中分档（三档语义保持 image-retrieval-threshold-gate 原样，不因 band 细分而改变）。
   *
   * @param cosine 图像路余弦分
   * @param threshold 生效阈值
   * @return {@link #TIER_SAME_IMAGE} / {@link #TIER_NEAR_DUPLICATE} / {@link #TIER_CROSS_IMAGE}
   */
  public static String tierOf(double cosine, double threshold) {
    double floor = effectiveFloor(threshold);
    String tier;
    if (cosine >= threshold) {
      tier = TIER_SAME_IMAGE;
    } else if (cosine >= floor) {
      tier = TIER_NEAR_DUPLICATE;
    } else {
      tier = TIER_CROSS_IMAGE;
    }
    return tier;
  }

  /**
   * 近似带落带：只对 {@code floor <= cosine < threshold} 有意义，区间外（同图/异图）返回 {@code null}。
   *
   * <p>边界比较一律「边界值归上一档」（{@code >=}）：段边界由带内基准现算（{@link #lowBandUpperBound(double)} / {@link
   * #highBandLowerBound(double)}，基准 = {@link #bandReferenceFloor(double)}），准入下限变动不移动内边界。
   *
   * @param cosine 图像路余弦分
   * @param threshold 生效阈值
   * @return {@link #BAND_NEAR_HIGH} / {@link #BAND_NEAR_MID} / {@link #BAND_NEAR_LOW}；区间外 null
   */
  public static String bandOf(double cosine, double threshold) {
    double floor = effectiveFloor(threshold);
    String band;
    if (cosine < floor || cosine >= threshold) {
      band = null;
    } else if (cosine >= highBandLowerBound(threshold)) {
      band = BAND_NEAR_HIGH;
    } else if (cosine >= lowBandUpperBound(threshold)) {
      band = BAND_NEAR_MID;
    } else {
      band = BAND_NEAR_LOW;
    }
    return band;
  }

  /**
   * band → 降权系数：缺失/未知 band 一律回落最低档 0.5（旧候选或直接构造的候选行为可预测，且不放松）。
   *
   * @param band 近似带标记，可空
   * @return 权重（{@value #NEAR_DUPLICATE_WEIGHT_LOW} / {@value #NEAR_DUPLICATE_WEIGHT_MID} / {@value
   *     #NEAR_DUPLICATE_WEIGHT_HIGH}）
   */
  public static double weightOfBand(String band) {
    double weight;
    if (BAND_NEAR_HIGH.equals(band)) {
      weight = NEAR_DUPLICATE_WEIGHT_HIGH;
    } else if (BAND_NEAR_MID.equals(band)) {
      weight = NEAR_DUPLICATE_WEIGHT_MID;
    } else {
      weight = NEAR_DUPLICATE_WEIGHT_LOW;
    }
    return weight;
  }

  /**
   * 三档闸门：异图丢弃，同图与近似同图保留并打档位标记（不在此处改分，降权由 {@link #downWeightNearDuplicates(List)} 在重排后做）；近似同图另打
   * {@code imageSimilarityBand} 细分带。
   *
   * <p>准入下限 = 生产现值 0.982（Owner 2026-10-24 签收下探，依据 {@code
   * docs/rag-quality/near-dup-floor-signoff-v1.json}： 跨文档代价恒为零、ΔR/ΔS_same = 8.333333
   * 为八档最高）；[0.9887, 0.9944) 是锚诚实标注的不可分区间（锚异图上限 0.9887359400788237 → 4 位小数
   * 0.9887），单阈值无法区分、只能降权，边界样本落在近似档而非被硬丢弃；新准入区 [0.982, 0.9887) 是签收扩入的降权面（全落最低档 0.5），带内边界仍以 0.9887
   * 为冻结基准。
   *
   * @param candidates 图像路召回候选
   * @param threshold 生效阈值
   * @return 过滤+打标后的候选（保序）
   */
  public static List<RetrievalCandidate> gate(
      List<RetrievalCandidate> candidates, double threshold) {
    List<RetrievalCandidate> result = new ArrayList<>(candidates.size());
    for (RetrievalCandidate candidate : candidates) {
      double score = candidate.hit().score();
      String tier = tierOf(score, threshold);
      if (!TIER_CROSS_IMAGE.equals(tier)) {
        VectorSearchHit taggedHit = tag(candidate.hit(), TIER_METADATA_KEY, tier);
        if (TIER_NEAR_DUPLICATE.equals(tier)) {
          taggedHit = tag(taggedHit, BAND_METADATA_KEY, bandOf(score, threshold));
        }
        result.add(new RetrievalCandidate(taggedHit, candidate.rerankScore()));
      }
    }
    return List.copyOf(result);
  }

  /**
   * 近似同图降权（重排后调用）：带 {@link #TIER_NEAR_DUPLICATE} 标记的候选按 {@code imageSimilarityBand} 乘对应权重 （缺失/未知
   * band → {@value #NEAR_DUPLICATE_WEIGHT_LOW}），其余（含真同图）原样返回；保序。
   *
   * <p>幂等：降权后的命中写入 {@link #BAND_WEIGHT_METADATA_KEY} 作为已施加标记，同一候选即使被调用两次也不会叠加降权 （主链只在 {@code
   * KnowledgeRetrievalServiceImpl#retrieveFromKnowledgeBases} 调用一次，本标记是对重复调用的显式防重）。
   *
   * @param candidates 重排后的图像路候选
   * @return 降权后的候选（保序）
   */
  public static List<RetrievalCandidate> downWeightNearDuplicates(
      List<RetrievalCandidate> candidates) {
    List<RetrievalCandidate> result = new ArrayList<>(candidates.size());
    for (RetrievalCandidate candidate : candidates) {
      Map<String, Object> metadata =
          candidate.hit().metadata() == null ? Map.of() : candidate.hit().metadata();
      boolean nearDuplicate = TIER_NEAR_DUPLICATE.equals(metadata.get(TIER_METADATA_KEY));
      boolean alreadyWeighted = metadata.containsKey(BAND_WEIGHT_METADATA_KEY);
      if (nearDuplicate && !alreadyWeighted) {
        double weight = weightOfBand(stringOf(metadata.get(BAND_METADATA_KEY)));
        result.add(
            new RetrievalCandidate(
                tag(candidate.hit(), BAND_WEIGHT_METADATA_KEY, weight),
                candidate.rerankScore() * weight));
      } else {
        result.add(candidate);
      }
    }
    return List.copyOf(result);
  }

  /** 复制命中并写入一个 metadata 标记（不原地改 metadata，冻结契约数据保持不可变）。 */
  private static VectorSearchHit tag(VectorSearchHit hit, String key, Object value) {
    Map<String, Object> metadata =
        new LinkedHashMap<>(hit.metadata() == null ? Map.of() : hit.metadata());
    metadata.put(key, value);
    return new VectorSearchHit(hit.chunkId(), hit.documentId(), hit.score(), hit.text(), metadata);
  }

  /** metadata 值转字符串（null 保持 null，交由 band 回落逻辑处理）。 */
  private static String stringOf(Object value) {
    String text = null;
    if (value != null) {
      text = String.valueOf(value);
    }
    return text;
  }
}
