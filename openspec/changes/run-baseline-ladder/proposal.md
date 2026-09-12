# 基线阶梯执行（run-baseline-ladder）——解锁提案 1–5 的量化验收

> 性质：**测量回补案**，不是功能变更。目标：用五回真检索基准补齐被成本闸门卡住的量化验收，产出五份对照锚点 JSON，解锁 12 个悬空任务项。
> 立项依据：`openspec/changes/enhance-query-transformation/attribution-report.md` §3/§4（缺漏审计 A1/A2/C1 与解除顺序）。

## Why

- **量化验收整体缺位**：提案 2–5 的代码已合入（585 单测绿），但"不回退/提升"类验收（LEXICAL 召回提升、token 下降、切分不回退、查询侧归因）只有单测级等价证据，没有真检索基准数字——"评估先行"被并行执行倒置，本提案按 attribution-report §4 的解除顺序以"阶梯回补"补齐。
- **现 runner 恰好是回退态口径**：`RagRealRetrievalBenchmarkIT` 用旧构造器装配（sparseRecallService/contextBuilder 为 null = 升级前行为，`KnowledgeRetrievalServiceImpl` 类注释明示"未装配（兼容构造）时保持升级前纯拼接"）——第一跑 baseline-v1 可原样执行，零改造。
- **after-* 各跑需要 runner 泛化**：全量新管线（稀疏路 + RRF + 双 reranker + ContextBuilder + 语义切分）与开关矩阵注入现 runner 不支持，须扩展。
- **预算已获用户授权（本提案随批）**：单跑 ≈¥0.1（60–70 次 DashScope 调用），五回 ≤¥5 含重试余量；评测库 fixtures 重嵌入一并授权。key 走 `.env`/环境变量（已在 .gitignore）。

## What Changes

### 1. Runner 泛化（测试侧，不改 src/main 行为）

- 报告输出路径参数化（`-Drag.benchmark.out`，缺省 `docs/rag-quality/baseline-v1.json` 保持兼容）。
- `DynamicConfig` 桩（Map 背书）替换 `emptyDynamicConfigProvider()`，按跑注入开关矩阵；报告落盘含**当跑矩阵快照**（复现性）。
- 全量新管线装配（全参构造器）+ 稀疏路评测装配（决策点见风险§1）。
- 无 Docker 且稀疏路必需时**显式跳过并警告**，不静默降级出假数字。

### 2. 五跑阶梯（累积矩阵，每跑解锁对应悬空项）

| 跑 | 开关矩阵 | 产出 | 解锁 |
|---|---|---|---|
| 1 | 回退态（现 runner 原样，旧构造器） | baseline-v1.json | 提案1 4.1/4.4 |
| 2 | + fusion=rrf + 稀疏路 on | baseline-after-hybrid.json | 提案2 5.1/5.2 + 1.1 补勾 |
| 3 | + neighbors=1 + compressor=rule（parent-expand=off：fixed 切分无父块） | baseline-after-context.json | 提案3 5.1/5.2 |
| 4 | + chunking=semantic + parent-expand=on（fixtures 语义重嵌入） | baseline-after-chunking.json | 提案4 5.1/5.2 + 4.4 文档段 |
| 5 | + multi-query on（hyde/derived 视归因）——**先过机械触发判定** | baseline-after-query.json | 提案5 4.1/4.2（或改标"触发不成立"） |

### 3. 回填

baseline.md 数字节、各案 tasks 勾选与验证记录、`ladder-report.md`（五跑核心指标一览 + 遗留清单）。

## Impact

- **代码**：`src/test/java/com/slz/crm/quality/`（runner 扩展、必要时 preparer 的 semantic 对齐）+ 可能新增测试侧装配类。**不改 `src/main` 行为**——若决策 0.1 选"抽接口"必须动 `src/main`，停下向用户报告后再动。
- **数据**：`docs/rag-quality/` 新增 5 份 JSON（目录不在 .gitignore，直接入库）；评测库 99001 的 fixtures 重嵌入（InMemory/评测容器，**不触生产库**）。
- **openspec**：5 个既有 change 的 tasks.md 勾选与验证记录回填。

## 风险与决策点

1. **稀疏路评测装配（任务 0.1，前置决策）**：`SparseRecallService` 是 MySQL FULLTEXT ngram 实现（V22），基准环境 chunks 只在 InMemoryVectorStore——两案：(a) Testcontainers MySQL 复用 FlywayMigrationIT 容器模式，V1..V23 全迁移 + fixtures chunk 行入库，稀疏路连真库（最真实）；(b) 测试侧内存实现替换（轻，但与生产口径有差）。按耦合度定，结论写入验证记录。
2. **semantic 切分下的黄金对齐（任务 0.2）**：chunk 边界变化后 `RagBenchmarkDataPreparer` 的占位符→chunkId 对齐机制是否仍成立；不可行则第四跑降级为 fixed 切分等价验证，提案4 5.1/5.2 据实标注（不硬凑）。
3. **回归发现即上报，不静默处置**：任一跑较锚点回退 → 记录差异 + 汇报用户（已合入的默认值是否翻转属新决策，本提案只测量不拍板）。
4. **`.env` 已含 key 的连带外发风险**：跑基准只许 `-Dit.test=RagRealRetrievalBenchmarkIT` 过滤——全量 `verify` 会把其他 DashScope IT 一并真外发（fix 分支已验证过此坑的反向）。

## Non-Goals

- 生产库全量 reingest（提案4 4.4 执行段——另授权，本提案只回填其"存量迁移方案"文档段）。
- `WriteChainRegressionIT` 2 红（存量疑似写链路缺陷，另案）。
- Qdrant / 生产授权语义 / 方案 11/14（deferred 台账不变）。
- 查询侧三项的默认值翻转（默认保持 false，增幅是否可接受留用户决策）。
