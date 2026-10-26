# 任务卡 · near-dup-conditional-min-criterion（卡 36 · B 档 · 零外呼两腿一卡）

## §1 目标

**腿 A**：把卡 35 撞死的那句判据改成**条件化形式**。卡 35 的 R3 要求「4 条零位移全在非退化查询且两分支同时取最小」——bm25 分支在本语料 6/6 退化，所以不可满足。新的写法按 bm25 分支状态分三支：

| bm25 分支 | 结论 |
| --- | --- |
| 全 0 | `r = 0 ⟺ normVec = 0`（向量分支末位即充要） |
| 全 1 | `r ≥ wb > 0`，`r = 0` **不可能** |
| 非退化 | 两分支同时取最小（原判据 = 特例） |

判定词从三值扩到四值，新增 `PROVEN-CONDITIONAL`（带 `applicabilityDomain`）；四个值都要写可达路径。

**腿 B**：0.90 不是改一个常量——卡 28/31/32/33/34 的锚都在 `0.9887` 口径下测。列出「哪几张锚会因降门槛自相矛盾、各自怎么重建、要不要花钱」。**不改常量、不执行重建、不替 Owner 决策。**

## §2 背景（三条教训）

1. **板规 31**：判据要能自证可满足。卡 35 的 R3 是「理论上有、本语料没有」⇒ 宿命。
2. **板规 30**：分档/分情况结论必须被反向断言钉住（`PROVEN` 档里不许偷偷写未成立的措辞）。
3. **`tierOf`/`bandOf` 是 `cos` + 门槛的纯函数** —— 所以「降门槛后的分类」在锚已冻 `cos`/向量的情况下可以**零外呼**重算。这条决定腿 B 的代价量级。

## §3 写集（**只许写这 4 个文件**）

| 路径 | 内容 |
| --- | --- |
| `src/test/java/com/slz/crm/quality/NearDupConditionalMinCriterionTest.java` | 读四锚 + 生产纯函数 → 生成锚（写模式） |
| `src/test/java/com/slz/crm/quality/NearDupConditionalMinCriterionGuardTest.java` | 守卫（三分支互斥穷尽 / 四值可达 / 逐条复判 / 反向断言 / 清单可指回 / SHA / md 行数） |
| `docs/rag-quality/near-dup-conditional-min-criterion-v1.json` | 写模式生成（纯 LF） |
| `docs/rag-quality/near-dup-conditional-min-criterion-v1.md` | ≤40 行 |

写模式：`-Dnear.dup.conditional.min.write=true`。

## §4 禁改面（越界即判死）

- `src/main/**`、`pom.xml`、`.github/**` 零 diff；不新增依赖；**不改任何生产常量**。
- **既有锚零字节变动**（卡 32/33/34/35 四张 + 其余全部只读）。
- 禁改 `NearDupBandEntry*` / `NearDupWindowCoupling*` / `NearDupOq1Attribution*` / `NearDupRerankDecomposition*` / `NearDupS1CropCorpus` / `NearDuplicateCorpus` / `NearDupS1Corpus` / `NearDupIngestGateProbe*` / `NearDupIngestS1*`。
- **零外呼**：不设 `RAG_*_REAL`、不启动 Spring、不跑检索。

## §5 成本铁律

零外呼。腿 B 里的「最小外呼量」只是估算，**不执行**；即使某张锚「可零外呼重算」，**本卡也不重算**（那是另卡）。

## §6 验收（摘要）

1. 三分支互斥穷尽（6 条查询归类合计 6）；判据判定式可执行。
2. 四值可达路径齐备，`PROVEN`（无条件）写明「可达但本语料未出现」。
3. 12 条事件逐条判分支与充要条件；4 条零位移全落 bm25 全 0 桶且 `normVec = 0`。
4. 影响清单逐张锚带字段路径与样例；重锚清单含顺序与代价，`executed = false`。
5. 锚 LF、默认复跑逐字节一致、上游 SHA 实算、md ≤40 行、无推荐措辞、`src/main` 与既有锚零 diff。

## §7 停手条件

- 出现第四种分支状态 ⇒ 记 `UNDETERMINED` + `proposal §8`，不许硬塞进三支。
- 某锚「是否冻了分类」指不出字段 ⇒ 记 `UNDETERMINED`，不许猜。
- 想让清单里出现「建议降/不降」⇒ 判死（决策是 Owner 的）。

## §8 回报（≤300 字）

提交 SHA / 4 文件行数 / `verdict` + 理由 / 三分支各覆盖几条查询 / 4 条零位移的桶与 `normVec` / 受影响锚数（自相矛盾者）与可零外呼重算数 / 最小外呼估算总量 / `executed` / 未解决项 / 待决策项。

## §9 细节留档位

执行者在此节追加：三分支判定式与实现位置、6 条查询的分支归类、12 条事件判定表、逐锚影响判定（字段路径 + 样例）、重锚顺序与依赖、任何偏离 proposal 的选择与理由。
