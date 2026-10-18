# Handoff · harden-single-replica-contract（单副本部署契约硬化）

> 执行子 Agent 交付快照（2026-10-18）。分支 `feature/harden-single-replica-contract`，基点 master@25d5a9a；**未 merge、未 push，停步等待 B 档独立复核**。

## 1. 结论一览

- I-4 进程内状态横向扩容失效风险已按规格闭环：8 大进程内状态组件 + 6 大无锁 `@Scheduled` 调度类全部纳入静态守卫登记表并在源码内落声明；`deploy/docker-compose.yml` 显式锁定 `deploy.mode=replicated / replicas=1` 并注入 `PLATFORM_ARCHITECTURE_SINGLE_REPLICA`；`.env.example` 与 `deploy/README.md` 权威指引同步落地。
- `NumberGenerator.getTimestampHash()` 越界缺陷已修复（`String.format("%04X", hash & 0xFFFF)` 恒定 4 位大写十六进制），类/方法 Javadoc 补单副本单号递增假设；新增 `NumberGeneratorTest` 6 用例补齐覆盖。
- 提交三笔（全在 feature 分支，均为内联 ASCII `-m`）：`9568a24` feat（14 文件 +328/−13）、`8650e20` test(baseline)（台账 4 行）、docs 笔（本 handoff + tasks.md 勾选 + 规格目录 + 任务卡）。
- 门禁：靶向单测 24/24 绿（ProductionConfigurationGuardTest 12 + NumberGeneratorTest 6 + 守卫 6）；`pmd:check` + `spotbugs:check` 绿；全量 `clean verify` BUILD SUCCESS（failsafe 118/0/0，7 真外发 opt-in 跳过，Docker 实跑）；`merge-gate.sh` exit 0（"所有已执行的子门禁绿"）。
- 基线：surefire 报告 262→263、用例 1628→1636（+1 套件 +8 用例，只增不减），failsafe 31 报告/118 用例不变（skipped=7）；台账 `source-revision=9568a24` 无 `-dirty`（先 commit 后 `--update`）。

## 2. 写集 diff stat

```
deploy/docker-compose.yml                                          | app 服务 deploy 块（mode/replicas）+ 环境变量注入
.env.example                                                       | 新增部署架构假设段（PLATFORM_ARCHITECTURE_SINGLE_REPLICA=true）
deploy/README.md                                                   | 新增《单副本单体部署契约与进程内状态组件清单》权威指引（8+6 表）
src/main/java/com/slz/crm/common/untils/NumberGenerator.java       | getTimestampHash %04X 加固 + 类/方法单副本假设 Javadoc
src/test/java/com/slz/crm/common/untils/NumberGeneratorTest.java   | 新增 6 用例（格式/并发唯一+递增/重置）
src/test/java/com/slz/crm/platform/architecture/SingleReplicaArchitectureAssumptionGuardTest.java | 登记表 3→8、6 调度类断言、compose/env 断言（+107 行级改动）
src/main/.../{IngestionReplayScheduler,AiMemoryOrchestrator,AiPendingActionExpireTask,ChurnWarningScheduler,ContractWarningScheduler,CustomerPoolRecycleScheduler,AiRateLimiter,DependencyResilienceExecutor}.java | 类 Javadoc 补"单副本假设"声明（各 +2 行级）
scripts/test-baseline.txt                                          | 4 行（--update 真实写入）
openspec/changes/harden-single-replica-contract/{proposal,tasks}.md | 规格随卡入库，tasks.md 全勾 + 6 空格证据行
work/handoff-harden-single-replica-contract.md、work/task-card-harden-single-replica-contract.md | 交接与任务卡
```

红线逐项：零 DB 迁移（下一可用号仍为 V43）✓、零新增 Maven 依赖（无 Redis/ShedLock）✓、零破坏性接口变更（前端与 OpenAPI 零触碰）✓、`frontend/typed-router.d.ts` 已 checkout 恢复 ✓、无 push / 无 merge ✓。

## 3. 关键实现决策（复核关注点）

