# 变更提案 · image-near-duplicate-band-weight（近似同图降权分段系数）

## 1. 问题
`ImageSimilarityGate`（image-retrieval-threshold-gate 落地的闸门）对「近似同图」候选只有一条规则：`0.9887 ≤ cosine < 阈值` → 重排后统一乘 `NEAR_DUPLICATE_DOWN_WEIGHT = 0.5`。

这把两类完全不同的候选压成同一档：
- 几乎就是同一张图（cosine 贴近阈值，例如 0.9943）——本应几乎等同于同图；
- 勉强过异图上限（cosine 例如 0.9889）——应被明显压制。

`production-gray-real-run` 真跑已记录真实语料「同图/异图间隔可低至 +0.011」，即间隔带内分布密、二值化损失大。单一系数在密带内等于丢弃排序信息。

## 2. 目标
1. 间隔带**三等分**为 `NEAR_HIGH` / `NEAR_MID` / `NEAR_LOW`，各档权重 `0.75 / 0.6 / 0.5`（严格单调、最低档 = 现状 0.5，**不放松**）。
2. 段边界由 `[effectiveFloor(threshold), threshold]` 按 1/3、2/3 **推导**，锚重标定时自动缩放——**不新增常量、不新增配置键、不新增同步点**。
3. `imageSimilarityTier` 保持 `NEAR_DUPLICATE` 不变（兼容），新增 `imageSimilarityBand` 携带细分档；`band` 缺失回落 0.5。
4. 用定标数据复核分段合理性并留档（90 对真实余弦的落带分布）。

## 3. 非目标
- 不改 `tierOf` 的三档语义（SAME / NEAR / CROSS 仍是闸门档）；不改 `gate` 的过滤行为；不改降权位置（仍在 rerank 之后）。
- 不改阈值键、默认值、权限档位（`rag.retrieval.image-similarity-threshold` 的 0.9944 / OPERATIONAL / 608 全部原样），`DynamicConfigKeyRegistry` 零改动。
- 不改文本路任何阈值语义。
- 不做跨模态权重、不做学习式排序、不引新依赖。

## 4. 方案
```
floor = effectiveFloor(threshold) = min(NEAR_DUPLICATE_FLOOR, threshold)
Δ     = threshold - floor
band(cosine):
  cosine ≥ threshold          → SAME_IMAGE（无降权）
  floor + 2Δ/3 ≤ cosine < thr → NEAR_HIGH → ×0.75
  floor +  Δ/3 ≤ cosine < floor + 2Δ/3 → NEAR_MID → ×0.6
  floor ≤ cosine < floor + Δ/3 → NEAR_LOW → ×0.5（= 现状）
  cosine < floor              → CROSS_IMAGE（过滤，不变）
```
- 新增常量：`NEAR_DUPLICATE_WEIGHT_HIGH = 0.75D`、`NEAR_DUPLICATE_WEIGHT_MID = 0.6D`、`NEAR_DUPLICATE_WEIGHT_LOW = 0.5D`；旧 `NEAR_DUPLICATE_DOWN_WEIGHT` 保留为 LOW 的别名并注明「= 最低档，兼容旧引用」，或全仓替换后删除——二选一，须在回报与文档里说明。
- 新增 metadata 键 `imageSimilarityBand`（`TIER_METADATA_KEY` 语义不变）。
- `downWeightNearDuplicates` 改为按 band 查权重；`band` 不在表内（缺失/未知值）→ 0.5。
- 断言型不变量（写进单测）：`0.5 ≤ w < 1.0`、`w_low < w_mid < w_high`、同图档权重 = 1.0。

## 5. 风险与取舍
| 风险 | 处置 |
|---|---|
| 三段权重是"拍脑袋" | 用 `docs/rag-quality/image-threshold-calibration-v1.json` 的 90 对余弦做落带统计，写 `image-near-duplicate-bands-v1.md`；若数据显示中高档几乎无样本，如实记录并保留 0.75/0.6/0.5（仍单调不放松） |
| 三等分在锚重标定后语义漂移 | 段边界由 floor/threshold 推导，随锚缩放；`docs/dynamic-config-keys.md` 锚耦合注记补一句「段边界自动缩放，但权重常量仍需人工复核」 |
| 旧候选无 band 导致权重丢失 | 缺失回落 0.5 = 旧行为，行为可预测 |
| 现场服务在跑（生产开关默认 false） | 该卡不改开关与默认值；行为变更只在图像路启用时生效 |

## 6. 验收
见 `tasks.md`；B 档（业务代码 + 测试，非迁移/权限/契约/真跑）→ 门禁退出码 0 + 主 Agent 审 diff 裁决。
