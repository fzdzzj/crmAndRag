# 任务清单 · fix-projectfile-txn-and-batch-insert

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」证据行；未完成不勾。

## 1. A-5+A-16 ProjectFile 三上传合一与事务边界
- [x] 1.1 抽 uploadInternal 模板：宿主校验/权限闸/实体字段装饰按宿主注入，三公开方法保留各自闸与文案
      补勾依据（2026-10-20 实测 ProjectFileServiceImpl 三公开方法改写为 uploadInternal + UploadHost 注入点（宿主闸/写盘前校验/decorate），PARAM_EMPTY、DATA_NULL「业务活动不存在」「订单项不存在」、PERMISSION_DENIED「无权向该业务活动上传附件」「无权向该订单上传附件」、FILE_READ_FAILED「文件上传失败: 」逐字保留；ProjectFileUploadSecurityTest 12 用例 + ProjectFileUploadTransactionTest 10 用例全绿）
- [x] 1.2 磁盘 IO 移出 @Transactional：先全部写盘（失败清盘上抛）→ 短事务批量落库（DB 失败回滚+清盘）
      补勾依据（2026-10-20 实测 方法级 @Transactional 移除（deleteByIds 保留），阶段一事务外写盘失败→rollbackUploadedFiles+FILE_READ_FAILED；阶段二 TransactionTemplate(REQUIRED) 内单次 baseMapper.insertBatch；落库失败→事务 rollback+全量清盘+同文案；mvn -B -ntp clean verify BUILD SUCCESS）
- [x] 1.3 靶向单测：先盘后库顺序、DB 失败全量清盘补偿、批量落库调用形态、PARAM_EMPTY/DATA_NULL/PERMISSION_DENIED 闸保留
      补勾依据（2026-10-20 实测 mvn -B -ntp "-Dtest=ProjectFileUploadTransactionTest,..." test：Tests run 102/0/0/0；其中先盘后库用 insertBatch Answer 断言「回调时两文件已在盘」，DB 失败断言 rollback 一次 + 目录清空 + 逐条 insert 零调用 + 空列表 PARAM_EMPTY 不触宿主）

## 2. E-15 saveBatch 同型修复
- [x] 2.1 ApprovalAttachmentServiceImpl.saveBatch：先全部落盘→批量入库+短事务+失败清盘补偿
      补勾依据（2026-10-20 实测 saveBatch 改为「全量 IOUtils.writeFile（失败清盘+原异常上抛）→ TransactionTemplate 内一次 insertBatch（失败回滚+清盘+原异常上抛）」；签名与异常类型不变；新增 ApprovalAttachmentMapper#insertBatch + mapper/ApprovalAttachmentMapper.xml，零迁移零新依赖）
- [x] 2.2 靶向单测：部分 insert 失败时已落盘文件全清、零半提交
      补勾依据（2026-10-20 实测 ApprovalAttachmentSaveBatchTest 4 用例全绿：落库失败后 tempDir 下 0 文件且 rollback 一次、异常原样 IllegalStateException；写盘中途失败清掉先前文件；成功路径 insertBatch 恰一次、单条 insert 零调用）

## 3. E-14 备注批量写
- [x] 3.1 CustomerContactRemarkValidator.replaceRemarks/insertRemarks：先全量校验后 insertBatch 单次
      补勾依据（2026-10-20 实测 先全量 validateRemarkType（任一非法零写入）→ 实体装饰（BeanUtils+contactId+creatorId+createTime）→ 单次 mapper.insertBatch（既有 XML 未改）；replaceRemarks 仍先 delete 并复用 insertRemarks；事务归属未动）
- [x] 3.2 靶向单测：校验失败零写入、insertBatch 单次调用且实体装饰（contactId/creatorId）正确
      补勾依据（2026-10-20 实测 CustomerContactRemarkValidatorBatchTest 5 用例全绿：校验失败 insertBatch/insert 均零调用；成功路径 insertBatch 恰一次且 contactId/creatorId/createTime 断言通过；replaceRemarks 用 InOrder 锁定 delete 先于 insertBatch）

## 4. E-16 活动创建批量写
- [x] 4.1 BusinessActivityServiceImpl create 路径双表 forEach → 复用 batchInsert 各一次
      补勾依据（2026-10-20 实测 create 两处 forEach 逐条 insert 删除，改调 BusinessActivityRelationSupport#insertActivityContactRelations/insertActivityUserRelations（内部各一次 A-8 batchInsert，零新增 SQL，create_time 走库默认），装饰 BeanUtils+creatorId+activityId 不变）
- [x] 4.2 靶向单测：双表 batchInsert 各恰一次、单条 insert 零调用、实体装饰正确
      补勾依据（2026-10-20 实测 BusinessActivityCreateBatchInsertTest 4 用例全绿：双表 batchInsert 各 1 次 + 单条 insert 零调用 + activityId/creatorId/contactId/userId/role 断言；null 与空列表均零 batchInsert）

## 5. 基线与物料
- [x] 5.1 定向测试全绿 + 全量 mvn -B -ntp clean verify（Docker 在线）
      补勾依据（2026-10-20 实测 docker info=29.6.2 Docker Desktop 在线；定向 102/0/0/0 BUILD SUCCESS；clean verify BUILD SUCCESS，surefire 287 报告/1802 用例/0 失败，failsafe 32 报告/119 用例/7 skip）
- [x] 5.2 树净单次 check-test-baseline.sh --update + 校验模式 EXIT=0 + 基线笔
      补勾依据（2026-10-20 实测 D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update：source-revision=e0f6457（无 -dirty）、surefire 287/1802/0、failsafe 32/119/7；复跑校验模式 EXIT=0「回归基线门禁通过」；基线笔 7d10617）
- [x] 5.3 merge-gate.sh 10/10（typed-router 噪声 checkout 还原）
      补勾依据（2026-10-20 实测 D:\git\Git\bin\bash.exe scripts/merge-gate.sh：unit/checkstyle/spotbugs/spotless/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline 共 10 项 PASS，GATE_EXIT=0；frontend/typed-router.d.ts 行尾噪声已 git checkout -- 还原，frontend/ 零 diff）
- [x] 5.4 handoff（事务边界等价性论证 + E-15 行为增强单列 + 四点 diff 摘要）+ tasks 勾选 + proposal/tasks 随 docs 笔提交；停步未 merge 未 push
      补勾依据（2026-10-20 实测 work/handoff-fix-projectfile-txn-and-batch-insert.md 已落：四点对照/diff 摘要、A-5 新旧事务边界与错误码逐码对照、E-15 增强单列、E-16 复用 A-8 说明、定向清单、基线四元组、merge-gate 10/10、提交哈希、偏差清单；docs 笔提交本目录；分支保留未 merge 未 push）
