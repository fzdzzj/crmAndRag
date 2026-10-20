# 任务清单 · harden-test-backlog-batch1

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 五点加固（全 src/test）
- [x] 1.1 BaseExceptionConvergenceTest 补 `new BaseException (` 空格形态正则 + 负向自证样本
      补勾依据（2026-10-20，`mvn -B -ntp "-Dtest=BaseExceptionConvergenceTest" test`：Tests run 7/0/0/0 绿；扫描定位由字面量 indexOf 改正则 `new\s+BaseException\s*\(`，新增自证用例含「旧实现漏检」「空格违规判红」「合法形态带空格仍计数判绿」「同一行两形态=2 点位」；src/main 实测 `new BaseException (` 命中 0、`new BaseException(` 513 点位，纯前瞻）
- [x] 1.2 新增 release 置空真库回环 IT（Docker failsafe，owner 置空落库断言）
      补勾依据（2026-10-20，`mvn -B -ntp "-Dit.test=CustomerPoolReleaseRoundTripIT" test-compile failsafe:integration-test failsafe:verify`：Tests run 3/0/0/0 绿；真库断言 owner_id 为 NULL + pool_status=1 + release_time 非空 + reason 原样 + is_deleted=0 可见 + RELEASE 日志成对；空白原因回退文案与 queryPool 可见性各一例）
- [x] 1.3 TokenBudgetPropertiesConcurrencyTest 区别力加固（移除 volatile 即红）+ 依据注释
      补勾依据（2026-10-20，`mvn -B -ntp "-Dtest=TokenBudgetPropertiesConcurrencyTest" test`：Tests run 4/0/0/0 绿；mutation 实测删 `enabled` 的 volatile → Tests run 4/Failures 1（`sharedMutableFieldsAreVolatile:49` 红），`git checkout --` 还原后 `git diff --stat -- src/main` 空；判据=反射 isVolatile 逐字段 + 非 volatile 负向自证，可见性用例为 latch+有界自旋语义冒烟，类 javadoc 写明依据、无 sleep）
- [x] 1.4 MessageConstant 9 处码断言补强（实读定位逐处补 getCode()）
      补勾依据（2026-10-20，`mvn -B -ntp "-Dtest=PermissionServiceImplMessageCodeTest,UserServiceImplTest,BusinessActivityRelationSupportTest,BusinessActivityRelationSupportBatchTest" test` 全绿（3+4+4+28）；9 处对照表见 handoff §1④，码值全部实读 ErrorCode：50002(L153)/92015(L258)/92014(L254)/50001(L150)/13001(L68)/92008(L236)/13002(L71)；新增权限三处码测试 + UserServiceImpl 追加 13002×2 与邻接 13001×1）
- [x] 1.5 UserHandoverServiceImplTest 补三类 null id 跳过分支用例
      补勾依据（2026-10-20，`mvn -B -ntp "-Dtest=UserHandoverServiceImplTest" test`：Tests run 8/0/0/0 绿；新增 from/to/operator 逐类 null 三例 + 三类全空（selectBatchIds 零调用）+ 混合页（全空行跳过、批量查询恰一次），均断言 null 项姓名 null、非 null 项回填、Captor 锁 id 集合、selectById 零调用）

## 2. 验证与留证
- [x] 2.1 定向全绿（含新 IT 的 failsafe 定向段）+ 全量 mvn -B -ntp clean verify（Docker 实测）
      补勾依据（2026-10-20，定向 58 tests/7 类绿 + 新 IT 3 tests 绿；`docker info` 实测 Server 29.6.2 在线，`mvn -B -ntp clean verify` BUILD SUCCESS 15:21：surefire 1848/0/0/0（297 报告）、failsafe 122/0/0/7（33 报告）、checkstyle 0、spotless/spotbugs/PMD 全过；日志 work/_harden-{targeted,it,verify}.log）
- [x] 2.2 树净单次 check-test-baseline.sh --update + 校验 EXIT=0 + merge-gate 10/10
      补勾依据（2026-10-20，先提交测试笔 `98c407e` 得树净，`D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update` EXIT=0 → 297/1848/33/122/7，source-revision=98c407e；复跑校验模式 EXIT=0；`bash scripts/merge-gate.sh` 10/10 PASS：unit/checkstyle/spotbugs/spotless/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline，[it] 默认序列按设计未执行；typed-router 噪声已还原）
- [x] 2.3 handoff（五点前后对照/断言清单/锚点行号）+ tasks 勾选 + docs 笔；停步未 merge 未 push
      补勾依据（2026-10-20，work/handoff-harden-test-backlog-batch1.md 落盘（含五点前后对照/②seed-断言设计/③区别力依据/④9 处码值表/src/main 零 diff/定向清单/基线四元组/10-10/哈希/偏差）；docs 笔含本文件与 handoff；`git diff --stat master...HEAD -- src/main` 空，未 merge 未 push，分支 feature/harden-test-backlog-batch1 保留）
