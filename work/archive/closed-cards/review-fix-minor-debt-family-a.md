# 独立复核报告 · feature/fix-minor-debt-family（A 档）

- **复核对象**：权威工作树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/fix-minor-debt-family`
- **基点 / tip**：master `370c7d2` → tip `805602a`（7 笔）
- **复核方式**：全只读（未改代码/台账、未 merge/push）；Maven / Docker / Git Bash 全部在本机独立实跑，不采信执行方日志
- **复核时间**：2026-10-19（本机实测）
- **结论**：**🟢 GO（有条件放行）** —— **0 CRITICAL / 1 MAJOR / 8 MINOR**。运行期与数据面未发现阻断缺陷；唯一 MAJOR 是**新增收敛门禁的防回归强度不足**（可 3 行内单点修，不阻断合并，建议合并前顺手修）。

> 环境：Java 21.0.9（`JAVA_HOME=D:\develop1\jdk21`）、Maven 3.9.4、Docker 29.6.2（在线）、Git Bash 4.4.23（`D:\git\Git\bin\bash.exe`；本机 `bash` 默认解析到 WSL，未使用）。

---

## 〇、七面结论速览

| # | 复核面 | 结论 | 关键实测 |
|---|---|---|---|
| 1 | git 核对 | ✅ | 7 笔单行 ASCII、线性无 merge；`diff master..HEAD` 对迁移/pom/typed-router 为空；master=origin/master=`370c7d2`；未 push（无远端分支含 HEAD） |
| 2 | M-1 范围扩张 | ✅（附 1 处映射表缺陷） | 四形态 24+56+13+2=95；HEAD 仅剩白名单 4 处；独立扫描 515 个构造点 0 违规；56/56 `.getMessage()` 枚举恒等、9/9 `formatted` 保留；镜像 21 码全在且零 diff |
| 3 | 白名单与 4 处待裁决 | ⚠ 白名单「数量精确、点位不精确」；4 处建议映射近义码 50002 | 白名单实测 = `USER_DUPLICATE×2 + CONTACT_ALREADY_EXIST×2`（48/165、92/135）；断言仅 `hasSize(4)` |
| 4 | 模块 2-6 | ✅（release 缺真库 IT 证据） | 96004 收口+不泄漏；100MB 有界读；assign 闸前置于配额锁；release 显式 set null；volatile 满足可见性契约 |
| 5 | 定向复跑 | ✅ | 18 类 → `Tests run: 103, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS |
| 6 | 门禁复跑 | ✅ | `clean verify` 16:37 BUILD SUCCESS（surefire 1714/0/0、failsafe 118/0/0/7、PMD 0）；baseline EXIT=0；merge-gate **10/10 PASS** |
| 7 | 证据链对账 | ⚠ 7 处数字/表述偏差（均非代码缺陷） | 见 §七；含“95 处已改”应为 91+4、映射表漏 `PaymentRecordServiceImpl:70`、引用的 `CustomerPoolServiceIT` 不存在 |

---

## 一、面 1 · git 核对（独立实跑）

```
HEAD=805602a09469be68a709cdeb1d43c3a2785abe61
BRANCH=feature/fix-minor-debt-family
MASTER=370c7d26c011996bd1b85471477a7974eb76eb5d
ORIGIN_MASTER=370c7d26c011996bd1b85471477a7974eb76eb5d
MERGE_BASE=370c7d26c011996bd1b85471477a7974eb76eb5d
COMMITS=7
```

- **7 笔全单行 ASCII**：`git log --format='%H|%s' master..HEAD` 7 条 subject 均为纯 ASCII 单行；`%B` 展开后每笔仅「subject + 空行」，无 body。
  1. `805602a docs: record fix-minor-debt-family evidence in tasks and handoff`
  2. `5510db9 test(baseline): update clean baseline to f2aebe4`
  3. `f2aebe4 docs: add fix-minor-debt-family change card`
  4. `e78b499 fix(token): make token budget properties volatile for cross-thread visibility`
  5. `d429b56 fix(pool): validate assign target user before quota lock and persist release owner clear`
  6. `f7ed56d fix(ingestion): return 96004 on content block and bound document scan buffering`
  7. `3979ced feat(m1): converge BaseException single-string ctor to ErrorCode enums in src/main`
- **线性无 merge**：`git log --merges master..HEAD` 为空。
- **敏感路径零 diff**：`git diff --stat master..HEAD -- src/main/resources/db/migration pom.xml frontend/typed-router.d.ts` → **空**。
- **master 未动未推**：`master == origin/master == 370c7d2`；`git branch -r --contains HEAD` **为空**（分支未推远端）。
- **终态树净**：merge-gate 的 vitest 生成物噪声已 `git checkout -- frontend/typed-router.d.ts` 还原，`git status --porcelain` **为空**；tip 仍 `805602a`。
- 总 diff：36 files changed, 1132 insertions(+), 122 deletions(-)。

