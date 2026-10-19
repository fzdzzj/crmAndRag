# 任务清单 · fix-engineering-debt-nplus1-loop-clone

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」证据行；未完成不勾。

## 1. A-7 UserHandover 分页 N+1 批量化
- [x] 1.1 convertToVOPage（:202-213）改批量取用户名：页内 from/to/operator 三类 id 去重后 selectBatchIds 单次查询建 Map 回填；getUserName 单条路径（executeHandover）保留
      补勾依据（2026-10-19 `git diff 2acabe2^..2acabe2 -- src/main/java/com/slz/crm/server/service/impl/UserHandoverServiceImpl.java` 实测）：convertToVOPage（新 :206-224）改调 batchGetUserNames（新 :228-248：LinkedHashSet 去重 → selectBatchIds 单次 → HashMap 回填），原 :207-209 逐行 buildHandoverVO 循环删除；buildHandoverVO/getUserName（新 :161）保留供 executeHandover 单条路径使用。
- [x] 1.2 靶向测试：分页路径断言 selectBatchIds 恰一次、selectById 零调用、VO 三名字正确；单条路径回归断言
      补勾依据（2026-10-19 `mvn -B -ntp "-Dtest=UserHandoverServiceImplTest" test` 实测）：Tests run: 3, Failures: 0, Errors: 0；两行记录共用 10/20/30 三类 id 时 selectBatchIds times(1)、捕获集合去重后 size=3、selectById never()、VO 三名字=张三/李四/王五；executeHandover 回归 selectById times(5) 且 selectBatchIds never()。

## 2. A-9 Invoice/Payment 批量软删收口
- [x] 2.1 InvoiceInfoServiceImpl.deleteByIds（:155-171）改单条 IN 批量更新（status=1），删循环逐条
      补勾依据（2026-10-19 `git diff 2acabe2^..2acabe2 -- src/main/java/com/slz/crm/server/service/impl/InvoiceInfoServiceImpl.java` 实测）：deleteByIds（新 :156-165）＝ `LambdaUpdateWrapper.set(getStatus,1).in(getId,idList)` + `mapper.update(null, wrapper)`；原 for + selectById + updateById 的 2N 条路径删除，PARAM_EMPTY 前置校验保留。
- [x] 2.2 PaymentRecordServiceImpl.deleteByIds（:174-190）同型改造（paymentStatus=2），与 2.1 形态对齐
      补勾依据（2026-10-19 同上 diff 实测）：deleteByIds（新 :175-184）＝ `set(getPaymentStatus,2).in(getId,idList)` + `update(null, wrapper)`；两处语句形状逐字对齐（仅实体/状态列/SET 值不同）。
- [x] 2.3 等价性论证落 handoff：仅更新存在行、返回计数含义、零 @Version/@TableLogic/MetaObjectHandler（立项实测）、重复 id 计数以实际行数为准（行为差异点单列）
      补勾依据（2026-10-19 `grep -n "@Version|@TableLogic" InvoiceInfoEntity.java PaymentRecordEntity.java` 零命中、`grep -rn "MetaObjectHandler" src/main/java` 零命中实测）：论证见 work/handoff-fix-engineering-debt-nplus1-loop-clone.md §3（含重复 id「旧 2 / 新 1」差异单列）。
- [x] 2.4 靶向测试：两服务各断言单条 update（SET 状态列 + IN idList）、逐条 selectById/updateById 零调用、返回计数正确
      补勾依据（2026-10-19 `mvn -B -ntp "-Dtest=InvoiceInfoDeleteByIdsBatchTest,PaymentRecordDeleteByIdsBatchTest" test` 实测）：各 Tests run: 3 全绿；捕获 wrapper 断言 sqlSet 含 status= / payment_status=、sqlSegment 含 id IN、参数含状态值 1/2 与两个 id，selectById/updateById never()，计数 2；重复 id 场景计数以实际行数 1 为准。

