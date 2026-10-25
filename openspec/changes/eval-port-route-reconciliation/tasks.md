# tasks · eval-port-route-reconciliation（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（实测命令 + 实测值 + 文件行号 + 关键输出行）。**禁止**写「已完成」了事。勾选三态：`[x]`=完成、`[-]`=按裁定不做、`[ ]`=未做。
判据 R0–R6 与决策 D1–D3 见 `proposal.md`，不得改动；要改先停手上报。

## §0 · 硬约束

- **零外呼**：`src/main` 零 diff；既有评测断言 / 基线锚 / 套件用例数**零变动**。
- **禁**写「基本等价」「大致相同」；每条口径判断必须有 `文件:行号`。

## A · 逐腿对账（R1）

- [ ] 1. 装配腿：证明 `RagBenchmarkPipelineFactory` 造的是生产 `KnowledgeRetrievalServiceImpl` + `DefaultWeightedReranker` + `RrfFusion`（贴行号）
- [ ] 2. 输入腿：证明向量是 `DeterministicEmbedding` 伪向量、模型是 `DeterministicModelProvider`、存储是内存（贴行号 + 指出**哪些指标因此不可外推**）
- [ ] 3. 稀疏腿：`SparseBenchmarkRecallService` 的近似范围（bigram vs FULLTEXT(ngram)、无授权 JOIN、无软删）与**被污染结论清单**
- [ ] 4. 度量腿：`DefaultWeightedReranker` 的 min-max 归一（`normalizeScores` 两次调用行号）+ 值域
- [ ] 5. 落 `(腿名, 生产件, 替身或近似, 文件:行号, 受影响结论)` 六列表进锚

## B · 平移不变性（R2，命门）

- [ ] 6. 直构**生产**重排器，同一查询同一候选集跑三遍：原始 / 向量分整体 `+100` / 整体 `×2`
- [ ] 7. 断言三遍 `rerankScore` **逐位相等**；并落「若不等则本卡改判」的停手条件
- [ ] 8. 附一条**对照**：把其中**一个**候选的向量分抬高（非整体），断言该候选名次上升 ⇒ 证明实验测的是归一化，不是恒等函数

## C · 结论与锚（R4/R5）

- [ ] 9. `verdict` 三选一由测量值与逐腿表机械推导；`PARTIAL-OFF-PRODUCTION` 必须逐腿给出偏差点
- [ ] 10. 「可支持 / 不可支持」清单逐条落锚，每条挂 R1/R2 证据编号
- [ ] 11. 锚 `docs/rag-quality/eval-port-route-v1.json`（LF，默认逐字节复算）+ `.md`（≤40 行）

## D · 守卫与门禁

- [ ] 12. 守卫 `EvalPortRouteGuardTest`：verdict 三选一 + 逐腿表与分类自洽 + 平移不变性结论位 + `src/main` 零 diff 声明
- [ ] 13. 变异自证三连：改一条腿的生产/替身标注 ⇒ 红；`verdict` 改第四种 ⇒ 红；平移结论位改「不变→变化」⇒ 红。三处还原后复绿，留红/绿日志
- [ ] 14. `mvn -B -ntp test` 全绿（surefire 只增不减）；`git diff master -- src/main` 空；`git status` 只含写集；`git diff --check` 干净；外呼 **0** 已对账