---

## 二、面 2 · M-1 范围扩张与映射审查（本卡核心面）

### 2.1 四形态计数（独立 grep 复算，不采信 handoff）

| 构造形态 | master（前） | HEAD（后） | 说明 |
|---|---:|---:|---|
| `new BaseException("字面量")` | **24** | **0** | 卡面口径 |
| `new BaseException(ErrorCode.X.getMessage())` | **56** | **0** | 含 `.formatted(..)` |
| `new BaseException(MessageConstant.Y)` | **13** | **4** | 4 处待裁决保持原样 |
| `new BaseException(String.format(...))` | **2** | **0** | 多行写法（CustomerCompanyListener:211/215），单行 grep 会漏，按 diff +2 行与源码复核确认 |
| **合计** | **95** | **4** | **实际收敛 91 处 + 待裁决 4 处** |

- 独立源码扫描（复刻门禁算法，扁平化后括号配平）：HEAD `src/main` 共 **515** 个 `new BaseException(` 构造点 = 136 个枚举单参 + 323 个枚举双参 + 52 个多行枚举构造 + 4 个白名单；**0 个违规**，且无 `new BaseException()`（空构造）现存量。
- **口径纠正**：handoff §0/§1.1 写“95 处收敛（4 处保持原样）”自相矛盾；准确说法是 **95 个候选点中 91 处收敛、4 处待裁决**（见 m-3）。

### 2.2 范围扩张理由与可操作性

- **认可 95 点范围**：卡面标题与 proposal §1 目标均为“`BaseException(String)` 全仓收敛 / code=null 降级 0”；后 71 处（56+13+2）走的是**同一个单 String 构造器**，只改 24 处会留下 71 个同坑。56 处 `.getMessage()` 属零判断机械映射（文案不变、补回本应存在的码），独立恒等校验 56/56 通过。
- **“GETMSG 可分离回退”可操作性不足（m-8）**：95 处改动全部在**同一笔提交 `3979ced`**、跨 13 个文件，字面量与 `.getMessage()` 常在同一 hunk 内交织（如 Invoice/Payment 的 validate 方法）；`git revert` 无法只回退 56 处，只能逐 hunk 手工剪裁。结论：**范围可以接受，但不要把“可分离回退”当作低成本退路**。

### 2.3 映射表分层抽查（≥15 处，逐点核对 code + 文案）

