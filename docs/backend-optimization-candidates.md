# 后端优化候选与证据边界

> 首版 2026-09-26（master@24efb19 只读审查记录）；2026-10-08 候选池重盘归档（owner 拍板，随 intake-openspec-proposal-specs 入库）；同日 §4 待取证方向收口复查（close-candidate-pool-residue，主 agent 亲测）。首版 7 项候选已全部闭环，本文从「待验证候选清单」转为「闭环归档 + 现行挂账 + 后续方向」。首版声明继续有效：本文不是实施提案、收益承诺或生产开关授权。

## 1. 证据等级与共通验收（方法论，首版原文保留）

- **本机实测**：已有 [固定负载合成基线](./request-hotpath-baseline.md)、[本机 MySQL/Qdrant + 确定性模型桩度量](./representative-hotpath-measurement.md) 和 [c8 并发归因实验](./hotpath-concurrency-attribution.md)。合成延迟不能推断生产瓶颈；本机真存储实验也**未测真实模型/embedding RTT、费用及生产负载**。历史 shim 数字不可追认为生产 store 基线；修复后独立数据见本机度量 §5.1。
- **代码可见**：当前循环、SQL 次数、授权分支或事务边界可以从下面的代码链接复查；代码调用形状不等于生产耗时。
- **假设/未知**：批量查询或写入可能更快、CRM 页面可能有明显 N+1，这些尚未用对应真实或代表性负载验证；生产瓶颈、JFR/锁等待和真实远程耗时仍未知。不同接口的毫秒数不能横向直接排序全站收益。
- 每案先固定同一查询、4 库/topK/命中数或同一摄取文档、角色及数据规模，记录每请求 SQL/远程调用/写入次数、P50/P95/P99、吞吐、错误、连接等待及资源；**一次只改变一类因素**，相同基线复测。指标无改善则回到度量，不叠加连接池、线程池或 JVM 参数。金额、权限、幂等、引用、失败清理与事务语义为硬约束。
- [c8 报告](./hotpath-concurrency-attribution.md) 的六个首次 c8 窗口 p50 均高于六个后续窗口；慢落点与单请求 SQL/存储段强相关，**JIT、MySQL、Qdrant 或宿主缓存等物理机制未隔离**。不得把「没有观察到某因素解释主要差异」扩大为所有环境下绝对排除，也不据此授权生产调参。

## 2. 已闭环候选归档（2026-10-08 重盘逐项实测核对）

| 首版条目 | 落地变更 | 重盘实测证据 |
|---|---|---|
| §2 检索上下文逐命中查快照 | update-context-snapshot-batch-read | ContextBuilder/NeighborContextSupport `selectBatchIds` 有界批读（SNAPSHOT_BATCH_LIMIT=500）+ 批查失败仅受影响批次按旧路径逐命中回查一次后降级 |
| §3 摄取切片逐条 insert | update-document-chunk-write-batching | DocumentIngestionSupport.persistChunks 父/子块各以固定上限受限批次多行 INSERT + 主键回填完整性校验，失败走既有清理语义 |
| §4 项目文件分页后逐行鉴权及重复判定 | batch-project-file-list-auth-reads + optimize-project-file-list-auth-reuse | user 读 N→1、维度实体/订单项 selectBatchIds ≤4、参与人 IN 查询；判定矩阵与单行入口逐字一致、三把 total 语义锁不动（后续安全整改与 NO-GO 改判教训在案：收益不再成立时诚实回滚生产优化） |
| §5 单库 scope 先枚举全部可见 KB | optimize-kb-scope-auth-fast-resolve + optimize-knowledge-base-write-auth-batching | 单库快解析与 KB 写授权批量化落地 |
| §6 可选 LLM 路无显式 executor | llmAuxExecutor 接线 | HydeQueryExpander/LlmContextCompressor/LlmReranker/VariantRouteCollaborator 的 supplyAsync 全传显式线程池 + 超时护栏 + finally cancel（超时后取消排队任务） |
| §6 逐库并行检索 / 批量外呼 | 不启动（维持首版结论） | 候选数、每库过滤、排序、超时、配额、费用边界未变，继续不启动 |
| §7 缺失 chunkIndex 时邻居回退拆箱 | fix-neighbor-missing-chunk-index-fallback | NeighborContextSupport.appendWithNeighbors 无效索引守卫：chunkIndex 缺失/非 Number/负数只按原文本输出，不拆箱 |

