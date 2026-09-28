# 性能与并发基线（measure-perf-baseline）

> **性质**：只测不改热路径。本文件是后续调线程池 / 切分默认策略 / SSE 超时前的**仓库内数字锚点**。
> **禁止**：把本机微基准 ns 数字写进 `ci.yml` 阈值（跨机抖动）；本单不启真服务打负载、不外呼 DashScope、不加 JMH/新 Maven 依赖。
> **日期**：2026-09-16
> **机器**：Windows 11 / Intel Core Ultra 7 255HX（20 逻辑核）/ 约 16 GB RAM
> **JDK（Maven）**：Oracle JDK 21.0.9（`D:\develop1\jdk21`）
> **合入前 HEAD**：master @ `27a67ac`（surefire 基线 645）

---

## 1. 已有指标资产盘点（读代码，无流量时为空）

**重要**：下列 Micrometer timer/counter/gauge 在**没有真实流量**时不会产生有意义的 p95/队列深度样本。本单**不**启动应用打压测；运行期数字需另案用 actuator + 受控负载采集。

### 1.1 SSE / 工具（`AiChatMetrics`）

| 指标名 | 类型 | 维度（低基数） | 含义 |
|---|---|---|---|
| `ai.chat.stream.active` | Gauge | — | 当前活跃 SSE 流（读 `AiStreamRegistry.activeCount`） |
| `ai.chat.stream.heartbeats` | Counter | — | 向客户端发送心跳次数 |
| `ai.chat.stream.completed` | Counter | `model`, `fallback` | 流正常完成 |
| `ai.chat.stream.failed` | Counter | `model`, `fallback` | 主备失败或 SSE 发送失败 |
| `ai.chat.stream.cancelled` | Counter | `model`, `fallback` | 用户取消 / 替换 / 断开清理 |
| `ai.chat.stream.first-token` | Timer | `model`, `fallback` | 首 token 耗时（TTFT；仅标记出首片段后记一次） |
| `ai.chat.stream.duration` | Timer | `model`, `fallback`, `status` | 整段流时长（status=completed/failed/cancelled） |
| `ai.tool.call.total` | Counter | `tool`, `status` | 工具调用次数（status=success/failure） |
| `ai.tool.call.duration` | Timer | `tool`, `status` | 工具调用耗时 |

源码：`src/main/java/com/slz/crm/server/ai/AiChatMetrics.java`。

### 1.2 平台线程池（`PlatformAsyncConfig` + `TaskExecutorMetricsBinder`）

默认值来自 `@Value(...:default)`（`application.yml` **未**逐项写出 `platform.async.*`，以代码默认 + 可覆盖键为准）。

| Bean / 前缀 | core | max | queue | 拒绝策略 | 说明 |
|---|---:|---:|---:|---|---|
| `platform-batch-upload` | 2 | 4 | 100 | `discard-log` | 批量上传；满则丢弃打 warn，等恢复接管 |
| `platform-stream-chat` | 4 | 8 | 50 | `abort` | 流式问答旁路池；满则快速失败 |
| `platform-embedding` | 2 | 4 | 100 | `caller-runs` | 嵌入；满则调用者线程跑，可反压检索/入库线程 |
| `platform-document-parsing` | 2 | 4 | 100 | `abort` | 文档解析；满则失败交批量恢复 |
| `platform-memory-bypass` | 2 | 2 | 64 | `abort` | 记忆旁路底层池（固定 2） |
| `platform-derived-questions` | 1 | 2 | 64 | `discard-log` | 衍生问题旁路；丢弃=退化为普通块 |
| `platform-llm-aux` | 2 | 4 | 4 | `abort` | 检索侧 LLM 辅助路（HyDE/重排/压缩，卡 E）；满则快速失败走各自降级，队列刻意浅（调用方 3s 即放弃） |

拒绝统一包装：`async.task.rejected{executor,policy}` Counter，再委托原策略。

`TaskExecutorMetricsBinder`（`ApplicationRunner`）把容器内全部 `ThreadPoolTaskExecutor` 绑到 Micrometer `ExecutorServiceMetrics`（活跃线程、队列长度、完成任务、池规模等；metric 名=去掉尾 `-` 的 threadNamePrefix）。