| 调用点（HEAD 行号） | 原文案 | 新码 | 判定 |
|---|---|---|---|
| `JWTInterceptor:69` | 用户不存在，请重新登录 | `TOKEN_INVALID` / 10003 | ✅ 与同方法 :60/:81 同口径 |
| `InvoiceInfoServiceImpl:72` | 合同不存在 | `CONTRACT_NOT_EXISTS` / 31001 | ✅ 1:1 |
| `InvoiceInfoServiceImpl:79` | 回款记录不存在 | `PAYMENT_NOT_EXISTS` / 92018 | ✅ 1:1 |
| `InvoiceInfoServiceImpl:83` | 回款记录不属于该合同 | `PAYMENT_NOT_EXISTS` / 92018 | △ 无“归属不匹配”专用码，语义最近，已披露 |
| `InvoiceInfoServiceImpl:105` | 生成发票编号失败：已达到最大重试次数… | `SYSTEM_BUSY` / 90001 | ✅ 重试耗尽=瞬时竞争 |
| `InvoiceInfoServiceImpl:133` | 开票信息不存在 | `ID_NOT_EXISTS` / 92008 | △ 泛化“按 ID 不存在”；同文件 `voidInvoice` 既有 `DATA_NULL`，已披露 |
| `InvoiceInfoServiceImpl:140` | 回款记录不属于该合同 | `PAYMENT_NOT_EXISTS` / 92018 | △ 同 :83 |
| `InvoiceInfoServiceImpl:181` | 开票信息不存在 | `ID_NOT_EXISTS` / 92008 | △ 同 :133 |
| `PaymentRecordServiceImpl:70` | 合同不存在 | `CONTRACT_NOT_EXISTS` / 31001 | ✅ 正确；**但映射表漏列此点**（见 m-2） |
| `PaymentRecordServiceImpl:77` | 订单明细不存在 | `ORDER_NOT_EXISTS` / 31002 | △ 最近既有码 |
| `PaymentRecordServiceImpl:81` | 订单明细不属于该合同 | `ORDER_NOT_EXISTS` / 31002 | △ 同 :83 类语义 |
| `PaymentRecordServiceImpl:127` | 生成回款单号失败：…重试次数 | `SYSTEM_BUSY` / 90001 | ✅ |
| `PaymentRecordServiceImpl:157/200/294` | 回款记录不存在 | `PAYMENT_NOT_EXISTS` / 92018 | ✅ |
| `PermissionServiceImpl:48` | 该用户没有权限 | `USER_NOT_EXIST_PERMISSION` / 92026 | ✅ 文案 2 参保留 |
| `PermissionServiceImpl:147` | 权限ID x 不存在 | `ID_NOT_EXISTS` / 92008 | ✅ |
| `PermissionServiceImpl:173/190` | 角色已/未关联权限ID… | `PARAM_FORMAT_ERROR` / 50002 | △ 请求侧集合校验；与本分支 Permission 先例一致 |
| `RoleServiceImpl:70` | 禁止删除管理员角色 | `PERMISSION_DENIED` / 12002 | ✅ 拒绝语义 |
| `UserLoginSupport:56` | 该用户角色已被删除… | `ROLE_NOT_EXISTS` / 13001 | ✅ 1:1 |
| `UserServiceImpl:299` | 删除失败，请注意用户状态 | `DATA_DELETE_FAILED` / 91003 | ✅ 1:1 |
| `UserPasswordSupport:50` | 新密码不能与原密码相同 | `PARAM_FORMAT_ERROR` / 50002 | ✅ 未复用旧密专用 `PASSWORD_ERROR` |
| `BusinessActivityRelationSupport:66/110/185/201` | `MessageConstant.*_NOT_EXIST` 原文 | `*_NOT_EXISTS` / 92015/92014 | ✅ 文案整串保留 |
| `PermissionServiceImpl:128/133/139` | `MessageConstant.*` 原文 | 50001/13001/92008 | ✅ 文案整串保留 |
| `UserServiceImpl:88/243` | `MessageConstant.MASTER_IS_ONE` | `ADMIN_EXISTS_ONLY_ONE` / 13002 | ✅ 2 参保留原常量整串（含 `MASTER_IS_ONE:` 前缀，见 m-4） |
| `ContactTaskServiceImpl:165` | `DATA_NULL.getMessage()` | `DATA_NULL` / 91001 | ✅ 恒等 |
| `SysDeptServiceImpl:112` | `ID_NOT_EXISTS…formatted("部门")` | `ID_NOT_EXISTS` / 92008 | ✅ `.formatted` 保留 |
| `UserPasswordSupport:40` | `ID_NOT_EXISTS…formatted("用户")` | 同上 | ✅ |
| `CustomerCompanyListener:211/215` | `String.format(EXCEL_FORMAT_ERROR…)` | `EXCEL_FORMAT_ERROR` / 92005 | ✅ 文案不变 |

- **无串码（独立程序化校验）**：对 13 个含 `.getMessage()` 的文件做“master 枚举多重集 vs HEAD 增量”比对，**56/56 枚举身份一致、0 mismatch**；9 处 `.formatted(..)` 与 HEAD 的 2 参 `ErrorCode.X, ErrorCode.X.getMessage().formatted(..)` **9/9 对应**。
- **M-1 diff 无夹带**：`git show 3979ced -- src/main` 中除 `BaseException/ErrorCode/MessageConstant` 与注释外，无其他增删行（脚本过滤结果为空）。

### 2.4 `GlobalExceptionHandler` 码透传

- `BaseException` 分支（`:60-71`）：`code != null` 时回 `Result.error(code, ex.getMessage())`（自定义文案与枚举默认文案不同则保留原文），**code 透传正确**；无码时仍走 `Result.error(message)`（历史兜底，未扩大）。
- 新增 `ContentRiskBlockedException` 专用分支（`:82-88`）返回 `96004 + PlatformErrorCode.CONTENT_RISK.getMessage()`，**不读取 `e.getMessage()` 作为响应文案**；调用方 `KnowledgeAdminController.upload` 无 catch，异常可达该 handler。

### 2.5 前端镜像

- `frontend/src/constants/error-code-map.ts` **零 diff**（`git diff --stat` 为空）。
- M-1 实际引入的 21 个枚举（含 `DATA_NULL`/`UPDATE_FAILED`/`EMAIL_FORMAT_ERROR`/`PHONE_FORMAT_ERROR`/`ADMIN_EXISTS_ONLY_ONE`/`BUSINESS_ACTIVITY_NOT_EXISTS` 等）**全部在镜像中已存在**（逐一 grep 命中）。
- `ErrorCodeMapParityTest` 在 `clean verify` 与 merge-gate 中均 3/3 绿（其断言覆盖两个后端枚举的名称/码值/文案/i18n 键全集）。

---

## 三、面 3 · 白名单精确性与待裁决 4 处

