# 提案：摄取切片写库批次化受控对照

> 2026-09-26 拟案。编写时权威树 `master@d60b923`，实施前以现场 HEAD、工作区与有效配置重新核对。**只讨论切片快照数据库写入；不是生产加速或真实模型费用授权。**

## Why

[本机真存储度量](../../../docs/representative-hotpath-measurement.md) §4.2 的固定文档（6600 字符、fixed 320/40、24 子块、0 父块）在一次性 MySQL/Qdrant 加确定性假模型条件下，每文档有 24 次单条 `chunkMapper.insert`，切片写入段约 445.463/456.662 ms，文档端到端 P50 在四组窗口间为 470.730–591.521 ms。[上案修订报告](../../../docs/context-snapshot-batch-read-evaluation.md) §3.4 确认摄取 27 SQL/文档的逻辑调用形状仍未变。这些均是本机候选证据，**未实测生产磁盘提交/fsync、真实 embedding RTT、费用或生产请求占比**；不能直接认定批写会提升生产吞吐。

当前 [DocumentIngestionSupport](../../../src/main/java/com/slz/crm/knowledge/document/DocumentIngestionSupport.java) 先按逻辑段 `insert` 父块（读取 DB 自增 ID），再逐条 `insert` 子块并回填 `parentChunkId`；子块 ID 同时用于向量 `chunkId`、引用和跳页锚点。`ingest` 与 `reingest` 共用此尾段，[DocumentIngestionService](../../../src/main/java/com/slz/crm/knowledge/document/DocumentIngestionService.java) 在失败后尝试清向量、物理删切片、保留文件并标 FAILED。重建前先物理删旧行，因为 `(document_id, chunk_index)` 唯一键与逻辑删除并存。

**可证伪假设**：在不改变逻辑行数、生成键、父子关系、失败补偿和外呼次数的条件下，只收敛切片数据库写入往返/提交范围，能改善同一摄取负载的**端到端**墙钟与吞吐；数据库段下降但端到端没有稳定改善时不得作为成功优化合入。

## What Changes

1. **前测与主键可行性闸**：改生产代码前，显式运行现有本机真存储安全入口至少两条独立命令，固定摄取 c4、每轮 40 样本、24 子块、相同预热/种子/有效配置，分开报告 r1/r2；同时记录 `chunk_insert`、端到端 P50/P95、成功吞吐、错误/超时、逻辑行数、可观测的物理数据库执行/提交次数及连接等待与资源；不可观测项标 unknown，绝不能将 Mapper 调用次数当作物理 commit 或网络往返。单独用本机一次性 MySQL 验证拟选批写机制的自增主键**逐行回填**、父块先写后子块关联与部分失败返回；若不能可靠回填，停止实施，不改 ID 生成方案或迁移来凑目标。旧历史数字不充当前测。
2. **只改数据库写入尾段**：在 `DocumentIngestionSupport.persistChunks` 与必要的窄协作类/Mapper 中，以受限批次写入父块和子块；父块 ID 确认可用后才绑定子块，子块 ID 确认可用后才创建 `VectorRecord`。单文档大切片数下批次容量有固定上限，不构造无界 SQL/参数或新增随块数无界增长的批处理缓冲（原有 chunks/children/vectorRecords 集合口径不在本案调整）；保持原 chunkIndex、文本、哈希、锚点、角色、关键词、父子分组与返回排序。可使用短的**仅 DB 写入**事务/批次边界，但不得让解析、文件 I/O、逐块 embedding、Qdrant 删除/upsert、异步衍生问题或审计落在长期持有数据库连接的事务里；须明确普通 ingest 与 reingest 的可见性和回退语义。
3. **失败与双路径回归**：对固定切分（24 子块无父块）和语义切分（父块+子块）分别断言 DB 行、生成 ID、`parentChunkId`、`VectorRecord.chunkId` 一一对应；以真 MySQL 或可证明同等语义的测试分别注入批次中途失败、主键回填不完整、embedding 失败、Qdrant upsert 失败、reingest 再次执行失败。既有写授权、PROCESSING→COMPLETED/FAILED、物理删旧行、清向量和切片、审计/异步旁路不变；不可让 DB batch 异常被静默吞掉，正常完成失败清理后不能留半量可检索结果；清理自身失败须记录残留风险，不得误报成功。
4. **同条件复测与明确停止条件**：在同一负载与本机隔离环境下至少两条独立后测，与本轮新鲜前测逐执行/逐轮比较，并在 `docs/document-chunk-write-batching-evaluation.md` 记录原始条件、逻辑行数与可实测的物理 DB 执行/提交两种计数（缺测标 unknown）、端到端 P50/P95/吞吐/失败、切片写入段、连接等待/CPU/内存/GC及观测开销。**首要验收是端到端**：若两执行稳态端到端改善不一致、幅度无法区分前测波动、P95 或失败明显恶化、吞吐重复退化，则仅报告“无稳定端到端收益/无法判定”，不交付或合入生产批写实现；不能以 SQL/提交次数下降或切片分段变快替代这一门槛。生产真实模型时间仍未知，不宣称生产收益。

## Impact

- **规范来源**：`openspec/changes/archive/upgrade-semantic-chunking-and-index/specs/document-chunking/spec.md`（双粒度与重建幂等）；本案在 `specs/document-ingest/spec-delta.md` 新增内部批写/验收约束，不修改归档规格。`openspec/changes/archive/add-vision-pdf-ingest-pilot/` 在途且有真实视觉费用节点，本案不进入该路径或授权它。
- **潜在生产范围**：共享的 `knowledge/document/DocumentIngestionSupport.java`，必要时一处窄的 DB 批写协作组件与 `server/mapper/DocumentVectorChunkMapper.java`；若需触碰 `DocumentIngestionService.java` 的失败补偿或短事务接线，只准语义等价的最小修改并在动手前说明。测试可调整 `DocumentIngestionServiceTest`、真实 MySQL 的定向 IT/显式本机度量、`RequestHotpathBaselineTest` 与 `RepresentativeHotpathBenchmark` 的精确形状计数；不删除旧硬断言，只分开计量“24 逻辑子行”与“物理执行/提交”。
- **不变/禁区**：权限、文档解析/切分、embedding 次数与输入、向量 upsert/delete、来源引用、费用开关、动态配置默认、API/DTO、冻结契约、Flyway 迁移和索引、依赖、CI/JVM/线程池/连接池参数均不改；不改旧报告历史数字。生产真实 Provider、vision/embedding 试点及 reingest 真实外呼均不运行。
- **费用与隔离**：只用已有显式 opt-in 的一次性 MySQL/Qdrant 和硬绑定确定性假模型；Docker/本地镜像缺失时 fail closed，绝不 pull 或回退到业务数据库。默认 surefire/merge-gate 不启动容器度量；真模型、生产开关及成本试点仍要 owner 另行授权。
- **工作树/交付**：仅提案三件套在本轮写入，原 `D:\code\crmAndRag` 工作树和权威树未跟踪 `docs/backend-optimization-candidates.md` 不动；不提交、不合并、不 push、不归档。后续交给子 agent 的任务卡是一轮覆盖前测、可行性、实现、回归、后测、门禁及**验收成立时**提交合入，不能每次 Git 操作单独停一轮。若目标不成立，留证据、停下，不改口径追认加速。
