# 任务清单 · 单副本部署契约硬化 (harden-single-replica-contract)

<!-- id: harden-single-replica-contract -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 编排与环境部署契约硬化

- [x] 1.1 `deploy/docker-compose.yml` 硬化：`app` 服务显式声明 `deploy: replicas: 1` 与 `mode: replicated`；环境变量追加 `PLATFORM_ARCHITECTURE_SINGLE_REPLICA: "true"`；`.env.example` 补充对应配置与注释说明。
      依据：deploy/docker-compose.yml app 服务新增 `deploy: mode: replicated / replicas: 1` 块（含单副本契约注释），environment 注入 `PLATFORM_ARCHITECTURE_SINGLE_REPLICA: "true"`；`.env.example` 新增"部署架构假设（单副本单体）"段；守卫新用例 `dockerComposePinsSingleReplicaDeployment` 将三处声明全部钉住。
- [x] 1.2 `deploy/README.md` 文档加固：增补《单副本单体部署契约与进程内状态组件清单》权威说明，逐项列明 8 大进程内状态组件与 6 大定时任务的单副本依赖。
      依据：deploy/README.md 新增权威指引章节，含 8 大组件表（组件/进程内状态/多副本后果）与 6 大 @Scheduled 调度表（触发方式/多副本后果），并指明 compose `replicas: 1` 与 `--scale` 禁令。

## 任务组 2 · 单副本静态架构守卫全量扩容

- [x] 2.1 扩容 `SingleReplicaArchitectureAssumptionGuardTest` 组件登记表：从既有 3 个扩展至 8 大组件（`RequestQuotaService`, `AiChatStreamLifecycle`, `DynamicConfigCache`, `AiRateLimiter`, `DependencyResilienceExecutor`, `NumberGenerator`, `LoginAttemptService`, `TokenBlacklistService`），逐一核验进程内标记与单机假设声明。
      依据：SINGLE_MACHINE_COMPONENTS 3→8（新增 AiRateLimiter/DependencyResilienceExecutor/NumberGenerator/LoginAttemptService/TokenBlacklistService 五条登记）；后四者与 AiRateLimiter 源码内补/改"单副本假设"注释；LoginAttemptService 与 TokenBlacklistService 实际路径为 server/service/support/（任务卡写 impl/ 有误，按实测路径登记）。
- [x] 2.2 增加定时任务与编排断言：守卫测试中新增针对 6 大 `@Scheduled` 类单副本假设声明检查，以及 `deploy/docker-compose.yml` 必须包含 `replicas: 1` 约束的断言。
      依据：新增 `scheduledTaskClassesDeclareSingleReplicaAssumption`（6 调度类源码须含登记表内"单副本假设：无锁单机调度"声明，空白归一化比对）与 `dockerComposePinsSingleReplicaDeployment`（compose 须含 mode: replicated + replicas: 1 + 环境变量，.env.example 须含同名声明）；6 个调度类 Javadoc 均已补声明。

## 任务组 3 · NumberGenerator 健壮性加固与单测覆盖

- [x] 3.1 加固 `NumberGenerator` 时间戳哈希生成：使用 `String.format("%04X", (hash & 0xFFFF))` 防范长度不足 4 位的潜在越界，补充类及方法级单副本假设 Javadoc。
      依据：getTimestampHash() 改为 `String.format("%04X", hash & 0xFFFF)`（修复原先 toHexString 前导零被吞致 substring 越界的缺陷）；类 Javadoc 与方法 Javadoc 均补单副本单号递增假设声明。
- [x] 3.2 补齐 `NumberGeneratorTest` 单元测试：覆盖 `generatePaymentNo` 与 `generateInvoiceNo` 格式、并发单号唯一性、日期跨越序号重置等用例。
      依据：新建 src/test/java/com/slz/crm/common/untils/NumberGeneratorTest.java 共 6 用例（PAY/INV 格式分段断言、8 线程×200 并发唯一性+按日分组序号 1..k 连续、resetPaymentSequence/resetInvoiceSequence 重置回 0001），定向运行 6/6 绿。

## 任务组 4 · 门禁与基线对账

- [x] 4.1 全量测试通过，`mvn -B -ntp clean verify` 全绿后以 `bash scripts/check-test-baseline.sh --update` 单次写入最新基线（Surefire 用例数在 1628 基础上只增不减）。
      依据：先提交实现 9568a24 再跑 clean verify（BUILD SUCCESS，failsafe 118/0/0 + 7 真外发跳过），`check-test-baseline.sh --update` 写入 surefire.tests=1636（1628→+8：NumberGeneratorTest 6 + 守卫新用例 2，只增不减）、failsafe 118/7、source-revision=9568a24、无 -dirty；基线提交 8650e20。
- [x] 4.2 本地运行 `bash scripts/merge-gate.sh` 确保 8/8 全 PASS，还原 `frontend/typed-router.d.ts` 噪声，产出 `work/handoff-harden-single-replica-contract.md` 并停步待复核。
      依据：merge-gate.sh exit 0（"所有已执行的子门禁绿"，含 frontend-unit vitest 197、spotbugs 双射 12==12、pmd-baseline 实测 0==登记 0==pom 阈值 0、hook 转发器在位）；typed-router.d.ts 生成物噪声已 `git checkout --` 还原；handoff 已产出；停步待 owner 派发 B 档独立复核。
