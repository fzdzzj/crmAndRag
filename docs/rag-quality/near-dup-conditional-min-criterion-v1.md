# near-dup-conditional-min-criterion-v1

卡 36 · 零外呼 · 腿 A 判据条件化（三分支）/ 腿 B 0.90 影响与重锚清单（不执行、不替 Owner 决策）

## 结论

- verdict: PROVEN-CONDITIONAL
- 适用域: 本语料 6 条查询的分支状态：B0（bm25 全 0）4 条（q1,q3,q4,q5）、B1（bm25 全 1）2 条（q0,q2）、B2（非退化）0 条；结论只在「零位移事件全部落在 B0 或 B1 查询」这一域内成立，B2 域本卡未观测到，故不主张
- 复算: r = 0.6·normVec + 0.4·normBm25，15/15 行一致（容差 1e-12，maxAbsDiff 0.0）
- 零位移: 4/4 落 B0 且 normVec = 0；非零 8/8 满足所属分支条件

## 三分支（互斥穷尽）

| 分支 | 判定式 | r = 0 的充要条件 | 查询 | 零位移事件 |
| --- | --- | --- | --- | --- |
| B0-BM25-ALL-ZERO | `max(normBm25[q]) <= 0.0` | r = wv · normVec + wb · 0 = wv · normVec ⇒ r = 0 ⟺ normVec = 0（向量分支取最小即充分必要） | 4 | 4 |
| B1-BM25-ALL-ONE | `max(normBm25[q]) == min(normBm25[q]) && max > 0.0` | r = wv · normVec + wb · 1 >= wb > 0 ⇒ r = 0 不可能（可证的不可能性） | 2 | 0 |
| B2-BM25-NON-DEGENERATE | `max(normBm25[q]) > min(normBm25[q])` | 两个归一化项都参与 ⇒ r = 0 ⟺ normVec = 0 ∧ normBm25 = 0（卡 34 的原判据 = 本分支，属特例） | 0 | 0 |

## 0.90 影响（NEAR_DUPLICATE_FLOOR 0.982 -> 0.90；BAND_REFERENCE_FLOOR 0.9887 冻结）

- 自相矛盾 9/16；不受影响 6；未判定 1
- 可零外呼重算 9/16；最小外呼估算总量 18（全量重跑上界 221）
- 判定式与逐锚字段路径见 impact.contradictionRule / impact.perAnchor

## 重锚清单（executed = false）

- 顺序（被引用者先）：1. near-dup-floor-signoff-v1，2. near-dup-ingest-s1-v1，3. near-dup-band-entry-v1，4. near-dup-rerank-decomposition-v1，5. near-dup-exchange-rate-v1，6. image-variant-cross-matrix-v1，7. near-dup-displacement-evidence-v1，8. near-dup-fusion-impact-v1，9. near-dup-ablation-v1
- 方式：改写 operating-point 字面量并按签收流程重签（零外呼；逐项外呼与依据见 reanchorPlan.ordered）

## 边界

- 不改任何生产常量；src/main / pom.xml / .github 零 diff；既有锚零字节变动
- 未判定项按 R8 记 UNDETERMINED，不猜；降不降由 Owner 决定，本清单不给倾向值