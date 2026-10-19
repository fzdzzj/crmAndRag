# 任务清单 · fix-engineering-debt-loop-write-batch

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」证据行；未完成不勾。

## 1. A-6 级联批量化下沉
- [x] 1.1 ForeignKeyDeleteUtil 增 logicalDeleteByCascadeBatch(Class, List<Long>)：级联单趟（deleteCascade 原样吃全量 ids）+ 主表单条 IN 逻辑删；单条入口保留
      补勾依据（2026-10-19 实读 `ForeignKeyDeleteUtil.java`：`logicalDeleteByCascadeBatch` :110-120 + `logicalDeleteMainRecords` :154-178 新增；`deleteCascade` :43-78 未改；`logicalDeleteByCascade` :87-95 保留且仍走 updateById）
- [x] 1.2 CustomerContactServiceImpl.batchLogicalDeleteByIds 删自调用循环（:437-439）改一次委托批量入口；@Transactional 保留
      补勾依据（2026-10-19 实读 `CustomerContactServiceImpl.java` :429-436：循环 :437-439 已删，改 `return foreignKeyDeleteUtil.logicalDeleteByCascadeBatch(CustomerContactEntity.class, idList)`；`@Transactional(rollbackFor=Exception.class)` 与 `PARAM_EMPTY` 闸保留）
- [x] 1.3 靶向测试：批量入口断言级联单趟 + 主表单条 IN 更新；service 层断言委托恰一次、循环零
      补勾依据（2026-10-19 `mvn -B -ntp "-Dtest=…ForeignKeyDeleteUtilBatchTest,…CustomerContactBatchLogicalDeleteTest,…" test` → EXIT=0，`Tests run: 5 / 3, Failures: 0, Errors: 0`；含「1 个 id 与 3 个 id SQL 条数相等」单趟断言与 `verify(delegate, times(1))` 委托断言）
- [x] 1.4 等价性论证落 handoff：级联层级/顺序不变、计数含义（重复 id 以实际行数计）、事务语义不劣化
      补勾依据（2026-10-19 `work/handoff-fix-engineering-debt-loop-write-batch.md` §1.2 (a)-(d)：层级/顺序不变、SQL 条数 N→1、计数（JDBC URL 未设 useAffectedRows → 匹配行数口径下重复 id 2→1 已单列）、事务不劣化）

## 2. A-8 活动关联维护集合化
- [x] 2.1 8 处循环改集合一次存在性比对（in 查询取已存在集合 + list-vs-set 比对）+ 批量写（允许新增 mapper 批量方法，多值 insert 或等价）
      补勾依据（2026-10-19 实读 `BusinessActivityRelationSupport.java`：8 处循环（原 :42/:59/:87/:105/:128/:159/:187/:202）全部改为 1 次 `selectExisting*Ids` + 1 次 `batchInsert` / `deleteByActivityIdAnd*Ids`；新增 mapper 方法 6 个（2 接口 × 3）+ 2 份 XML 语句；旧单条 exists/delete 方法保留给其他调用方）
- [x] 2.2 错误语义逐路径锁定：add 重复→PARAM_FORMAT_ERROR 同文案；delete 缺失→NOT_EXISTS 同码同文案；null 项跳过保留；请求内重复 id 旧语义保留（add 第二轮抛重复 / delete 第二轮抛不存在）
      补勾依据（2026-10-19 `BusinessActivityRelationSupportBatchTest` 28 例逐路径断言：50002 `BUSINESS_ACTIVITY_USER_DUPLICATE` / `..._CONTACT_ALREADY_EXIST`，92015 `..._USER_NOT_EXISTS`、92014 `..._CONTACT_NOT_EXISTS` 同码同文案；null 项跳过与「全 null 零交互」；请求内重复 id 走 `hasDuplicatedId` 保持旧语义）
