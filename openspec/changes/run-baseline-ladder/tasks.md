# Tasks — run-baseline-ladder

> 成本闸门状态：**用户已随本提案授权**五回真检索基准（预算 ≤¥5 含重试）+ 评测库 fixtures 重嵌入。未授权项见 proposal.md Non-Goals。
> 跑法铁律：一律 `mvn -B -ntp test-compile failsafe:integration-test -Dit.test=RagRealRetrievalBenchmarkIT`（`.env` 有 key，全量 verify 会连带其他 DashScope IT 真外发）。

## 0. 盘点与决策（不改行为，结论写入本文件"验证记录"节）

- [ ] 0.1 稀疏路评测装配决策：读 `SparseRecallService` 实现与耦合度，在 (a) Testcontainers MySQL 真库装配（V1..V23 全迁移 + fixtures chunk 行入库）与 (b) 测试侧内存替换之间选定，理由与改造点清单写入验证记录——若 (b) 需动 `src/main`，停下向用户报告
- [ ] 0.2 黄金对齐适配决策：`RagBenchmarkDataPreparer` 的占位符→chunkId 对齐在 semantic 切分下是否成立；不成立列出改造点（归任务组 5）
- [ ] 0.3 回退态口径确认：确认现 runner 旧构造器（sparseRecallService/contextBuilder=null）= 升级前行为，第一跑可零改造执行（引用 `KnowledgeRetrievalServiceImpl` 类注释与既有等价单测）
- [ ] 0.4 压缩口径确认：rule 压缩器在基准上下文预算内是否无损（不裁剪）；有裁剪则给关闭值或按等价测试口径处理，写入验证记录

## 1. 第一跑：baseline-v1（回退态）——解锁提案1 4.1/4.4

- [ ] 1.1 `RAG_BENCHMARK_REAL=1` 用现 runner 原样跑（0.3 已确认口径）→ `docs/rag-quality/baseline-v1.json`
- [ ] 1.2 断言：18 条全评分、failureRate ≤0.25、hitRate >0（runner 内置）；`baseline.md` 回填 metrics 摘要 + generatedAt，勾提案1 4.1/4.4
- [ ] 1.3 机械归因留档：从 v1 报告读 LEXICAL 6 条 vs TEXT 类 recall 差，写入验证记录——第五跑触发判定的输入

## 2. Runner 泛化（测试侧代码，单测可验）

- [ ] 2.1 输出路径参数化：`-Drag.benchmark.out=docs/rag-quality/<name>.json`，缺省 `baseline-v1.json` 保持兼容
- [ ] 2.2 开关矩阵注入：`DynamicConfig` 桩（Map 背书）替换 `emptyDynamicConfigProvider()`，矩阵含 fusion.mode / context.neighbors / context.compressor.mode / context.parent-expand / chunking.strategy / query.*；报告 JSON 含**当跑矩阵快照**
- [ ] 2.3 全量新管线装配：全参构造器 + 0.1 结论的稀疏路装配；无 Docker 且稀疏路必需时显式跳过并警告（不静默降级）
- [ ] 2.4 单测：矩阵装配断言（回退矩阵 = 旧构造器行为等价，可引用既有等价测试）；`mvn -B -ntp test` 绿（585+新增）

## 3. 第二跑：after-hybrid——解锁提案2 5.1/5.2 + 补勾 1.1

- [ ] 3.1 矩阵 {fusion=rrf + 稀疏路 on，其余回退} 跑 → `baseline-after-hybrid.json`
- [ ] 3.2 断言：LEXICAL recall@k/MRR 较 v1 **提升**；聚合 recall@k/hitRate/citationPrecision **不低于 v1**；差异摘要写入提案2 验证记录，勾 5.1/5.2
- [ ] 3.3 勾选漂移修正：提案2 1.1 补勾并注明"V22 已由 fix/v6-sensitive-reserved-word 分支完成，FlywayMigrationIT 真库断言绿"

## 4. 第三跑：after-context——解锁提案3 5.1/5.2