首版 §8 建议调查顺序①-⑤（检索批读 → 摄取批写 → 项目文件度量 → KB 授权 → 模型超时）全部执行完毕。首版之后另落地：wire-llm-call-timeout（模型超时护栏）与熔断 + 恢复五卡（P-u 接线 → P-v 三态半开 → P-w 全局动态化 + F-3 → P-y 按依赖名覆盖级联 → P-x 摄取恢复重放，`platform.resilience` 命名空间 12 键 + `rag.ingest` 2 键）。

## 3. 现行挂账与约束（2026-10-08 重盘定夺）

- **hooks 前端转发（接受现状，观察项关闭）**：core.hooksPath 指向主树 `.git\hooks`（权威树为 worktree，共享主 .git/hooks 是 git 默认语义）；pre-commit 转发器自带「无 staged 前端文件即 exit 0」短路，纯后端提交零阻塞（历次后端卡提交佐证）。不单独配 hooksPath。
- **PlatformErrorCode.DEPENDENCY_UNAVAILABLE(96008)（维持预留）**：src/main 零消费；熔断异常按 P-u 拍板转回既有 IllegalStateException/StorageException，前端零改动。启用即错误码契约变更（前端需识别新 code），待前端需要「依赖暂不可用」专门 UI 时再议。
- **ModelProviderImpl NCSS 149/150**：余量 1，任何触碰它的卡必须先拆协作者（P-u 先例 ModelCallGuard）。
- **rag.ingest.replay-enabled 默认 false**：生产启用需 owner 授权（费用红线）；启用后观察 PENDING 积压与重放成功率。
- **执行侧留痕习惯（P-y 复核建议）**：后续卡三套自测（agent-helper / check-test-baseline / merge-gate）的独立 raw 一并归档；raw 日志编码拉平 UTF-8。
- **openspec 三件套口径**：自 intake-openspec-proposal-specs 起全量入库（proposal.md / tasks.md / specs/*/spec-delta.md），work/ 执行留痕保持 untracked 惯例。

## 4. 后续候选方向（2026-10-08 收口复查，主 agent 亲测；仍非实施授权）

- **前端质量残留复查 → 已清零关闭**：权威树 `pnpm lint:check`（`eslint --ext .js,.vue src`）@master 3c8a8b5 实测零输出（0 警告 0 错误），P-i 的 22 处警告消解未被回退。注：`lint:check` 无 `--max-warnings 0`，警告本不拦 CI 绿，故以本日实测为准而非 CI 颜色。
- **docs/ingest-gap-map.md 遗留项重盘 → 主切口已落地**：gap-map 唯一推荐切口「图像 PDF 检测 + 单页 VLM 转写试点（默认关，质量闸门失败回退文本层）」已随 add-vision-pdf-ingest-pilot 合入（`PdfVisionTranscriber` 入 src/main，`rag.retrieval.vision-pdf.enabled/min-text-chars/max-pages` 已注册 DynamicConfigKeyRegistry，enabled 默认 false）。剩余差距项（检测阈值从试点泛化、PPTX 接入、扫描件 OCR、PDF 内表格结构还原、VLM 生产化推广与配额/审计）均为需 owner 成本拍板的功能延伸，未立项。
- **重放运行配套 → 维持待取证**：PENDING 积压监控指标/告警仍依赖 `rag.ingest.replay-enabled` 生产启用（owner 授权，费用红线）后的运行数据；启用前建面存在阈值设计盲区，不立项。
- 以上均未立项、未测负载、未授权任何代码改动或真实外呼。