源码：`src/main/java/com/slz/crm/platform/async/PlatformAsyncConfig.java`、`TaskExecutorMetricsBinder.java`。

### 1.3 助手线程池（`AiThreadPoolConfig` ← `crm.ai.thread-pool.*`）

配置见 `application.yml`：

| Bean | core | max | queue | 拒绝策略 | 用途 |
|---|---:|---:|---:|---|---|
| `aiChatExecutor` | 4 | 8 | 50 | `CallerRunsPolicy` | 主对话流（LLM，耗时长）；饱和反压 Tomcat 请求线程 |
| `aiTitleExecutor` | 2 | 4 | 100 | `DiscardPolicy` | 标题生成（可丢） |
| `aiAuditExecutor` | 2 | 4 | 200 | `DiscardOldestPolicy` | 审计入库（可丢最旧） |

源码：`src/main/java/com/slz/crm/server/ai/AiThreadPoolConfig.java`。

### 1.4 相关超时 / Actuator（只登记，本单不改）

| 键 | 默认 | 备注 |
|---|---|---|
| `crm.ai.llm-timeout-seconds` | 60 | LLM 调用超时 |
| `crm.ai.sse-timeout-seconds` | 300 | SSE 服务端超时（**本单禁止改**） |
| `crm.ai.heartbeat-interval-seconds` | 15 | SSE 心跳 |
| `crm.ai.heartbeat-pool-size` | 4 | 心跳调度池 |
| `platform.ai.model.timeout-seconds` | 60 | Provider 单次调用超时 |
| `platform.actuator.protected-enabled` | true | Actuator 保护总开关；metrics 需超管 |

---

## 2. 本机 ¥0 微基准（surefire，`PerfBaselineSmokeTest`）

夹具：`src/test/java/com/slz/crm/quality/PerfBaselineSmokeTest.java`。

- **切分**：`FixedChunkingStrategy`（320/40，与升级前等价；**本单不改实现**），中文重复文本 ~10KB / ~100KB。
- **对齐**：I-05 形短句 + 3 条 `SourceReference`，`CitationAligner.align` × 1e4。
- **拆句**：`ConstraintQuerySplitter.split("客户主体变更后重新签合同要满足条件")` × 1e4。
- 预热后多轮计时；stdout 打印 `PERF ...` 行；断言仅「跑完 / 块数>0 / 无异常」。

### 2.1 本机实测一行表（2026-09-16，JDK 21.0.9，Ultra 7 255HX）

| 场景 | 规模 | 结果 | ns/op（约） | 备注 |
|---|---|---|---:|---|
| Fixed 切分 | 10240 chars | **37** chunks | **8297** | 200 iters 平均；ns/char ≈ 0.81 |
| Fixed 切分 | 102400 chars | **366** chunks | **107352** | 50 iters 平均；ns/char ≈ 1.05 |
| CitationAligner | 1e4 次 I-05 形 | last citations=`[3]`（REMAP） | **21540** | total ≈ 215 ms / 1e4 |
| ConstraintQuerySplitter | 1e4 次 | routes=3 | **1376** | total ≈ 14 ms / 1e4 |

原始 stdout 样本：

```
PERF fixed-chunk 10KB chars=10240 chunks=37 iters=200 total_ns=1659300 ns_per_op=8296.5 ns_per_char=0.810
PERF fixed-chunk 100KB chars=102400 chunks=366 iters=50 total_ns=5367600 ns_per_op=107352.0 ns_per_char=1.048
PERF CitationAligner iters=10000 total_ns=215396000 ns_per_op=21539.6 last_citations=[3]
PERF ConstraintQuerySplitter iters=10000 total_ns=13759201 ns_per_op=1375.9 routes=3
```

**解读边界**：

- 这是进程内 CPU 微基准，**不是**端到端检索/SSE 延迟，也**不是** p95 门禁。
- 跨机/跨 JDK 抖动大；只作本机对照与回归时「数量级是否离谱」的参考。
- Fixed 切分相对廉价（10KB ~8µs、100KB ~0.1ms 量级）；Aligner 明显更重（~20µs/次），但仍远低于一次 LLM/嵌入 RTT。

