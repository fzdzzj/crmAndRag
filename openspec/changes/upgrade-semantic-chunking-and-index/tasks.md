# Tasks — upgrade-semantic-chunking-and-index

## 1. 语义切分策略（方案 02）

- [x] 1.1 切分器策略抽象：`rag.chunking.strategy = fixed | semantic`（DynamicConfig，默认 fixed）；fixed 路径行为等价单测（同输入同 chunk 序列）
- [x] 1.2 semantic 实现：段落/标题/转折词边界 + `rag.chunking.max-chunk-size` 上限；单测断言：切点均落在边界、超长段按上限二次切分
- [x] 1.3 锚点保留断言：semantic 切分后每个 chunk 携带正确 pageNo（PDF 按页）与 rowIndex（Excel 按行）；D15 页级引用回归不破坏
- [x] 1.4 切分质量对比测试：构造跨段话题文档，semantic 相对 fixed 的块内话题一致性（构造断言：同一小节标题下的内容不分裂到两个 chunk）

## 2. 块头注入（方案 05）

- [x] 2.1 摄取侧构造：嵌入文本 = `【文件名 | 类目 | 页码】`+ 块文本；DB `chunk_text` 与 `SourceReference.excerpt` 保持原文（单测断言两文本分离）
- [x] 2.2 嵌入调用点单测：`EmbeddingService.embed` 收到带前缀文本（mock 断言入参）

## 3. 双粒度索引（方案 03）

- [ ] 3.1 Flyway `V23__chunk_parent_link.sql`：`document_vector_chunk` 增 `parent_chunk_id`（可空，自引用父块行）；`FlywayMigrationIT` 断言通过
- [ ] 3.2 语义切分时生成父块记录（逻辑段聚合），子块挂 `parent_chunk_id`；fixed 策略下父块=块自身（`parent_chunk_id` 空，行为不变）
- [ ] 3.3 `ContextBuilder` 升级：双粒度模式下命中子块 → 展开父块进上下文；`SourceReference` 仍指子块（引用锚点不降级）；`rag.context.parent-expand = on | off`（默认 on，off 回退邻居模式）
- [ ] 3.4 单测：命中子块时上下文含父块全文；引用/跳页锚点指向子块

## 4. 幂等重建入库（reingest）

- [ ] 4.1 reingest runner：按 documentId 清向量 → 清 chunk 行 → 重切分 → 重嵌入 → 重写 DB；失败清理复用 markFailed 语义（不留半量）
- [ ] 4.2 幂等单测：同一文档连续 reingest 两次，chunk 集合（文本+锚点+父子关系）一致
- [ ] 4.3 超管触发入口（管理端点或运维 runner，二选一在实现时定），挂平台审计（单测断言审计记录产生）
- [ ] 4.4 存量迁移方案：跑批复用 batch_task 状态机或独立 runner——实现时按耦合度定，写入验证记录；全量 reingest 前向用户确认 API 成本

## 5. 基线验收（对照最新基线）

- [ ] 5.1 重嵌入后重跑真检索基准：recall@k / MRR 较提案 2/3 后基线提升或持平；citationPrecision 不回退（锚点仍准）
- [ ] 5.2 产出 `baseline-after-chunking.json` 落盘，差异摘要写入本 change 验证记录
- [ ] 5.3 `mvn -B -ntp test` 绿（surefire 计数只增）；`mvn -B -ntp verify` failsafe 不减
- [ ] 5.4 回退演练：`rag.chunking.strategy=fixed` + `rag.context.parent-expand=off` 下，检索与上下文行为回到提案 3 完成态（基线用例不回退）

## 6. Git 操作（按 `openspec/git-workflow.md` 执行）

- 分支：`git checkout -b feature/upgrade-semantic-chunking-and-index`。
- **前置：提案 2/3 已合入 master**（本提案改嵌入输入，提前做会导致两遍重嵌入）。
- 提交序（任务组 → 提交）：
  1. `feat(document)`: 切分策略抽象 + semantic 实现 + 锚点/质量对比断言（1.1–1.4）
  2. `feat(document)`: 块头注入与展示分离（2.1–2.2）
  3. `feat(db)`: V23 parent_chunk_id 迁移（3.1）；`feat(retrieval)`: 父块生成与展开（3.2–3.4）——可拆两个提交
  4. `feat(document)`: reingest runner + 幂等/失败清理/审计单测（4.1–4.3）
  5. `test(quality)`: 基线对照 + `baseline-after-chunking.json` 入库 + 回退演练（5.1–5.4）
  6. `chore(ci)`: surefire 基线 bump
- **成本闸门（本提案特有）**：4.4 存量全量 reingest = 真实嵌入 API 成本 × 现有 chunk 总量，执行前**必须获用户确认**——其余任务可先行，全量重嵌入放最后。
- 合并：亲验后 `git checkout master; git merge --no-ff feature/upgrade-semantic-chunking-and-index -m "Merge branch '...'：提案4/5 语义切分+块头+双粒度索引"`。