- [x] 2.3 靶向测试：8 方法逐个覆盖上述四类语义 + 批量写调用形态断言；既有 BusinessActivityRelationSupportTest 4 例保持全绿
      补勾依据（2026-10-19 定向 `Tests run: 28 + 4, Failures: 0, Errors: 0`；既有 4 例断言与文案逐字未改（仅桩点由 exists* 换成 selectExisting*），见 handoff §8.1）
- [x] 2.4 check-then-act 竞态不劣化论证落 handoff
      补勾依据（2026-10-19 handoff §2.3：调用方 4 处 `@Transactional` 使窗口由 N 次往返压到 1 读 1 写；`uk_activity_contact_unique` / `uk_activity_user_unique` 唯一键兜底未变；失败粒度由「部分写 + 回滚」变「整条不写」）

## 3. A-10 用户批删单条化 + 置空修复
- [x] 3.1 循环逐条（:303-308）改单条 update：set(status,3).set(email,null).set(phone,null).in(id, id)；预检闸（:292-300）与 @CacheEvict/@Transactional 保留
      补勾依据（2026-10-19 实读 `UserServiceImpl.java` :290-311：写路径 :302-309 改为单条 `LambdaUpdateWrapper`；预检闸 :292-300 逐行保留；`@CacheEvict(userName, allEntries=true)` 与 `@Transactional` 保留）
- [x] 3.2 置空缺陷修复实证：MP NOT_NULL 默认策略下旧 updateById 置空从未生效（复立项证据链）→ 新显式 set 真置空释放 uk_email/uk_phone，差异论证落 handoff
      补勾依据（2026-10-19 证据链：`application.yml:204-206` mybatis-plus 块仅 mapper-locations（全仓 yml 无 update-strategy/field-strategy 命中）+ `UserEntity` email(:47)/phone(:43) 无 @TableField + `uk_email`(:20)/`uk_phone`(:24) 唯一索引 → 旧实现 SET 丢列；新实现 null 绑定参数进 SQL，差异表见 handoff §3.2/§3.3）
- [x] 3.3 靶向测试：单条 SET/IN 断言、预检闸（size 不符抛 DATA_DELETE_FAILED、重复 id 抛）、置空新语义断言
      补勾依据（2026-10-19 `UserDeleteByIdsBatchTest` 3 例 EXIT=0：SET 三列 + `id IN` + 恰 2 个 null 绑定参数 + `never(updateById)`；size 不符抛 91003 同文案且零写；重复 id 被预检闸拦下）

## 4. 基线与物料
- [x] 4.1 定向测试全绿 + 全量 mvn -B -ntp clean verify（Docker 在线）
      补勾依据（2026-10-19 `docker info` = 29.6.2 在线；`mvn -B -ntp clean verify` = BUILD SUCCESS，surefire 1777/0/0（283 报告）+ failsafe 118/0/0/7 跳（31 报告），日志 `work/logs-loop-write-batch/full-verify.log`）
- [x] 4.2 树净单次 check-test-baseline.sh --update + 校验模式 EXIT=0 + 基线笔
      补勾依据（2026-10-19 `D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update` EXIT=0，写入 source-revision=d9f3ae4（无 -dirty）、surefire 283/1777/0 + failsafe 31/118/7；基线笔 `5ab0446`；校验模式由 merge-gate `[baseline]` PASS 复证）
- [x] 4.3 merge-gate.sh 10/10（typed-router 噪声 checkout 还原）
      补勾依据（2026-10-19 `D:\git\Git\bin\bash.exe scripts/merge-gate.sh` → unit/checkstyle/spotbugs/spotless/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline 全 PASS（10/10），结论行「merge-gate 通过」；`frontend/typed-router.d.ts` 已 `git checkout --` 还原）
- [x] 4.4 handoff + tasks 勾选 + 任务卡随 docs 笔提交；严格停步未 merge 未 push
      补勾依据（2026-10-19 提交三笔：fix `d9f3ae4` → test(baseline) `5ab0446` → docs（本 tasks 所在笔，含 proposal.md / task-card / work/handoff-…md）；`git log` 停在分支 HEAD，未 merge、未 push、未 reset/rebase/amend）