1. **两处组件实际路径与任务卡不符**：`LoginAttemptService`、`TokenBlacklistService` 实际位于 `server/service/support/`（任务卡写 `server/service/impl/`），守卫登记表按实测路径登记，源码内既有"契合单副本部署契约"注释保留。
2. **调度类声明断言做空白归一化**：`scheduledTaskClassesDeclareSingleReplicaAssumption` 将源码与登记声明先 `replaceAll("\\s+","")` 再比对——google-java-format 会对 Javadoc 重排换行，逐字比对会被格式化误杀；声明句子登记在守卫测试与 6 个调度类 Javadoc 中逐字一致。
3. **环境变量名按卡面字面落地**：`PLATFORM_ARCHITECTURE_SINGLE_REPLICA`（SINGLE 与 REPLICA 间带下划线）。运行期有效性说明：`ProductionConfigurationGuard` 经 `environment.getProperty("platform.architecture.single-replica")` 读取，Spring `SystemEnvironmentPropertySource` 会尝试"点与连字符→下划线"变体解析，该全下划线形态可被第 8 项校验读到；主声明源仍是两份 application*.yml 的 `single-replica: true`（守卫测试双钉，删任一即红）。
4. **哈希段改为低 16 位掩码**：原实现取 8 位十六进制的前 4 位（前导零被吞时 `substring(0,4)` 越界）；按卡面改为 `hash & 0xFFFF` + `%04X`。业务唯一性由"前缀+日期+4 位序号"承担，哈希段仅混淆；两处调用方（InvoiceInfoServiceImpl / PaymentRecordServiceImpl）均整号透传存储、无哈希段语义解析，全量 verify 绿佐证无行为回归。
5. **compose `deploy:` 块**：`mode: replicated` + `replicas: 1` 为编排层显式契约（compose v2 生效，叠加 `container_name` 结构性禁止 scale）；运行期真正防线是 `ProductionConfigurationGuard` 启动期 fail-fast 与静态守卫，三层各自独立。
6. **守卫 compose 断言同样做空白归一化**（`mode:replicated` / `replicas:1`），容忍 YAML 缩进/换行重排；`PLATFORM_ARCHITECTURE_SINGLE_REPLICA` 断言保持原文比对。

## 4. 残留观察（超出本卡规格，请 owner 定夺，未改动）

- `deploy.replicas` 只约束 compose 编排形态；若未来引入 K8s/Helm 等其他编排载体，需另行登记副本数契约（当前仓库无此类清单，不构成缺口）。
- PMD 7.9.0 对 `pmd-rules.xml` 打印 "RuleSet description is missing" 警告（不阻断、门禁绿），系既有状态非本卡引入。

## 5. 验证记录（可复跑）

```
mvn -B -ntp test -Dtest="NumberGeneratorTest,SingleReplicaArchitectureAssumptionGuardTest,ProductionConfigurationGuardTest"
    # Tests run: 24, Failures: 0, Errors: 0, Skipped: 0
mvn -B -ntp pmd:check spotbugs:check               # BUILD SUCCESS
mvn -B -ntp clean verify                           # BUILD SUCCESS；failsafe Tests run: 118, Failures: 0, Errors: 0, Skipped: 7
bash scripts/check-test-baseline.sh --update       # surefire.tests=1636 / failsafe 118/7，source-revision=9568a24，无 -dirty
bash scripts/merge-gate.sh                         # exit 0，"merge-gate 通过：所有已执行的子门禁绿"
                                                   # 截存段实测：frontend-unit vitest 197/197、bijection 12==12、pmd-baseline 0==0==0、hook 在位
git checkout -- frontend/typed-router.d.ts         # 还原 vitest 行尾噪声
```

注：merge-gate 后台输出经 `tail -40` 截存，前四门（unit/spotbugs/pmd/baseline）的逐行 PASS 未逐字留档，以脚本最终裁定行与 exit 0 为准；B 档复核复跑即可取得全量逐门记录。

## 6. 闭环终态记录（2026-10-18）

- **合并提交**：`b602122`（merge --no-ff feature/harden-single-replica-contract 入 master，3 笔全入主干）；
- **基线提交**：`22fad6b`（test(baseline): update clean baseline to b602122）；
- **远端 CI**：GitHub Actions Run `39091524290` 6/6 全绿（Frontend 197 / OpenAPI / fail-fast / static-analysis / thread-pool-metrics / build-and-gate 1636 单测 + 118 集成测试）；
- **测试基线**：Surefire 263 份报告 / 1636 用例 / 0 失败（+1 套件，+8 用例），Failsafe 31 份报告 / 118 用例 / 7 skipped，`source-revision=b602122`；
- **复核裁决**：B 档独立复核 🟢 GO（报告落盘 `work/archive/closed-cards/review-harden-single-replica-contract-b.md`），4 项 MINOR 记录在案不阻断，全链闭环合入主干。