### 3.1 白名单实测

独立复刻 `BaseExceptionConvergenceTest#noSingleStringConstructorInMainSources` 的扫描算法，结果：

```
scan points = 515
pending count = 4
  48: MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE
  92: MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST
 135: MessageConstant.BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST
 165: MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE
offenders = 0
```

- **当前确实恰圈 4 个点位（2+2）**，无多圈/漏圈，点位在 `BusinessActivityRelationSupport` 内未漂移。✅
- **但断言只锁“数量=4”**（`hasSize(EXPECTED_PENDING_DECISION_COUNT)`），未断言“每种片段各 2 次”、也未断言文件/方法；`file.getFileName()` 只记录不校验。因此：新增第 5 处同类会红（多圈可测），删到 3 处会红（漏圈可测），但**把其中一处移到别的方法/别的类、或两类互换数量，仍 `hasSize(4)` 绿（点位漂移不可测）**（m-1）。
- 另：门禁把 `args.startsWith("ErrorCode.")` 一律视为合规，**`new BaseException(ErrorCode.X.getMessage())`（正是本卡收敛的 56 处形态）与 `new BaseException()` 都会静默通过**；这是唯一 MAJOR（见 §八 MAJOR-1）。

### 3.2 待裁决 4 处定夺意见（三选一，Owner 拍板）

事实校正：`ErrorCode` 中确实存在“已存在”语义的码——`CONTACT_ALREADY_EXISTS(21002)`、`OPPORTUNITY_ALREADY_EXISTS(30002)`；只是**没有商业活动关联域的同名“已存在”码**。handoff §1.3 “枚举只有…没有‘已存在’语义的码”表述不准确。

**建议：选「映射近义码」= `PARAM_FORMAT_ERROR`（50002），4 处一次改完。** 理由：

1. 4 处均为**逐条「判存→插入」循环中的重复关联校验**（`existsBy… == 1` 即抛，属请求侧关联校验，非数据库约束冲突），与 `PermissionServiceImpl:173/190` 同一语义类；本分支已把后者映射为 50002，**同一张卡内口径自洽**。
2. 零新增错误码，满足红线；不动前端镜像与 parity。
3. 2 参构造保留原 `MessageConstant` 整串（含 `BUSINESS_ACTIVITY_…:` 前缀，客户端文案与 legacy 完全一致）。
4. 备选：`CONTACT_ALREADY_EXISTS(21002)` 只语义贴近“联系人已存在”，但属客户联系人域，API 消费者可能误判为联系人实体冲突，**不建议**；新增 92016/92017 语义最准，但需解冻红线 + 镜像 + parity + 基线，**性价比低**；保持待办池则这 4 个商业活动关联写入路径仍向客户端返回 `code=0`，与本卡标题“全仓收敛”矛盾。

落地为单点改动：4 处 `new BaseException(MessageConstant.X)` → `new BaseException(ErrorCode.PARAM_FORMAT_ERROR, MessageConstant.X)`，同时把门禁白名单清空（`List.of()` + 期望 0），重跑 `BaseExceptionConvergenceTest`。

---

## 四、面 4 · 模块 2-6 焦点审查

### 4.1 摄取 BLOCK 收口 96004

- `ContentRiskBlockedException`（`:6-35`）新增 `private final Integer code = PlatformErrorCode.CONTENT_RISK.getCode();` + `getCode()`；`PlatformErrorCode.CONTENT_RISK(96004, "内容存在风险，已阻止本次处理")` 为既有常量，枚举零 diff。
- 响应出口只回通用文案；`GlobalExceptionHandlerContentRiskTest` 断言 `getMsg()` 不含 `prompt-injection`/`系统指令`；`DocumentIngestionServiceTest:202-203` 断言真实摄取链 BLOCK 的 code==96004。
- 语义红线：`AiChatServiceImpl:179` / `AiChatResumeSupport` 的 `CONTENT_BLOCKED` 事件路径未改；`DocumentIngestionService:236` 在 store/insert 之前抛出。

### 4.2 内存优化（有界读取）

- `MAX_DOCUMENT_BYTES = 100L*1024*1024`（`:64`）与 `application.yml:56 max-file-size: 100MB` 对齐（实测 yml 两行：max-file-size 100MB / max-request-size 300MB）。
- 顺序（`:187-198`）：`assertWithinDeclaredSize` → `readAllBytesBounded` → `evaluateDocumentSecurity` → `fileStorageService.store` → `uploadedFileMapper.insert`。**BLOCK 仍在任何存储/落库/文件写之前**；超限拒绝只会更早。
- `readAllBytesBounded`（`:255-265`）用 `readNBytes((int)Math.min(maxBytes, Integer.MAX_VALUE-16L)+1)`，`data.length > maxBytes` 即抛 `PARAM_OUT_OF_RANGE(50007)`；边界测试覆盖“恰好等于上限放行 / 超 1 字节拒绝 / 声明超限时流一次未被读 / 正常中文正文”。
- 残余观察（非本卡引入）：`evaluateDocumentSecurity` 仍把 ≤100MB `byte[]` 转成 `String`（`:234`），UTF-8→UTF-16 峰值可高于 100MB；“峰值内存不超过上限”只对**缓冲**成立（观察项，不计缺陷）。

