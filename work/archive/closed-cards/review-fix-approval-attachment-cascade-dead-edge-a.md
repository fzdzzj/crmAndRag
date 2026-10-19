# 复核报告 · fix-approval-attachment-cascade-dead-edge（A 档 · 只核不改）

- **结论：GO**（0 MAJOR / 5 MINOR / 4 待 Owner 知悉）
- 复核对象：权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/fix-approval-attachment-cascade-dead-edge`
- HEAD `a24c85d`｜master `5024d8e`（`origin/master` 同）｜`git merge-base master HEAD` = `5024d8e`（分支自 master 尖端检出）
- 复核方式：只读核验 + 定向/全量复跑；**未 merge、未 push、未改任何受版本控制文件**。复跑只重建 `target/`（构建产物），复核侧日志落 `work/evidence/review-*.log`（`work/*` 忽略区，均未跟踪）
- 复跑环境：Docker `29.6.2` 在线；Maven 命令经 PowerShell 直跑（`-Dtest` 逗号列表未过 Git Bash）；基线脚本显式 `D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh`（校验模式，未 `--update`）
- 复核轮耗时与 handoff 存在机器差异：本轮 `clean verify` 19:08（handoff 15:38），计数结论完全一致，不影响判定

---

## 0. 判定摘要

| 维度 | 结果 |
|------|------|
| git 面（写集/零 diff/基点/终态） | 通过（8/9 项；终态非空 = 任务卡未跟踪，MINOR-M4） |
| 代码面（死边/补删/IT/registry/根盘点/空转） | 通过（含 4 条文档级 MINOR） |
| 复跑面（定向单测、新 IT、clean verify、基线校验） | 全绿（证据见 §3） |
| 红线（迁移/依赖/错误码/契约/前端/裸 mapper 删） | 零触碰 |
| 阻断项 | **无** |

**合并意见**：可以合并。建议合并前（或合并同一轮）处理 M4（任务卡去留，需 Owner 拍板）与 M2/M3/M5 三处 handoff/tasks 文案更正；M1 作为已知边界记录即可，不需返工。

---

## 1. git 面

### 1.1 全量 diff 清单（`git diff --numstat master..HEAD`，9 文件 +622/−21）

| 文件 | +/− | 归类 |
|------|-----|------|
| `openspec/changes/fix-approval-attachment-cascade-dead-edge/proposal.md` | +22 | 新卡物料 |
| `openspec/changes/fix-approval-attachment-cascade-dead-edge/tasks.md` | +35 | 新卡物料（12/12 勾选） |
| `scripts/test-baseline.txt` | +5/−5 | 基线台账 |
| `src/main/java/com/slz/crm/server/config/ForeignKeyConfig.java` | +8/−9 | 死边移除 |
| `src/main/java/com/slz/crm/server/service/impl/SalesOpportunityServiceImpl.java` | +48/−0 | 显式补删 |
| `src/test/java/com/slz/crm/common/untils/ForeignKeyDeleteUtilBatchTest.java` | +11/−7 | registry 清空 |
| `src/test/java/com/slz/crm/integration/controller/SalesOpportunityDeleteAttachmentCascadeIT.java` | +207 | 新 IT |
| `src/test/java/com/slz/crm/unit/service/SalesOpportunityServiceTest.java` | +61 | 委托/顺序单测 |
| `work/handoff-fix-approval-attachment-cascade-dead-edge.md` | +225 | 交接快照 |

写集面与执行提示词预期一致：**无**支撑类新增、**无**超出「实现 + 单测 + 新 IT + registry + 基线 + 卡物料」的文件。任务卡 `work/task-card-fix-approval-attachment-cascade-dead-edge.md` 未入库（M4）。

### 1.2 必须零 diff 的面（实测全部为空）

```
git diff --name-only master..HEAD -- pom.xml                        → 空
git diff --name-only master..HEAD -- frontend/                      → 空（含 typed-router.d.ts / error-code-map.ts）
git diff --name-only master..HEAD -- src/main/resources/db/migration → 空
git diff --name-only master..HEAD -- src/main/java/com/slz/crm/common/untils → 空
```

`git diff --check master..HEAD` → EXIT=0（无空白错误）。

### 1.3 基点、HEAD、终态、未推送

- HEAD = `a24c85daf7…`，分支 = `feature/fix-approval-attachment-cascade-dead-edge`，merge-base = master = `5024d8e`（无 rebase/夹带 master 新提交）
- `git branch -r --contains HEAD` → 空；`origin/master` = `5024d8e` ⇒ **分支未 push、未 merge**
- `git status --porcelain=v1 --untracked-files=all` → **仅** `?? work/task-card-fix-approval-attachment-cascade-dead-edge.md`（M4）；其余终态干净（`work/evidence/**` 与 `target/**` 均被忽略）

### 1.4 提交结构（4 笔，均含预期内容）

| # | hash | 内容 |
|---|------|------|
| 1 | `a688198` | 实现+测试（5 文件，+337/−16） |
| 2 | `56ca735` | PMD `OnlyOneReturn` 单出口重构（仅 `SalesOpportunityServiceImpl` +18/−20） |
| 3 | `5ec0793` | 基线台账（仅 `scripts/test-baseline.txt`） |
| 4 | `a24c85d` | 卡物料 + handoff（3 文件，docs-only） |

顺序与 handoff §10/§12-D7 一致；无 amend 痕迹（独立第 2 笔，理由 handoff §12-D3）。

---

## 2. 代码面（逐项实测）

### 2.1 死边移除干净 + ALL_RELATIONS 评估有实测依据

- `ForeignKeyConfig.java:163-170` 保留「已移除 + 为什么」注释（原文含真库只有 `and_id`、实体只有 `andId`、缓存态必抛 `ID_NOT_EXISTS`、改由商机删除路径 canonical 补删）。
- `ALL_RELATIONS` 初始化块（`:55-80`）**已无** `ApprovalAttachmentEntity` 条目；`APPROVAL_ATTACHMENT_RELATIONS` 常量声明已删；全仓 `git grep APPROVAL_ATTACHMENT_RELATIONS` 仅命中 handoff 文字 ⇒ 无残留引用。
- `SALES_STAGE_APPROVAL_RELATIONS` 现为空图（`:163` 起只有注释，无 `put`）。
- **空图移除的等价性实测**：`git grep -n ApprovalAttachmentEntity -- src/main/java` 在 `ForeignKeyConfig` 零命中，且全部 `*_RELATIONS` 的 child 值中无 `ApprovalAttachmentEntity` ⇒ 无任何边指向该实体；`ForeignKeyDeleteUtil:51-55` 对「null / 空 map」走同一早退分支 ⇒ 删除空图条目与保留空图行为等价。`ForeignKeyDeleteUtilBatchTest#cascadeGraph` 仍遍历真实图全绿（§2.4）。
- **级联图无 `and_id` 裸列边**：`git grep -n "andId\|and_id\|approvalId" ForeignKeyConfig.java` 仅命中新注释 3 行，无任何关系边。

### 2.2 显式补删（顺序/模型/canonical/事务/异常路径）

- **顺序**：`SalesOpportunityServiceImpl.java:162` `deleteStageApprovalAttachments(id)` 在 `:164` `deleteCascade(...)` 之前；`@Transactional(rollbackFor = Exception.class)` 位于 `:150`，覆盖补删与级联。
- **收集口径**：`:191-199` 复用既有 `salesStageApprovalMapper.selectAllApprovalsByOpportunityId`（`SalesStageApprovalMapper:37-38`，`SELECT * ... WHERE opportunity_id = ?`，无 `is_deleted` 过滤——`sales_stage_approval` 表本身无 `is_deleted` 列，级联物理删全部审批行，覆盖一致）。
- **多态判别**：`:201-203` `listEntitiesByAndIds(approvalIds, ModelName.APPROVAL_ATTACHMENT)`；`ModelName.APPROVAL_ATTACHMENT = "approval_attachment"`。该 service 实现 `ApprovalAttachmentServiceImpl:325-335` 同时按 `and_id IN` 与 `model_name =` 过滤，**无** `@Privacy` 切面（不会因数据范围拦截半途失败）。
- **canonical 删除**：`:205-210` 仅取附件 id 后调 `approvalAttachmentService.removeByIds(ids, ModelName.APPROVAL_ATTACHMENT)`；该实现 `:89-107` = `IOUtils.deleteFile(entities)`（磁盘）+ `deleteBatchIds(ids)`（DB 物理删）。`git grep ApprovalAttachmentMapper SalesOpportunityServiceImpl` 零命中 ⇒ **无 mapper 裸删**。
- **无新增 SQL/依赖/常量**：未改 mapper；复用既有查询；`pom.xml` 零 diff。
- **异常路径（M1）**：DB 侧由 `@Transactional` 保证原子（任一步抛异常整事务回滚，不会半删行）；**磁盘侧非事务**——若 `removeByIds` 成功后 `deleteCascade`/`logicalDeleteById` 抛异常，回滚会恢复附件行但文件已物理删除。缓解事实：`IOUtils.deleteFile` 对「文件不存在」是 no-op（`IOUtils:242-250`），重试删除可自愈；且该形态与 `deleteStage`（`SalesStageApprovalController:138/155`）同型，属既有代码库口径、非本卡引入。handoff 未记录该边界（MINOR-M1）。

### 2.3 新 IT 质量

`src/test/java/com/slz/crm/integration/controller/SalesOpportunityDeleteAttachmentCascadeIT.java`（207 行）：

- 种子：固定主键商机 `9500001`（stage=5）、审批 `9600001`、`model=approval_attachment` 目标附件、`model=business_activity` 且**同 `and_id`** 的对照附件（`:67-100`）；`@Sql(/init_data.sql, BEFORE_TEST_METHOD)` 供 FK 父行（`:49`）。
- 断言链（非空转）：
  1. 前置种子断言（`:118-124`）：目标/对照附件各 1 行、两文件均存在；
  2. `delete()` 返回 true（`:126-127`，修复前此处抛 `ID_NOT_EXISTS`）；
  3. 审批行归零（`:129-132`）；
  4. 目标附件 DB 行归零 + 磁盘文件不存在（`:133-141`）；
  5. **他模型同 `and_id` 对照附件 DB 行存活 + 磁盘文件存活**（`:137-142`）——跨模型误杀回归网在位；
  6. 商机 `is_deleted=1`（`:143-148`）。
- 清理：`@AfterEach` 按固定主键清 DB 行并递归删两个模型目录（`:102-111`）；本轮两次独立执行后 `test-files/` 文件数为 0，清理有效、无残留。
- **前证核对（如实性）**：`work/evidence/pre-fix-it.log:111-113/161` 实测 `Tests run: 2, Failures: 1, Errors: 1` 与 `BaseException: 【...ApprovalAttachmentEntity.approvalId】不存在`，与 handoff §2 数字/文字一致；`pre-fix-evidence-only.log:108/112` 单跑 `Tests run: 1, F0/E0`（即临时回滚用例）。两点保留：临时用例未入库（handoff §12-D5 已披露）；合并运行中该临时用例自身 FAILURE（`pre-fix-it.log:159`，`expected <1> but was <2>`，系两用例共享固定主键的夹具串扰），handoff 只给数字未给归因（MINOR-M5）。

### 2.4 registry 清空后的门禁语义

- `ForeignKeyDeleteUtilBatchTest.java:68` `KNOWN_DEAD_RELATIONS = Set.of()`；`:174-198` 仍按「实体是否真实声明该属性」逐边过滤并与登记表 `assertEquals`（`:196`），**dropped 断言逻辑未弱化**：任何可达子图内的新死映射立即红，且不依赖 MP `TableInfo` 初始化顺序（`hasProperty` `:201-212`）。
- 覆盖范围限定（MINOR-M3）：`cascadeGraph` 根固定为 `CustomerContactEntity`（`:149`），只有从该根可达的子图被测到；从联系人根不可达的分支（如 `SYS_USER_RELATIONS:178+`、`SYS_ROLE_RELATIONS:172-176`、`PERMISSIONS_RELATIONS:147`）新增死映射不会转红。handoff §5 「任何未来把不存在的属性写进 ForeignKeyConfig 的改动都会立刻转红」表述过宽。

### 2.5 级联根盘点

`git grep -n "deleteCascade\|logicalDeleteByCascade" src/main/java` 实测 5 个根，与 handoff §6 表一致：

| 根 | 入口（实测行） | 图可达 `sales_stage_approval` | 附件语义 |
|----|----------------|-------------------------------|----------|
| `SalesOpportunityEntity` | `SalesOpportunityServiceImpl:164` | 是（直连 `opportunity_id`） | 本卡已补删审批附件 |
| `CustomerCompanyEntity` | `CustomerCompanyServiceImpl:259` / `:294` | 是（`company_id`→商机→审批） | 未补删（F1，属实） |
| `CustomerContactEntity` | `CustomerContactServiceImpl:261` / `:426` / `:437` | 是（`contact_id`→商机→审批） | 未补删（F1，属实） |
| `ContractEntity` | `ContractServiceImpl:291` | 否 | 不涉及审批附件 |
| `ContractOrderItemEntity` | `ContractOrderItemServiceImpl:101` | 否 | 不涉及审批附件 |

- 可达性判断正确：`CUSTOMER_COMPANY_RELATIONS → SalesOpportunityEntity(companyId)`（`ForeignKeyConfig:107-115`）、`CUSTOMER_CONTACT_RELATIONS → SalesOpportunityEntity(contactId)`（`:124-127`）、`SALES_OPPORTUNITY_RELATIONS → SalesStageApprovalEntity(opportunityId)`（`:159-161`）。
- 补充边界核实：`CustomerCompanyServiceImpl#deleteById:271` 与 `SalesOpportunityServiceImpl#cascadeDeleteByCompanyId:298` / `#cascadeDeleteByContactIds:308` 均只做 `is_deleted` 位更新、不进级联图（已读源码确认），handoff 表述准确。
- F2 核实：`ForeignKeyConfig:93-95` 确实把 `TaskCommentEntity(taskId)` 挂在 `CUSTOMER_CONTACT_RELATIONS` 下（注释写「合同任务表」），既有漂移属实。
- **盘点表「附件语义」列不完整（MINOR-M2）**：商机/公司删除还会物理删 `business_activity(opportunity_id/company_id)` 与 `contact_task(opportunity_id/company_id/contact_id)` 行（`ForeignKeyConfig:151-155`、`:107-115`、`:124-127`；`ForeignKeyDeleteUtil:222-242` 物理 `delete`），而这两类模型的附件写入方确实存在（`BusinessActivityController:147-148`、`ContactTaskController:52`）⇒ 同一「多态附件孤儿」类不止审批附件，F1 的范围应与 handoff §6 表述一并更正（不阻塞本卡）。

### 2.6 测试无断言空转

- `SalesOpportunityServiceTest:140`（新）用 `InOrder(listEntitiesByAndIds → removeByIds(canonical, ModelName) → deleteCascade)` 锁顺序，并断言 `removeByIds` 收到附件 id（非 `any()` 宽匹配）；`:176`（新）断言无审批时 `listEntitiesByAndIds` 零调用且仍走级联。两条均实际驱动 `delete()` 真实路径（stub `selectById` + `logicalDeleteById`）。
- `ForeignKeyDeleteUtilBatchTest` 的调用次数/`update`/`updateById` 断言保持（`79-143`），registry 对表断言保留。
- IT 断言全部为「删除后状态」硬断言，无 `assertNotNull` 充数、无 `@Disabled`/`assumeTrue` 静默跳过（该类在 failsafe 真跑）。

### 2.7 红线核对

零迁移（`db/migration` 零 diff）／零新依赖（`pom.xml` 零 diff）／零新错误码／冻结契约未动／`frontend/` 零 diff／附件删除只走 `removeByIds`（含磁盘清理），无 mapper 裸删。**通过**。

---

## 3. 复跑面（本轮独立实测）

> 日志：`work/evidence/review-clean-verify.log`、`review-targeted-unit.log`、`review-targeted-it.log`、`review-full-surefire.log`、`review-baseline-check.log`、`review-baseline-check2.log`（均未跟踪、被 `work/*` 忽略）。

### 3.1 定向单测（PowerShell 直跑，逗号列表）

```
mvn -B -ntp "-Dtest=ForeignKeyDeleteUtilBatchTest,SalesOpportunityServiceTest" test
→ ForeignKeyDeleteUtilBatchTest  Tests run: 5, F0, E0, S0
→ SalesOpportunityServiceTest    Tests run: 6, F0, E0, S0
→ 合计 Tests run: 11, Failures: 0, Errors: 0, Skipped: 0；BUILD SUCCESS；EXIT=0
```

（`review-targeted-unit.log:349/351/355/362`。命令与参数与 handoff §7.1 完全一致；原计划把 `target/surefire-reports` 临时移出以避免污染全量报告集，但该备份重命名被 PowerShell 拒绝、随后目录被误删，实际是在默认 reports 目录上执行的，事后以全量 `mvn test` 重建（附录 B）。）

### 3.2 新 IT（两路证据）

1. 本轮 `mvn -B -ntp clean verify` 内 failsafe 真跑：`Running ...SalesOpportunityDeleteAttachmentCascadeIT`（`review-clean-verify.log:8541`）、`Tests run: 1, F0, E0, S0`（`:8563`）。
2. 定向 failsafe 复跑：`mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=SalesOpportunityDeleteAttachmentCascadeIT"` → `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`、`BUILD SUCCESS`、`IT_EXIT=0`（`review-targeted-it.log:107/111/114`，耗时 87.55s，Docker MySQL 8.0.36 真库）。

### 3.3 全量 `mvn -B -ntp clean verify`（本轮）

| 口径 | 实测 |
|------|------|
| surefire | 报告 283 / Tests run **1779** / F0 / E0 / S0（`review-clean-verify.log:7474`） |
| failsafe | 报告 32 / Tests run **119** / F0 / E0 / **S7**（`:10615`） |
| 静态 | checkstyle 0（`:15`）、spotless 1137 clean（`:19`）、spotbugs check（`:10622-10629`）、pmd check（`:10657-10660`） |
| 结果 | `BUILD SUCCESS`（`:10662`），总耗时 19:08（`:10664`） |

与 handoff §7.2（283/1779、32/119/7、15:38）数字一致，仅耗时差异（机器/容器负载）。

### 3.4 基线校验模式（未 `--update`）

```
D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh
surefire：报告=283，Tests run=1779，F=0，E=0，S=0｜基线 reports>=283 tests>=1779 skipped<=0
failsafe：报告=32，Tests run=119，F=0，E=0，S=7｜基线 reports>=32 tests>=119 skipped<=7
回归基线门禁通过
CHECK_EXIT=0
```

（`review-baseline-check2.log`；台账 `scripts/test-baseline.txt` 四元组 = 283/1779/0、32/119/7，`source-revision=56ca735`，与 handoff §8 吻合。）复跑后 `git status` 仍仅 `??` 任务卡，`HEAD` 未变。

### 3.5 handoff 逐项声明 × 实测证据链

| handoff 声明 | 实测核对 |
|--------------|----------|
| §1 S1–S7 七源（config/V1 DDL/实体/ColumnUtils/delete 闸/附件语义/canonical 删除） | §1、V1:23-35（`and_id` :27、`model_name` :33、无 `is_deleted`）、实体仅 `andId`、`ForeignKeyDeleteUtil:252-276`、`SalesStageApprovalController:140-160/184-204`、`ApprovalAttachmentServiceImpl:89-107` 均与文字一致 |
| §2 前证双态（ID_NOT_EXISTS + 回滚） | 日志数字/异常消息一致；归因缺口见 M5 |
| §3.1 diff 摘要 4 改 1 增 + 委托单测 | 与 numstat 一致（§1.1） |
| §3.2 ALL_RELATIONS 评估（图零边 ⇒ 空图一并移除） | grep 零边 + 图测试全绿，等价性成立（§2.1） |
| §3.3 补删伪代码 | 与 `:190-213` 实现一致 |
| §4 鉴权差异（deleteStage 逐条 `canWriteAttachments` vs 本路径系统级） | `SalesStageApprovalController:148-151` 逐条判定属实；`removeByIds` 本身无鉴权（`ApprovalAttachmentServiceImpl:90-107`）属实 |
| §5 registry 语义保持 | `:196` 断言未弱化属实；「任何」过宽（M3） |
| §6 5 根/3 可达 + F1/F2 | 5 根与行号全对、可达性正确、F1/F2 属实；附件语义列不完整（M2） |
| §7.1 定向 11 / IT 1 | 本轮复跑一致（§3.1/§3.2） |
| §7.2 clean verify 283/1779、32/119/7 | 本轮独立复跑一致（§3.3） |
| §8 基线四元组 + 校验 EXIT=0 | 本轮校验 EXIT=0、四元组一致（§3.4） |
| §9 merge-gate 10/10 | 仅日志核对：`merge-gate.log` 十条 `PASS [unit/checkstyle/spotbugs/spotless/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline]`；本轮未复跑 merge-gate（超出复核提示词的三项复跑要求） |
| §10 提交清单 4 笔 | hash/message/顺序一致，未 push（§1.4） |
| §11 tasks 12 项全勾 | `^\- \[x\]` = 12，`^- \[ \]` = 0，每项下有补勾依据行 |
| §12 D1–D7 | D1（3 根）实测属实；D2（任务卡未跟踪）属实= M4；D3（PMD 单出口独立笔）属实；D4（`@Sql`）属实；D5（临时用例未入库）属实；D6（`work/evidence` 忽略）属实；D7（基线笔顺序）属实 |

---

## 4. 三级清单

### 4.1 MAJOR（0）

无。核心修复（死边移除 + 级联前按 `(model_name, and_id)` canonical 补删）逻辑正确、真库双态证据成立、复跑全绿、红线零触碰。

### 4.2 MINOR（5）

| id | 事实 | 证据 | 建议 |
|----|------|------|------|
| M1 | 异常路径「DB 原子、磁盘非事务」：补删成功后若级联/逻辑删抛异常，附件行回滚恢复但文件已删（重试因 `file.exists` no-op 可自愈） | `SalesOpportunityServiceImpl:150/162/164/210`；`ApprovalAttachmentServiceImpl:102-106`；`IOUtils:242-250/253-270` | handoff §12 增补一行边界说明；文件删除后置属独立设计，不在本卡扩写 |
| M2 | 盘点表「附件语义」列不完整：商机/公司删除同时物理删 `business_activity`/`contact_task`，其模型附件同样成孤儿，F1 范围只写了审批附件 | `ForeignKeyConfig:107-115/124-127/151-155`；`BusinessActivityController:147-148`；`ContactTaskController:52`；`ForeignKeyDeleteUtil:222-242` | 更正 handoff §6 表述（或把该类并入 F1/另立卡），不影响本卡修复 |
| M3 | handoff §5 「任何新增死映射都会立刻转红」过宽：测试只从 `CustomerContactEntity` 单根遍历可达子图；`SYS_USER/SYS_ROLE/PERMISSIONS/CUSTOMER_COMPANY` 等不可达分支新增死映射不会红 | `ForeignKeyDeleteUtilBatchTest:149/174-198/196`；`ForeignKeyConfig:147/172-176/178+` | 文案改为「可达子图内」；或另卡遍历全部 `ALL_RELATIONS.keySet()`（需先实测未覆盖分支的既有死边，勿在本卡扩写） |
| M4 | 终态非空 + 任务卡口径自相矛盾：`git status` 有 `?? work/task-card-*.md`；`tasks.md` 4.4 勾选文案写「任务卡随 docs 笔提交」而依据写「保持未跟踪」（`D2` 已披露） | `.gitignore:31-32`；`tasks.md:34-35`；`git status --porcelain` | Owner 拍板：补入 docs 笔（与 `.gitignore` 反选语义一致）或删除未跟踪文件并改 4.4 文案 |
| M5 | 前证可复现性/归因：临时回滚用例未入库（仅日志）；合并运行中该用例自身 FAILURE（夹具串扰 `expected 1 but was 2`）未在 handoff 归因，易被误读为缺陷第二表现 | `pre-fix-it.log:159/161/163`；`pre-fix-evidence-only.log:108/112`；handoff §2/§12-D5 | handoff §2 补一句「Failure 来自临时用例夹具串扰，回滚证据取单跑」；无需重跑 |

### 4.3 待 Owner 知悉（4）

1. **A1 鉴权面（已在 handoff §4 披露）**：商机删除权持有人可连带删除他人上传的阶段审批附件（系统级级联、不逐附件 `canWriteAttachments`）。`removeByIds` 本身无鉴权（`ApprovalAttachmentServiceImpl:90-107`），鉴权只在 `deleteStage`（`SalesStageApprovalController:148-151`）；本卡复用 canonical 入口未引入新端点/权限码，但把「删父对象连带删他人附件」由「事务崩溃」变为「实际发生」，属拍板形态 C 的既有级联语义。
2. **A2 既有缺口**：F1（公司/联系人根审批附件残留）、F2（`ForeignKeyConfig:95` 把 `TaskCommentEntity` 挂在联系人根）均已在 handoff §6 登记，交后续卡；另 `SalesStageApprovalService#removeByIds:321-324` 会清 `assist_request`，级联物理删审批行不经过该服务 ⇒ 商机删除后 `assist_request`（`model=sales_stage_approval`）同类残留，建议并入 F1 批次评估。
3. **A3 复核副作用**：本轮在权威树执行 `clean verify`/`mvn test`，`target/` 被重建；期间一次 reports 目录备份操作失误导致 `target/surefire-reports` 被删，已用全量 `mvn test` 重建（283/1779/0/0/0，06:46）并重跑基线校验 EXIT=0（附录 B）。**未改任何受版本控制文件**，`HEAD`/`git status` 与复核开始时一致。
4. **A4 同源硬化点（pre-existing，本卡未改）**：`ApprovalAttachmentServiceImpl#removeByIds` 按 `model_name` 过滤查询（`:91-95`）但按入参 ids 直接 `deleteBatchIds`（`:106`）；当前调用方 ids 均来自模型过滤结果，无暴露；若未来复用于混模型 id 列表会跨模型删行，建议后续统一收口。

---

## 5. 结论与建议动作

- **GO**：分支具备合并条件（无 MAJOR；三面证据自洽且可在本机复现）。
- 合并前建议（低成本、不返工）：
  1. M4：Owner 拍板任务卡去留，顺带统一 `tasks.md` 4.4 文案；
  2. M2/M3/M5：handoff 三处措辞/归因更正（M2 盘点表、M3「可达子图内」、M5 前证归因）。
- M1/A1/A2/A4 记录即可，交后续卡统一处理（F1 建议扩写为「多态附件随删除路径的孤儿清理」）。
- 合并方式沿用 `--no-ff` 保留分支；**push 需 Owner 显式授权**（本复核未执行）。

---

## 附录 A · 证据索引（本轮）

| 证据 | 位置 |
|------|------|
| git 面 | §1 命令输出（HEAD/merge-base/numstat/name-status/status/`branch -r --contains`） |
| 全量复跑 | `work/evidence/review-clean-verify.log`（283/1779、32/119/7、BUILD SUCCESS、19:08） |
| 定向单测 | `work/evidence/review-targeted-unit.log`（11/0/0/0，EXIT=0） |
| 定向 IT | `work/evidence/review-targeted-it.log`（1/0/0/0，IT_EXIT=0） |
| 基线校验 | `work/evidence/review-baseline-check2.log`（CHECK_EXIT=0） |
| 全量 surefire 重建 | `work/evidence/review-full-surefire.log`（1779/0/0/0，06:46） |
| handoff 既有证据 | `work/evidence/pre-fix-it.log`、`pre-fix-evidence-only.log`、`post-fix-it.log`、`targeted-unit.log`、`baseline-update.log`、`baseline-check.log`、`clean-verify.log`、`merge-gate.log` |

## 附录 B · 复核侧对 `target/` 的一次误操作（如实披露）

为隔离定向单测的报告目录，执行 `Rename-Item target\surefire-reports target\surefire-reports.fullrun` 时报「Cannot rename ... path or device name」，脚本按顺序继续执行并删除了 `target/surefire-reports`（仅构建产物，非受版本控制文件）；定向单测本身已在默认目录上跑完并留证。随后：

1. `mvn -B -ntp test` 全量重建 → `Tests run: 1779, F0, E0, S0`，BUILD SUCCESS，06:46（`review-full-surefire.log:7458/7465/7467`）；
2. 重新执行基线校验 → `CHECK_EXIT=0`，四元组 283/1779/0、32/119/7（`review-baseline-check2.log`）；
3. `git status` 仍仅 `?? work/task-card-*.md`，`HEAD` 未变。

该失误不影响任何结论：所有源码、基线台账、卡物料均未改动；`target/` 最终状态为一次完整 `mvn test` + 一次完整 failsafe 报告集（32 份）。
