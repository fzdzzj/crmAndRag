# 切片批次写库评测报告（update-document-chunk-write-batching）

- 权威工作树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- 度量时 HEAD：`d60b92310af569841d1c8806b544efd93743d51a`（分支 `master`，前测时工作区仅有本案改动之外的未跟踪项）
- 证据类别：`local-real-storage + local-model-stub`（一次性本机 MySQL 8.0.36 + Qdrant 容器 + 确定性假模型）
- 隔离边界：`REPRESENTATIVE_HOTPATH_MEASURE=1` 显式 opt-in；`DASHSCOPE_API_KEY` 置空；不读真实密钥、不下载镜像（镜像预检只读 inspect）、不连业务库
- 一律保持未知：真实模型 RTT、真实费用、生产环境收益、物理数据库执行次数/网络往返/commit 次数

---

## 1. 结论（主验收裁决）

**主验收成立。** 以端到端为准，后测两条独立执行（各 2 轮，共 4 轮）相对前测两条独立执行（各 2 轮，共 4 轮）呈现**一致且量级明显**的改善，且不被前测自身波动解释：

| 指标（c4 摄取） | 前测 4 轮区间 | 后测 4 轮区间 | 判定 |
| --- | --- | --- | --- |
| 端到端 P50 (ms) | 469.037 – 516.703 | 86.973 – 115.150 | 后测最差仍优于前测最好约 4.1 倍（469.037 / 115.150） |
| 端到端 P95 (ms) | 599.252 – 694.624 | 126.704 – 287.888 | 后测最差 287.888 < 前测最好 599.252，4/4 轮全优 |
| 成功吞吐 (ok/s) | 6.461 – 6.753 | 14.940 – 21.616 | 4/4 轮严格高于前测任一值 |
| 失败数 | 0 | 0 | 未恶化（均为 0） |

- 前测波动幅度（P50 极差约 47.7 ms / 约 10%）远小于前后差距（约 354 ms），**改善可区别于前测波动**。
- 无 P95 恶化、无失败、无吞吐退化。
- 写库段（`seg_chunk_insert`）亦从 436.993–456.240 ms 降至 33.004–39.123 ms，但**该分段不构成验收依据**，仅作旁证；见第 5 节的口径声明。

---

## 2. 方法

### 2.1 命令原文（前测/后测同一条，仅输出文件名不同）

前测两条独立命令（原文取自执行记录）：

```powershell
# 前测执行 A
$env:REPRESENTATIVE_HOTPATH_MEASURE="1"; $env:DASHSCOPE_API_KEY=""; $out = Join-Path $env:TEMP "rephot_pre_1.txt"; mvn -B -ntp -Dtest=RepresentativeHotpathBenchmark test 2>&1 | Tee-Object -FilePath $out | Out-Null
# 前测执行 B
$env:REPRESENTATIVE_HOTPATH_MEASURE="1"; $env:DASHSCOPE_API_KEY=""; $out = Join-Path $env:TEMP "rephot_pre_2.txt"; mvn -B -ntp -Dtest=RepresentativeHotpathBenchmark test 2>&1 | Tee-Object -FilePath $out | Out-Null
```

后测两条独立命令（同条件，仅换输出名）：

```powershell
# 后测执行 C
$env:REPRESENTATIVE_HOTPATH_MEASURE="1"; $env:DASHSCOPE_API_KEY=""; $out = Join-Path $env:TEMP "rephot_postbatch_1.txt"; mvn -B -ntp -Dtest=RepresentativeHotpathBenchmark test 2>&1 | Tee-Object -FilePath $out | Out-Null
# 后测执行 D
$env:REPRESENTATIVE_HOTPATH_MEASURE="1"; $env:DASHSCOPE_API_KEY=""; $out = Join-Path $env:TEMP "rephot_postbatch_2.txt"; mvn -B -ntp -Dtest=RepresentativeHotpathBenchmark test 2>&1 | Tee-Object -FilePath $out | Out-Null
```

