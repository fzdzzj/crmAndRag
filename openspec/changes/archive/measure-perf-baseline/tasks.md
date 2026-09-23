# Tasks — measure-perf-baseline

> 先于 add-paragraph-chunking。master 以当前 HEAD 为准。surefire **645→649**。零外呼。

## 0. 执行记录
- 微基准本机（2026-09-16，JDK 21.0.9，Ultra 7 255HX）：fixed 10KB 37chunks ~8297 ns/op；100KB 366chunks ~107352 ns/op；Aligner ~21540 ns/op；Splitter ~1376 ns/op。详见 docs/perf-baseline.md。
- surefire 合计：**649**（Failures 0 / Errors 0 / Skipped 0），`$env:DASHSCOPE_API_KEY='' ; mvn -B -ntp test` 亲验。

## 1. 盘点 + 微基准（¥0）
- [x] 1.1 读 AiChatMetrics / PlatformAsyncConfig / AiThreadPoolConfig / application.yml，写入 `docs/perf-baseline.md` 资产表
- [x] 1.2 `PerfBaselineSmokeTest`：fixed 切分 10KB/100KB、Aligner、Splitter；stdout 打印 ns
- [x] 1.3 文档抄入本机表；未测项专节（SSE 并发、embed CallerRuns、解析 abort、超时尾延迟）

## 2. CI
- [x] 2.1 `mvn -B -ntp test` 全绿，实测改 ci.yml 三处。微基准数字**不要**写进 ci 阈值

## 3. 收尾
- [x] 3.1 HANDOFF 链到 perf-baseline.md；写明切分提案尚未改默认策略
- [x] 3.2 `feature/measure-perf-baseline`；`--no-ff`；不 push；三未跟踪件勿动
