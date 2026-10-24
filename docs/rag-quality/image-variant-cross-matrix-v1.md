# 变体↔第三图全矩阵 v1（near-dup-missing-evidence · G2 真跑）

> **性质：代理有效性判定（存在性），不是生产检索分布。** 落锚
> docs/rag-quality/image-variant-cross-matrix-v1.json（sha256:340a9c861fd3c67d0793bcec909592461d398cf6184f573923ab5e3349301066）。
> 真跑 opt-in：`RAG_IMAGE_CROSS_MATRIX_REAL=1` + `DASHSCOPE_API_KEY`（multimodal-embedding-v1）；写锚 `-Dimage.cross.matrix.write=true`；
> 默认复跑（opt-in 无 write）从锚内冻结的 144 条向量本地重算矩阵与全部分位，零外呼，序列化与磁盘锚**逐字节一致**（复跑已验证）。
> 语料 = `NearDuplicateCorpus.generate()`（确定性，未改生成逻辑、未新增语料：24 base + 120 variant，逐文档 8/4/12，skips=0）。

## M1 · 全矩阵（144×144，每图单次编码）

- externalCalls=**144**（= 图数，embedImage 单调用计数，TrackingProvider 真调用点累加）；节奏 1100ms + 429 有界退避（节奏只影响墙钟）。
- 矩阵对称（守卫逐元素精确断言）；**对角自余弦实测落 `selfCosines`**：实测范围 [0.9999999999999998, 1.0000000000000002]，未硬写 1.0（单编码下即余弦函数对同向量的自算值）。

## M2 · 偏差对账（锋利判据：>1e-3 即停 G2）

与 v2 定标锚已登记的 near-duplicate 120 对 + same-class 100 对 + cross-class 176 对**逐对**比较（禁「取较小偏差的那批」）：

| 档 | 对数 | maxAbsDeviation |
| --- | --- | --- |
| near-duplicate | 120 | 0.0 |
| same-class | 100 | 0.0 |
| cross-class | 176 | 0.0 |
| **合计** | **396** | **0.0（超阈 0 对，PASS）** |

单次编码与定标卡 A 向量**逐位一致**（396 对偏差全为 0）——multimodal-embedding-v1 对同字节输入的编码是确定的，模型与管道自定标卡以来未漂移。

## M3 · variant↔第三图（120×23，排除自身 base，2760 值）

| 统计 | 值 |
| --- | --- |
| p50 | 0.2216 |
| p90 | 0.5024 |
| p99 | 0.9809 |
| max | **0.9973**（nist-sp800-183-p1-b1@jpeg-q80 ↔ nist-sp800-101r1-p1-b1，两张 NIST 封面） |
| 是否存在 cos ≥ 0.9887 | **是（14 对）** |
| proxyVerdict | **`PROXY-UNDERSTATES`** |

**结论：base 对代理低估带内竞争者。** 带内（cos ≥ 0.9887）的变体↔第三图对实测 14 对，全部来自三组高相似 base 簇：NIST 两封面（101r1-p1-b1 ↔ 183-p1-b1）与 DOE p4-b7/b8 连拍。上一卡 R4 的竞争者分数只能从 base 两两余弦里取（带内候选 7 个、共现竞争者观测 2 条），本矩阵证明：**变体（查询侧真实输入）与第三图之间也存在带内竞争者，且最高 0.9973——R4 与口径三的进入带样本数偏少，代理缺口方向为低估。**

## M4 · 逐条回填两处代理缺口

1. **`image-near-dup-truth-v1.json` 的 `deviations[0]`**（原文：「proposal §2.2 负例 A/B 的『query = base_i 变体』落地为『query = base_i 再编码』：……不存在变体↔异 base 对……」）：本矩阵恰好补上这个洞——变体↔异 base 对现在有实测（2760 对），其中 14 对落在带内 ⇒ 该 deviation 所述的代理缺口在**负例方向成立但被低估**：口径三 `negativeBSameDocCompetitorRate` 的竞争者分数若改用变体↔第三图实测，进入带的竞争者集合比 base 对代理更大（同文档方向 DOE p4-b7/b8 簇；跨文档方向 NIST 封面簇计入 cross-class 带内点）。`contaminationAtK` 不受影响（cross-class 余弦最高 0.988736 的记录被本矩阵修正为：变体↔跨文档 base 最高 0.9973）。
2. **融合锚 R4 的 gap 分布**（带内候选 7 个、共现竞争者观测 2 条、maxFusedGap 0.0014）：按 M3 判为**低估**——R4 的竞争者面只有 base 对，看不见变体↔第三图的 14 对带内竞争者；这些对的降权窗口换算（0.3×Δcos）落在 R4 未覆盖的更小区间上。R4 的「necessaryNotSufficient」定性不变，但其样本数下限应按本矩阵上调（进入带竞争者观测从 2 条至少扩到 base 对 2 条 + 变体↔第三图 14 对）。

## 边界

- 本矩阵回答「base 对代理是否成立」（存在性 + 方向），**不是**生产检索分布；结论只对同一确定性语料（`NearDuplicateCorpus.generate()`）与当前 embedding 模型（multimodal-embedding-v1，2026-10-24 实测）有效。
- 模型换版/语料变更 ⇒ 本锚失效，须重跑（M2 的 1e-3 对账就是失效探测器）。
- `PROXY-OVERSTATES` 未触发：M3 判据只定义两支（存在带内对 ⇒ UNDERSTATES；否则 SUFFICIENT）；若未来发现 base 对代理系统性**高于**实测，才走该支，本锚不预填。