### 4.3 assign 目标用户闸

- `CustomerPoolServiceImpl.assign:147-151`：`userMapper.selectById(targetUserId)` → `CustomerPoolTargetUserGuard.assertAssignable` → `assertQuotaNotExceeded`（FOR UPDATE）。
- 闸序：不存在 `ID_NOT_EXISTS(92008)` → 冻结 `USER_IS_FROZEN(12006)` → 离职 `USER_IS_QUIT(12007)` → 其它非正常 `USER_STATUS_EXCEPTION(12003)`；与 `PermissionsInterceptor#checkUserStatus`（`:77-88`）同口径，且对 `roleId/status` 增加 null 安全（更稳）。
- 测试：不存在用例断言“未进入配额锁”（`selectList` 零调用、零计数、零写库零日志）；冻结/离职/状态异常各 1；happy path 用 `InOrder` 断言“先 selectById(目标) 再 selectList(FOR UPDATE)”。

### 4.4 release 显式置空

- `release:118-126` 改 `LambdaUpdateWrapper`：`set(poolStatus,公海) / set(ownerId,null) / set(poolReleaseTime,now) / set(poolReleaseReason,reason)` + `eq(id)` + `eq(isDeleted,false)` + `update(null, wrapper)`；与 `casToPrivate:264-274` 同型。
- 单测重写为：`sqlSet` 含 4 列、参数表 `containsValue(null)`（证明 `owner_id` 置空进入 SQL 而非被 NOT_NULL 吞）、`updateById` 零调用、RELEASE 日志 reason 正确。
- **证据缺口（m-5）**：全仓不存在 `CustomerPoolServiceIT`（也没有任何含 release 的 IT）；handoff §5 “真库回环由既有 `CustomerPoolServiceIT` 系列覆盖”为**不实引用**。当前“DB 行真清空”只有 Mockito 层等价证明（MP 的 `set(null)` 语义可靠），无真库回环。

### 4.5 TokenBudget volatile

- `TokenBudgetProperties` 三字段均 `volatile`（`enabled/dailyLimit/monthlyLimit`，`:27-33`）；`TokenBudgetService.check` 对每个字段只读一次（`:77/:88/:99`），无“读-改-写”复合操作 → **纯可见性需求，volatile 充分**（含 JLS 17.7 的 long 撕裂防护）；若要“多字段一致快照”才需锁，但当前契约无此要求。
- 测试：反射 `Modifier.isVolatile` 结构断言（去掉 volatile 立刻红）是有效契约；第 3 例“跨线程写入可见性冒烟”因 `Thread.join()` 自带 happens-before，**去掉 volatile 也会绿**，无区别力（m-6，不构成错误但不应当作可见性证据）。

---

## 五、面 5 · 定向复跑

```
mvn -B -ntp -o test "-Dtest=BaseExceptionConvergenceTest,DocumentIngestionSizeLimitTest,
GlobalExceptionHandlerContentRiskTest,TokenBudgetPropertiesConcurrencyTest,TokenBudgetServiceTest,
ContentSecurityServiceTest,CustomerPoolServiceTest,DocumentIngestionServiceTest,UserPasswordUpdateTest,
InvoiceDataScopeSecurityTest,InvoiceInfoWriteEndpointValidationTest,PaymentRecordWriteEndpointValidationTest,
PermissionWriteEndpointValidationTest,CustomerPoolWriteEndpointValidationTest,JWTInterceptorTest,
UserServiceTest,UserServiceImplTest,DocumentIngestionRecoveryReplayTest"
```

- 实测：`Tests run: 103, Failures: 0, Errors: 0, Skipped: 0` / **BUILD SUCCESS**（2:22 min，日志 `work/_review-a-targeted.log`）。该次后台作业的外层 pwsh 退出码为 1（日志内 0 个 `[ERROR]`、末行 BUILD SUCCESS），判定为 Tee/Select 管道伪影；以 Maven 日志为准。
- 码断言非空洞性：`BaseExceptionConvergenceTest` 断言数字码 10003/92026/50002/91003/90001；`UserPasswordUpdateTest` 在真实 service 路径断言 50002；`DocumentIngestionServiceTest` 断言 96004；`CustomerPoolServiceTest` 断言 92008/12006/12007/12003。
- 但**站点级覆盖偏薄（m-7）**：`MessageConstant` 9 处映射（`ADMIN_EXISTS_ONLY_ONE`、`BUSINESS_ACTIVITY_*_NOT_EXISTS`）在测试中零引用；`JWTInterceptor:69` 的“用户不存在”也没有行为码断言——这些点目前只靠源码门禁 + 本次人工映射审查兜底。

