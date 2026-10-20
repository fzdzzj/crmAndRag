# 任务清单 · fix-semantic-hook-support-unify

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」证据行；未完成不勾。

## 1. 基类抽取
- [x] 1.1 新建 SemanticHookSupport：runAfterCommitAsync 单一实现（guarded WARN + afterCommit/直发双路径）
      补勾依据（2026-10-20 实测：新增 src/main/java/com/slz/crm/server/service/impl/SemanticHookSupport.java（85 行 final 工具类，PMD 豁免理由注释随代码承接）；五域 runAfterCommitAsync 均改为一行委托；定向 mvn -B -ntp "-Dtest=SemanticHookSupportTest,ContractSemanticHookSupportTest,CustomerSemanticHookSupportTest,ContactTaskSemanticHookSupportTest,SalesOpportunitySemanticHookSupportTest,BusinessActivitySemanticHookSupportTest,CustomerContactServiceImplTest" test → Tests run: 41, Failures: 0, Errors: 0, Skipped: 0, BUILD SUCCESS）
- [x] 1.2 泛型 reingestById(id, loader, shouldDelete, ingest, delete) 函数式注入
      补勾依据（2026-10-20 实测：基类签名 static <E> void reingestById(Long, Function<Long,E>, Predicate<E>, Consumer<E>, LongConsumer)；软删/物理删分叉由各域谓词显式表达（Customer/SalesOpportunity 带 Boolean.TRUE.equals(getIsDeleted())，Contract/ContactTask/BusinessActivity 仅 full == null）；上条定向测试中 5 个域门面 + 基类的 reingest 分支用例全绿）
- [x] 1.3 基类靶向单测：afterCommit 提交后才派发 / 无事务直发 / 异常 WARN 不阻断 / reingest 三分支
      补勾依据（2026-10-20 实测：新增 SemanticHookSupportTest 7 用例——transactionActive_registersAfterCommitAndDispatchesOnlyAfterCommit / transactionRolledBack_doesNotDispatch / noTransaction_dispatchesToExecutorImmediately / actionFailure_isWarnedAndNotPropagated（含日志文案断言「客户语义向量同步失败（失败隔离），可由全量回埋端点兜底」）/ reingestById_missingEntityDeletesVector / reingestById_softDeletedEntityDeletesVector / reingestById_presentEntityIngests；定向测试 Results: Tests run: 41, Failures: 0）

## 2. 五域门面委托
- [x] 2.1 Contract/Customer/ContactTask/SalesOpportunity/BusinessActivity 五类收缩为薄门面（静态签名不变，委托基类），调用点零改动
      补勾依据（2026-10-20 实测：git diff --numstat c17cc18 a423041 仅 6 个 main 文件（1 新增基类 + 5 门面）与 4 个 test 文件，无任何 ServiceImpl/调用方文件改动；门面公共方法签名逐个比对未变——runAfterCommitAsync(String,Executor,Runnable)、reingestById(Long,IngestionService,Function)、deleteById(Long,IngestionService)、ContactTask 四个便捷闸签名原样）
- [x] 2.2 各域 reingest 语义分叉保留（Customer 软删谓词在位、Contract 物理删域无谓词），Contract deleteById 按调用面实测处置
      补勾依据（2026-10-20 实测：Customer/SalesOpportunity 谓词全文为 full == null || Boolean.TRUE.equals(full.getIsDeleted())，Contract/ContactTask/BusinessActivity 为 full == null；deleteById 调用面 grep 实测全部有调用方——ContractServiceImpl:299、SalesOpportunityServiceImpl:170、BusinessActivityServiceImpl:267、ContactTaskServiceImpl:131/155（便捷闸上层）与 ContactTaskSemanticHookSupport:124/145（便捷闸内部）→ 五域 deleteById 全部保留门面（无删除项），见 handoff「deleteById 调用面实测证据」）
- [x] 2.3 Customer/BusinessActivity 专项测试缺失则补薄门面回归；既有 3 专项 + 1 间接全绿零弱化
      补勾依据（2026-10-20 实测：新增 CustomerSemanticHookSupportTest 5 用例（含软删谓词注入正确性）与 BusinessActivitySemanticHookSupportTest 4 用例（物理删域不误删）；既有 Contract/ContactTask/SalesOpportunity 三专项与 CustomerContactServiceImplTest 一间接未改断言、定向 41 用例全绿；唯一改动是 CustomerContactServiceImplTest javadoc 失效链接 #runAfterCommit → #runAfterCommitAsync，见偏差清单）

## 3. 基线与物料
- [x] 3.1 定向测试全绿 + 全量 mvn -B -ntp clean verify（Docker 在线）
      补勾依据（2026-10-20 实测：docker info → ServerVersion 29.6.2（EXIT=0）；mvn -B -ntp clean verify → BUILD SUCCESS，surefire 290 报告 / Tests run 1818 / 0 失败、failsafe 32 报告 / 119 / 7 skip（基线 287/1802、32/119/7 → 只增不减））
- [x] 3.2 树净单次 check-test-baseline.sh --update + 校验模式 EXIT=0 + 基线笔
      补勾依据（2026-10-20 实测：D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update → UPDATE_EXIT=0，source-revision=a423041，写入 surefire.reports=290/tests=1818/skipped=0、failsafe.reports=32/tests=119/skipped=7；复跑校验模式 → CHECK_EXIT=0「回归基线门禁通过」；基线笔 2392260）
- [x] 3.3 merge-gate.sh 10/10（typed-router 噪声 checkout 还原）
      补勾依据（2026-10-20 实测：D:\git\Git\bin\bash.exe scripts/merge-gate.sh → GATE_EXIT=0，PASS 计数 10/10：[unit][checkstyle][spotbugs][spotless][pmd][baseline][frontend-unit][hook][bijection][pmd-baseline]；gate 顺带改动的 frontend/typed-router.d.ts 已 git checkout -- 还原，git status 无该文件）
- [x] 3.4 handoff（五域前后对照表：签名/谓词/deleteById/日志文案 + 等价性论证）+ tasks 勾选 + proposal/tasks 随 docs 笔提交；停步未 merge 未 push
      补勾依据（2026-10-20 实测：work/handoff-fix-semantic-hook-support-unify.md 已落盘（含五域对照表、afterCommit 三轴等价性论证、deleteById 调用面证据、定向清单、基线四元组、门禁 10/10、提交哈希、偏差清单）；proposal.md/tasks.md 随 docs 笔提交；git rev-parse --abbrev-ref HEAD = feature/fix-semantic-hook-support-unify，未 merge、未 push）
