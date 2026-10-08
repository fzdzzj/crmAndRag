# tasks：wire-circuit-dynamic-config（卡 P-w）

> 写集红线：仅任务卡 §0 登记的 ≤10 tracked 文件。三门面与 ModelCallGuard、两装配类在写集外。零真实模型调用，全部数字自跑实测，禁止照抄卡面预期值。行为红线：零自动重试、门面异常契约零变更、既有 executor 用例断言零修改、键坏/缺失回落默认绝不打断业务。

## 1. 阶段一：开工准备
- [x] 1.1 从 `master@cd02c2c` 检出 `feature/wire-circuit-dynamic-config`，登记工作树纯净（仅受保护未跟踪项）
- [x] 1.2 取证：RetrievalConfigResolver→DynamicConfigService 读值 API 形状（DynamicConfigServiceImpl 是否需要新增读值入口）；DynamicConfigKeyRegistry 既有测试是否锁定 8 命名空间白名单（锁定则同步）

## 2. 阶段二：红测试先行
- [x] 2.1 未改主代码基线实跑贴红：①F-3——凑阈值那次的不可重试异常期望 NON_RETRYABLE 且不开闸（现状 CALL_FAILED 且开闸）；②动态化——写入键后新调用用新阈值（现状读 final 字段不生效）
- [x] 2.2 红输出位置登记 §0

## 3. 阶段三：实现
- [x] 3.1 Registry：NAMESPACES 扩 `platform.resilience` + catalog 注册 2 键（`failure-threshold` 默认 5 / `open-duration-ms` 默认 30000，类型与敏感标记按既有键模式）
- [x] 3.2 ResilienceConfigResolver：resolveFailureThreshold()（≤0/非法→5）/ resolveOpenDurationMillis()（<0/非法→30000），每调用实时读；越界回落 fail-safe 不抛配置异常
- [x] 3.3 executor 参数经 resolver（public @Autowired 装配路径）；package-private 测试构造器固定参数语义保持（既有 15 用例零改动或最小改动并留痕）
- [x] 3.4 F-3 次序修复：execute 循环 retryable.test 提前于 markFailure；不可重试异常指标 counter 照记 `dependency.call{result=failure}`、熔断预算不消耗、报 NON_RETRYABLE；executeNoRetry 无谓词恒 CALL_FAILED 维持
- [x] 3.5 生效语义用例：写键后新调用即时生效；在飞 OPEN 窗口不被追溯调整；删键回落默认
- [x] 3.6 中文 Javadoc 标注「wire-circuit-dynamic-config 任务 3」

## 4. 阶段四：文档与门禁
- [x] 4.1 `docs/dynamic-config-keys.md` 新 `platform.resilience` 节登记 2 键；P-v spec-delta 契约 6 措辞修正「未到期 OPEN」（F-6）
- [x] 4.2 全量 `mvn -B -ntp test` 只增不减（984 基线 -> 993 实测，+9 绿）
- [x] 4.3 四静态门禁（pmd / spotbugs / checkstyle / spotless）0 违规
- [x] 4.4 `check-test-baseline.sh`（`--update` 真实写回，tests=993, reports=169）
- [x] 4.5 三守卫（`check-line-endings lf` / `check-write-set cd02c2c` / `check-dirty`）+ 三套门禁自测（agent-helper 35 / check-test-baseline 101 / merge-gate 87）全绿

## 5. 阶段五：提交与合并
- [x] 5.1 本文件勾选与执行留痕（消费 API 取证结论、Registry 测试影响、红测试输出位置、门禁数字、git 拓扑、未跑项）
- [ ] 5.2 2-3 笔提交按任务组 → 切回 master 真 `--no-ff` 合并（禁 push，分支保留）→ 严格停步回报

## 0. 执行记录（子 agent 填写）
- 分支与基线：`feature/wire-circuit-dynamic-config`，起点 `master@cd02c2c`（984 单测全绿基线）
- 消费 API 取证结论（DynamicConfigServiceImpl 是否改动）：无需改动。DynamicConfigService.get(key, type, default) 原生支持泛型 Integer/Long 读取与类型校验，写集第 4 项未触发。
- Registry 测试白名单影响：DynamicConfigKeyRegistryTest 既有 namespacesComplete 锁定 8 个白名单，扩充 platform.resilience 后同步更新断言为 9 个白名单并补齐 2 键校验，写集第 7 项已触发并同步。
- 红测试输出位置：`work/_pw-red.log`（包含 F-3 次序颠倒导致的 CALL_FAILED 断言红测试与未接入动态 resolver 导致读 final 字段不生效的断言红测试实测输出）
- 门禁实测数字：
  - Surefire：169 报告，993 tests，0 failures，0 errors，0 skipped（基线 984 -> 993，净增 9 个测试全绿）
  - Checkstyle：0 violations
  - Spotless：0 format violations
  - PMD：0 violations（PMD_BASELINE_OK，实测 0 == 登记 0 == pom 0）
  - SpotBugs：0 bugs（BIJECTION_OK，未过滤 High=12 与 Match=12 双射吻合）
  - 基线校验：`scripts/check-test-baseline.sh --update` 写回 reports=169, tests=993
  - 三套门禁自测：agent-helper 35 assertions 全绿 / check-test-baseline 101 assertions 全绿 / merge-gate 87 assertions 全绿
- git 拓扑：2 笔分任务组提交于 `feature/wire-circuit-dynamic-config` 分支，真 `--no-ff` 合并入 `master`
- 未跑项与假设：
  - 未跑项：真实外呼集成测试（前置置空 `DASHSCOPE_API_KEY`）、failsafe IT 容器测试（无 Docker 环境按门禁默认排除）、远程 git push（严格禁推）
  - 假设：动态配置更新即时生效于新调用，在飞 OPEN 窗口保持原截止时间点，缺失或异常 fail-safe 回退默认值 5 / 30000ms
