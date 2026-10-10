# tasks：wire-llm-call-timeout（卡 P-s）

> 写集红线：仅 `ModelProviderImpl`（必要时 +`CompatibleModeSupport`）、新建/扩展的 `ModelProviderImpl` 单测、`scripts/test-baseline.txt`（仅测试数变化时）、本文件。零真实模型调用，全部数字自跑实测，禁止照抄卡面预期值。

## 1. 阶段一：开工准备
- [x] 1.1 从 `master@ba881f8` 检出 `feature/wire-llm-call-timeout`，登记工作树纯净（仅受保护未跟踪项）——实测 HEAD==master==ba881f8（ba881f8 即卡 P-r 合并点），`git diff --name-only HEAD` 为空，未跟踪项均为受保护目录（openspec/changes、work/ 等）
- [x] 1.2 通读 `work/task-card-llm-call-timeout.md` 与本三件套，确认写集与行为红线

## 2. 阶段二：实现路径核实
- [x] 2.1 读 `CompatibleModeSupport` client 构造与 Spring AI ChatModel/ChatOptions 超时能力，判定路径 A（JDK 有界等待）/ 路径 B（原生超时）可行性——**核实结论：任务卡"死配置/零消费点"前提已过时**。卡 G（`9582829`，已在 ba881f8 之前合入）已把 `timeout-seconds`/`connect-timeout-seconds` 接到 HTTP 层两处：档1 `CompatibleModeSupport#buildCompatibleChatModel` 的 RestClient（chat 兼容回退 + vision 路）、档2 `AiModelRestClientCustomizer` 容器 `RestClient.Builder`（覆盖 dashscope 原生 chat/embed），并有 `ModelTimeoutKnobWiringGuardTest` 永久锁。剩余真实缺口 = 调用方侧 SLA：HTTP 读超时可被"慢滴"响应逐字节重置绕过，SDK 内非 HTTP 环节不受 HTTP 层约束。另核实 `AsyncExecutorGovernanceGuardTest` 强制异步提交显式传受治理池；`llmAuxTaskExecutor`（core2/max4/queue4/abort）已被检索三消费方外层占用，provider 内复用会同池嵌套自争用
- [x] 2.2 选定路径与理由登记于 §0

## 3. 阶段三：实施
- [x] 3.1 红测试先行（超时快速失败 / `timeout=0` 不限时 / 阈值内正常调用逐字段等价 / chat+vision+embed 覆盖），未改主代码基线上实跑贴红——新建 `ModelProviderImplTimeoutTest`（10 用例，纯 Mockito + 类级 `@Timeout(10)` 兜底）；未改主代码实跑（2026-10-07 14:31 本机）：`Tests run: 10, Failures: 0, Errors: 4`——chat/vision/vision-options/embed 四条挂死用例被 `@Timeout` 击杀判红（基线无超时实证），`timeout=0`、正常等价、异常穿透三条语义锁 6 用例基线即绿
- [x] 3.2 实现转绿，中文 Javadoc 标注「wire-llm-call-timeout 任务 3」——`callWithTimeout` 背锅护栏 + `platform-model-call-*` 虚拟线程 per-task 执行器 + `@PreDestroy` 关停；四调用点（chat / embed / vision / vision(options)）包裹；定向复跑 10/10 绿且 `CompatibleModeSupportTimeoutTest` 5/5 绿（HTTP 断口保留）

## 4. 阶段四：门禁验证
- [x] 4.1 全量 `mvn -B -ntp test` 只增不减——**948 全绿**（938 基线 + 10 新增，0 Failures / 0 Errors / 0 Skipped，BUILD SUCCESS）
- [x] 4.2 四静态门禁（pmd / spotbugs / checkstyle / spotless）0 违规——checkstyle 0、spotbugs 通过、pmd 通过、spotless 0 changes；过程踩两处新增违规已修：`OnlyOneReturn`（护栏改单出口）、`NcssCount` 类 151>150（护栏去 MDC/UserContext 装饰器，理由见主代码 Javadoc）
- [x] 4.3 `check-test-baseline.sh`（新增测试则 `--update` 真实写回）——`--update` 前检查 rc=0（下限口径：948≥938 本就通过）；`--update` 从本轮真实全量运行写回：surefire.reports 165→**166**、surefire.tests 938→**948**、skipped 0；failsafe 24/83/6 不变；写回后复验 rc=0
- [x] 4.4 三守卫（`check-line-endings lf` / `check-write-set ba881f8` / `check-dirty`）+ 136 条门禁自测全绿——自测实测：agent-helper-selftest **35**、check-test-baseline-selftest **101**（两套合计即卡面所称 136），另 merge-gate-selftest **87**（加分覆盖），三套 rc=0 全绿；三守卫结果见 §0 git 拓扑后实测记录