- [ ] 4.1 矩阵 {承 3.1 + neighbors=1 + compressor=rule + parent-expand=off} 跑 → `baseline-after-context.json`
- [ ] 4.2 断言：token（生成+判卷两段口径）较 after-hybrid **下降**；要点覆盖/answerConsistency/citationPrecision **不回退**；回填勾选

## 5. 第四跑：after-chunking——解锁提案4 5.1/5.2 + 4.4 文档段

- [ ] 5.1 0.2 的对齐改造落地（如需）；fixtures 语义重嵌入 + 矩阵 {承 4.1 + chunking=semantic + parent-expand=on} 跑 → `baseline-after-chunking.json`（评测库重嵌入，非生产 reingest）
- [ ] 5.2 断言：recall@k/MRR 较 4.1 **不降**；citationPrecision 不回退（锚点仍准）；回填勾选
- [ ] 5.3 提案4 4.4 文档段回填：存量迁移方案（跑批复用 batch_task 状态机 or 独立 runner 的结论）写入验证记录；**生产 reingest 执行仍标注"另授权"**

## 6. 第五跑：after-query——解锁提案5 4.1/4.2（先过机械触发判定）

- [ ] 6.1 机械判定：v1/after-hybrid 的 LEXICAL 实测数字是否支持"词汇失配为主要漏召"——**不支持则只回填归因结论**，P5 4.1/4.2 改标"触发不成立"并勾选，任务组到此为止
- [ ] 6.2 支持则矩阵 {承 5.1 + multi-query on（hyde/derived 视归因结论）} 跑 → `baseline-after-query.json`；词汇失配类 recall ↑、聚合不回退、TTFT/token 增幅记录；回填勾选（默认值不动，增幅可接受与否留用户决策）

## 7. 收尾

- [ ] 7.1 五份 JSON + baseline.md + 各案验证记录齐并入库；`ladder-report.md`（五跑核心指标一览 + 遗留清单：生产 reingest 另授权 / WriteChainRegression 2 红另案 / 11·14 deferred 不变）
- [ ] 7.2 `mvn -B -ntp test` 绿（surefire ≥ 585+新增）；有新增则 ci.yml 基线 bump 三处同步
- [ ] 7.3 回归处置核对：任一跑较锚点回退的，均已记录差异并在汇报中列明（默认值翻转属用户决策，本提案不拍板）

## 8. Git 操作（按 `openspec/git-workflow.md` 执行）

- 分支：`git checkout -b feature/run-baseline-ladder`（基于 master）。
- 提交序（任务组 → 提交）：
  1. `docs(openspec)`: 盘点决策记录（任务组 0，含 0.1/0.2 结论）
  2. `test(quality)`: baseline-v1 首跑落盘 + baseline.md 回填 + 提案1 勾选（任务组 1）
  3. `test(quality)`: runner 泛化——路径参数化/矩阵注入/新管线装配（任务组 2）
  4. `test(quality)`: after-hybrid 跑 + 提案2 回填与 1.1 补勾（任务组 3）
  5. `test(quality)`: after-context 跑 + 提案3 回填（任务组 4）
  6. `test(quality)`: after-chunking 跑 + 提案4 回填（任务组 5）
  7. `test(quality)`: after-query 跑或触发不成立结论 + 提案5 回填（任务组 6）
  8. `docs(openspec)`: ladder-report 汇总 + ci 基线 bump（任务组 7，如有新增测试）
- 每跑的 JSON + 勾选回填**同提交**；tasks.md 勾选随代码走（git-workflow §2）。
- **成本边界**：五回真跑与 fixtures 重嵌入已授权（≤¥5）；生产 reingest / push / src/main 行为变更（0.1 若需）一律停下要授权。
- 合并：亲验（`mvn -B -ntp test` 绿 + status 干净）后 `git checkout master; git merge --no-ff feature/run-baseline-ladder -m "Merge branch 'feature/run-baseline-ladder'：基线阶梯五回——解锁提案1-5量化验收"`。

## 验证记录（执行时回填）

（0.1/0.2/0.3/0.4 决策结论与各跑差异摘要、ladder-report 链接）
