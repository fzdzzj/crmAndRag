# handoff · fix-engineering-debt-nplus1-loop-clone（工程债三合卡，A 档）

- 权威树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- 基点：`master@6e99443`（surefire 275 报告 / 1720 用例、failsafe 31 报告 / 118 用例 / 7 skipped、merge-gate 10/10，卡面锚点 2026-10-19 实测）
- 分支：`feature/fix-engineering-debt-nplus1-loop-clone`（自 master 检出，已保留）
- 状态：**未 merge、未 push**；三笔提交已落分支，待 A 档独立复核
- 日志留证（不入库，`work/*` 已 gitignore）：`work/targeted-test.log`、`work/clean-verify.log`、`work/merge-gate.log`

---

## 1. 三模块锚点前后对照与 diff 摘要

### A-7 UserHandover 分页 N+1（`UserHandoverServiceImpl.java`，单文件）

| 项 | 前（master@6e99443） | 后（2acabe2） |
| --- | --- | --- |
| 分页转换 | `convertToVOPage` :202-213，:207-209 逐行 `buildHandoverVO` | `convertToVOPage` :206-224，先 `batchGetUserNames(entities)` 再回填 |
| 姓名取值 | 每行 3 次 `getUserName`（:157-166）→ `userMapper.selectById` | 页内 from/to/operator 三类 id 去 `LinkedHashSet` 后**单次** `selectBatchIds`，建 `Map<Long,String>` 回填（新 :228-248） |
| 查询次数 | `3N + 1`（N=页大小） | `2`（分页 1 + 批量用户 1；空页 1，见下） |
| 单条路径 | `executeHandover` → `buildHandoverVO` → `getUserName` 逐条 | **未改**（`buildHandoverVO`/`getUserName` 原样保留，新 :161/:152-158） |

diff 摘要：imports 增 `HashMap/LinkedHashSet/Map/Set`；`convertToVOPage` 循环体改为直接 `UserHandoverVO.fromEntity(entity, map.get(from), map.get(to), map.get(operator))`；新增私有 `batchGetUserNames` + 静态 `collectUserId`。

等价性注意点：
- 空 id / null id：旧实现 `getUserName(null)` 直接返回 null 不查库；新实现 `collectUserId` 跳过 null，`HashMap.get(null)` 返回 null。**null 姓名语义一致**。
- 用户被删除（批量结果缺该 id）：旧实现 `selectById` 为 null → 姓名 null；新实现 map 无该键 → null。**一致**。
- 用户存在但 `real_name` 为 null：两边都回填 null。**一致**。
- 空页（records 为空）：旧实现 0 次用户查询；新实现 `userIds.isEmpty()` → 不查。**一致**。
- `selectBatchIds` 入参是 `LinkedHashSet<Long>`（保序去重）；MyBatis 生成 `IN (...)`，与逐条 `selectById` 的可见结果集合相同。

### A-9 Invoice / Payment 批量软删双胞胎（2 文件）

| 服务 | 前（master@6e99443） | 后（2acabe2） |
| --- | --- | --- |
| `InvoiceInfoServiceImpl.deleteByIds` | :155-171，`for` 循环 `selectById` + `entity.setStatus(1)` + `updateById`，计数 `count += updateById(...)` | :156-165，`LambdaUpdateWrapper.set(getStatus,1).in(getId,idList)` + `invoiceInfoMapper.update(null, wrapper)` |
| `PaymentRecordServiceImpl.deleteByIds` | :174-190，同形，`entity.setPaymentStatus(2)` | :175-184，`set(getPaymentStatus,2).in(getId,idList)` + `paymentRecordMapper.update(null, wrapper)` |
| SQL 条数 | `2N`（每 id 一条 SELECT + 一条 UPDATE） | `1`（一条 `UPDATE ... SET status=1 WHERE id IN (...)`） |
| 形态对齐 | 两处 body 逐字同形 | 两处 body 逐字同形（仅实体 / 状态列 / SET 值不同） |

前置校验 `idList == null || isEmpty()` → `BaseException(ErrorCode.PARAM_EMPTY)` 两处均原样保留；`@Transactional(rollbackFor = Exception.class)` 未动；错误码与文案零变化。

### A-1 SQLExceptionHandler 双轨收敛（handler + application.yml）