## 3. A-1 SQLExceptionHandler 双轨收敛
- [x] 3.1 application.yml slz.database-field-map（:220-240）增 7 个索引名键：uk_phone/uk_email/uk_company_name/idx_mobile/uk_contract_no/uk_payment_no/uk_invoice_no，值与现 switch 逐项一致
      补勾依据（2026-10-19 实读 application.yml 实测）：7 键追加于 task_id 之后（:244-250），值＝手机号/邮箱/企业名字/联系电话/合同编号/支付编号/发票编号，与原 switch 逐项一致；composite-unique-index-map（:251-254）与既有列名键未动。
- [x] 3.2 删 getFieldNameByIndex 手写 switch（:78-102）改查配置单源（default「数据」保留）；:104 TODO 标记消除、维护说明单点化
      补勾依据（2026-10-19 `git diff 2acabe2^..2acabe2 -- src/main/java/com/slz/crm/server/handler/SQLExceptionHandler.java` 实测）：switch（原 :89-99）删除，getFieldNameByIndex（新 :82-92）改为 `databaseFieldMap.getOrDefault(parts[1], "数据")`，无表名前缀仍回落「数据」；原 :104 `TODO注意维护` 注释改为指向 yml 单源的维护说明；getNameByIndex 行为（缺失回落「未知字段」）不变。
- [x] 3.3 靶向测试：duplicate-key 路径已知索引/未知索引/组合索引三态文案断言；FK 路径与 doesn't-have-default-value 路径回归断言
      补勾依据（2026-10-19 `mvn -B -ntp "-Dtest=SQLExceptionHandlerTest" test` 实测）：Tests run: 9 全绿；测试直接从 application.yml 读回两套映射注入 handler（yml 少键/错值即红），覆盖 7 键逐项映射、未知索引「数据」、组合索引「共享 客户A」、不可解析兜底文案、FK「用户ID / 未知字段」、缺少必填字段「数据」。

## 4. 基线与物料
- [x] 4.1 定向测试全绿 + 全量 mvn -B -ntp clean verify（Docker 在线）
      补勾依据（2026-10-19 `docker info` 实测 29.6.2 Docker Desktop 在线；`mvn -B -ntp clean verify` EXIT=0，日志 work/clean-verify.log）：BUILD SUCCESS，surefire Tests run: 1738 / Failures 0 / Errors 0 / Skipped 0（≥1720），failsafe Tests run: 118 / Skipped 7（与基线持平），checkstyle/spotless/PMD/SpotBugs 全过。
- [x] 4.2 树净单次 check-test-baseline.sh --update + 校验模式 EXIT=0 + 基线笔
      补勾依据（2026-10-19 `bash.exe scripts/check-test-baseline.sh --update` 实测）：写入 surefire 279/1738/0、failsafe 31/118/7（source-revision=2acabe2）；复跑校验模式输出「回归基线门禁通过」EXIT=0；基线笔 3b68766。
- [x] 4.3 merge-gate.sh 10/10（typed-router 噪声 checkout 还原）
      补勾依据（2026-10-19 `bash.exe scripts/merge-gate.sh` EXIT=0 实测，日志 work/merge-gate.log）：unit/checkstyle/spotbugs/spotless/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline 共 10 项全部 PASS，末行「merge-gate 通过」；vitest 改写的 frontend/typed-router.d.ts 已 `git checkout --` 还原，分支零前端 diff。
- [x] 4.4 handoff + tasks 勾选 + 任务卡随 docs 笔提交；严格停步未 merge 未 push
      补勾依据（2026-10-19 `git log --oneline 6e99443..HEAD` 实测）：三笔提交 2acabe2(fix) → 3b68766(test baseline) → docs 笔（本文件与 handoff、任务卡、proposal 同笔入库）；分支 feature/fix-engineering-debt-nplus1-loop-clone 保留，未 merge、未 push。