### 2.2 固定负载（写死，前测/后测一致，未改生产默认值）

- 摄取：`INGEST_THREADS=4`（c4）、预热 `INGEST_WARMUP=4`、`INGEST_SAMPLES_PER_THREAD=10` → 每轮 40 样本；`ROUNDS=2`
- 摄取文本 6600 字符 / fixed 320 分隔 40 重叠 → 24 个非空子块、0 个父块
- 检索相、语料、种子与确定性假模型（`StubModelProvider`）均未改动

### 2.3 口径分野（硬约束）

- **逻辑行**：`INSERT_CHILD_CALLS` / `INSERT_PARENT_CALLS` 表示切片逻辑行数（24 / 0），前测后测一致，断言未放宽。
- **可观测 Mapper 调用**：`sql_all.calls_per_req`、`conn_acquire_inclusive.calls_per_req` 表示经 `timed` 代理观测到的 Mapper 方法调用次数（27 → 4）。
- **不可观测**：真实数据库语句执行次数、网络往返次数、commit 次数在本基准**无法观测**，一律 unknown；不得由 Mapper 调用次数反推物理写入次数。

---

## 3. 前测原始数据（旧路径：逐行 insert）

### 3.1 执行 A（`rephot_pre_1.txt`，BUILD SUCCESS，Tests run: 1, Failures: 0, Errors: 0, Skipped: 0）

```
REPHOT ingest r1 c4 window: samples=40 success=40 failure=0 timeout=within-5min-deadline throughput_ok_per_s=6.571 window_s=6.088
REPHOT ingest r1 c4 e2e: n=40 p50_ms=486.648 p95_ms=694.624 p99_ms=751.251 max_ms=751.251
REPHOT ingest r1 c4 seg_chunk_insert: avg_ms=456.240 calls_per_req=24.00
REPHOT ingest r1 c4 seg_file_sql: avg_ms=39.782 calls_per_req=2.00
REPHOT ingest r1 c4 seg_conn_acquire_inclusive: avg_ms=0.352 calls_per_req=27.00
REPHOT ingest r1 c4 seg_sql_all: avg_ms=498.610 calls_per_req=27.00
REPHOT ingest r1 c4 cpu: process_cpu_load_avg=0.071 samples=43 container_cpu_mem=unknown-docker-stats-not-wired
REPHOT ingest r1 c4 heap: used_start_mb=40.9 used_end_mb=55.0 peak_used_mb=128.9 committed_end_mb=155.2 max_mb=4143.972352
REPHOT ingest r1 c4 gc: collections=3 pause_total_ms=15
REPHOT ingest-state: round=1 chunks_before=44 chunks_after=44 files_before=4 files_after=4
REPHOT ingest r2 c4 window: samples=40 success=40 failure=0 timeout=within-5min-deadline throughput_ok_per_s=6.461 window_s=6.191
REPHOT ingest r2 c4 e2e: n=40 p50_ms=516.703 p95_ms=623.270 p99_ms=636.057 max_ms=636.057
REPHOT ingest r2 c4 seg_chunk_insert: avg_ms=448.706 calls_per_req=24.00
REPHOT ingest r2 c4 seg_conn_acquire_inclusive: avg_ms=0.277 calls_per_req=27.00
REPHOT ingest r2 c4 seg_sql_all: avg_ms=486.841 calls_per_req=27.00
REPHOT ingest r2 c4 cpu: process_cpu_load_avg=0.057 samples=47 container_cpu_mem=unknown-docker-stats-not-wired
REPHOT ingest r2 c4 heap: used_start_mb=46.2 used_end_mb=66.3 peak_used_mb=123.8 committed_end_mb=146.8 max_mb=4143.972352
REPHOT ingest r2 c4 gc: collections=1 pause_total_ms=1
REPHOT ingest-state: round=2 chunks_before=44 chunks_after=44 files_before=4 files_after=4
```