| 项 | 前 | 后 |
| --- | --- | --- |
| 唯一索引名映射 | 手写 `switch`（`getFieldNameByIndex` :78-102，:89-99 七 case） | `databaseFieldMap.getOrDefault(parts[1], "数据")`（新 :82-92），**配置单源** |
| 配置 | `slz.database-field-map` :220-240，仅列名键（20 项） | 追加 7 个索引名键（:244-250），配置块加注释说明「唯一维护点」（:219-222） |
| `default` 语义 | `default -> "数据"` | `getOrDefault(..., "数据")`，**「数据」保留** |
| 裸列名（无表名前缀） | `parts.length < 2` → 「数据」 | 同左 → 「数据」（`doesn't have a default value` 路径逐字不变） |
| TODO | `getNameByIndex` 上 `/** 根据字段名获取该字段的评论 TODO注意维护 */`（:104） | `TODO` 消除，改为「映射单源同上，缺失时回落『未知字段』」维护说明（新 :94） |
| duplicate-key 组合索引分支 | `compositeUniqueIndexMap`（:48-51 路径） | **未改**（仍先查组合表，文案仍为 `<组合名> <值首段> 已存在`） |
| FK 分支 | `getNameByIndex`（列名 → 中文，缺失「未知字段」） | **未改**（同一 `databaseFieldMap` 现同时承载列名与索引名键，键空间不重叠） |

三 handler 逐路径文案均未变（`数据已存在，无法重复添加` / `<字段> <值> 已存在` / `ID_IS_NULL：【...】不存在` / `未知字段存在关联数据` / `未知错误` / `缺少必填字段：...` / `SERVER_ERROR`）。

---

## 2. A-9 等价性论证

**语义等价面（立项实测 + 本次复核）**

1. **只更新存在的行**：`WHERE id IN (...)` 只命中库中真实存在的行；旧实现也是「`selectById` 为 null 则跳过、不 update」。不存在 id 在两边都不产生更新。
2. **零乐观锁 / 零逻辑删除**：`InvoiceInfoEntity` / `PaymentRecordEntity` 均无 `@Version`、无 `@TableLogic`（`grep -n "@Version|@TableLogic"` 对两文件零命中）；`InvoiceInfoServiceImpl` 用的是独立 mapper，不经过 `ServiceImpl.updateById` 的字段策略差异。
3. **零自动填充**：`grep -rn "MetaObjectHandler" src/main/java` 零命中 → 不存在「`updateById(entity)` 会补 `update_time`、而 `update(null, wrapper)` 不补」的语义差。旧实现 `updateById(entity)` 也是全量字段更新（实体由 `selectById` 载入，非空字段全带），新实现只 SET 状态列——**实际写入列变窄**，但因为目标列只有状态列且其余列值来自同一行快照，净效果等价；这也顺带消除了「带旧快照回写其他列」的潜在覆盖风险。
4. **返回计数含义**：旧实现 = 「`updateById` 返回行数之和」；新实现 = 「一条 UPDATE 的影响行数」。两者在 MySQL Connector/J 默认 `useAffectedRows=false`（URL 未显式开启，见 application.yml :35）下都是**匹配行数 → 存在行数**。测试以 mock 返回值锁定「返回计数 = 数据库命中行数」这一契约。

**行为差异点（单列，以新实现为准）**

- **重复 id 输入**：`idList = [11, 11]`，行 11 存在时——旧实现 `[selectById(11) → status=1, updateById → 1, selectById(11) → status=1, updateById → 1]`，**返回 2**；新实现 `WHERE id IN (11,11)` 只命中 1 行，**返回 1**。即：新实现按**实际行数**计数，旧实现按**输入元素数**计数。
- 判定：返回值的语义在「批量软删」语境下应是「实际作废了多少行」，新行为更正确；调用方（controller）只透传该整数，不做算术依赖；重复 id 属前端重复提交/入参脏数据，非契约路径。
- 该差异已被 `InvoiceInfoDeleteByIdsBatchTest#deleteByIds_duplicateIds_singleUpdateCountedByRows` 与 `PaymentRecordDeleteByIdsBatchTest#deleteByIds_duplicateIds_singleUpdateCountedByRows` 显式锁定（断言计数=1）。

---

## 3. A-1 yml 增键 7 项逐项对照表

`src/main/resources/application.yml` → `slz.database-field-map`（单引号 JSON 标量，:223-250）