---

## 六、面 6 · 门禁复跑（Docker 在线）

### 6.1 `mvn -B -ntp clean verify`

- **BUILD SUCCESS**，16:37 min（日志 `work/_review-a-verify.log`）。
- surefire：**`Tests run: 1714, Failures: 0, Errors: 0, Skipped: 0`**，报告文件 **274**（`target/surefire-reports/*.txt`）。
- failsafe：**`Tests run: 118, Failures: 0, Errors: 0, Skipped: 7`**，报告文件 **31**（Testcontainers MySQL 真跑）。
- checkstyle：0 violations；spotless：1127 files clean；spotbugs：check 通过；**PMD：`target/pmd.xml` 0 个 violation，`pmd:check` 通过**。

### 6.2 `bash scripts/check-test-baseline.sh`（校验模式，**未用 --update**）

```
surefire 测试：报告=274（默认口径），Tests run=1714，Failures=0，Errors=0，Skipped=0
failsafe 测试：报告=31（默认口径），Tests run=118，Failures=0，Errors=0，Skipped=7
回归基线门禁通过
BASELINE_EXIT=0
```

- 台账 `scripts/test-baseline.txt`：`source-revision=f2aebe4`、274/1714 + 31/118/7，与 handoff 一致；本次复核后该文件零改动。

### 6.3 `bash scripts/merge-gate.sh`（Git Bash）

- **10/10 PASS**，末行 `== merge-gate 通过：所有已执行的子门禁绿 ==`：

```
PASS [unit]        （1714/0/0）
PASS [checkstyle]  （0 violations）
PASS [spotbugs]    （High 门禁通过）
PASS [spotless]    （1127 clean）
PASS [pmd]         （violations=0）
     [it] 默认不跑（未加 --with-verify）
PASS [baseline]    （274/1714 + 31/118/7）
PASS [frontend-unit]（vitest: 21 files / 197 tests passed）
PASS [hook]
PASS [bijection]   （12 豁免 ↔ 12 High）
PASS [pmd-baseline]（实测 0 == 登记 0 == pom 0）
```

- `frontend/typed-router.d.ts` 被 vitest 重新生成（新增 `salesLeadList` 路由，17 行，**并非仅行尾噪声**；该文件在 master 上相对 `pages/sale/lead.page.vue` 已过期，与本分支无关），已 `git checkout --` 还原，**终态 porcelain 为空**。

---

## 七、面 7 · 证据链对账与偏差处置

| handoff 声明 | 本次实测 | 处置意见 |
|---|---|---|
| 7 笔、基点 370c7d2、tip 805602a | 一致 | ✅ 认可 |
| 定向 18 类 103/0/0 | 一致（BUILD SUCCESS） | ✅ 认可 |
| surefire 1714 / failsafe 118/7 / 报告 274+31 | 一致 | ✅ 认可 |
| PMD 0 violations | 一致（target/pmd.xml 0） | ✅ 认可 |
| baseline 274/1714 + 31/118/7、f2aebe4、EXIT=0 | 一致 | ✅ 认可 |
| merge-gate 10/10 | 一致（10 PASS） | ✅ 认可 |
| “95 处已改” | 实为 **91 收敛 + 4 待裁决**（合计 95） | 建议改口径（m-3） |
| §1.2 字面量 24 行映射表 | 仅 23 个真实点 + #24 占位重复；**漏 `PaymentRecordServiceImpl:70`** | 建议补行/删占位（m-2） |
| §5 “既有 CustomerPoolServiceIT 系列” | **不存在该 IT**（全仓无 pool IT） | 建议改口径或另卡补 IT（m-5） |
| §1.3 “MASTER_IS_ONE 文案一字不差” | 常量含 `MASTER_IS_ONE:` 前缀，与 `ADMIN_EXISTS_ONLY_ONE` 默认文案不同；2 参保留原文案，行为正确 | 建议改表述（m-4） |
| §1.6 “本次引入的码…50008…” | 50008 未在本卡使用；实际引入 21 个码，清单漏列 91001/92001/50004/50005/13002 等 | 结论（镜像零改动）仍成立，建议按 21 码重列（m-8） |
| §7.2 “PMD 曾红→抽类→复跑绿” | 终态绿、基线未动；**无原始红跑日志可核** | 过程声明不可验证，建议留存红跑日志或不作此声明（观察项） |
| tasks 15/15（提示词） | 实际 **18 项全勾、0 未勾** | 提示词数字过期，无未勾项（说明项） |
| §1.5 五条偏差（①Invoice 类内码不一致、②文案保留、③test 未动、④目录拼写、⑤2 参选择） | ②-⑤逐条成立（2 参/1 参保留；`src/test` 字面量未改；目录 `exiception` 未动；零新增码）；①属既有差异且已披露、本卡未扩范围 | ✅ 认可（①按披露外置） |
| §9.6/§9.7 未改文件、零新增 | 一致（迁移/pom/typed-router/error-code-map 零 diff；枚举零 diff） | ✅ 认可 |

