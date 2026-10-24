# 近重复下限签收决议 near-dup-floor-signoff-v1

> 状态：**已签收**。Owner 于 2026-10-24 在 `near-dup-floor-decision-request-v1.md` §6 勾选「下探 0.982」。
> 机读锚：`near-dup-floor-signoff-v1.json`（LF 行尾；守卫 `NearDupFloorSignoffGuardTest` 实算校验）。

## 1. 签收结论

- 签收档：**下探 0.982**（`ImageSimilarityGate.NEAR_DUPLICATE_FLOOR` 0.9887 → 0.982），执行卡 `near-dup-floor-lowering`。
- 被拒档位：0.9887（维持，放弃 ΔR 0.083333）、0.98（ΔR/ΔS_same=4.583333 劣于 0.982 档且多压 2 张）、0.90（多压 6 张，降权面约 6 倍，待观测真实位移后再议）。
- 三口径同值（pair 级 = 检索级，T4 一致）：`recallAtK` 0.583333 → 0.666667（ΔR 0.083333）；同文档竞争率 0.02 → 0.03（多压 1 张）；跨文档污染恒 1/176（cross-class 最高 0.988736、次高 0.73，量级断层）。

## 2. 代价六格（0.982 档）

| 字段 | 值 |
| --- | --- |
| recallAtK | 0.666667 |
| sameDocSuppression | 0.03 |
| deltaR | 0.083333 |
| deltaS_same | 0.01 |
| extraSuppressedSameDoc | 1 |
| exchangeRate（ΔR/ΔS_same） | 8.333333 |

## 3. 冻结决策（执行卡 D1–D3，不得改判）

- **D1**：生产准入下限（0.982）与三档带内基准（0.9887 = 定标锚 v2 异图上限 0.9887359400788237 的 4 位小数）拆为两个常量；内边界由 `bandReferenceFloor` 推导，**不随准入下限缩放**。
- **D2**：本变更对 `[0.9887, 0.9944)` **纯加性**——内边界逐字保持 0.9906/0.9925，旧带内候选零改档；新准入区 `[0.982, 0.9887)` 全落 `NEAR_LOW`/0.5（最重降权）。三档权重 0.75/0.6/0.5 与 `W_max=0.15` 零改动。
- **D3**：六份证据锚**零改动**——锚内 `floor=0.9887` 是「测于该档」的历史事实，不随生产现值重写；证据侧分析器与守卫比对一律用冻结基准，禁止引用 `NEAR_DUPLICATE_FLOOR`。

## 4. 六份证据锚「测于 0.9887」口径

下述锚生成时生产档位为 0.9887，其内记录的 `floor`/`nearDuplicateFloor` 均指测档而非生产现值（SHA 见机读锚 `evidenceAnchors`，守卫实算校验）：

1. `image-threshold-calibration-v2.json`（数据真相源）
2. `near-dup-exchange-rate-v1.json`（pair 级价目表）
3. `near-dup-fusion-impact-v1.json`（融合级位移上界）
4. `image-near-dup-truth-v1.json`（检索级真值）
5. `near-dup-displacement-evidence-v1.json`（真实查询位移证据）
6. `image-variant-cross-matrix-v1.json`（变体↔第三图全矩阵）

生产准入下限与带内基准是**故意的不相等**（0.982 < 0.9887）：生产准入面已下探到证据档之下，新准入区是签收扩入的降权面；任何「顺手把两常量对齐」的改动都会被守卫红牌。
