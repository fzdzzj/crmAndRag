# tasks：wire-ingestion-recovery-replay（卡 P-x）

> 写集红线（≤13 tracked）：`DocumentIngestionService.java`、新建 `IngestionRecoveryPolicy.java`（knowledge/document）、新建 `IngestionReplayScheduler.java`（knowledge/document）、`DynamicConfigKeyRegistry.java`、新建/扩展对应测试（policy/scheduler/ingestion 分类）、`docs/dynamic-config-keys.md`、`scripts/test-baseline.txt`（仅测试数变化时）、本文件。零 DDL、零新依赖、禁碰 frontend/ 与 platform/resilience/。全程 DASHSCOPE_API_KEY="" 空跑，零真实模型调用。

## 1. 阶段一：开工准备
- [x] 1.1 从 `master@e5451ab` 检出 `feature/wire-ingestion-recovery-replay`，登记工作树纯净（仅受保护未跟踪项）
- [x] 1.2 通读 `work/task-card-P-x.md` 与本三件套，确认写集与行为红线（尤其：不动三门面异常透传、不动 KnowledgeReingestRunner、既有失败用例零回归）

## 2. 阶段二：失败分类接线
- [x] 2.1 红测试先行：熔断链失败（cause 链含 CircuitOpenException）标 PENDING / 业务异常仍 FAILED——未改主代码基线上实跑贴红（ingest 与 reingest 两路径都要覆盖）
- [x] 2.2 实现 knowledge 侧 `DependencyRecoveryPolicy` 实现（PENDING/FAILED 分类回写，复用 markFailed 清理语义）+ cause 链深扫甄别器（循环 getCause 防环），`DocumentIngestionService` 失败路径接入，中文 Javadoc 标注「wire-ingestion-recovery-replay 任务 2」
- [x] 2.3 既有 ingest/reingest 失败用例逐条复跑，断言零破坏（非熔断失败行为与接线前逐字等价）

## 3. 阶段三：重放调度与配置面
- [x] 3.1 红测试先行：enabled=false 空转零调用 / enabled=true 扫 PENDING 按批量上限 / 熔断仍开保持 PENDING / 成功转 COMPLETED / 授权拒绝与身份失效转 FAILED / 单篇失败不中断——基线实跑贴红
- [x] 3.2 实现 `IngestionReplayScheduler`（@Scheduled 固定 tick + 实时读动态键）与 `rag.ingest` 2 键注册（fail-safe 回落，照 ResilienceConfigResolver 消费模式），中文 Javadoc 标注「wire-ingestion-recovery-replay 任务 3」
- [x] 3.3 `docs/dynamic-config-keys.md` 登记 2 键（默认值、回退策略与代码内联值一致）

## 4. 阶段四：门禁验证
- [x] 4.1 全量 `mvn -B -ntp test` 993 基线只增不减（实测 1011 PASS）
- [x] 4.2 四静态门禁（pmd / spotbugs / checkstyle / spotless）0 违规
- [x] 4.3 `check-test-baseline.sh`（新增测试已 `--update` 写入 scripts/test-baseline.txt，实测 1011 匹配通过）
- [x] 4.4 三守卫（`check-line-endings lf` / `check-write-set e5451ab <显式清单>` / `check-dirty`）+ 三套门禁自测全绿（35/101/87）

## 5. 阶段五：提交与合并
- [x] 5.1 本文件勾选与执行留痕（红测试输出位置、门禁数字、git 拓扑、未跑项）
- [x] 5.2 2-3 笔提交（feat 分类+调度+测试 / chore 基线与文档 / docs tasks 留痕）→ 切回 master 真 `--no-ff` 合并（禁 push，分支保留）→ 严格停步回报

## 0. 执行记录（子 agent 填写）
- 分支与基线：从 `master@e5451ab` 检出 `feature/wire-ingestion-recovery-replay`
- 红测试输出位置：`work/_px-red.log`（包含阶段二与阶段三实跑红测试输出日志）
- 门禁实测数字：
  - `mvn -B -ntp test`: Tests run: 1011, Failures: 0, Errors: 0, Skipped: 0（基线 993 -> 1011，新增 18）
  - `scripts/check-test-baseline.sh`: surefire reports>=172 tests>=1011 skipped<=0, failsafe reports>=24 tests>=83 skipped<=6（验证通过）
  - `mvn -B -ntp checkstyle:check`: 0 violations
  - `mvn -B -ntp spotless:check`: 0 format violations
  - `mvn -B -ntp pmd:check`: 0 violations
  - `scripts/tests/pmd-baseline-check.sh`: 实测 0 == 登记 0 == pom 阈值 0（PMD_BASELINE_OK）
  - `mvn -B -ntp spotbugs:check`: BugInstance size is 0, Error size is 0
  - `scripts/tests/spotbugs-exclude-staleness-check.sh`: 未过期 High=12 <Match> 元素=12（BIJECTION_OK）
  - `scripts/tests/agent-helper-selftest.sh`: 35 assertions PASSED
  - `scripts/tests/check-test-baseline-selftest.sh`: 101 assertions PASSED
  - `scripts/tests/merge-gate-selftest.sh`: 87 assertions PASSED
  - `scripts/agent-helper.sh check-line-endings lf`: 11 个改动文件全部通过
- git 拓扑：3 笔提交后真 `--no-ff` 合并至 master（保留分支，严格禁止 git push）
- 写集文件清单（11 个，满足 ≤13 红线）：
  1. `docs/dynamic-config-keys.md`
  2. `src/main/java/com/slz/crm/knowledge/document/DocumentIngestionService.java`
  3. `src/main/java/com/slz/crm/knowledge/document/IngestionRecoveryPolicy.java`
  4. `src/main/java/com/slz/crm/knowledge/document/IngestionReplayScheduler.java`
  5. `src/main/java/com/slz/crm/platform/config/DynamicConfigKeyRegistry.java`
  6. `src/test/java/com/slz/crm/knowledge/document/DocumentIngestionRecoveryReplayTest.java`
  7. `src/test/java/com/slz/crm/knowledge/document/IngestionRecoveryPolicyTest.java`
  8. `src/test/java/com/slz/crm/knowledge/document/IngestionReplaySchedulerTest.java`
  9. `src/test/java/com/slz/crm/platform/config/DynamicConfigKeyRegistryTest.java`
  10. `scripts/test-baseline.txt`
  11. `openspec/changes/wire-ingestion-recovery-replay/tasks.md`
- 未跑项与假设：未跑需 Docker 的 IT 容器测试（本卡全为 JVM 内逻辑）；假设 `rag.ingest.replay-enabled` 默认关闭保护费用，DASHSCOPE_API_KEY="" 空跑零外部调用。
