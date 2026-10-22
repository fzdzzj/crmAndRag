package com.slz.crm.knowledge.retrieval;

import com.slz.crm.platform.contract.VectorSearchHit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 图像路同图判别闸门（image-retrieval-threshold-gate）：把定标锚 {@code
 * docs/rag-quality/image-threshold-calibration-v1.json} 的判别阈值落到检索侧，并解决「近重复图被单阈值误杀」。
 *
 * <p><b>三档语义</b>（{@code cosine} = 图像路命中分，与文本路 {@code rag.retrieval.minScore=0.20} 完全解耦）：
 *
 * <ul>
 *   <li>{@code cosine >= threshold}（默认 0.9944，键 {@code rag.retrieval.image-similarity-threshold}）→
 *       {@link #TIER_SAME_IMAGE 高置信同图}：正常召回；
 *   <li>{@code floor <= cosine < threshold}（{@code floor} = {@link #NEAR_DUPLICATE_FLOOR} = 锚的异图上限
 *       0.9887，即锚标出的「同图下限 − 异图上限」间隔带）→ {@link #TIER_NEAR_DUPLICATE 近似同图}：<b>召回</b>并打标记， 重排后按 {@link
 *       #NEAR_DUPLICATE_DOWN_WEIGHT} 降权（宁降权不误杀）；
 *   <li>{@code cosine < floor} → {@link #TIER_CROSS_IMAGE 异图}：过滤，不进候选。
 * </ul>
 *
 * <p>下限取锚的异图上限、降权放在重排之后（默认重排器用 {@code hit.score()} 做 min-max 归一化，召回期打折会被抹平）的理由见 {@link #gate(List,
 * double)} / {@link #downWeightNearDuplicates(List)} 方法注释与 {@code docs/dynamic-config-keys.md}
 * 对应键条目；档位标记写入 {@link VectorSearchHit#metadata()}，不外泄到冻结契约。
 */
public final class ImageSimilarityGate {

  /** 独立图像阈值键（Owner 2026-10-22 拍板：图像路不再复用文本 {@code rag.retrieval.minScore}）。 */
  public static final String THRESHOLD_KEY = "rag.retrieval.image-similarity-threshold";

  /** 默认判别阈值 = 定标锚 {@code threshold.recommendedThreshold} 0.9943679700394117 的展示口径（0.9944）。 */
  public static final double DEFAULT_THRESHOLD = 0.9944D;

  /** 近似同图下限 = 定标锚 {@code threshold.crossImageCeiling} 0.9887359400788237（4 位小数取 0.9887）。 */
  public static final double NEAR_DUPLICATE_FLOOR = 0.9887D;

  /** 近似同图档降权系数：召回保留但排序落在真同图之后（真同图 1.0 vs 近似 0.5）。 */
  public static final double NEAR_DUPLICATE_DOWN_WEIGHT = 0.5D;

  /** 档位标记键（internal，只落在图像路候选的 {@code hit.metadata()}，不进 SourceReference）。 */
  public static final String TIER_METADATA_KEY = "imageSimilarityTier";

  public static final String TIER_SAME_IMAGE = "SAME_IMAGE";

  public static final String TIER_NEAR_DUPLICATE = "NEAR_DUPLICATE";

  public static final String TIER_CROSS_IMAGE = "CROSS_IMAGE";

  private ImageSimilarityGate() {}

  /**
   * 生效下限：阈值的配置越界/运营把阈值调到下限以下时，下限随阈值收敛，区间退化为「无近似档」而非反向区间。
   *
   * @param threshold 生效阈值
   * @return 近似档下限
   */
  public static double effectiveFloor(double threshold) {
    return Math.min(NEAR_DUPLICATE_FLOOR, threshold);
  }

  /**
   * 单条命中分档。
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
   * 三档闸门：异图丢弃，同图与近似同图保留并打档位标记（不在此处改分，降权由 {@link #downWeightNearDuplicates(List)} 在重排后做）。
   *
   * <p>下限取锚的异图上限（0.9887359400788237 → 4 位小数 0.9887）：边界样本落在近似档而非被硬丢弃——这正是锚诚实标注的 +0.0113
   * 不可分区间，单阈值无法区分，只能降权；低于该上界的同型高分图（锚判为异图）仍按异图过滤，是否算「同图」属业务定义问题， 本闸门不越过锚。
   *
   * @param candidates 图像路召回候选
   * @param threshold 生效阈值
   * @return 过滤+打标后的候选（保序）
   */
  public static List<RetrievalCandidate> gate(
      List<RetrievalCandidate> candidates, double threshold) {
    List<RetrievalCandidate> result = new ArrayList<>(candidates.size());
    for (RetrievalCandidate candidate : candidates) {
      String tier = tierOf(candidate.hit().score(), threshold);
      if (!TIER_CROSS_IMAGE.equals(tier)) {
        result.add(new RetrievalCandidate(tag(candidate.hit(), tier), candidate.rerankScore()));
      }
    }
    return List.copyOf(result);
  }

  /**
   * 近似同图降权（重排后调用）：带 {@link #TIER_NEAR_DUPLICATE} 标记的候选 {@code rerankScore} 乘 {@value
   * #NEAR_DUPLICATE_DOWN_WEIGHT}，其余原样返回。
   *
   * @param candidates 重排后的图像路候选
   * @return 降权后的候选（保序）
   */
  public static List<RetrievalCandidate> downWeightNearDuplicates(
      List<RetrievalCandidate> candidates) {
    List<RetrievalCandidate> result = new ArrayList<>(candidates.size());
    for (RetrievalCandidate candidate : candidates) {
      boolean nearDuplicate =
          TIER_NEAR_DUPLICATE.equals(candidate.hit().metadata().get(TIER_METADATA_KEY));
      result.add(
          nearDuplicate
              ? new RetrievalCandidate(
                  candidate.hit(), candidate.rerankScore() * NEAR_DUPLICATE_DOWN_WEIGHT)
              : candidate);
    }
    return List.copyOf(result);
  }

  /** 复制命中并写入档位标记（不原地改 metadata，冻结契约数据保持不可变）。 */
  private static VectorSearchHit tag(VectorSearchHit hit, String tier) {
    Map<String, Object> metadata =
        new LinkedHashMap<>(hit.metadata() == null ? Map.of() : hit.metadata());
    metadata.put(TIER_METADATA_KEY, tier);
    return new VectorSearchHit(hit.chunkId(), hit.documentId(), hit.score(), hit.text(), metadata);
  }
}