**其他偏差/说明**

- **release 真库证据**：单测等价证明成立，但“DB 行真清空”没有被真库回环实测；handoff 的不实引用必须修正（m-5）。
- **typed-router 噪声描述**：merge-gate NOTE 称“vitest 改写行尾”，实际是新增路由条目；属生成物，还原正确，不影响结论。
- **提示词“15/15”**：与实测 18/18 不符，属提示词口径问题；分支无未勾项。

---

## 八、发现清单

### MAJOR-1 · 收敛门禁可放行 `ErrorCode.X.getMessage()` 与空构造（防回归失效）

- **位置**：`src/test/java/com/slz/crm/common/exiception/BaseExceptionConvergenceTest.java:63-69`
- **证据**：
  ```java
  String args = flattened.substring(argsStart, argsEnd).trim();
  from = argsEnd;
  if (args.isEmpty() || args.startsWith("ErrorCode.")) {
    continue;
  }
  ```
  `new BaseException(ErrorCode.PARAM_EMPTY.getMessage())` 的 `args` = `"ErrorCode.PARAM_EMPTY.getMessage()"`，`startsWith("ErrorCode.")` 为真 → 被当作合规；`new BaseException()` 也因 `args.isEmpty()` 被跳过。独立复刻扫描验证：`startsWith("ErrorCode.") => True (test treats as OK)`。
- **影响**：本卡收敛的 56/95 处正是该形态；将来任何人重新引入 `.getMessage()` 单参构造，门禁**静默绿**，`code=null` 回归。当前 HEAD 无该形态（0 处），故**不影响运行期正确性**，但门禁的“防回归”承诺不成立。
- **单点改法**（3 行内）：
  ```java
  boolean enumCtor = args.matches("ErrorCode\\.[A-Z0-9_]+(,\\s*.+)?");
  if (enumCtor) continue;          // 枚举单参或“枚举+自定义文案”双参
  // 白名单判断保留；其余（含空参数、.getMessage() 单参）一律进 offenders
  ```
  改后重跑 `BaseExceptionConvergenceTest`（应仍 4/4 绿），并确认新增 `new BaseException()` 会红。
- **级别理由**：非运行期缺陷（当前代码正确），但属本卡新增验收物的实质缺口；修复成本极低，建议合并前修，避免把“看似有门禁”的假安全带入 master。

### MINOR

- **m-1 · 白名单只锁数量、不锁点位**：断言仅 `hasSize(4)`，未断言“两类各 2 次/文件=BusinessActivityRelationSupport/行号/方法”；同类调用整体移位或两类数量互换仍绿。建议按片段分别计数并断言相对路径。
- **m-2 · 映射表漏 `PaymentRecordServiceImpl:70`（合同不存在）且 #24 为占位重复行**：该点源码已正确改为 `CONTRACT_NOT_EXISTS(31001)`，但表里查不到；#24 是重列的 Invoice:72，不是独立调用点。建议补 `PaymentRecordServiceImpl:70` 行、删除占位 #24。
- **m-3 · “95 处已改”口径不准**：95 是候选总数（24+56+13+2），实际收敛 91、待裁决 4。建议 handoff §0/§1.1 改为“91 处收敛 + 4 处待裁决（合计 95）”。
- **m-4 · `MASTER_IS_ONE` 文案“一字不差”不实**：常量值含 `MASTER_IS_ONE:` 前缀（`"MASTER_IS_ONE:终极管理员只能存在一位"`），与 `ADMIN_EXISTS_ONLY_ONE` 默认文案不同；采用 2 参构造后客户端文案仍为带前缀原文，**行为正确**，仅理由表述需改为“2 参保留原常量整串（含前缀）”。
- **m-5 · `release` 真库回环缺失 + handoff 引用不存在的 `CustomerPoolServiceIT`**：全仓无 pool IT；当前证据为 Mockito 层“SET 含 owner_id + 参数 null + 未走 updateById”。建议修正 handoff 表述；若要求真库证明，另卡补 `CustomerPoolReleaseIT`（本次不阻断，MP 语义可靠）。
- **m-6 · TokenBudget 并发测试第 3 例无区别力**：`Thread.join()` 建立 happens-before，去掉 volatile 仍绿；有效契约是反射结构断言。建议删除该例或改注释，避免“已测跨线程可见性”的误读。
- **m-7 · 站点级码断言覆盖薄**：`ADMIN_EXISTS_ONLY_ONE`、`BUSINESS_ACTIVITY_USER_NOT_EXISTS`、`BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS` 在测试中零引用；`JWTInterceptor:69`、Invoice/Payment 的多数新码只靠源码门禁兜底。建议在既有 service 测试各补 1 条数字码断言（不新增测试类）。
- **m-8 · handoff §1.6 码清单失真 + “GETMSG 可分离回退”过强**：清单含未使用的 50008、漏列实际引入码；回退只能逐 hunk 手工（单 commit 内交织），不能 `git revert`。建议按实际 21 码重列，并把回退方案表述为“逐 hunk 手工回退”。