复现：

```powershell
$env:DASHSCOPE_API_KEY=''
mvn -B -ntp -Dtest=PerfBaselineSmokeTest test
# 在 surefire 输出或 TEST-*.xml 的 system-out 中搜 PERF
```

---

## 3. 未测项（本单禁止假装有数）

下列项**尚未测量**。优化建议（例如「把 core 调到 16」）在未取得对应证据前一律无效。每项只给「下次怎么测」，不给调参处方。

### 3.1 同时 SSE 会话 vs `aiChatExecutor`（4–8 / q50 / CallerRuns）

- **风险假设**：会话数超过 max+queue 后 CallerRuns 会占用 Tomcat 工作线程，HTTP 接受能力下降，表现为全站变慢而非仅 AI 慢。
- **下次怎么测**：
  1. 受控环境启动应用，超管拉 `/actuator/metrics/ai.chat.stream.active` 与线程池 `executor` 指标（队列长度、活跃线程）。
  2. 用 N 路并发长 SSE（工具调用或慢模型）爬升 N，记录 TTFT p50/p95（`ai.chat.stream.first-token`）、总时长、是否出现请求线程堆积。
  3. 对照 `async.task.rejected`（若平台 stream 池 abort）与 Tomcat 线程 dump。

### 3.2 嵌入 `caller-runs` 堵住检索/入库线程

- **风险假设**：`platform-embedding` 饱和时，提交 embed 的检索或入库线程自己跑嵌入，拉长检索尾延迟。
- **下次怎么测**：批量入库或并发检索时观察 embedding 池 queue/active、检索耗时分布、`async.task.rejected{executor=platform-embedding}`（CallerRuns 仍会计拒绝后由调用者执行——以实际 binder/策略语义为准）、业务日志中的 embed 耗时。

### 3.3 解析池 `abort` 后批量上传失败恢复

- **风险假设**：`platform-document-parsing` Abort 后上传任务失败；依赖 discard-log 的 batch-upload 与状态机/恢复流程补救，若恢复不及时会积压「解析失败」文档。
- **下次怎么测**：构造解析队列打满（大文件/慢解析 mock），确认 abort 异常路径、文档状态机落点、恢复任务是否重入；看 `async.task.rejected{executor=platform-document-parsing|platform-batch-upload}` 与业务失败计数。

### 3.4 DashScope/模型 60s 超时 vs SSE 300s 尾延迟

- **风险假设**：单次 LLM 60s 超时可在 300s SSE 窗内多次重试/工具轮次，用户感知尾延迟可接近分钟级；TTFT 与总时长分布需分开看。
- **下次怎么测**：在**已授权**真模型环境看 `ai.chat.stream.first-token` / `duration` 直方图与工具 `ai.tool.call.duration`；刻意注入慢响应，确认超时、fallback、SSE 心跳仍存活。**禁止**在无授权时对 DashScope 打压测。

---

## 4. 与后续提案的边界

| 提案 | 关系 |
|---|---|
| **本单 measure-perf-baseline** | 只产出本文 + `PerfBaselineSmokeTest`；热路径零 diff |
| `add-paragraph-chunking` | 已可选用 `rag.chunking.strategy=paragraph`（同窗同重叠、段落界收刀）；**yml 默认仍 fixed**，本基线微基准数字仍对 fixed，勿拿 paragraph 切片数冒充 |
| 任意调 `crm.ai.thread-pool.*` / `platform.async.*` / `sse-timeout-seconds` | 必须先有 §3 对应运行期证据，再开变更单 |

### 热路径未改核对（合入时亲验）

```text
FixedChunkingStrategy      — 无 diff
AiThreadPoolConfig         — 无 diff
crm.ai.sse-timeout-seconds — 无 diff
crm.ai.thread-pool.*       — 无 diff
```

---

## 5. 修订记录

| 日期 | 说明 |
|---|---|
| 2026-09-16 | 首版：资产表 + 本机微基准 + 未测项；surefire +4（PerfBaselineSmokeTest） |