| # | 索引名键（新增） | yml 值 | 原 switch 值（master@6e99443 :91-97） | 一致 |
| --- | --- | --- | --- | --- |
| 1 | `uk_phone` | 手机号 | 手机号 | ✅ |
| 2 | `uk_email` | 邮箱 | 邮箱 | ✅ |
| 3 | `uk_company_name` | 企业名字 | 企业名字 | ✅ |
| 4 | `idx_mobile` | 联系电话 | 联系电话 | ✅ |
| 5 | `uk_contract_no` | 合同编号 | 合同编号 | ✅ |
| 6 | `uk_payment_no` | 支付编号 | 支付编号 | ✅ |
| 7 | `uk_invoice_no` | 发票编号 | 发票编号 | ✅ |

- 与既有 20 个列名键**键空间不重叠**（列名键形如 `user_id`，索引名键形如 `uk_*`/`idx_*`），因此 `getNameByIndex`（FK 路径，传列名）与 `getFieldNameByIndex`（duplicate-key 路径，传索引名）共用同一 Map 不会互相污染。
- `slz.composite-unique-index-map`（:251-254）三项未动：`uk_share_unique` / `uk_role_permission` / `contract_order_item_pk`。
- 配置块的「唯一维护点」注释（:219-222）即本项新增的维护说明单点化载体。

---

## 4. 定向测试清单与结果

命令（PowerShell 直跑，未过 Git Bash，避免逗号被注入空格）：

```
mvn -B -ntp "-Dtest=UserHandoverServiceImplTest,InvoiceInfoDeleteByIdsBatchTest,PaymentRecordDeleteByIdsBatchTest,SQLExceptionHandlerTest" test
```

| 测试类 | 用例数 | 覆盖点 | 结果 |
| --- | --- | --- | --- |
| `unit.service.UserHandoverServiceImplTest`（新增） | 3 | 分页 `selectBatchIds` 恰一次 / `selectById` 零调用 / 三名字回填 / 去重集合 size=3；缺失用户与空页回落；`executeHandover` 单条路径 `selectById` 5 次且无批量查询 | 全绿 |
| `unit.service.InvoiceInfoDeleteByIdsBatchTest`（新增） | 3 | 单条 `update(isNull(), wrapper)`：SET `status=`、`id IN`、参数含 1 与两 id；计数=2；`selectById`/`updateById` never()；重复 id 计数=1；空入参 PARAM_EMPTY 且零交互 | 全绿 |
| `unit.service.PaymentRecordDeleteByIdsBatchTest`（新增） | 3 | 同型，SET `payment_status=`、参数含 2；其余同上 | 全绿 |
| `unit.server.handler.SQLExceptionHandlerTest`（新增） | 9 | 直接从 application.yml 读回两套映射注入（yml 少键/错值即红）：7 键逐项映射、已知索引文案、未知索引「数据」、组合索引「共享 客户A」、不可解析兜底、FK 用户ID、FK 未知字段、`doesn't have a default value` 裸列名「数据」 | 全绿 |

定向合计：**Tests run: 18, Failures: 0, Errors: 0, Skipped: 0 → BUILD SUCCESS**（`work/targeted-test.log`；PowerShell `Tee-Object` 末段偶发 exit 1 假信号，以日志 `BUILD SUCCESS` 为准）。

全量：`docker info` 实测 **29.6.2 Docker Desktop 在线**；`mvn -B -ntp clean verify` → **BUILD SUCCESS**（`work/clean-verify.log`）

- surefire：`Tests run: 1738, Failures: 0, Errors: 0, Skipped: 0`（基线 1720 → **+18**，全部为本次 4 个新测试类）
- failsafe：`Tests run: 118, Failures: 0, Errors: 0, Skipped: 7`（与基线持平）
- 静态门禁：checkstyle / spotless / PMD（0 violation）/ SpotBugs（High 基线 12 条双射）全过
- 中途一次红→绿：PMD `OnlyOneReturn` 命中 `batchGetUserNames` 的多出口写法，已改为单出口（逻辑等价，`userIds.isEmpty()` 提前返回并入 `if (!userIds.isEmpty())`），复跑 `mvn -B -ntp pmd:check` BUILD SUCCESS 后再做全量。

---

## 5. 基线四元组

树净单次 `--update`（Git Bash 绝对路径，PATH 的 bash 解析到未装 WSL）：

| 口径 | 报告数 | 用例数 | skipped | 变化 |
| --- | --- | --- | --- | --- |
| surefire | 279 | 1738 | 0 | 275/1720 → 279/1738（+4 报告 / +18 用例） |
| failsafe | 31 | 118 | 7 | 持平 |