### 观察项（不计缺陷）

- 摄取内存：`byte[]`→`String` 的评估阶段峰值可高于 100MB（非本卡引入）。
- PMD “曾红”无原始日志（终态绿、基线未动，机制自洽）。
- merge-gate NOTE 把 typed-router 生成物描述为“行尾噪声”，实际是新增 `salesLeadList` 路由（master 生成物过期，与本分支无关）。

---

## 九、定夺意见汇总（供 Owner 拍板）

1. **M-1 范围扩张（24→95）**：**认可**。理由：同一构造器/同一 code=null 缺陷、标题即“全仓收敛”、文案逐字保留、零新增码、独立恒等校验 56/56。附带条件：修正 m-2/m-3/m-4 的映射表与口径；若 Owner 仍要求严格卡面，回退 56 处需逐 hunk 手工（m-8），不建议。
2. **待裁决 4 处**：**建议映射近义码 `PARAM_FORMAT_ERROR(50002)`（4 处一次改完）**，与同卡 `PermissionServiceImpl:173/190` 先例一致、零新增码、文案保留；备选 21002（联系人域，不建议）/ 新增 92016+（需解冻，性价比低）/ 保持待办池（与卡目标矛盾）。Owner 拍板后可由执行侧单点改动并重跑门禁。
3. **MAJOR-1**：建议**合并前**按上述 3 行改法收紧门禁并重跑 `BaseExceptionConvergenceTest`；若不改，须在 handoff 明确“该门禁不覆盖 `.getMessage()` 回归”，避免后续误信。

---

## 十、raw 留存路径清单

| 文件 | 内容 |
|---|---|
| `work/_review-a-targeted.log` | 18 类定向复跑完整日志（103/0/0，BUILD SUCCESS） |
| `work/_review-a-verify.log` | `mvn -B -ntp clean verify` 完整日志（1714/118/7、PMD 0、BUILD SUCCESS） |
| `work/_review-a-baseline.log` | `check-test-baseline.sh` 校验模式输出（EXIT=0） |
| `work/_review-a-mergegate.log` | `merge-gate.sh` 10/10 完整日志（含 frontend 197、bijection 12/12） |
| `work/_review-a-git-evidence.txt` | 分支/tip/merge-base/7 笔/敏感路径/status/基线快照 |
| `work/_review-a-m1-utf8.diff` | M-1 提交（3979ced）src/main 全量 diff（853 行，UTF-8） |
| `work/review-fix-minor-debt-family-a.md` | 本报告 |

---

## 十一、裁决建议

**🟢 GO（有条件放行）。**

判据：
1. 无 CRITICAL；唯一 MAJOR 是新增测试门禁的防回归缺口，当前 HEAD 无该形态，运行期与数据面无阻断缺陷；
2. M-1 91 处收敛经独立计数、56/56 恒等校验、≥15 点人工映射审查与“无夹带”diff 扫描，未发现串码/丢文案；
3. 模块 2-6 的实现与语义红线逐项成立；release 有 Mockito 等价证明（真库 IT 缺口已如实记录）；
4. 门禁独立复跑全绿：clean verify（1714/118/7、PMD 0）、baseline EXIT=0、merge-gate 10/10、定向 103/0/0；分支 7 笔线性、敏感路径零 diff、master 未动未推、终态树净。

放行条件（不阻断合并，建议合并前/后立即处理）：MAJOR-1 的三行门禁收紧；m-2/m-3/m-5 的 handoff 事实性修正。待 Owner 对“4 处待裁决”与“范围扩张”拍板后，由主 agent 核验本报告并提请合并授权；**严禁未经授权 merge/push**。