### 3.2 执行 B（`rephot_pre_2.txt`，BUILD SUCCESS，Tests run: 1, Failures: 0, Errors: 0, Skipped: 0）

```
REPHOT ingest r1 c4 window: samples=40 success=40 failure=0 timeout=within-5min-deadline throughput_ok_per_s=6.512 window_s=6.142
REPHOT ingest r1 c4 e2e: n=40 p50_ms=506.374 p95_ms=599.252 p99_ms=644.064 max_ms=644.064
REPHOT ingest r1 c4 seg_chunk_insert: avg_ms=441.029 calls_per_req=24.00
REPHOT ingest r1 c4 seg_conn_acquire_inclusive: avg_ms=0.268 calls_per_req=27.00
REPHOT ingest r1 c4 seg_sql_all: avg_ms=480.447 calls_per_req=27.00
REPHOT ingest-state: round=1 chunks_before=44 chunks_after=44 files_before=4 files_after=4
REPHOT ingest r2 c4 window: samples=40 success=40 failure=0 timeout=within-5min-deadline throughput_ok_per_s=6.753 window_s=5.923
REPHOT ingest r2 c4 e2e: n=40 p50_ms=469.037 p95_ms=618.674 p99_ms=648.971 max_ms=648.971
REPHOT ingest r2 c4 seg_chunk_insert: avg_ms=436.993 calls_per_req=24.00
REPHOT ingest r2 c4 seg_conn_acquire_inclusive: avg_ms=0.285 calls_per_req=27.00
REPHOT ingest r2 c4 seg_sql_all: avg_ms=472.985 calls_per_req=27.00
REPHOT ingest-state: round=2 chunks_before=44 chunks_after=44 files_before=4 files_after=4
```

---

## 4. 生成键与父子引用证据（步骤 2 可行性闸）

一次性本机 MySQL（Testcontainers `mysql:8.0.36`）+ Flyway 全量迁移 + 裸 MyBatis `SqlSessionFactory`（仅 `addMapper(DocumentVectorChunkMapper.class)`、`PooledDataSource`、`openSession(true)`），确定性假嵌入（无 Provider/无密钥/无外呼），内存捕获桩替代 Qdrant：

