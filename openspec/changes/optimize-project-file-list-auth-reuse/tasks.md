# 任务分解与留痕：项目文件列表行级鉴权同请求复用（optimize-project-file-list-auth-reuse）

## 0. 执行记录
- 执行分支：`feature/optimize-project-file-list-auth-reuse`
- 起点 commit：`master@7d64b69`
- 实施人员：子 agent
- 终审复验：主 agent
- 新增用例：`src/test/java/com/slz/crm/unit/service/ProjectFileServiceImplTest.java` 共 8 条（纯 Mockito）；红阶段实测 8 跑 6 败（恰 N 次断言在旧形状 N+k 上全红），实现后 8 跑 0 败 0 错误 0 跳过
- Surefire 全量总数：实测 928（920 → 928，+8，只增不减；Failures=0 Errors=0 Skipped=0）
- 静态分析四门禁：`mvn -B -ntp pmd:check spotbugs:check checkstyle:check spotless:check` 实测 BUILD SUCCESS（exit=0），checkstyle 显式 `You have 0 Checkstyle violations.`
- 回归基线：`bash scripts/check-test-baseline.sh --update` 真实写回 `surefire.reports=165`（164→165）、`surefire.tests=928`（920→928）、`surefire.skipped=0`；failsafe 维持 24/83/6；写回后复验通过
- agent-helper 三守卫：提交后复验 `check-line-endings lf` 4 文件全 OK、`check-write-set 7d64b69` 写集内 4 文件全中、`check-dirty` CLEAN；门禁自测 `check-test-baseline-selftest.sh` 101 条 + `agent-helper-selftest.sh` 35 条 = 136 条全绿
- 受控写集实交：`ProjectFileServiceImpl.java` + 新增 `ProjectFileServiceImplTest.java` + `scripts/test-baseline.txt` + 本 `tasks.md`（共 4 文件）
- 实现说明：5 个列表方法收敛到统一「判定一次 + 已知可读转换」私有链（`toReadableVOs` + `toKnownReadableVO`），保留行的下载令牌签发直接复用同请求判定结果，单请求判定调用 N+k → N；同时移除列表路径第二次判定与「拦截越权项目文件下载链接签发」warn 分支（列表路径内不可达）；复用严格限于单请求内，零跨请求角色/权限缓存，`PublicAttachmentController` 下载端逐次独立复核保持原状。
- 未跑项（默认门禁外）：failsafe IT（`mvn verify`，需 Docker）、opt-in 请求级基准（可选阶段 6）、真实模型外部调用、生产环境验证。

## 1. 任务分解清单

### 阶段 1：分支检出与基线准备
- [x] 1.1 从 `master@7d64b69` 检出特性分支 `feature/optimize-project-file-list-auth-reuse`
- [x] 1.2 确认工作树状态纯净，无冲突未决改动；未跟踪的 `docs/backend-optimization-candidates.md`、`work/` 与既有 `openspec/changes/` 物料保持原状

### 阶段 2：红测试先行（先实测红，再动主代码）
- [x] 2.1 新增 `src/test/java/com/slz/crm/unit/service/ProjectFileServiceImplTest.java`（纯 Mockito，不起 Spring 上下文），类与关键方法中文 Javadoc 标注「optimize-project-file-list-auth-reuse 任务 2.1」
- [x] 2.2 覆盖清单：queryPage 混合可读页（records 仅可读行、可读行 `downloadUrl` 签发、`canReadProjectFile` 恰 N 次且 `verifyNoMoreInteractions`）；queryPage 全无权页（records 空、`total` 保留库内条件总数）；`listByActivityId`/`listByOrderId`/`listByContractId`/`listByOpportunityId` 各路径判定恰 1 次/行；上传人姓名转换（`dataConvertService.getUserName`）仍生效；空列表零调用
- [x] 2.3 在未改主代码的基线上运行定向单测实测红（恰 N 次断言在旧形状 N+k 上必红），回报粘贴红输出

### 阶段 3：实现同请求判定复用
- [x] 3.1 收敛 5 个列表方法到统一私有转换链：每行 `canReadProjectFile` 判定恰 1 次（`filterReadable` 过滤语义不变），保留行进入已知可读转换并直接签发下载令牌（中文 Javadoc 标注「optimize-project-file-list-auth-reuse 任务 3.1」）
- [x] 3.2 列表路径移除第二次判定与「拦截越权项目文件下载链接签发」warn 分支（列表路径内该分支实际不可达）；`PublicAttachmentController` 单行下载复核保持原状
- [x] 3.3 恪守 PMD（OnlyOneReturn 等既有规则集）/SpotBugs/Checkstyle/Spotless；定向单测实测转绿，回报粘贴绿输出；`total` 口径与 `ProjectFileListAuthHotpathGuardTest` 冻结口径保持一致

### 阶段 4：质量门禁与基线核验
- [x] 4.1 全量单测 `mvn -B -ntp test` 0 失败 0 跳过，总数 920 → 928 只增不减
- [x] 4.2 静态分析 `mvn -B -ntp pmd:check spotbugs:check checkstyle:check spotless:check` 0 违规
- [x] 4.3 `bash scripts/check-test-baseline.sh --update` 真实写回并复验通过
- [x] 4.4 `agent-helper.sh check-line-endings lf <写集4文件>`、`check-write-set 7d64b69 <写集4文件>`、`check-dirty` 全绿；门禁自测 `check-test-baseline-selftest.sh` + `agent-helper-selftest.sh`（合计 136 条）全绿

### 阶段 5：提交、合并与停步
- [x] 5.1 补齐本文件 §0 执行记录实测数字并勾选全部条目，按阶段粒度规范提交（建议 3 笔：perf 实现与测试 → docs tasks 留痕 → chore 基线校准）
- [x] 5.2 切回 `master` 执行真 `--no-ff` 合并，分支保留不删
- [x] 5.3 严格停步：绝对禁止 `git push`，等待主 agent 亲跑终审复验与 owner 授权

### 阶段 6（可选）：请求级基准前后对照
- [ ] 6.1 本地 Docker 可用时，经 opt-in 开关 `PROJECT_FILE_LIST_HOTPATH_MEASURE=1` 运行 `ProjectFileListAuthHotpathBenchmark` 前后对照（钉扎 `mysql:8.0`，零外呼零真实模型）并回报数字；Docker 不可用或未跑时必须在「未跑项」中明列，不得留空、不得以代码推导冒充实测
      维持未勾核验（2026-10-08 P-ab 逐格核验）：opt-in 请求级基准属可选阶段 6，未跑已在本卡 §0「未跑项」明列（failsafe IT / opt-in 基准 / 真实模型外部调用 / 生产验证四项）；触发条件未再行使，维持未勾。