## 5. 阶段五：提交与合并
- [x] 5.1 本文件勾选与执行留痕（选定路径、红测试输出位置、门禁数字、git 拓扑、未跑项）
- [x] 5.2 2-3 笔提交（perf 实现+测试 / chore 基线（若有）/ docs tasks 留痕）→ 切回 master 真 `--no-ff` 合并（禁 push，分支保留）→ 严格停步回报

## 0. 执行记录（子 agent 填写）
- **分支与基线**：`feature/wire-llm-call-timeout` 自 `master@ba881f8` 检出；检出时工作树 tracked 零修改。
- **选定路径与理由**：**路径 A（JDK 有界等待）修正版**。① 路径 B（原生 HTTP 超时）已被卡 G 在基线前完成（双档接线 + 永久守卫），无剩余"原生"接线点；② 剩余缺口是调用方侧背锅护栏（慢滴响应/SDK 非 HTTP 环节盲区），路径 A 恰好补这层；③ **机制修正**：卡面 `CompletableFuture.cancel(true)` 实际不中断执行线程（JDK 文档明示 `mayInterruptIfRunning` 无效），改用 `ExecutorService.submit`（FutureTask）+ `cancel(true)` 真中断，虚拟线程执行器使阻塞 socket 读对中断真实响应；执行器不复用 `llmAuxTaskExecutor`（避免同池嵌套自争用）、不在 `PlatformAsyncConfig` 加 bean（写集外），取 `ModelProviderImpl` 私有虚拟线程池（每任务一线程，量级天然受调用方约束，无队列饱和与拒绝放大）；④ **护栏预算 = 配置值 + 5s 宽限**，刻意落在同旋钮值的 HTTP 读超时之后不抢断口——首次全量实测（护栏与 HTTP 同值 1s）曾令卡 G 回归锁 `CompatibleModeSupportTimeoutTest.compatibleChatModelChatHonoursConfiguredReadTimeout` 因根因断言 `HttpTimeoutException` 失败而红（该文件写集外不可改），改为背锅层后 5/5 绿；⑤ `timeout<=0` 直调不包裹，"不限时"语义与历史版本逐字节一致；零重试，超时以 `IllegalStateException(cause=TimeoutException)` 快速失败上抛，六消费方宽 catch 降级路径零改动。
- **红测试输出位置**：`src/test/java/com/slz/crm/platform/model/ModelProviderImplTimeoutTest.java`；未改主代码基线实跑输出 `Tests run: 10, Failures: 0, Errors: 4`（2026-10-07 14:31 本机 surefire），四红为 chat/vision/vision-options/embed 挂死用例被类级 `@Timeout(10)` 击杀。
- **门禁实测数字**：全量 `mvn -B -ntp test` = **948** 全绿（0F/0E/0S）；四静态门禁 = checkstyle **0** / spotbugs **0** / pmd **0** / spotless **0**；基线 `--update` 写回 surefire **166/948/0**（failsafe 24/83/6 不变）；三守卫实测：`check-line-endings lf` 两写集 Java 文件 **LINE_ENDINGS_OK**、`check-write-set ba881f8`（5 文件声明集，合并前实测）**通过**、`check-dirty` **CLEAN**；门禁自测 = **35 + 101 + 87 全绿 rc=0**（前两套合计即卡面 136）。
- **git 拓扑**：`ba881f8 → feature/wire-llm-call-timeout（3 笔：perf 实现+测试 / chore 基线 / docs tasks 留痕）→ master --no-ff 合并`；未 push，分支保留。
- **未跑项与假设**：未跑 failsafe IT（Testcontainers 系列需本地 Docker；`DASHSCOPE_API_KEY` 保持置空，零真实模型调用）、未做真实 DashScope/vLLM 外呼与生产验证。假设一：卡 G 档2 的容器 `RestClient.Builder` 定制确实覆盖 dashscope 原生 chat/embed 的 HTTP 层超时（依据 `AiModelRestClientCustomizer` Javadoc 生效顺序论证 + `ModelTimeoutKnobWiringGuardTest` 强制双接线点）；假设二：5s 宽限足够覆盖 HTTP 客户端读超时触发开销（`CompatibleModeSupportTimeoutTest` 类注释实测锚点 ≤0.3s，宽限与触发开销差 ≥4.7s）。遗留：若未来把护栏下沉为独立组件或需要在模型客户端日志内携带 trace 身份，需另立任务重估类 NCSS 预算（pmd-rules.xml 150）。
