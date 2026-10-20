# handoff · fix-projectfile-txn-and-batch-insert（B 档，四点合一）

- 权威树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- 分支：`feature/fix-projectfile-txn-and-batch-insert`（自 master@e936459 检出）
- 提交：`e0f6457` fix（实现+靶向单测）→ `7d10617` test(baseline) → `c921a8d` docs（openspec 卡 + tasks 勾选证据）
- 收尾树态：`git status --porcelain` 仅剩 `work/` 下未跟踪的任务卡与 handoff（work/* 不入库）；`frontend/`、`db/migration/`、`pom.xml` 零 diff
- 停步：**未 merge、未 push**，分支保留。

---

## 一、四点锚点前后对照与 diff 摘要

| 点 | 锚点（2026-10-20 实读） | 改后形态 | diff 规模 |
| --- | --- | --- | --- |
| A-5+A-16 | `ProjectFileServiceImpl` 三上传 :61-121 / :123-183 / :185-233（约 90% 克隆，`@Transactional` 包住写盘+逐条 insert） | 三方法保留各自闸/文案，公共流程收进 `uploadInternal` + `UploadHost` 注入点（宿主闸、写盘前校验、实体装饰）；写盘移出事务，落库改 `TransactionTemplate` 内一次 `insertBatch` | 266 行改动（净 −约 40 行克隆） |
| E-15 | `ApprovalAttachmentServiceImpl.saveBatch` :72-79（循环内 writeFile+insert，无事务无补偿） | 先全部 `IOUtils.writeFile` → 短事务单次 `insertBatch` → 两侧失败均清盘并**原异常上抛** | +50 行 |
| E-14 | `CustomerContactRemarkValidator.replaceRemarks/insertRemarks` :19-62（逐条校验+逐条 insert） | 先全量 `validateRemarkType`（任一非法零写入）→ 构建实体列表（装饰不变）→ 单次 `mapper.insertBatch`（XML foreach 既有） | +58/-? 行 |
| E-16 | `BusinessActivityServiceImpl.create` :86-112（双表 `forEach` 逐条 insert） | `BusinessActivityRelationSupport` 新增两个静态批量方法（复用 A-8 `batchInsert`），create 各调一次 | create −40 行，support +45 行 |

配套新增（非新依赖、非迁移）：`ProjectFileMapper#insertBatch` + `mapper/ProjectFileMapper.xml`（原文件仅 namespace 空壳）、
`ApprovalAttachmentMapper#insertBatch` + `mapper/ApprovalAttachmentMapper.xml`（新建）。

---

## 二、A-5 事务边界迁移等价性论证

**旧**：`uploadByXxx` 方法级 `@Transactional(rollbackFor = Exception.class)` 覆盖「宿主查询 → 权限判定 → 逐条写盘 → 逐条 insert」全程，
磁盘 IO 持事务。

**新**：方法级注解移除，`uploadInternal` 分两阶段——

1. 阶段一（**无事务**）：`PARAM_EMPTY` 空列表 → `validateCategory` → 宿主存在性/权限闸（直接上抛）→ 逐条写盘；
   任一步失败 → `rollbackUploadedFiles(uploadedFiles)` 全量清盘 → 上抛 `FILE_READ_FAILED`。
2. 阶段二（**短事务**）：`new TransactionTemplate(transactionManager).executeWithoutResult(...)`（`REQUIRED`，外层有事务则加入）
   内执行一次 `baseMapper.insertBatch(entities)`；失败 → 事务回滚 → 清盘 → 上抛 `FILE_READ_FAILED`。

**错误面逐码对照（码与文案逐字一致）**

| 触发点 | 旧行为 | 新行为 | 等价性 |
| --- | --- | --- | --- |
| `dtoList` null/空 | `PARAM_EMPTY`「上传文件列表不能为空」（try 外） | 同（`uploadInternal` 首检，先于宿主闸） | 逐字一致，且**顺序不变**（空列表仍不查宿主） |
| 分类非法 | `PARAM_EMPTY` 分类文案（try 外） | 同 | 逐字一致 |
| 活动/订单项不存在 | `DATA_NULL`「业务活动不存在」/「订单项不存在」（try 外） | 同（宿主闸，try 外） | 逐字一致 |
| 无写权限 | `PERMISSION_DENIED`「无权向该业务活动上传附件」/「无权向该订单上传附件」 | 同 | 逐字一致，且**零落盘零落库**（回归测试锁定） |
| 写盘失败 / 文件为空 | 包裹为 `FILE_READ_FAILED`「文件上传失败: 」+ 内层文案 | 同（阶段一 catch） | 逐字一致 |
| 独立上传销售机会不存在 | 原实现该检查在 try **内** → 包裹为 `FILE_READ_FAILED`「文件上传失败: 销售机会不存在，opportunityId=N」 | **原样保留**（`preWriteCheck` 在阶段一 try 内执行，不借机改成 `DATA_NULL`） | 逐字一致（测试 9 锁定） |
| 落库失败 | `FILE_READ_FAILED`「文件上传失败: 」+ 内层文案，事务回滚，**但已写文件残留** | 事务回滚 + **全量清盘** + 同一文案 | 错误面一致，补偿面增强（本卡目标） |

**可确认的形状变化（已在测试中锁定，均不影响调用方）**

1. 落库由 N 次单条 `insert` 变 1 次多值 `insertBatch`：单语句天然原子，不存在「前几条已入库」；
2. 自增主键不再回填到实体（旧 `insert` 会回填）：三个上传方法返回 `void`，控制器只回 `Boolean`，全链路无消费点；
3. 事务只覆盖落库（本卡目标），宿主查询与权限判定由「事务内单条读」变「事务外单条读」，只读语义不变；
4. 独立上传的 `opportunityId` 校验仍在写盘前、仍在「包裹为 `FILE_READ_FAILED`」的位置；
5. 成功日志由三句改为一句 `成功上传 N 个文件到 {targetLabel}`（`业务活动 100` / `订单项 200` / `独立文件区`）——**仅日志文案，非错误契约**。

---

## 三、E-15 行为增强（单列）

| 维度 | 旧 `saveBatch` | 新 `saveBatch` |
| --- | --- | --- |
| 写盘 | 循环内逐条 `IOUtils.writeFile` 后立刻 `insert` | 先全部 `writeFile` 落盘，任一失败 → 清盘已写 → **原异常上抛** |
| 事务 | 无 | 短事务（`TransactionTemplate`，REQUIRED）内一次 `insertBatch` |
| 落库失败补偿 | 无：已写文件与已插行残留（半提交附件） | 事务回滚 + 全量清盘 + 原异常上抛 |
| 对外签名/异常类型 | `void saveBatch(List<ApprovalAttachmentDTO>)` / `ServiceException` 等原样 | **不变**（不换类型、不改文案、不包装） |
| 空/null 列表 | null → NPE；空 → 空转 | null/空 → 直接返回（严格化，非空路径不受影响） |

结论：这是**行为增强**（旧无补偿 → 新有补偿），非等价重构；调用方（controller / `saveAttachments` / `AssistSourceAttachmentSupport`）
均不依赖旧的「半提交」副作用。

---

## 四、E-16 与 A-8 基建复用说明

- 复用点：`BusinessActivityContactMapper#batchInsert` / `BusinessActivityUserMapper#batchInsert`（A-8 已合入，XML 多值 INSERT，
  `create_time` 由库默认 `CURRENT_TIMESTAMP` 填充，与旧单条 insert 的列省略口径一致）——**本卡未新增任何 SQL**。
- 新增 `BusinessActivityRelationSupport#insertActivityContactRelations/insertActivityUserRelations`：只做「null/空跳过 → 构建实体
  （`BeanUtils` 拷贝 + `creatorId` + `activityId`）→ 一次 `batchInsert`」；**不做存在性判定**（新建活动必无既有行，与旧 create 语义一致），
  与 A-8 的 `applyActivityXxxRelations`（add 模式，需存在性比对）刻意分开，避免误用 add 语义。
- `create` 里两处 `forEach` 逐条 `insert` 删除，改为各调一次；`BeanUtils` 拷贝、`creatorId`、`activityId` 装饰与旧实现逐字一致。

---

## 五、E-14 备注批量写说明

- 顺序：`replaceRemarks` 仍**先 delete**（既有语义，调用方 `CustomerContactServiceImpl.updateList` 依赖「整体替换」），再走共用的
  `insertRemarks`：先全量 `validateRemarkType`（任一非法 → 零写入），再构建实体列表（`BeanUtils` 拷贝 + `contactId` + `creatorId` 不变）
  后单次 `mapper.insertBatch`（XML foreach 既有，未改 SQL）。
- `create_time` 显式填充：既有 `insertBatch` 的 XML 显式写 `create_time` 列，实体为 null 会写 NULL 违反非空约束；显式 `LocalDateTime.now()`
  是既有 Excel 导入链路的同款做法（`CustomerContactExcelSupport.saveImportedRemarks`），与旧单条 insert 的库默认值等价。
- 事务归属未动：本类仍为静态工具、**不自立事务边界**，由 `CustomerContactServiceImpl.updateList/insert` 的 `@Transactional` 界定。

---

## 六、定向测试清单与结果

命令（PowerShell 直跑，逗号列表无空格）：

```
mvn -B -ntp "-Dtest=ProjectFileUploadTransactionTest,ProjectFileUploadSecurityTest,ProjectFileServiceImplTest,ApprovalAttachmentSaveBatchTest,ApprovalAttachmentServiceTest,CustomerContactRemarkValidatorBatchTest,CustomerContactServiceImplTest,CustomerContactBatchLogicalDeleteTest,CustomerContactBirthdaysServiceTest,BusinessActivityCreateBatchInsertTest,BusinessActivityRelationSupportTest,BusinessActivityRelationSupportBatchTest,SalesStageApprovalAttachmentControllerTest,BusinessActivityControllerTest,BusinessActivityWriteEndpointValidationTest,CustomerContactWriteEndpointValidationTest" test
```

结果：**Tests run: 102, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS**（日志 `work/.tmp-targeted.log`）。

| 新增测试类 | 用例 | 覆盖 |
| --- | --- | --- |
| `ProjectFileUploadTransactionTest` | 10 | 三方法宿主缺失/权限闸文案与零落盘；先盘后库（insertBatch 回调时文件已在盘）；落库失败 → 事务 rollback + 全量清盘 + `FILE_READ_FAILED`；写盘中途失败 → 清掉已写文件；单次 `insertBatch` 且逐条 `insert` 零调用；独立上传销售机会缺失沿用 `FILE_READ_FAILED` 包裹；DTO 直填装饰 |
| `ApprovalAttachmentSaveBatchTest` | 4 | 成功路径两文件真实落盘 + `insertBatch` 恰一次 + 单条 insert 零调用 + commit；落库失败 → rollback + 两文件全清 + 原异常上抛；写盘中途失败 → 清掉先前文件 + 零入库；null/空列表零副作用（真实 `IOUtils`，`filePath` 指向 `@TempDir`） |
| `CustomerContactRemarkValidatorBatchTest` | 5 | 成功路径 `insertBatch` 恰一次 + contactId/creatorId/createTime 装饰；校验失败零写入；`replaceRemarks` delete 前置（`InOrder`）；replace 校验失败 delete 已执行但零插入；null/空零交互 |
| `BusinessActivityCreateBatchInsertTest` | 4 | create 双表各恰一次 `batchInsert`、单条 insert 零调用、activityId+creatorId+业务字段装饰；仅联系人时用户表零写；null 列表跳过；空列表不产生空 SQL |

既有相关域回归（同批 102 内全绿）：`ProjectFileUploadSecurityTest`(12)、`ProjectFileServiceImplTest`(9)、`ApprovalAttachmentServiceTest`(1)、
`CustomerContactServiceImplTest`(3)、`CustomerContactBatchLogicalDeleteTest`(3)、`CustomerContactBirthdaysServiceTest`(3)、
`BusinessActivityRelationSupportTest`(4)、`BusinessActivityRelationSupportBatchTest`(28)、
`BusinessActivityControllerTest`(2)、`BusinessActivityWriteEndpointValidationTest`(7)、`CustomerContactWriteEndpointValidationTest`(5)、
`SalesStageApprovalAttachmentControllerTest`(2)。

---

## 七、全量验证

- `docker info`：`29.6.2 Docker Desktop`（在线）。
- `mvn -B -ntp clean verify`：**BUILD SUCCESS**（日志 `work/.tmp-verify.log`）。
- 单元（surefire）：报告 **287**、Tests run **1802**、Failures 0、Errors 0、Skipped 0。
- 集成（failsafe）：报告 **32**、Tests run **119**、Failures 0、Errors 0、Skipped **7**（仅既有 7 项 skip）。
- 静态分析随 verify 全绿：checkstyle / spotless / PMD（`maxAllowedViolations=0`）/ SpotBugs 无新增违规。

## 八、基线四元组

`scripts/test-baseline.txt`（单次 `--update`，树净，`source-revision=e0f6457`，无 `-dirty`）：

```
surefire.reports=287  surefire.tests=1802  surefire.skipped=0
failsafe.reports=32   failsafe.tests=119   failsafe.skipped=7
```

对照基点（283/1779、32/119/7）：**只增不减**（+4 报告 / +23 用例）。复跑校验模式 **EXIT=0**：
`回归基线门禁通过（基线文件：.../scripts/test-baseline.txt）`。

## 九、merge-gate

`D:\git\Git\bin\bash.exe scripts/merge-gate.sh` → **10/10 PASS，GATE_EXIT=0**：
`unit` / `checkstyle` / `spotbugs` / `spotless` / `pmd` / `baseline` / `frontend-unit` / `hook` / `bijection` / `pmd-baseline`
（日志 `work/.tmp-merge-gate.log`）。`frontend/typed-router.d.ts` 行尾噪声已 `git checkout --` 还原，`frontend/` 零 diff。

## 十、偏差清单

1. **新增两个 mapper 批量方法 + 两个 XML**：A-5/E-15 的目标是「批量落库」，而 `ProjectFileMapper`/`ApprovalAttachmentMapper` 原本
   无任何批量方法（`ProjectFileMapper.xml` 是仅 namespace 的空壳）。新增方法/XML 属代码层，**零数据库迁移、零新依赖、零新错误码**；
   E-14 复用既有 `CustomerContactRemarkMapper.insertBatch`（XML 未改）。
2. **独立上传成功日志文案**改为 `成功上传 N 个文件到 独立文件区`（三方法共用一句），仅日志，非契约面。
3. **E-15 对 null/空列表 early return**：旧 null 会 NPE；新为无操作。属严格化，非空路径行为不变。
4. **`ProjectFileUploadSecurityTest` 断言形状同步**：`verify(insert)` → `verify(insertBatch)` + 注入事务管理器 mock；这是批量化
   的必要同步，非语义放宽（仍锁定 uploaderId/activityId/orderId/contractId 与「零落盘」反向用例）。
5. **事务模板每调用新建实例**：`new TransactionTemplate(transactionManager)`（无共享可变状态，`REQUIRED` 传播，外层事务存在时加入），
   未引入 `@Bean` 或配置类改动。
6. **未新增 A-5 的「多值 INSERT 失败时部分行」语义差异风险**：单语句多值 INSERT 全成全败，比旧逐条更严格；已在测试 4 中锁定
   「回滚 + 全量清盘」。