```
[INFO] Running com.slz.crm.knowledge.document.ChunkBatchInsertGeneratedKeyIT
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 33.48 s
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

四个用例各自的证据：

1. **固定 24 子块**：`useGeneratedKeys + keyProperty=id` 对多行 INSERT **逐行回填**，24 个主键全部非空且互不重复，与各行的 `chunk_index` 一一对应；`VectorRecord.chunkId` 等于该行数据库自增主键。
2. **跨批次边界（150 子块，>64 上限）**：每批逐行回填，`chunk_index` 连续无缺。
3. **父子顺序语义**：index 0..2 同段落 → 1 个父块；父块先取得 ID（`parent.id < min(child.id)`），父块 `chunkIndex=25`；同段子块 `parent_chunk_id` 指向该父块 ID；index 3 单块段与 4..23 的 `parent_chunk_id` 为 null；向量恰好 24 条，`chunkId` 引用对应子块。
4. **部分失败返回**：预置冲突行撞 `uk_document_vector_chunk` 唯一键 → 整条多行 INSERT **原子失败**（库中仍只有 1 行），`upsertAll` 未被调用；随后 `deletePhysicallyByDocumentId` 清理 → 重试成功写入 24 行。

结论：拟选批写方式**能可靠逐行回填自增主键**，父子引用顺序成立，部分失败可返回并被既有清理路径消解。未改主键策略、未改冻结契约、未改库结构。

---

## 5. 后测原始数据（新路径：受限批次写库）

### 5.1 执行 C（`rephot_postbatch_1.txt`，BUILD SUCCESS，Tests run: 1, Failures: 0, Errors: 0, Skipped: 0）

```
REPHOT ingest r1 c4 window: samples=40 success=40 failure=0 timeout=within-5min-deadline throughput_ok_per_s=21.616 window_s=1.850
REPHOT ingest r1 c4 e2e: n=40 p50_ms=86.973 p95_ms=131.547 p99_ms=137.740 max_ms=137.740
REPHOT ingest r1 c4 seg_chunk_insert: avg_ms=33.004 calls_per_req=24.00
REPHOT ingest r1 c4 seg_file_sql: avg_ms=35.944 calls_per_req=2.00
REPHOT ingest r1 c4 seg_conn_acquire_inclusive: avg_ms=0.053 calls_per_req=4.00
REPHOT ingest r1 c4 seg_sql_all: avg_ms=70.991 calls_per_req=4.00
REPHOT ingest r1 c4 cpu: process_cpu_load_avg=0.147 samples=14 container_cpu_mem=unknown-docker-stats-not-wired
REPHOT ingest r1 c4 heap: used_start_mb=112.6 used_end_mb=49.2 peak_used_mb=121.1 committed_end_mb=151.0 max_mb=4143.972352
REPHOT ingest r1 c4 gc: collections=2 pause_total_ms=6
REPHOT ingest-state: round=1 chunks_before=44 chunks_after=44 files_before=4 files_after=4
REPHOT ingest r2 c4 window: samples=40 success=40 failure=0 timeout=within-5min-deadline throughput_ok_per_s=14.940 window_s=2.677
REPHOT ingest r2 c4 e2e: n=40 p50_ms=115.150 p95_ms=287.888 p99_ms=321.890 max_ms=321.890
REPHOT ingest r2 c4 seg_chunk_insert: avg_ms=38.766 calls_per_req=24.00
REPHOT ingest r2 c4 seg_file_sql: avg_ms=68.344 calls_per_req=2.00
REPHOT ingest r2 c4 seg_conn_acquire_inclusive: avg_ms=0.033 calls_per_req=4.00
REPHOT ingest r2 c4 seg_sql_all: avg_ms=109.534 calls_per_req=4.00
REPHOT ingest r2 c4 cpu: process_cpu_load_avg=0.063 samples=21 container_cpu_mem=unknown-docker-stats-not-wired
REPHOT ingest r2 c4 heap: used_start_mb=50.6 used_end_mb=61.9 peak_used_mb=117.7 committed_end_mb=151.0 max_mb=4143.972352
REPHOT ingest r2 c4 gc: collections=4 pause_total_ms=10
REPHOT ingest-state: round=2 chunks_before=44 chunks_after=44 files_before=4 files_after=4
```

### 5.2 执行 D（`rephot_postbatch_2.txt`，BUILD SUCCESS，Tests run: 1, Failures: 0, Errors: 0, Skipped: 0）

```
REPHOT ingest r1 c4 window: samples=40 success=40 failure=0 timeout=within-5min-deadline throughput_ok_per_s=19.499 window_s=2.051
REPHOT ingest r1 c4 e2e: n=40 p50_ms=101.053 p95_ms=126.704 p99_ms=139.399 max_ms=139.399
REPHOT ingest r1 c4 seg_chunk_insert: avg_ms=36.067 calls_per_req=24.00
REPHOT ingest r1 c4 seg_conn_acquire_inclusive: avg_ms=0.061 calls_per_req=4.00
REPHOT ingest r1 c4 seg_sql_all: avg_ms=79.867 calls_per_req=4.00
REPHOT ingest-state: round=1 chunks_before=44 chunks_after=44 files_before=4 files_after=4
REPHOT ingest r2 c4 window: samples=40 success=40 failure=0 timeout=within-5min-deadline throughput_ok_per_s=17.407 window_s=2.298
REPHOT ingest r2 c4 e2e: n=40 p50_ms=104.808 p95_ms=178.866 p99_ms=287.088 max_ms=287.088
REPHOT ingest r2 c4 seg_chunk_insert: avg_ms=39.123 calls_per_req=24.00
REPHOT ingest r2 c4 seg_conn_acquire_inclusive: avg_ms=0.038 calls_per_req=4.00
REPHOT ingest r2 c4 seg_sql_all: avg_ms=91.563 calls_per_req=4.00
REPHOT ingest-state: round=2 chunks_before=44 chunks_after=44 files_before=4 files_after=4
```

---

## 6. 逐执行、逐轮对照（端到端为主，写库段为辅）

| 执行 | 轮 | P50 (ms) | P95 (ms) | 吞吐 (ok/s) | 失败 | chunk_insert (ms) | sql_all calls/req | conn_acquire calls/req |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 前 A | r1 | 486.648 | 694.624 | 6.571 | 0 | 456.240 | 27.00 | 27.00 |
| 前 A | r2 | 516.703 | 623.270 | 6.461 | 0 | 448.706 | 27.00 | 27.00 |
| 前 B | r1 | 506.374 | 599.252 | 6.512 | 0 | 441.029 | 27.00 | 27.00 |
| 前 B | r2 | 469.037 | 618.674 | 6.753 | 0 | 436.993 | 27.00 | 27.00 |
| 后 C | r1 | 86.973 | 131.547 | 21.616 | 0 | 33.004 | 4.00 | 4.00 |
| 后 C | r2 | 115.150 | 287.888 | 14.940 | 0 | 38.766 | 4.00 | 4.00 |
| 后 D | r1 | 101.053 | 126.704 | 19.499 | 0 | 36.067 | 4.00 | 4.00 |
| 后 D | r2 | 104.808 | 178.866 | 17.407 | 0 | 39.123 | 4.00 | 4.00 |

逐轮判定：P50 4/4 优、P95 4/4 优、吞吐 4/4 优、失败 4/4 持平（均 0）。

### 6.1 写库段与调用形状（旁证，非验收依据）

- `seg_chunk_insert` 从 436.993–456.240 ms 降至 33.004–39.123 ms。该值口径为 `INSERT_NANOS / requests`，即**每请求的切片写库总耗时**；其 `calls_per_req` 恒为 24.00，因为分母仍是**逻辑子块行数**——前后测一致，未被放宽。
- `seg_sql_all.calls_per_req` 与 `seg_conn_acquire_inclusive.calls_per_req` 由 27.00 降至 4.00：这是**可观测的 Mapper 调用次数**（库读取 1 + 切片受限批次 1 + 文件 insert 1 + 文件 update 1）。它**不是**物理数据库语句执行次数、网络往返次数或 commit 次数——后三者在本基准**不可观测**，标记 unknown。

### 6.2 可观测资源与观测开销

- 窗口时长由约 6.09–6.19 s 缩短至 1.85–2.68 s，资源采样器名义间隔 50 ms，故同窗样本数由 43–47 降为 14–21。**这是窗口变短导致的采样点变少，不是资源开销增加**；若按样本/秒折算，观测开销口径与前后一致。
- `process_cpu_load_avg` 前测 0.057–0.071，后测 0.063–0.186（窗口极短时该均值噪声放大）；`gc` 前后均在 1–4 次 / 1–15 ms 量级；`heap` 峰值前后均约 117–129 MB。未见后测资源恶化。
- `container_cpu_mem=unknown-docker-stats-not-wired`（前后测一致，未接 docker stats）。

---

## 7. 语义回归与故障注入（步骤 4）

盲区覆盖（纯 Mockito 单测，无 Spring、无 Docker、无外呼）：

| 用例 | 覆盖点 | 结果 |
| --- | --- | --- |
| 24 子块单批 | 1 批 24 逻辑行 + 向量 chunkId 对齐 | 绿 |
| 大文档拆批（150 子块） | `[64,64,22]` 受限批次、逻辑行不丢 | 绿 |
| 语义父块批先行（100 子块同段） | `[1,64,36]`；父块 chunkIndex=101；`parent.id < minChildId`；全部子块挂父键；向量 100 | 绿 |
| 生成键不完整 | 抛 `IllegalStateException`（含「切片批次主键回填不完整」），**不进入向量写** | 绿 |
| 批次中途失败 | 异常可见，已写批次被清理 | 绿 |
| embedding 失败 | 清理已写切片 | 绿 |
| Qdrant upsert 失败 | 清理切片与向量 | 绿 |
| ingest 失败后重试 | 重试成功 | 绿 |
| reingest 失败后清理并重试 | 重试成功 | 绿 |
| 清理子操作自身失败 | 仍标 FAILED、原始异常仍上抛，**不误报成功** | 绿 |

普通 ingest 与 reingest 双路径均覆盖。原文件保留、FAILED 标记、物理清切片与向量删除沿用既有「分别尝试 + 失败告警」语义；清理失败不假称零残留，也不误报成功。写授权、审计与衍生问题触发时序未改。

实测：`DocumentChunkBatchWriteTest` 10/10 绿、`DocumentIngestionServiceTest` 10/10 绿。

---

## 8. 实现边界（步骤 3）

- 修改范围仅限共享切片持久化 `DocumentIngestionSupport#persistChunks` 及其窄协作（`DocumentVectorChunkMapper#insertBatch`）。
- 批次上限 `PERSIST_BATCH_SIZE = 64`，为**有界**常量；未新增无界 SQL 参数或批处理缓冲。
- 父块与子块**分两遍**、各自受限批次：父块先落库取得自增 ID，子块第二遍才挂 `parent_chunk_id`，保证 `parent.id < child.id`。
- 未引入显式事务：单条多行 INSERT 自身即原子；事务不跨文件 I/O、解析、embedding、Qdrant 外呼、审计或异步旁路，不长期占用连接。
- 保持不变的语义：`chunkIndex`、文本、哈希、锚点、CHILD/PARENT 角色、父子分组、返回顺序、逻辑行数。
- 逐轮 `ingest-state` 前后测均为 `chunks 44→44`、`files 4→4`，无残留。

