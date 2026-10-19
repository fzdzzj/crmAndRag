# 复核报告 · fix-engineering-debt-nplus1-loop-clone（A 档，独立复核）

- **结论：GO**（0 MAJOR / 3 MINOR / 4 待 Owner 知悉；无阻断项，可提请 merge 授权）
- 复核对象：权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/fix-engineering-debt-nplus1-loop-clone`（未 merge、未 push）
- 基点 `master@6e99443`；三笔提交 `2acabe2`（fix）→ `3b68766`（test baseline）→ `91f6b71`（docs card）
- 复核方式：只读核验 + 定向复跑。**未 merge / 未 push / 未改任何被跟踪文件 / 未 reset / 未 rebase**；仅新增两个 gitignore 范围内的临时日志（`work/review-mvn-test.log`、`work/review-baseline.log`，属 `work/*` + `*.log` 规则内）用于留证
- 复核时间：2026-10-19（本机实测，git 2.20.1.windows.1 → `git branch --show-current` 不可用，改用 `rev-parse --abbrev-ref`）

---

## 0. 结论摘要

| 面 | 判定 | 关键实测 |
| --- | --- | --- |
| git 面 | PASS | HEAD=91f6b71 / master=6e99443 未动 / 全量 diff 14 文件 = 预期写集 / `db/migration`·`pom.xml`·`frontend/` 零 diff / `status --porcelain` 终态空 |
| 代码面 A-7 | PASS | 分页路径单次 `selectBatchIds`（`LinkedHashSet` 去重 + null 跳过）、`selectById` 零残留、单条路径零改动、单出口形态合规 |
| 代码面 A-9 | PASS | 两处逐字同形单条 `LambdaUpdateWrapper`（status=1 / paymentStatus=2 各就各位 + IN），逐条调用零残留，`PARAM_EMPTY` 与 `@Transactional` 保留，重复 id 差异已被测试锁定并单列 |
| 代码面 A-1 | PASS | yml 7 键与原 switch 逐项一致、switch 已删、`parts.length<2` 守卫仍在、三 handler 其余路径逐字未动、测试为 yml 驱动（非复制 switch） |
| 测试面 | PASS | 4 类 18 用例（3/3/3/9），断言均为真实行为断言（次数/参数/SQL 片段/文案），无空转 |
| 复跑面 | PASS | `mvn -Dtest=4类 test` → Tests run: 18 / 0F / 0E / 0S + BUILD SUCCESS；`check-test-baseline.sh` 校验模式 **EXIT=0**，surefire 279/1738/0、failsafe 31/118/7，`source-revision=2acabe2` |

未复跑项（明确声明，非缺陷）：`mvn clean verify` 全量与 `merge-gate.sh` 10/10 超出本次定向复跑预算，改以作者原始日志交叉印证（见 §3.4）；结论不依赖其假绿。

---

## 1. git 面（逐项实测）

### 1.1 引用与树态

```
git rev-parse --abbrev-ref HEAD  -> feature/fix-engineering-debt-nplus1-loop-clone
git rev-parse HEAD               -> 91f6b719cb27c92eb668512a20f65f57313a081e
git rev-parse master             -> 6e994432056642e6c8e0a19fba79a9792b97aab6   （基点未动）
git status --porcelain           -> 空（复核开始与结束各测一次，均空）
git log --oneline -5             -> 91f6b71 / 3b68766 / 2acabe2 / 6e99443 / 49e3288
```

### 1.2 全量 diff 清单（`git diff --name-status master..HEAD`，14 文件）

| 状态 | 路径 | 归属 |
| --- | --- | --- |
| M | `src/main/java/com/slz/crm/server/service/impl/UserHandoverServiceImpl.java` | A-7（2acabe2） |
| M | `src/main/java/com/slz/crm/server/service/impl/InvoiceInfoServiceImpl.java` | A-9（2acabe2） |
| M | `src/main/java/com/slz/crm/server/service/impl/PaymentRecordServiceImpl.java` | A-9（2acabe2） |
| M | `src/main/java/com/slz/crm/server/handler/SQLExceptionHandler.java` | A-1（2acabe2） |
| M | `src/main/resources/application.yml` | A-1（2acabe2，仅 `slz.database-field-map`） |
| A | `src/test/java/com/slz/crm/unit/service/UserHandoverServiceImplTest.java` | A-7 测试 |
| A | `src/test/java/com/slz/crm/unit/service/InvoiceInfoDeleteByIdsBatchTest.java` | A-9 测试 |
| A | `src/test/java/com/slz/crm/unit/service/PaymentRecordDeleteByIdsBatchTest.java` | A-9 测试 |
| A | `src/test/java/com/slz/crm/unit/server/handler/SQLExceptionHandlerTest.java` | A-1 测试 |
| M | `scripts/test-baseline.txt` | 3b68766（基线笔，卡面 4.2 要求，handoff §5 已声明） |
| A | `openspec/changes/fix-engineering-debt-nplus1-loop-clone/proposal.md` | 91f6b71 卡物料 |
| A | `openspec/changes/fix-engineering-debt-nplus1-loop-clone/tasks.md` | 91f6b71 卡物料 |
| A | `work/handoff-fix-engineering-debt-nplus1-loop-clone.md` | 91f6b71 交接快照 |
| A | `work/task-card-fix-engineering-debt-nplus1-loop-clone.md` | 91f6b71 任务卡 |

写集面判定：**与授权写集完全一致**。3 处「预期外」条目均有据：`scripts/test-baseline.txt` 属卡面 4.2 的基线笔（独立提交）；`work/handoff-*.md`、`work/task-card-*.md` 被 `.gitignore:31-34`（`work/*` + `!work/task-card-*.md` + `!work/handoff-*.md`）显式放行，属精选入库设计，不是未跟踪污染。

### 1.3 零 diff 红线路径（全部实测为空）

```
git diff --name-only master..HEAD -- src/main/resources/db/migration pom.xml frontend  -> 空
```
- `db/migration`：零 diff（Flyway 链未动）
- `pom.xml`：零 diff（零新依赖）
- `frontend/`：零 diff，含被点名的 `frontend/typed-router.d.ts` 与 `frontend/src/**/error-code-map.ts`（全目录零 diff 已覆盖两者）
- 另：`src/main/java/**/ErrorCode.java`、`spec/changes/.../contracts-frozen.md` 均不在 diff 清单内 → 零新增错误码、未动冻结契约

### 1.4 逐笔提交内容（`git show --stat`）

| 提交 | 内容 | 与 handoff §7 一致性 |
| --- | --- | --- |
| `2acabe2` | 9 files, +738/−44（4 源文件 + application.yml + 4 测试类） | ✅ 逐字吻合 |
| `3b68766` | 1 file, +4/−4（仅 `scripts/test-baseline.txt`） | ✅ |
| `91f6b71` | 4 files, +256（proposal / tasks / handoff / task-card） | ✅ |

`application.yml` 的 diff 只有两处 hunk：`slz.database-field-map` 上方新增 3 行注释（:219-222）+ JSON 标量内追加 7 个索引名键（:244-250，合计 +12/−2 行内变更），**未触碰 datasource / 其他配置块**。

---

## 2. 代码面（逐项读码实测）

### 2.1 A-7 UserHandover 分页 N+1（`UserHandoverServiceImpl.java`）

| 核验点 | 实测 | 判定 |
| --- | --- | --- |
| 分页路径结构 | `queryHandoverRecords` :173-203 → :199 `page(...)` → :202 `convertToVOPage`；`convertToVOPage` :206-225 先取 `entities`（:210）→ `batchGetUserNames`（:211）→ 循环 `UserHandoverVO.fromEntity`（:215-221） | ✅ |
| 三类 id 去重 | :229 `Set<Long> userIds = new LinkedHashSet<>()`；:231-233 逐行采集 from/to/operator；`:229` 保序去重 | ✅ |
| null id 安全 | :251-255 `collectUserId` 跳过 null（不查库）；`HashMap.get(null)` 返回 null → 姓名 null，与旧 `getUserName(null)` 语义一致 | ✅ |
| Map 回填正确 | :238 `selectBatchIds(userIds)` 恰一次；:239-246 以 `user.getId() → getRealName()` 建 Map；:218-220 取 from/to/operator 三键 | ✅ |
| 空页零查询 | :237 `if (!userIds.isEmpty())` 包裹唯一查询 → 空页 0 次用户查询 | ✅ |
| 分页路径 `selectById` 零残留 | 全文件 `selectById` 仅 :85 / :91（`executeHandover` 校验 from/to 用户）与 :166（`getUserName`，仅被 :152-158 `buildHandoverVO` 调用）；`buildHandoverVO` 唯一调用点是 :79 单条路径 | ✅ |
| 单条路径未变 | `buildHandoverVO` :152-158、`getUserName` :161-170、`executeHandover` 调用链在 diff 中零改动（仅 comments 未动） | ✅ |
| PMD `OnlyOneReturn` 合规 | `convertToVOPage`（:206-225）单 return :224；`batchGetUserNames`（:228-248）单 return :247；`collectUserId` 为 void 单出口。与 handoff §4 记录的「返工后单出口」形态一致 | ✅ |
| 查询复杂度 | 每页 1（分页）+ 1（批量用户）= 2 次，取代 3N+1 | ✅ |

### 2.2 A-9 Invoice / Payment 批量软删（双胞胎）

**Invoice**（`InvoiceInfoServiceImpl.java:154-165`）与 **Payment**（`PaymentRecordServiceImpl.java:173-184`）：

| 核验点 | Invoice | Payment | 判定 |
| --- | --- | --- | --- |
| `@Transactional` 保留 | :155 | :174 | ✅ |
| `PARAM_EMPTY` 前置保留 | :157-159（null 或空） | :176-178 | ✅ |
| 状态列各就各位 | :163 `set(getStatus, 1)` | :182 `set(getPaymentStatus, 2)` | ✅ |
| IN 条件 | :163 `.in(getId, idList)` | :182 同形 | ✅ |
| 单条 update + 单 return | :164 `return invoiceInfoMapper.update(null, wrapper)` | :183 同形（`paymentRecordMapper`） | ✅ |
| 逐条 `selectById`/`updateById` 零残留 | 方法体内零命中（等价判断只依赖 WHERE id IN） | 同上 | ✅ |
| 两处形态逐字对齐 | 除「实体类名 / 状态 getter / SET 值 / mapper 变量」外语句逐字相同 | 同上 | ✅ |
| 返回计数语义 | 直接透传「单条 UPDATE 的影响行数」（:164 / :183） | 同上 | ✅ |

等价性实测（支持 handoff §2）：
- `InvoiceInfoEntity` / `PaymentRecordEntity` **零 `@Version` / 零 `@TableLogic`**；`src/main/java` **全仓零 `MetaObjectHandler`** → 不存在乐观锁、逻辑删除、自动填充三条语义差。
- 数据源 URL（`application.yml:35`）**未设 `useAffectedRows`** → Connector/J 默认 `useAffectedRows=false`（置 CLIENT_FOUND_ROWS），旧 `updateById` 与新 `update` 的返回都是「匹配行数」，除重复 id 去重外计数口径一致。
- 调用方仅透传：`InvoiceInfoController:55` / `PaymentRecordController:55` 均 `Result.success(service.deleteByIds(...))`；`frontend/src` 内 `deleteByIds` **零命中** → 重复 id 的计数差异无前端算术依赖。
- 行为差异（重复 id：旧按输入元素数计 2 / 新按实际命中行数计 1）**已被两条测试显式锁定**（`InvoiceInfoDeleteByIdsBatchTest:104-115`、`PaymentRecordDeleteByIdsBatchTest:103-113`，断言 `assertEquals(1, count)`），并在 `handoff §2「行为差异点（单列，以新实现为准）」` 单列论证 → 满足卡面要求，不构成 MAJOR。
- 实现变窄的附带收益：旧实现「读快照 + 全字段回写」有覆盖并发写风险，新实现只 SET 状态列（handoff §2 第 3 条已论证）。

### 2.3 A-1 SQLExceptionHandler 双轨收敛（handler + `application.yml`）

| 核验点 | 实测 | 判定 |
| --- | --- | --- |
| 7 键逐项一致 | yml :244-250 与 master switch 逐项比对：`uk_phone`=手机号 / `uk_email`=邮箱 / `uk_company_name`=企业名字 / `idx_mobile`=联系电话 / `uk_contract_no`=合同编号 / `uk_payment_no`=支付编号 / `uk_invoice_no`=发票编号 | ✅ 7/7 一致 |
| 键不重复 | 7 键在 `application.yml` 内各出现 **1** 次（无与既有 20 个列名键撞键） | ✅ |
| switch 删除 | `getFieldNameByIndex` :82-92 改为 `databaseFieldMap.getOrDefault(parts[1], "数据")`（:89）；旧 7-case switch 全删 | ✅ |
| `parts.length<2` 守卫仍在 | :85-87 `if (parts.length < 2) { result = "数据"; }` 原文保留 → 裸列名不越界 | ✅ |
| 裸列名文案不变 | `handleSQLException` :158-161 `split("`")[1]` → 裸列名 → 「数据」→「缺少必填字段：数据」；由测试 :184-190 锁定 | ✅ |
| default「数据」 | :89 `getOrDefault(..., "数据")` 与原 `default -> "数据"` 等价 | ✅ |
| 「未知字段」保留 | :94-97 `getNameByIndex` 未改（缺失回落「未知字段」），FK 路径 :130 同 | ✅ |
| :104 TODO 消除 | master 的 `/** 根据字段名获取该字段的评论 TODO注意维护 */` 已改为「映射单源同上，缺失时回落『未知字段』」（现 :94），yml 上方另加「唯一维护点」说明（:219-222） | ✅ |
| duplicate-key 路径 | :35-75 除 :54/:60 调用的私有方法外**零改动**；文案 `<字段> <值> 已存在` 与解析失败兜底 `数据已存在，无法重复添加` 不变 | ✅ |
| 组合索引分支 | :48-51 仍先查 `compositeUniqueIndexMap`，`<组合名> <值首段> 已存在` 不变 | ✅ |
| FK 分支 | :108-144 逐字未改（`ID_IS_NULL` / `未知字段存在关联数据` / `未知错误` 三条文案不变） | ✅ |
| SQL 分支 | :147-167 逐字未改（`SERVER_ERROR` / `缺少必填字段：...` 不变） | ✅ |

diff 实证：`git diff master..HEAD -- SQLExceptionHandler.java` 仅 2 处 hunk（javadoc/guard 段 + switch→getOrDefault 段），其余行号区间逐字未动 → 「逐路径文案等价」为结构性成立，不是只靠测试推断。

**测试是否 yml 驱动（防假绿）**：`SQLExceptionHandlerTest` :33 `Paths.get("src/main/resources/application.yml")` → :43 `Files.readString` → :44 SnakeYAML 解析 → :46-52 用 Jackson 从 `slz.database-field-map` / `slz.composite-unique-index-map` 两个 JSON 标量读回真值 → :54-56 `ReflectionTestUtils.setField` 注入。实现取的是 yml 真值，yml 少键/错值/JSON 损坏会直接红。:76-86 的 `assertEquals("手机号", fieldMap.get("uk_phone"))` 等是「以 master switch 的 7 个值为期望」，方向正确（期望来自改前契约，实际来自配置），**不是**在测试里复刻实现。

### 2.4 测试面（4 新类 / 18 用例）断言真实性

| 类 | 用例 | 关键断言（去噪） | 空转风险 |
| --- | --- | --- | --- |
| `UserHandoverServiceImplTest` | 3 | :135-136 mock `selectBatchIds`；:140-145 三名字逐 VO 断言；:149-151 `verify(times(1)).selectBatchIds` + 捕获集合 size=3 + containsAll；:154 `verify(never()).selectById`；:173-176 缺失用户回落 null；:188-191 空页后批量仍 times(1) 且 selectById never；:216 `verify(times(5)).selectById` + :217 `never().selectBatchIds` | 无空转 |
| `InvoiceInfoDeleteByIdsBatchTest` | 3 | :82-86 计数=2；:88-95 `getSqlSet()` 含 `status=`、`getSqlSegment()` 含 `id IN`、`getParamNameValuePairs()` 含 1/11L/12L；:98-99 selectById/updateById never；:110 重复 id 计数=1；:120-123 PARAM_EMPTY + `verifyNoInteractions` | 无空转 |
| `PaymentRecordDeleteByIdsBatchTest` | 3 | 同型，:89 断言 `payment_status=`、:93 断言参数含 2、:108 重复 id 计数=1、:121 零交互 | 无空转 |
| `SQLExceptionHandlerTest` | 9 | :79-85 7 键逐项；:90-95 已知索引文案+code=0；:99-128 其余 6 项逐项；:132-136 未知索引→「数据 X-1 已存在」；:140-144 组合索引→「共享 客户A 已存在」；:148-154 不可解析兜底；:158-167 FK→「用户ID」；:171-180 FK 未知列→「未知字段」；:184-190 裸列名→「缺少必填字段：数据」 | 无空转 |

合计 **18 = 3 + 3 + 3 + 9**，与 handoff §4 表格逐行吻合。

---

## 3. 复跑面（本机实测，命令与结论原文留证）

### 3.1 定向测试（PowerShell 直跑，未过 Git Bash）

```
mvn -B -ntp "-Dtest=SQLExceptionHandlerTest,UserHandoverServiceImplTest,InvoiceInfoDeleteByIdsBatchTest,PaymentRecordDeleteByIdsBatchTest" test
```

实测输出（`work/review-mvn-test.log`）：

```
[INFO] Running com.slz.crm.unit.server.handler.SQLExceptionHandlerTest
[INFO] Tests run: 9, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.slz.crm.unit.service.InvoiceInfoDeleteByIdsBatchTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.slz.crm.unit.service.PaymentRecordDeleteByIdsBatchTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Running com.slz.crm.unit.service.UserHandoverServiceImplTest
[INFO] Tests run: 3, Failures: 0, Errors: 0, Skipped: 0
[INFO] Tests run: 18, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
```

进程 exit code = 1，但 stderr 只有 `Java HotSpot ... Sharing is only supported for boot loader classes`（`Tee-Object` 把 stderr 归到 NativeCommandError 造成的假信号）；日志末尾 `BUILD SUCCESS` + 18 用例全绿 → **判绿**，与 handoff 记录的同一环境坑一致，非本次新问题。

### 3.2 回归基线门禁（校验模式，未 `--update`）

```
D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh        (树净)
```

实测（`work/review-baseline.log`），**CHECK_EXIT=0**：

```
surefire 测试：报告=279（默认口径），Tests run=1738，Failures=0，Errors=0，Skipped=0｜基线 reports>=279 tests>=1738 skipped<=0
failsafe 测试：报告=31（默认口径），Tests run=118，Failures=0，Errors=0，Skipped=7｜基线 reports>=31 tests>=118 skipped<=7
回归基线门禁通过（基线文件：.../scripts/test-baseline.txt）
```

四元组与 handoff §5 / 卡面完全吻合：**surefire 279 报告 / 1738 用例 / 0 skipped；failsafe 31 / 118 / 7**。

### 3.3 基线元数据

`scripts/test-baseline.txt`（:2-10）实测：`source-revision=2acabe2`、`measured-at=2026-10-19T07:57:00Z`、surefire 279/1738/0、failsafe 31/118/7 —— 与 `3b68766` 的 diff（+4/−4）一致，`source-revision` 指向 fix 笔而非 docs 笔，符合「基线来自一次真实默认运行」的口径。

### 3.4 未复跑项的作者日志交叉印证（不作为独立证据）

| 声明（handoff） | 原始日志实测行 | 判定 |
| --- | --- | --- |
| 全量 clean verify BUILD SUCCESS、surefire 1738、failsafe 118/7、checkstyle 0 violation | `work/clean-verify.log`：`You have 0 Checkstyle violations.` / `Tests run: 1738, Failures: 0, Errors: 0, Skipped: 0` / `Tests run: 118, Failures: 0, Errors: 0, Skipped: 7`（`[WARNING]` 前缀）/ `BUILD SUCCESS` | 与 handoff §4 一致 |
| merge-gate 10/10 | `work/merge-gate.log`：`PASS [unit]/[checkstyle]/[spotbugs]/[spotless]/[pmd]/[baseline]/[frontend-unit]/[hook]/[bijection]/[pmd-baseline]` 10 条齐全；`Test Files 21 passed (21)` / `Tests 197 passed (197)` / `未过滤 High=12 <Match> 元素=12`；末行 `== merge-gate 通过：所有已执行的子门禁绿 ==` | 与 handoff §6 表格逐行一致 |
| PMD `OnlyOneReturn` 返工后绿 | 代码形态可独立复核（A-7 两个方法均单出口）；`merge-gate.log` 的 `[pmd]` PASS + `[pmd-baseline]` 实测 0 == 登记 0 == 阈值 0 | 形态证据充分 |

### 3.5 复跑未污染树态

定向 `mvn test` 仅重写 4 份 surefire 报告（逐类覆盖，不删既有 275 份），复跑后基线读数仍为 279/1738 → 两轮复跑互不干扰；`git status --porcelain` 在全部复跑后仍为空（`target/`、`work/*.log` 均被忽略）。

---

## 4. 问题清单

### MAJOR（阻断合并）：无

未发现写集越界、红线触碰、功能性回归、断言空转、基线假绿或证据与声明不符。

### MINOR（建议本卡内或紧随一张小卡修，不阻断）

| # | 位置 | 问题 | 影响 / 建议 |
| --- | --- | --- | --- |
| M1 | `openspec/changes/fix-engineering-debt-nplus1-loop-clone/tasks.md:17` | 2.3 的补勾依据写「论证见 `work/handoff-...md` **§3**」，但 A-9 等价性论证实际在 handoff **§2**（「A-9 等价性论证」），§3 是 A-1 的 yml 对照表 | 纯文档交叉引用错位，不影响实现与门禁；建议改 §2（一笔 docs 修补即可） |
| M2 | `UserHandoverServiceImplTest`（缺用例） | A-7 的 **null userId 分支**（`collectUserId` :251-255 跳过 null）只有代码评审证据，18 条用例中无 `fromUserId/toUserId/operatorId == null` 的页 | 代码正确且与旧语义等价，属覆盖缺口；建议补 1 条「含 null 三类 id 的页 → 不查库/姓名 null」用例，防将来误删守卫 |
| M3 | `application.yml:223-250` + `SQLExceptionHandler:89` | 「列名键与索引名键共用一个 Map、键空间不重叠」目前是**命名约定**（`uk_*`/`idx_*` vs `user_id`），不是结构隔离；若将来某唯一索引名恰等于某列名键，duplicate-key 文案会由「数据」变为该列中文名 | 现网索引命名下概率极低，且变化方向是「更具体」；建议在 yml 注释里补一句「键空间靠命名约定隔离」（handoff §3 已声明不重叠，但未点明是约定） |

### 待 Owner 知悉（非缺陷，需拍板/备案）

| # | 事项 | 说明 |
| --- | --- | --- |
| K1 | **A-9 返回计数语义的对外可见变化** | 重复 id 输入：旧 = 输入元素数（2），新 = 实际命中行数（1）。已论证 + 两条测试锁定 + handoff §2 单列；controller 只透传、前端零引用 → 影响面可控。若 Owner 认为该 API 返回值属契约面，需在 merge 说明中备案 |
| K2 | `SQLExceptionHandler:48` 裸索引名越界风险（**pre-existing**） | `indexName.split("\\.")[1]` 无长度守卫（master 同形，本卡未碰）：若 duplicate-key 报文里的 key 不含 `.`，此处先于 `getFieldNameByIndex` 抛 AIOOBE；本卡新增的 `parts.length<2` 守卫覆盖不到该行。MySQL 实际报文恒为 `表名.索引名`，风险低；建议后续卡补守卫或用例 |
| K3 | A-1 测试依赖相对路径 | `SQLExceptionHandlerTest:33` 用 `src/main/resources/application.yml`（相对 working dir）。Maven/CI 的 working dir = 模块根，满足；IDE 单跑若改 working dir 会红。属测试脆弱点，非缺陷 |
| K4 | `work/handoff`、`work/task-card` 随 docs 笔入库 | 与「`work/*` 已 gitignore」的表述看似冲突，实为 `.gitignore:31-34` 的否定规则精选入库（handoff 属交付物）。如 Owner 要求 `work/` 完全不入库，需改否定规则并 rework 该笔 |

---

## 5. handoff 声明 ↔ 实测逐项对照

| handoff 声明 | 实测 | 结论 |
| --- | --- | --- |
| 基点 master@6e99443、分支已保留、未 merge 未 push | `rev-parse master`=6e99443、HEAD 在 feature 分支、三笔提交齐全 | ✅ |
| A-7 改 `convertToVOPage`（:206-224）+ 新增 `batchGetUserNames`（:228-248） | 行号逐一对上（:206-225 / :228-248） | ✅ |
| A-9 两处 deleteByIds（:156-165 / :175-184） | 逐一对上，形态逐字同形 | ✅ |
| A-1 7 键在 yml :244-250、值与 switch 一致 | 逐项 7/7 一致，键唯一 | ✅ |
| 「parts.length<2 → 数据」保持、`doesn't have a default value` 路径逐字不变 | 代码 :85-87 保留、:158-161 未改、测试锁定 | ✅ |
| 三 handler 逐路径文案不变 | diff 仅 2 hunk，其余逐字未动 + 9 条文案断言 | ✅ |
| 定向 18 用例全绿 | 独立复跑：18/0/0/0 + BUILD SUCCESS | ✅ |
| 基线 279/1738 + 31/118/7、source-revision=2acabe2 | 独立复跑校验模式 EXIT=0，读数完全吻合 | ✅ |
| 全量 1738/118/7、checkstyle 0、merge-gate 10/10 | 作者日志交叉印证（未重跑，已在 §3.4 标注） | ✅（间接） |
| 重复 id 行为差异已单列并被测试锁定 | handoff §2 + 两条 `duplicateIds` 用例 | ✅ |
| 偏差清单（OnlyOneReturn 返工/入参 Set/计数细差/yml 注释） | 4 项均可在代码或 diff 中复核，无隐瞒项 | ✅ |
| PMD OnlyOneReturn 返工 1 次 | 单出口形态可复核；无独立 PMD 复跑 | ✅（形态证据） |

**未发现 handoff 夸大或漏报**。唯一文档瑕疵为 M1（tasks.md 交叉引用错位）。

---

## 6. 环境坑复现记录（供后续复核复用）

| 坑 | 本次复现 | 处置 |
| --- | --- | --- |
| `bash` 解析到未装 WSL 的 Windows 版 | 未踩（一律显式 `D:\git\Git\bin\bash.exe`） | 基线脚本一次跑通 |
| `-Dtest` 逗号经 Git Bash 被注入空格 | 未踩（PowerShell 直跑，整体加引号） | 18 用例一次跑全 |
| `Tee-Object` 使进程 exit code 变 1 | **复现**：`Tee-Object` 下 exit 1、日志 `BUILD SUCCESS` | 以日志 `Tests run: 18` + `BUILD SUCCESS` 判绿 |
| `git 2.20.1` 无 `branch --show-current` | **复现** | 改用 `git rev-parse --abbrev-ref HEAD` |
| 定向 `mvn test` 破坏基线读数 | 未发生：逐类覆盖报告，279 份不变 | 先跑基线校验亦可，顺序无关 |

---

## 7. 复核结论与建议

**GO**。三面证据链闭合：写集精确（14 文件、红线路径零 diff）、三处改造语义与卡面一致且等价性可复核、18 用例断言真实、定向测试与基线门禁均在本机独立复现。

合并前建议（均非阻断）：
1. 顺手修 `tasks.md:17` 的 §3→§2 交叉引用（M1），可随 merge 说明或下一笔 docs 处理；
2. 在 merge 记录中备案 K1（A-9 重复 id 计数语义变化）；
3. 后续小卡择机处理 K2（`SQLExceptionHandler:48` 守卫）与 M2（null id 用例补齐）。

复核过程声明：本次复核只读代码/配置/文档并执行「定向测试 + 基线校验」，未执行 merge / push / reset / rebase / 分支删除，未修改任何被跟踪文件；`git status --porcelain` 复核结束时为空。
