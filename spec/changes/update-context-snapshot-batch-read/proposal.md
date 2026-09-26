# 提案：检索上下文快照读取批量化对照

> 2026-09-26 拟案；起点 `master@24efb19` 仅是编写时快照，实施前以现场 HEAD、工作区和既有规格为准。本案只针对**检索上下文 DB 读取**，并不以本机证据宣称已找到生产瓶颈。

## Why

代码审查发现，[ContextBuilder](../../../src/main/java/com/slz/crm/knowledge/retrieval/ContextBuilder.java) 对命中逐条查询子块/父块，回退时 [NeighborContextSupport](../../../src/main/java/com/slz/crm/knowledge/retrieval/NeighborContextSupport.java) 逐条查询前后邻居。[本机隔离度量](../../../docs/representative-hotpath-measurement.md) 的 4 授权库、topK 5、5 命中样本为每请求父块查询 5 次 + 邻居查询 5 次，c1 稳态 context 段约 18.045/18.066 ms。该段包含 SQL，数字不等于可节省时间；模型改写/嵌入使用确定性桩，生产 RTT 未知。[c8 归因](../../../docs/hotpath-concurrency-attribution.md) 只把慢落点关联到**全部 SQL/存储段**，未将其中增量单独归于上下文查询，也未隔离物理机制。

**瓶颈假设**：在相同输入与授权下，将每命中快照读取收敛为有限次按键批查，可减少上下文 SQL 往返及其墙钟贡献；若重复测量不支持该假设，保留否定结果而不叠加参数或宣称生产收益。旧报告数字不改、不与历史 shim 数字混算。

## What Changes

1. **先取当轮旧路径基线**：实施代码前在同一权威树的现行生产实现上，用已有安全的显式本机真存储入口、固定 4 库/同查询/topK 5/5 命中与确定性假模型，至少两次独立命令记录 c1 和 c8 的对应轮次、SQL 次数、context/端到端 P50/P95、吞吐、失败、连接等待及资源。检查本地镜像/Docker，缺失即记未测，不自动下载；不开任何真实模型开关。已有 18 ms 仅供量级参考，不作本案新旧对照基线。
2. **仅替换快照读取策略**：在 `ContextBuilder`、`NeighborContextSupport` 与必要的 `DocumentVectorChunkMapper` 范围内，把数字命中 ID 的子块、存在的父块，以及需回退的同文档前后邻居按有限大小分组批查。按原命中顺序拼装；查询可带多组文档/索引，但结果必须再次按对应文档、`CHILD` 角色、索引和现有逻辑删除口径校验，不能因扩大 `IN` 集合引入跨文档或未授权上下文。单命中、空命中、开关关闭以及混合父块/邻居路径仍等价。批查异常时保留已成功批次；仅受影响批次按既有 DB 异常语义有界回查或降级并告警，禁止持续故障时无上限逐命中重试放大数据库压力；不打开新事务或改变检索主链路。
3. **行为与调用形状双重测试**：构造原路径与新路径对照，验证输出上下文逐字、`[n]`/sources 锚点、压缩前后口径、同页优先和同页主键小者、首末块/跨文档、重复命中、缺行及批查异常有界降级。固定 5 命中/无父块样本，成功路径把原 5 次子块 `selectById` + 5 次邻居 `selectList` 收敛到有界批查（目标不超过 3 条快照 SQL），且不触发逐命中旧查询；大候选集合要限制单次 SQL 参数量，不能引入无界 `IN`。父块存在时单独核对父子关系与原结果。现有 `RequestHotpathBaselineTest` 和 `RepresentativeHotpathBenchmark` 把旧 5+5 次写成硬断言，`HotpathConcurrencyAttribution` 按旧计数索引关联请求；实施时必须让测试侧内存快照桩支持新批查、更新精确断言与逐请求计数，不能删除或宽松化门禁。现存 `chunkIndex` 缺失时的拆箱问题是需另案处理的正确性缺口，不得以本案数据混报为性能修复。
4. **同负载复测与裁决**：新增 `docs/context-snapshot-batch-read-evaluation.md` 单独记录当轮原新证据，既有合成、代表性与 c8 报告的历史数字不改。新旧使用同一环境条件、种子和固定负载，实施前后各至少两次独立执行、逐轮对齐，c1 稳态轮为主要比较，c8 的 r1/r2 分开报告并披露首窗波动、JaCoCo/采样开销；列出 SQL 次数、context/端到端 P50/P95、吞吐、资源与错误。SQL 次数减少是必要而非充分条件；若延迟/吞吐收益不可复现或出现输出/权限/失败语义回归，不将实现作为成功优化合入，记录反例并回到度量。

## Impact

- **规范来源与增量**：现行行为来自 `openspec/changes/archive/add-context-compression-and-enrichment/specs/rag-context/spec.md`（邻居、编号、压缩）、`openspec/changes/archive/upgrade-semantic-chunking-and-index/specs/document-chunking/spec.md`（父块与小块引用）；本案在 `specs/rag-context/spec-delta.md` 新增内部批读与验证约束，不改旧归档规格。
- **潜在代码范围（实施时）**：`src/main/java/com/slz/crm/knowledge/retrieval/ContextBuilder.java`、`NeighborContextSupport.java`、必要时 `server/mapper/DocumentVectorChunkMapper.java`；相关 `src/test` 单测，`RequestHotpathBaselineTest`、`RepresentativeHotpathBenchmark` 及必要的 `HotpathConcurrencyAttribution` 精确计数/断言接线；新增单独的前后对照报告。若需要扩大生产文件范围，先说明原因并重新审范围。
- **不触碰**：API/DTO、冻结的 `VectorSearchHit`/`SourceReference`、授权逻辑、检索与向量库调用形状、摄取/reingest、迁移/索引、动态配置默认、模型/embedding、依赖、CI 阈值、连接池/JVM/线程池参数、旧报告历史数字。不会改变金额/账户预算或开放生产费用开关。
- **环境与费用**：可用已有一次性本机 MySQL/Qdrant 与固定假模型显式入口；默认单测/merge-gate 不运行容器基准。镜像不存在、Docker 不可用或装配安全条件不满足时 fail closed 并报告未测；不下载镜像、不读密钥、不外发模型请求。
- **风险与回退**：批查失败可能使多个命中同时丢上下文；通过成功批次隔离、有界回查或降级及故障注入测试约束。跨文档/角色混选、逻辑删除、父块链接、同页取舍、SQL 参数上限均是反例。若重复性能实验不成立，停止本优化，保留报告而不改变生产读取路径。

## 验收与执行边界

- 提案阶段仅创建本目录三件套，**不实施、不提交、不合并**。经用户评审后，可把一张完整任务卡交子 agent，在一个执行轮次内完成前测、实现、回归、复测、门禁和范围可控时的提交/合并；不把单次 Git 操作拆成多轮。
- 执行时重新核对工作树，保护现有未跟踪 `docs/backend-optimization-candidates.md`（本案不引用、不纳入提交），只暂存本案批准文件，原 `D:\code\crmAndRag` 工作树不做 reset/stash/clean。若前测不可比、存在真实付费外呼风险、行为回归或收益无证据，停下报告，不假装完成，不 push、不归档。
- 定向测试与默认 `scripts/merge-gate.sh` 逐项报告；默认门禁不等于 failsafe IT。若测试基线需要更新，只允许按仓库流程由一次真实干净运行经脚本 `--update` 写入，禁止手改阈值。真实模型/生产试点另需 owner 明确授权。
