# tasks：wire-circuit-per-dependency-override（卡 P-y）

> 写集红线（≤10 tracked）：`DynamicConfigKeyRegistry.java`、`ResilienceConfigResolver.java`、`DependencyResilienceExecutor.java`、对应测试（Registry 键断言条件项 / ResolverTest / ExecutorTest 扩展）、`docs/dynamic-config-keys.md`、`scripts/test-baseline.txt`（仅测试数变化时）、本文件。零 DDL、零新依赖、禁碰 frontend/。全程 DASHSCOPE_API_KEY="" 空跑，零真实模型调用。

## 1. 阶段一：开工准备
- [x] 1.1 从 `master@ed3c619` 检出 `feature/wire-circuit-per-dependency-override`，登记工作树纯净（仅受保护未跟踪项）
- [x] 1.2 通读 `work/task-card-P-y.md` 与本三件套，确认写集与行为红线（尤其：既有 executor/resolver 用例断言零修改、测试构造器签名不变、三门面与 P-x 产物零触碰）

## 2. 阶段二：键注册与级联解析
- [x] 2.1 红测试先行：级联三态（覆盖合法生效 / 缺失走全局 / 越界回落全局 / 全局越界回落默认）——未改主代码基线实跑贴红（work/_py-red.log / work/_py-red-raw.txt）
- [x] 2.2 Registry catalog 追加 10 平键（`platform.resilience.failure-threshold.<dep>` / `open-duration-ms.<dep>`，范围同全局，描述注明逐级回落），Resolver 新增带依赖名重载（覆盖键 get(key, type, null) → null/越界 → 全局键 → 越界/异常 → 默认；无参版委托 dependency=null 路径），中文 Javadoc 标注「wire-circuit-per-dependency-override 任务 2」
- [x] 2.3 `docs/dynamic-config-keys.md` 登记 10 键（默认值、范围、逐级回落语义与代码内联值一致）

## 3. 阶段三：Executor 接线
- [x] 3.1 红测试先行：per-dependency 独立（两依赖不同 threshold 各自开闸节奏）——基线实跑贴红
- [x] 3.2 Executor supplier 字段泛化为 `Function<String, Integer/Long>`：生产构造器传 `name -> resolver.resolveXxx(name)`，两个测试构造器签名不变内部包装恒定函数，`circuitOf` 改 `() -> resolver.apply(name)`，中文 Javadoc 标注「wire-circuit-per-dependency-override 任务 3」
- [x] 3.3 既有 executor 测试逐条复跑断言零破坏；生效语义用例（新写覆盖值即刻生效 / 在飞 OPEN 窗口不追溯 / 删覆盖键恢复全局）

## 4. 阶段四：门禁验证
- [x] 4.1 全量 `mvn -B -ntp test` 1011 基线只增不减（实测 1019，新增 8 用例）
- [x] 4.2 四静态门禁（pmd / spotbugs / checkstyle / spotless）0 违规
- [x] 4.3 `check-test-baseline.sh`（新增测试则 `--update` 真实写回，surefire.tests 1011 → 1019）
- [x] 4.4 三守卫（`check-line-endings lf` / `check-write-set ed3c619 <显式清单>` / `check-dirty`）+ 三套门禁自测全绿（35 + 101 + 87）

## 5. 阶段五：提交与合并
- [x] 5.1 本文件勾选与执行留痕（红测试输出位置、门禁数字、git 拓扑、未跑项）
- [x] 5.2 2-3 笔提交（feat 键注册+级联+executor 接线+测试 / chore 基线与文档 / docs tasks 留痕）→ 切回 master 真 `--no-ff` 合并（禁 push，分支保留）→ 严格停步回报

## 0. 执行记录（子 agent 填写）
- 分支与基线：master@ed3c619 → `feature/wire-circuit-per-dependency-override`；合回 master 真 --no-ff（禁 push，分支保留）
- 红测试输出位置：`work/_py-red.log`（红绿两阶段留痕）与 `work/_py-red-raw.txt`（65 处编译红：resolveFailureThreshold(String)/resolveOpenDurationMillis(String) 不存在）
- 门禁实测数字：全量 mvn test 1019（基线 1011，+8）0 失败 0 错误；checkstyle 0 违规；spotless clean；pmd 0（pmd-baseline OK 0==0）；spotbugs High 12 豁免双射 BIJECTION_OK；check-test-baseline --update surefire.tests 1019；三守卫 lf/写集/clean 通过；三套自测 35+101+87
- git 拓扑：见最终回报（合并节点 hash、分支拓扑）
- 未跑项与假设：failsafe IT（83 用例）沿用既有目标报告（本卡零改动该轨，未跑 --with-verify）；全程 DASHSCOPE_API_KEY 置空串零真实模型外呼；前端 Vitest 单元轨未执行（node_modules 存在判定按 merge-gate 降级处理）
