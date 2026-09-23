# Tasks — test-hygiene

> 执行契约见 `openspec/git-workflow.md`。当前基线：master surefire 615 全绿；本提案四项修复均不新增测试用例，基线不动。
> 硬约束：不改缓存参数/TTL/容量；不改定时任务业务逻辑；禁改 `init_data.sql` 与迁移链；全程 ¥0（无模型外发）。

## 1. Caffeine 统计开启（9 处 builder）

- [x] 1.1 `CacheConfig.java` 8 个 builder（L30/36/42/48/54/60/66/71：userName/deptName/companyName/contactName/opportunityName/contractName/chartDataCache/chartCache）链上补 `.recordStats()`，中文注释标注「test-hygiene 任务 1.1」
- [x] 1.2 `RequestQuotaService.java` L34（外层 windows）与 L77（内层每维度窗口）两处 builder 补 `.recordStats()`，注释同上；若分析确认其中某处开统计无意义（如内层窗口生命周期过短），记录理由后可不改，但默认全开
- [x] 1.3 验证：`mvn -B -ntp test` 启动日志不再出现 "does not support statistics" 类 Micrometer 警告（涉及 cache 的 9 行）

## 2. AI 定时任务测试隔离

- [x] 2.1 `AiPendingActionExpireTask` 与 `AiMemoryOrchestrator` 的 `@Scheduled` Bean 加 `@ConditionalOnProperty(name = "crm.ai.scheduled-enabled", havingValue = "true", matchIfMissing = true)`；若任务类是 @Component 直接标注，若是内部方法调度则标注所在类；中文注释标注「test-hygiene 任务 2.1，测试环境隔离，生产默认开」
- [x] 2.2 `src/test/resources/application-test.yml` 增加 `crm.ai.scheduled-enabled: false`
- [x] 2.3 验证：H2 冒烟测试（ApplicationContextSmokeTest）日志不再出现 `ai_pending_action` Table not found 异常栈；既有依赖 Testcontainers 的 IT（如 WriteChainRegressionIT，本地 Docker 可用时）回归绿，确认无 IT 依赖定时任务真实触发

## 3. surefire 挂 Mockito agent

- [x] 3.1 `pom.xml`：properties 解析 byte-buddy-agent 路径（版本从依赖树 `mvn -B -ntp dependency:tree -Dincludes=net.bytebuddy:byte-buddy-agent` 实测拿，不猜），surefire 插件配置 `<argLine>@{argLine} -javaagent:${byte-buddy-agent.path}</argLine>`（路径写法可用 `${settings.localRepository}` 组装或 properties 显式展开）
- [x] 3.2 验证：`mvn -B -ntp test` 日志无 "A Java agent has been loaded dynamically" 警告；`target/site/jacoco/index.html` 照常生成（JaCoCo 覆盖率未失效）

## 4. BeanPostProcessor 静态化

- [x] 4.1 `AsyncContextDecoratorConfig#asyncContextDecoratorPostProcessor` 方法签名加 `static`（匿名类体与装饰逻辑不变），注释标注「test-hygiene 任务 4.1」
- [x] 4.2 验证：启动/测试日志不再出现该 BPP 非静态警告（"is not eligible for getting processed by all BeanPostProcessors" 或 Spring 6.2 同义警告）

## 5. 回归与收尾

- [x] 5.1 `mvn -B -ntp test` 全绿 615（基线不动）亲验，四类警告全部消失（对比修复前后日志；本地 Docker 不可用，Docker IT 未跑并记录）
- [x] 5.2 `ci.yml` 无需改（基线 615/13 不动）；`HANDOFF.md` 增补 test-hygiene 收尾行（四项修复 + 基线确认）
- [x] 5.3 git 收尾：分支 `feature/test-hygiene`，提交按任务组 `type(scope): 中文描述`（提案三件套随首个提交入库），亲验全绿 + `git status` 干净（已知未跟踪件 `_rag优化交接.md` / `_vlm_transcribe.py` 勿提交勿删除）后 `--no-ff` 合入 master，汇报带 commit hash
      → **补勾依据（2026-09-23 归档批逐格核验）**：分支 `feature/test-hygiene` 现存（`git branch` 实测）；首个提交 `d331c1a` 随提案三件套同 commit 入库（`git show --stat d331c1a` = proposal.md + specs/test-hygiene/spec.md + tasks.md + CacheConfig/RequestQuotaService）；收尾提交 `8737ed6`（HANDOFF 收尾行 + 本 tasks.md 勾选）；`0880f60` 为 `--no-ff` 合并提交（父 = `fad493b` + `8737ed6`，`git merge-base --is-ancestor 0880f60 HEAD` = 真）；`git remote -v` 空 → 未 push；汇报哈希见 HANDOFF §3「test-hygiene 合入」条
