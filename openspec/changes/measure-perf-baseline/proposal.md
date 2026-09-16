# 提案：性能与并发基线（只测不改热路径）

> 变更 ID：`measure-perf-baseline` ｜ 能力域：`platform-perf` ｜ 序列：架构/效率优化的测量先行
> **本单禁止改检索/切分/SSE 热路径、禁止加 Maven 依赖、禁止 DashScope 外呼。** 必须先于 `add-paragraph-chunking` 合入，避免切分改动污染首测。

## Why

用户要架构/效率/响应/并发/极端情况优化。当前没有 p95、队列深度、拒绝次数的仓库内基线；Micrometer 其实已经有一部分（`AiChatMetrics`、`TaskExecutorMetricsBinder`），但没有一份可复现的数字文档。没有数字就改线程池或切分，属于空转。

## What Changes

产出 `docs/perf-baseline.md` + ¥0 单测夹具，不改生产行为。

### 1. 资产盘点（读代码写入文档）
| 面 | 已有证据位置 |
|---|---|
| SSE TTFT / 总时长 | `AiChatMetrics`：`ai.chat.stream.first-token` / `duration` / `active` |
| 工具调用 | `ai.tool.call.duration` |
| 平台线程池 | `PlatformAsyncConfig`：batch-upload / stream-chat / embedding / document-parsing；拒绝策略 abort/caller-runs/discard-log |
| 助手线程池 | `AiThreadPoolConfig`：chat 4–8 q50 CallerRuns；title Discard；audit DiscardOldest |
| 配置 | `application.yml` `crm.ai.thread-pool.*`；`platform.async.*` 默认 core/max/queue |
| Actuator | `platform.actuator.protected-enabled` 默认 true |

文档必须写清：**这些 timer 在没流量时是空的**；本单不启真服务打负载。

### 2. ¥0 可复现微基准（surefire，假嵌入）
`src/test/java/com/slz/crm/quality/PerfBaselineSmokeTest.java`（或 `unit/perf`）：
- 切分：对 ~10KB / ~100KB 中文重复文本跑 `FixedChunkingStrategy`，记录 ns/op、块数（不要用真 PDF/VLM）
- 对齐：对现成 I-05 形短句跑 `CitationAligner.align` 1e4 次
- 拆句：`ConstraintQuerySplitter.split` 1e4 次
- 输出打印到 stdout；把**本机实测一行表**抄进 `docs/perf-baseline.md`（注明机器/JDK，不当事后跨机门禁）

不把微基准数字写进 ci.yml（跨机抖动）。surefire 只断言：跑完、块数>0、无异常。

### 3. 并发与极端情况（纸面，不造压测框架）
文档专节列出**尚未测量、禁止本单假装有数**的项：
- 同时 SSE 会话数 vs `aiChatExecutor` 队列 50 + CallerRuns（会反压 Tomcat 线程）
- 嵌入 CallerRuns 导致检索线程被 embed 堵住
- 解析池 abort 后批量上传失败恢复
- DashScope 超时 60s 与 SSE 300s 的尾延迟

每一项给「下次怎么测」（actuator + 日志），不给「应该把 core 调到 16」这种无依据建议。

## Non-Goals
- 不改池大小、不改切分、不加 JMH、不打真实 LLM、不暴露 actuator 到公网。

## 验收
- `docs/perf-baseline.md` 存在且含资产表 + 本机微基准表 + 「未测项」
- surefire 新测绿；ci 基线按实测上调