---

## 9. 测试与门禁原文（步骤 7 汇总）

定向单测（无 Docker）：

```
[INFO] Tests run: 16, Failures: 0, Errors: 0, Skipped: 0 -- com.slz.crm.quality.HotpathConcurrencyAttributionGuardTest
[INFO] Tests run:  6, Failures: 0, Errors: 0, Skipped: 0 -- com.slz.crm.quality.RepresentativeHotpathBenchmarkGuardTest
[INFO] Tests run:  2, Failures: 0, Errors: 0, Skipped: 0 -- com.slz.crm.quality.RequestHotpathBaselineTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- com.slz.crm.unit.knowledge.document.DocumentChunkBatchWriteTest
[INFO] Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -- com.slz.crm.unit.knowledge.document.DocumentIngestionServiceTest
[INFO] Tests run: 44, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

可观测调用形状断言（`RepresentativeHotpathBenchmark#assertIngestTotals`，未删未放宽）：

- `INSERT_CHILD_CALLS == samples * 24`（子块**逻辑行**）
- `INSERT_PARENT_CALLS == 0`（父块逻辑行）
- `INSERT_BATCH_CALLS == samples * 1`（**可观测**批次写库调用；物理执行/往返/commit 不可观测）
- `SQL_CALLS == samples * 4`（**可观测** Mapper 调用）
- `EMBED_CALLS == samples * 24`、`QDRANT_UPSERT_CALLS == samples`、`AUTH_SQL_CALLS == samples * 1`

其余（默认 merge-gate、`git diff --check`、完整 `mvn test`）见终态汇报的逐项「已跑/未跑」区分。

---

## 10. 未跑项 / 保持未知

- 未跑：默认 merge-gate 不含的 failsafe `**/*IT.java` 全量（除本案新增的 `ChunkBatchInsertGeneratedKeyIT` 已单独实跑 4/4）；真实 LLM/embedding/vision；生产 reingest；任何生产费用开关。
- 保持未知：真实模型 RTT、真实费用、生产环境收益、物理数据库执行次数/网络往返/commit 次数、真实连接池行为。