- `scripts/test-baseline.txt`：`source-revision=2acabe2`、`measured-at=2026-10-19T07:57:00Z`
- 校验模式复跑：`surefire 报告=279 Tests run=1738 … 基线 reports>=279 tests>=1738 skipped<=0` / `failsafe 报告=31 Tests run=118 Skipped=7` → **「回归基线门禁通过」CHECK_EXIT=0**
- 口径外残留：无（`mvn clean verify` 后无 `*Benchmark` 等 opt-in 报告）

---

## 6. merge-gate 10/10

`D:\git\Git\bin\bash.exe scripts/merge-gate.sh` → **GATE_EXIT=0**，末行「== merge-gate 通过：所有已执行的子门禁绿 ==」（`work/merge-gate.log`）

| # | 子门禁 | 结果 |
| --- | --- | --- |
| 1 | `[unit]` | PASS（Tests run: 1738, Failures: 0, Errors: 0） |
| 2 | `[checkstyle]` | PASS |
| 3 | `[spotbugs]` | PASS |
| 4 | `[spotless]` | PASS |
| 5 | `[pmd]` | PASS（violations=0） |
| 6 | `[baseline]` | PASS |
| 7 | `[frontend-unit]` | PASS（Test Files 21 passed / Tests 197 passed） |
| 8 | `[hook]` | PASS |
| 9 | `[bijection]` | PASS（未过滤 High=12 ↔ 12 条豁免） |
| 10 | `[pmd-baseline]` | PASS（实测 0 == 登记 0 == 阈值 0） |

typed-router 生成物噪声：vitest 改写 `frontend/typed-router.d.ts` 行尾，已按卡面 `git checkout -- frontend/typed-router.d.ts` 还原；还原后 `git status --short` 仅剩待入库的 docs 物料。

---

## 7. 提交哈希清单（单行 ASCII message，三笔）

| 序 | 哈希 | message | 入库内容 |
| --- | --- | --- | --- |
| 1 | `2acabe2` | `fix(debt): batch handover user lookup and invoice payment soft delete, unify sql field map` | 3 个 service/handler + application.yml + 4 个新测试类（9 files, +738/−44） |
| 2 | `3b68766` | `test(baseline): update clean baseline to 2acabe2` | `scripts/test-baseline.txt`（+4/−4） |
| 3 | docs 笔 | `docs(card): record fix-engineering-debt-nplus1-loop-clone evidence and handoff` | openspec 卡（proposal + tasks 含勾选证据）、`work/handoff-*.md`、`work/task-card-*.md` |

红线自查：`db/migration` 零改动（`git diff --stat master..HEAD -- src/main/resources/db/migration` 空）、`pom.xml` 零改动、零新增错误码常量（`ErrorCode` 未改）、冻结契约未动（`spec/changes/.../contracts-frozen.md` 未改）、`frontend/` 零 diff。

---

## 8. tasks.md 勾选证据

`openspec/changes/fix-engineering-debt-nplus1-loop-clone/tasks.md` 全部 4 组 12 项已勾选，每行下 6 空格「补勾依据（2026-10-19 实测命令与结论）」；证据文本与本文 §1-§7 的实测数字同源。

---

## 9. 偏差清单（与卡面目标的差异）

| 偏差 | 说明 | 影响 |
| --- | --- | --- |
| 无功能性偏差 | 三模块目标均按卡面达成，零红线触碰 | — |
| PMD `OnlyOneReturn` 返工 1 次 | 初版 `batchGetUserNames` 用 `if (empty) return` 早退，被 PMD 判 1 条违规（`clean verify` 首轮红）；改为单出口后绿 | 仅实现内部结构，对外行为不变；已在 §4 记录 |
| `selectBatchIds` 入参类型 | 卡面写「去重后 `selectBatchIds`」；实现用 `LinkedHashSet<Long>`（保序去重）而非 `List`，语义等价且少一次 `distinct` | 无 |
| 计数语义细差 | A-9 重复 id 输入下旧 2 / 新 1（卡面已预告「重复 id 输入按实际行数计」） | 已单列论证并测试锁定 |
| A-1 配置注释新增 | 为「维护说明单点化」在 `database-field-map` 上方加 3 行 YAML 注释（配置内容零变化） | 无（注释不进 JSON 标量） |

**停步声明**：本次仅完成实现、自跑门禁与分支提交，**未执行 merge、未 push、未 reset/rebase/amend、未删分支**；分支 `feature/fix-engineering-debt-nplus1-loop-clone` 保留待 A 档独立复核。
