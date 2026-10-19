# 交接 · fix-minor-debt-family（待办池 MINOR 家族合卡，A 档，六点合一）

- 分支：`feature/fix-minor-debt-family`（从权威树 `master` = `370c7d2` 检出）
- 权威工作树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- 卡面：`openspec/changes/fix-minor-debt-family/{proposal.md,tasks.md}`
- 状态：**六模块全部落地 + A 档独立复核（1 MAJOR / 8 MINOR）+ 返场补丁（MAJOR-1 收紧 + 4 处拍板项 + 文档 MINOR）；严格停步：未 merge、未 push**，待 Owner 核验返场证据后提请合并授权。

---

## 0. 一句话结论

六项 MINOR 债务全部处置完毕 + A 档复核后的返场补丁：M-1 把 `src/main` 里 **95/95 处**「单 String 构造器」调用点收敛到既有 `ErrorCode` 枚举（**零新增错误码常量**；原 4 处「商业活动关联已存在」待裁决项已于返场轮次按 Owner 拍板映射 `PARAM_FORMAT_ERROR`(50002)，收敛门禁白名单清零）；摄取 BLOCK 统一 96004；摄取改「声明尺寸前置校验 + 有界读取」；assign 补目标用户存在/在职闸（前置于配额行锁）；release 改显式 `UpdateWrapper set null` 真落库；TokenBudget 三个共享可变字段确认无锁后加 `volatile` 并补并发契约断言。

---

## 1. M-1 `BaseException(String)` 全仓收敛

### 1.1 盘点方法与范围决策（**重要偏差，请复核确认**）

卡面给的盘点口径是「全仓 grep `new BaseException("`」，实测命中 **24 处**。但同一次盘点发现**同一缺陷（code=null）还有 71 处**，只是首个实参不是字面量：

| 构造形态 | 处数 | 为什么同属缺陷 |
| --- | --- | --- |
| `new BaseException("字面量")` | 24 | 卡面口径 |
| `new BaseException(ErrorCode.X.getMessage())`（含 `.getMessage().formatted(..)`） | 56 | 走的是同一个 `BaseException(String)` 构造器，`code` 仍为 null |
| `new BaseException(MessageConstant.Y)` | 13 | 同上（`MessageConstant` 全是 String 常量） |
| `new BaseException(String.format(ErrorCode.X.getMessage(), n))` | 2 | 同上 |
| **合计** | **95** | |

**决策：95 处全收敛**，理由：卡面标题与 proposal §1 的目标均是「`BaseException(String)` 全仓收敛」「错误码语义混乱（code=null 降级 0）」，只改 24 处会留下 71 处同坑。其中 56 处 `.getMessage()` 形态属**零判断机械映射**（消息文本不变，仅补回本该属于它的码）。

> 若 A 档复核认为超出卡面范围，可只回退「GETMSG」一类（56 处），其余不受影响——两类改动在 diff 里是分离的。

### 1.2 映射表 · 字面量 24 处（原文案 → 码 → 理由）

| # | 调用点（`src/main/java/com/slz/crm/`） | 原文案 | ErrorCode | code | 选择理由 |
| --- | --- | --- | --- | --- | --- |
| 1 | `server/interceptor/JWTInterceptor.java:69` | 用户不存在，请重新登录 | `TOKEN_INVALID` | 10003 | token 指向的用户已不存在＝token 失效；与同方法既有 `TOKEN_INVALID`（:60/:81）同口径 |
| 2 | `server/service/impl/InvoiceInfoServiceImpl.java:72` | 合同不存在 | `CONTRACT_NOT_EXISTS` | 31001 | 语义一一对应 |
| 3 | `InvoiceInfoServiceImpl.java:79` | 回款记录不存在 | `PAYMENT_NOT_EXISTS` | 92018 | 语义一一对应 |
| 4 | `InvoiceInfoServiceImpl.java:83` | 回款记录不属于该合同 | `PAYMENT_NOT_EXISTS` | 92018 | 从合同视角「该回款记录不存在」；无「归属不匹配」专用码，零新增红线内取最近语义 |
| 5 | `InvoiceInfoServiceImpl.java:105` | 生成发票编号失败：已达到最大重试次数（…） | `SYSTEM_BUSY` | 90001 | 重试耗尽＝瞬时资源竞争，文案本身即「请稍后重试」 |
| 6 | `InvoiceInfoServiceImpl.java:133` | 开票信息不存在 | `ID_NOT_EXISTS` | 92008 | 按 ID 查无记录（`【%s】不存在` 的泛化形态）；见 §1.5 偏差 ① |
| 7 | `InvoiceInfoServiceImpl.java:140` | 回款记录不属于该合同 | `PAYMENT_NOT_EXISTS` | 92018 | 同 #4 |
| 8 | `InvoiceInfoServiceImpl.java:181` | 开票信息不存在 | `ID_NOT_EXISTS` | 92008 | 同 #6 |
| 9 | `server/service/impl/PaymentRecordServiceImpl.java:77` | 订单明细不存在 | `ORDER_NOT_EXISTS` | 31002 | 订单域最近既有码 |
| 10 | `PaymentRecordServiceImpl.java:81` | 订单明细不属于该合同 | `ORDER_NOT_EXISTS` | 31002 | 同 #4 语义 |
| 11 | `PaymentRecordServiceImpl.java:127` | 生成回款单号失败：已达到最大重试次数（…） | `SYSTEM_BUSY` | 90001 | 同 #5 |
| 12 | `PaymentRecordServiceImpl.java:157` | 回款记录不存在 | `PAYMENT_NOT_EXISTS` | 92018 | 同 #3 |
| 13 | `PaymentRecordServiceImpl.java:164` | 订单明细不属于该合同 | `ORDER_NOT_EXISTS` | 31002 | 同 #10 |
| 14 | `PaymentRecordServiceImpl.java:200` | 回款记录不存在 | `PAYMENT_NOT_EXISTS` | 92018 | 同 #3 |
| 15 | `PaymentRecordServiceImpl.java:294` | 回款记录不存在 | `PAYMENT_NOT_EXISTS` | 92018 | 同 #3 |
| 16 | `server/service/impl/PermissionServiceImpl.java:48` | 该用户没有权限 | `USER_NOT_EXIST_PERMISSION` | 92026 | 字面语义一致 |
| 17 | `PermissionServiceImpl.java:147` | 权限ID … 不存在 | `ID_NOT_EXISTS` | 92008 | 按 ID 查无记录；无 `PERMISSION_NOT_EXISTS` 码 |
| 18 | `PermissionServiceImpl.java:173` | 角色已关联权限ID …，无需重复新增 | `PARAM_FORMAT_ERROR` | 50002 | 纯请求侧集合重复校验（尚未写库）；无「已关联」专用码 |
| 19 | `PermissionServiceImpl.java:190` | 角色未关联权限ID …，无法删除 | `PARAM_FORMAT_ERROR` | 50002 | 同上（删除前置校验，未发生删） |
| 20 | `server/service/impl/RoleServiceImpl.java:70` | 禁止删除管理员角色 | `PERMISSION_DENIED` | 12002 | 业务禁止性动作，无专用码；拒绝语义最近 |
| 21 | `server/service/impl/UserLoginSupport.java:56` | 该用户角色已被删除，无法登录，请联系管理员 | `ROLE_NOT_EXISTS` | 13001 | 字面语义一致（角色不存在） |
| 22 | `server/service/impl/UserServiceImpl.java:299` | 删除失败，请注意用户状态 | `DATA_DELETE_FAILED` | 91003 | 字面语义一致（删除失败） |
| 23 | `server/service/support/UserPasswordSupport.java:50` | 新密码不能与原密码相同 | `PARAM_FORMAT_ERROR` | 50002 | 请求参数校验失败；§127 的 `PASSWORD_ERROR`(92028) 专指旧密校验失败，不复用 |
| 24 | `server/service/impl/PaymentRecordServiceImpl.java:70` | 合同不存在 | `CONTRACT_NOT_EXISTS` | 31001 | 与 #2 同语义（原表遗漏此点，返场轮次补漏） |

> 上表 24 行 = 字面量口径的真实 24 处改动点，无占位行；`InvoiceInfoServiceImpl` 共 7 处（72/79/83/105/133/140/181）。

### 1.3 映射表 · `MessageConstant` 13 处（9 映射 + 4 拍板映射）

| 调用点 | 原 MessageConstant | 新 ErrorCode | code | 理由 |
| --- | --- | --- | --- | --- |
| `BusinessActivityRelationSupport.java:66`、`:201` | `BUSINESS_ACTIVITY_USER_NOT_EXIST` | `BUSINESS_ACTIVITY_USER_NOT_EXISTS` | 92015 | 常量 1:1 同名，文案整串保留 |
| `BusinessActivityRelationSupport.java:110`、`:185` | `BUSINESS_ACTIVITY_CONTACT_NOT_EXIST` | `BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS` | 92014 | 同上 |
| `PermissionServiceImpl.java:128` | `ROLE_PERMISSION_IS_NULL` | `PARAM_EMPTY` | 50001 | 文案「…不能为空」的既有对应码 |
| `PermissionServiceImpl.java:133` | `ROLE_IS_NOT_EXIST` | `ROLE_NOT_EXISTS` | 13001 | 语义一一对应 |
| `PermissionServiceImpl.java:139` | `PERMISSION_IS_NOT_EXIST` | `ID_NOT_EXISTS` | 92008 | 无 `PERMISSION_NOT_EXISTS` 码，取泛化「按 ID 不存在」 |
| `UserServiceImpl.java:88`、`:243` | `MASTER_IS_ONE` | `ADMIN_EXISTS_ONLY_ONE` | 13002 | 两者文案一字不差（终极管理员只能存在一位） |

**原「待裁决 4 处」→ 返场轮次按 Owner 拍板 b 方案映射（`PARAM_FORMAT_ERROR` 50002，零新增码）**：

| 调用点（`BusinessActivityRelationSupport.java`） | MessageConstant | 文案 | 新 ErrorCode | code | 理由 |
| --- | --- | --- | --- | --- | --- |
| `:48`（`applyActivityUserRelations` 新增重复） | `BUSINESS_ACTIVITY_USER_DUPLICATE` | 商业活动用户关联已存在 | `PARAM_FORMAT_ERROR` | 50002 | 请求侧关联重复校验（判存→插入循环内，尚未写库），与同卡 `PermissionServiceImpl:173/190` 先例同口径；无域内「已存在」专用码 |
| `:165`（`addUserRelations` 已存在关联） | `BUSINESS_ACTIVITY_USER_DUPLICATE` | 商业活动用户关联已存在 | `PARAM_FORMAT_ERROR` | 50002 | 同上 |
| `:92`（`applyActivityContactRelations` 新增重复） | `BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST` | 商业活动联系人已存在 | `PARAM_FORMAT_ERROR` | 50002 | 同上 |
| `:135`（`addContactRelations` 已存在关联） | `BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST` | 商业活动联系人已存在 | `PARAM_FORMAT_ERROR` | 50002 | 同上 |

- 4 处一致写法：`new BaseException(ErrorCode.PARAM_FORMAT_ERROR, MessageConstant.X)`（2 参保留原常量整串，含 `BUSINESS_ACTIVITY_…:` 前缀，客户端文案与 legacy 逐字一致）。
- 码断言：`BusinessActivityRelationSupportTest` 4 例（4 个调用方各 1），在真实 service 方法路径断言 `code == 50002` 且 `getMessage()` 等于原常量整串。
- 收敛门禁白名单随之清零（见 §1.7），任何新增单 String 构造立刻红。

### 1.4 映射表 · `.getMessage()` 56 处（恒等映射）+ `String.format` 2 处

- **恒等映射 56 处**：`new BaseException(ErrorCode.X.getMessage())` → `new BaseException(ErrorCode.X)`；`…getMessage().formatted(a)` → `…(ErrorCode.X, ErrorCode.X.getMessage().formatted(a))`。
  码取调用点**自己点名的那一个枚举**，消息文本逐字不变，故无需逐条理由。全量位置（文件:行，均为改后行号）：

  | 文件 | 行号（全部恒等映射） |
  | --- | --- |
  | `BusinessActivityContactServiceImpl.java` | 48, 140, 145 |
  | `BusinessActivityServiceImpl.java` | 196, 201, 260, 352, 357, 374, 379, 396, 401 |
  | `BusinessActivityUserServiceImpl.java` | 44, 123, 128 |
  | `ContactTaskServiceImpl.java` | 63, 121, 147, 165, 204, 253, 258 |
  | `ContractServiceImpl.java` | 143 |
  | `CustomerCompanySupport.java` | 424（`ID_NOT_EXISTS.getMessage().formatted("客户公司")`） |
  | `InvoiceInfoServiceImpl.java` | 66, 128, 157, 176, 293 |
  | `PaymentRecordServiceImpl.java` | 64, 152, 176, 195, 289 |
  | `SysDeptServiceImpl.java` | 108, 112, 190, 216, 220 |
  | `TaskCommentServiceImpl.java` | 46, 62, 71, 80, 92, 97 |
  | `UserDeptSupport.java` | 35 |
  | `UserServiceImpl.java` | 83, 92, 96, 129, 177, 190, 221, 255, 279 |
  | `UserPasswordSupport.java` | 40 |

- **`String.format` 2 处**：`common/excellistener/CustomerCompanyListener.java:211,215`
  `new BaseException(String.format(ErrorCode.EXCEL_FORMAT_ERROR.getMessage(), n))` → `new BaseException(ErrorCode.EXCEL_FORMAT_ERROR, String.format(...))`（码 92005，文案不变）。

### 1.5 偏差与假设（请复核）

1. **`开票信息不存在` 的类内不一致（已知、未强改）**：`InvoiceInfoServiceImpl:133/181` 收敛为 `ID_NOT_EXISTS`(92008)，而同文件 `voidInvoice` 的同一文案既有实现是 `DATA_NULL`(91001)。二者本次都不再是 code=null；统一属另一个语义整理议题，未在 MINI 卡内顺手改（`voidInvoice` 那处**本来就有码**，不在 M-1 范围）。
2. **行为面变化**：这 95 处对客户端由 `code=0` 变为真实码，是卡面明示的目标；**消息文案逐字保留**（一律用 2 参构造 `BaseException(ErrorCode.X, 原文案)`，故所有按消息文本断言的既有测试不受影响）。
3. **`src/test` 内的 `new BaseException("…")` 未动**：测试替身不是生产出口，卡面也只要求 `src/main`。
4. **目录名** `common/exiception/` 保持原拼写未「修正」（卡面红线）。
5. 采用 2 参构造而非 1 参，是为了**同时**保住原文案与真实码；只有 `.getMessage()` 一类因文案本就等于枚举文案，直接退化为 1 参。

### 1.6 前端镜像核对（任务 1.4）

- 核对对象：`frontend/src/constants/error-code-map.ts`（含 `ApiErrorCode`、`errorCodeMap`、`96001-96010` 平台段）。
- 结论：本次引入的码 **10003 / 12002 / 13001 / 31001 / 31002 / 50001 / 50002 / 50007 / 50008 / 90001 / 91003 / 92008 / 92013 / 92014 / 92015 / 92018 / 92026 / 96004** 在镜像中**全部已存在**，且**零新增错误码常量**，故 `error-code-map.ts` **零改动**；两侧漂移门禁 `ErrorCodeMapParityTest` 保持绿（见 §7 证据）。
- 与 §127 的 92028 先例同型核对已完成：那次需要动镜像是因为**新增了码**，本次没有。

### 1.7 收敛门禁测试（防回归）

新增 `src/test/java/com/slz/crm/common/exiception/BaseExceptionConvergenceTest.java`（**6 例**，返场轮次由 4 例增至 6 例）：

- 源码扫描：`src/main` 下**不得再出现** `BaseException` 非枚举构造；扫描点数量下限断言防止文件遍历假绿；**白名单已清零**（零豁免，任何新增单 String 构造立刻红）。
- 实参形态**严格枚举匹配**（返场轮次收紧，原 `args.startsWith("ErrorCode.")` 会放行 `.getMessage()`）：只认 `ErrorCode.IDENT` 与 `ErrorCode.IDENT, 原文案`；`.getMessage()` 链式、空参、字符串 / `MessageConstant` / `String.format(...)` 形态一律判违规。
- 两条负向自证用例：违规样本（含 `.getMessage()`、`String.format(...getMessage())`、空参、字面量、`MessageConstant`）断言判红；合法枚举形态断言判绿。
- 构造器契约：2 参枚举构造透传真实 code 且保留自定义文案；1 参枚举构造文案取枚举值。
- 码族抽样：10003 / 92026 / 50002 / 91003 / 90001 各断言一次。
- 单 String 构造器仍保留（测试可用），仅 `src/main` 禁用。

---

## 2. 摄取 BLOCK 错误码收口 96004

- **改造前（实测）**：`ContentRiskBlockedException` 是裸 `RuntimeException`，**完全没有 code 字段**，出摄取链后落到 `GlobalExceptionHandler` 的兜底分支（`Result.error(String)` → `code=0`）。卡面「确认其当前码」的答案是：**无码**。
- **改造后**：
  - `platform/security/ContentRiskBlockedException.java`：新增 `private final Integer code = PlatformErrorCode.CONTENT_RISK.getCode();` + `getCode()`。
  - `server/handler/GlobalExceptionHandler.java`：新增 `@ExceptionHandler(ContentRiskBlockedException.class)`，返回 `Result.error(96004, "内容存在风险，已阻止本次处理")`，**不透出命中规则/原始内容**，只 WARN 记 traceId+端点。
- 码来源：`platform/contract/PlatformErrorCode.CONTENT_RISK(96004)`（**既有**常量，零新增），前端镜像 96004 已存在。
- 断言：新增 `GlobalExceptionHandlerContentRiskTest`（2 例：异常自带 96004；HTTP 出口回 96004 且不泄漏规则名）；`DocumentIngestionServiceTest` BLOCK 用例补 `getCode()==CONTENT_RISK` 断言。
- 语义红线复核：`publishEvent`/SSE 侧（`AiChatServiceImpl:179`、`AiChatResumeSupport.rejectContentBlocked`）行为不变，仍按 `CONTENT_BLOCKED` 事件呈现。

---

## 3. 大文件摄取扫描内存优化

- **改造前**：`DocumentIngestionService.ingest` 先 `content.readAllBytes()`（无上限）→ 再做内容安全评估 → 再写存储/落库。
- **改造后**（`knowledge/document/DocumentIngestionService.java`）：
  1. `MAX_DOCUMENT_BYTES = 100L * 1024 * 1024`，与 `application.yml` 的 `spring.servlet.multipart.max-file-size: 100MB` 对齐（HTTP 层已挡住更大上传，摄取侧不再当更大的缓冲区）。
  2. `assertWithinDeclaredSize(command)`：`command.fileSize()` 可知时**在读流之前**拒绝（连 `InputStream.read` 都不会发生）。
  3. `readAllBytesBounded(content, max)`：`readNBytes(max+1)`，超过上限即抛，峰值内存不超过上限；覆盖 `fileSize` 缺失/不可信的场景。
  4. 超限统一 `PARAM_OUT_OF_RANGE`(50007) + 明确文案，**零新增错误码**。
- **语义等价红线**：BLOCK 仍在任何存储/落库/文件写之前拒绝（`assertWithinDeclaredSize` → `readAllBytesBounded` → `evaluateDocumentSecurity` → `fileStorageService.store` → `uploadedFileMapper.insert` 顺序未变，超限拒绝**更早**）。
- 删除了原 `readAllBytes` 私有方法（改为有界实现，避免留死代码触发 PMD UnusedPrivateMethod）。
- 边界测试：新增 `knowledge/document/DocumentIngestionSizeLimitTest`（4 例）：
  - 声明超限 → 抛 50007，且**入参流一次都没被读**（用「一读就抛 AssertionError」的流证明前置校验真的前置）、零 `store`/零 `insert`；
  - 有界读取边界：恰好等于上限放行、超 1 字节即拒（码断言）；
  - 正常尺寸逐字节不变（中文正文）；
  - 上限与 HTTP 闸 100MB 对齐。

---

## 4. `assign` 目标用户存在性校验

- `CustomerPoolServiceImpl.assign` 在 `assertQuotaNotExceeded` **之前**（即 `SELECT … FOR UPDATE` 之前）先 `userMapper.selectById(targetUserId)` 并交给新类 `CustomerPoolTargetUserGuard.assertAssignable(...)` 校验。
- **为什么抽一个类**：首次实现是内联私有方法，`mvn clean verify` 的 PMD 直接红——`CustomerPoolServiceImpl` 类级圈复杂度 77 / NCSS 151，正好越过 `pmd-rules.xml` 的 75 / 150 双门限（PMD 基线只许下调，禁止加登记）。把闸抽到包内 `final class CustomerPoolTargetUserGuard` 后，服务类回落到线下，PMD 复跑 `BUILD SUCCESS`。**语义与顺序未变**，只是位置换了。
- 校验内容与码（全部复用既有码）：
  - 用户不存在 → `ID_NOT_EXISTS`(92008)，文案 `【目标业务员】不存在`；
  - `status != 1` 且 roleId=0 → `USER_IS_FROZEN`(12006)；roleId=2 → `USER_IS_QUIT`(12007)；其余 → `USER_STATUS_EXCEPTION`(12003)。
  - 状态语义与 `PermissionsInterceptor#checkUserStatus` 同口径（D3 状态闸）。
- 原 `assertQuotaNotExceeded` javadoc 里「目标用户行不存在时退化为现状语义（不新增存在性校验…）」一句已过期，改为说明「存在性由 `CustomerPoolTargetUserGuard#assertAssignable` 先行保证，本方法只管配额锁与计数」。
- 靶向测试（`CustomerPoolServiceTest` 扩展 4 例）：目标用户不存在（断言码 + **不进入配额行锁**：`userMapper.selectList` 零调用、零计数、零写库零日志）、冻结/离职/状态异常各 1 例；happy path 增加 `InOrder` 断言「先 `userMapper.selectById(目标)` 再 `FOR UPDATE`」。

---

## 5. `release` 置空显式落库

- **改造前**：`release` 用 `updateById(update)`（实体态），MP 默认 `NOT_NULL` 字段策略下 `setOwnerId(null)` **不产生 SQL SET**，`owner_id` 从未被清空 —— 与 `pool_status=公海` 组合成「公海客户仍挂着 owner」的脏状态。
- **改造后**：与同文件 `casToPrivate` 同型的显式 `LambdaUpdateWrapper`：
  `.set(poolStatus, 公海).set(ownerId, null).set(poolReleaseTime, now).set(poolReleaseReason, reason).eq(id).eq(isDeleted, false)`，`update(null, wrapper)`。
  置空语义按流转状态机「私海→公海写退回痕迹、公海→私海清除痕迹」保持（`casToPrivate` 侧不变）。
- 靶向测试（`CustomerPoolServiceTest.release_byOwner_updatesAndLogsRelease` 重写）：断言 SQL SET 子句含 4 列、**绑定参数里出现 null**（证明 `owner_id` 置空真进了 SQL 而非被 NOT_NULL 丢弃）、WHERE 含 id、`updateById` 零调用、RELEASE 日志 reason 落库。
  > 说明：单测是 Mockito 层，无法真连库验证行；「DB 行真被清空」的等价证明 = 显式 SET 子句含 `owner_id` 且绑定参数为 null（这正是 NOT_NULL 策略丢失的部分）。
  > **证据缺口（返场轮次如实修正）**：全仓**不存在** `CustomerPoolServiceIT`（也没有任何含 release 的 `*IT`），原稿「真库回环由既有 `CustomerPoolServiceIT` 系列覆盖」为不实引用，已删除。release 的「DB 行真清空」**无真库回环实测**，现仅 Mockito 层等价证明；已登记待办池（另卡补 `CustomerPoolReleaseIT`，本卡不新增 IT、不改门禁）。

---

## 6. TokenBudget 无锁断言盘点处置（**结论：确认存在，已修**）

- **盘点路径**：`AiChatServiceImpl:154` → `AiChatResumeSupport.rejectIfThrottled` → `rejectIfBudgetExceeded` → `TokenBudgetService.check(:77)` 读 `properties.isEnabled()` / `getDailyLimit()` / `getMonthlyLimit()`。
- **共享可变状态确认**：`TokenBudgetProperties` 是 `@Component + @ConfigurationProperties` **单例**（无 `@RefreshScope`），三个字段原为**普通可变字段**（非 volatile、非 final、非原子）：
  - 读侧：全部请求线程高频共读（每次流式对话进入前）；
  - 写侧：Spring 启动时配置绑定；后续若接 Lane E 动态配置/`@RefreshScope` 刷新，会在另一线程写同一批字段。
  - 因此「单例 + 多线程共读 + 可被重绑」= **无锁共享可变状态确实存在**；非 volatile 下写入内存可见性无保证，且 JLS 17.7 允许非 volatile `long` 读写撕裂（`dailyLimit/monthlyLimit` 正是 `long`）。
- **处置（已修，非弃置）**：三个字段加 `volatile` + 类级并发安全说明（单次字段读取、无读-改-写，故不需锁/原子类型）。
- **并发安全测试**：新增 `platform/token/TokenBudgetPropertiesConcurrencyTest`（3 例）：三字段 `volatile` 结构断言（回归即红）、灰度默认值（false / 100000 / 1000000）、跨线程写入可见性冒烟。
- 说明：默认 `enabled=false` 的灰度口径**未动**（不是本卡范围）。

---

## 7. 验证证据

（见下方逐条实测记录）

### 7.1 定向测试

命令（PowerShell 直跑，避开 Git Bash 对 `-Dtest` 逗号列表的注入）：

```
mvn -B -ntp -o test "-Dtest=BaseExceptionConvergenceTest,DocumentIngestionSizeLimitTest,GlobalExceptionHandlerContentRiskTest,TokenBudgetPropertiesConcurrencyTest,TokenBudgetServiceTest,ContentSecurityServiceTest,CustomerPoolServiceTest,DocumentIngestionServiceTest,UserPasswordUpdateTest,InvoiceDataScopeSecurityTest,InvoiceInfoWriteEndpointValidationTest,PaymentRecordWriteEndpointValidationTest,PermissionWriteEndpointValidationTest,CustomerPoolWriteEndpointValidationTest,JWTInterceptorTest,UserServiceTest,UserServiceImplTest,DocumentIngestionRecoveryReplayTest"
```

结果：`Tests run: 103, Failures: 0, Errors: 0, Skipped: 0` / **BUILD SUCCESS**。

返场定向复跑（`mvn -B -ntp -o test "-Dtest=BaseExceptionConvergenceTest,BusinessActivityRelationSupportTest"`，日志 `work/_reentry-targeted.log`）：`Tests run: 10, Failures: 0, Errors: 0, Skipped: 0`（6 + 4）/ **BUILD SUCCESS**。

### 7.2 全量 `mvn -B -ntp clean verify`

日志：`work/_reentry-verify.log`（返场补丁后复跑；Docker 在线，Testcontainers MySQL 真跑）。

- surefire：`Tests run: 1720, Failures: 0, Errors: 0, Skipped: 0`，报告 **275**（master 基线 1697 → **+23**，与 §8 净增例数一致；首轮 1714 → 返场 +6）
- failsafe：`Tests run: 118, Failures: 0, Errors: 0, Skipped: 7`，报告 **31**（与基线一致）
- `pmd:check`：0 violations；checkstyle / spotless / spotbugs 全绿；**BUILD SUCCESS**（EXIT=0）
- 首次跑曾因 PMD 类级门限红（`CustomerPoolServiceImpl` 77/151 > 75/150），按 §4 抽类后复跑全绿；**该红为保留现场，未绕过任何门禁**。

### 7.3 基线（surefire/failsafe 回归基线）

- `bash scripts/check-test-baseline.sh --update`（Git Bash，一次性、树净、紧接 clean verify 之后；日志 `work/_reentry-baseline.log`）→ `EXIT=0`：
  ```
  surefire.reports=275  surefire.tests=1720  surefire.skipped=0
  failsafe.reports=31   failsafe.tests=118   failsafe.skipped=7
  # source-revision=0d07514  # measured-at=2026-10-19T05:07:11Z
  ```
- 校验模式 `bash scripts/check-test-baseline.sh` → `回归基线门禁通过`，`EXIT=0`。
- 基线笔：`a8d136f test(baseline): update clean baseline to 0d07514`。
- 只增不减核对：surefire 1697 → **1720**（+23），failsafe 118 → 118（不变），报告数 270 → **275**。

### 7.4 `merge-gate.sh`

命令：`bash scripts/merge-gate.sh`（默认序列），日志 `work/_reentry-mergegate.log`（HEAD=`a8d136f`）。

**结果：10/10 PASS**，末行 `== merge-gate 通过：所有已执行的子门禁绿 ==`：

| # | 门禁 | 结果 |
| --- | --- | --- |
| 1 | `[unit]` surefire | PASS（1720 例） |
| 2 | `[checkstyle]` | PASS（0 violations） |
| 3 | `[spotbugs]` High | PASS |
| 4 | `[spotless]` | PASS（1128 files clean） |
| 5 | `[pmd]` | PASS（violations=0） |
| 6 | `[baseline]` 回归基线 | PASS（275/1720 + 31/118/7） |
| 7 | `[frontend-unit]` | PASS（21 files / 197 tests） |
| 8 | `[hook]` | PASS |
| 9 | `[bijection]` | PASS（12 豁免 ↔ 12 High） |
| 10 | `[pmd-baseline]` | PASS（实测 0 == 登记 0 == pom 0） |

- `frontend/typed-router.d.ts` 被 `[frontend-unit]`（vitest）重新生成（生成物噪声，与本分支无关），**已立即 `git checkout --` 还原**，最终树中该文件零改动。
- `[it]` 默认序列不跑（已由 §7.2 的 `clean verify` 单独覆盖，failsafe 报告即本次产物）。

---

## 8. 新增/修改测试清单

| 类型 | 文件 | 例数 | 覆盖 |
| --- | --- | --- | --- |
| 新增 | `src/test/java/com/slz/crm/common/exiception/BaseExceptionConvergenceTest.java` | 6（返场 +2） | M-1 源码门禁（严格枚举形态）+ 2 例负向自证 + 构造器码透传 |
| 新增 | `src/test/java/com/slz/crm/server/service/impl/BusinessActivityRelationSupportTest.java` | 4（返场新增） | 原 4 处待裁决映射点：50002 + 原文案 |
| 新增 | `src/test/java/com/slz/crm/knowledge/document/DocumentIngestionSizeLimitTest.java` | 4 | 尺寸上限前置校验 + 有界读取边界 |
| 新增 | `src/test/java/com/slz/crm/server/handler/GlobalExceptionHandlerContentRiskTest.java` | 2 | 96004 收口与不泄漏 |
| 新增 | `src/test/java/com/slz/crm/platform/token/TokenBudgetPropertiesConcurrencyTest.java` | 3 | volatile 契约 + 灰度默认值 |
| 修改 | `CustomerPoolServiceTest.java` | 16（原 12 + 新 4） | assign 目标用户闸 4 例 + release 显式置空重写 |
| 修改 | `DocumentIngestionServiceTest.java` | 12（断言增强） | BLOCK 码 96004 |
| 修改 | `UserPasswordUpdateTest.java` | 4（断言增强） | 新旧密码相同 → 50002 |

> 净新增用例：**23 例**（首轮 17 + 返场 2 + 返场 4），其余为既有用例的断言增强（不增不减）。surefire 基线只增不减，见 §7.3。

---

## 9. 假设与偏差汇总（复核请逐条看）

1. **M-1 范围超卡面 grep 口径**（24 → 95 处），理由与可回退方案见 §1.1。
2. **原 4 处 `MessageConstant` 待裁决项已拍板落地**：返场轮次按 Owner 拍板映射 `PARAM_FORMAT_ERROR`(50002) + 保留原文案，收敛门禁白名单清零（不再留待办）。
3. `InvoiceInfoServiceImpl` 内 `开票信息不存在` 存在 `ID_NOT_EXISTS` 与既有 `DATA_NULL` 两种码（§1.5 ①），未强改既有那处。
4. `release` 的「DB 行真清空」在单测层以「SQL SET 含 `owner_id` + 绑定参数为 null」等价证明（§5 说明）。
5. TokenBudget 判定为「确认存在无锁共享可变状态」→ 已修（非弃置）。
6. 未改动：`frontend/typed-router.d.ts`、`frontend/src/constants/error-code-map.ts`、任何迁移脚本、任何冻结契约、任何依赖。
7. 迁移/依赖/错误码常量：**零新增**（`PlatformErrorCode` / `ErrorCode` 两个枚举本次无任何新增条目）。

---

## 10. 停步声明

按卡面要求**严格停步**：未 `git merge`、未 `git push`。分支 `feature/fix-minor-debt-family` 保留在权威工作树，等待 Owner 核验返场证据后提请合并授权。

---

## 11. 返场补丁（A 档复核后：MAJOR-1 + 拍板项 + 文档 MINOR）

### 11.1 做了什么

1. **MAJOR-1 收紧（防回归）**：`BaseExceptionConvergenceTest` 原 `args.startsWith("ErrorCode.")` 会放行 `new BaseException(ErrorCode.X.getMessage())` 与本卡收敛前的 56 处形态，也会跳过 `new BaseException()` 空参。改为**严格枚举形态正则** `ErrorCode\.[A-Z0-9_]+(\s*,\s*.+)?`：只认 `ErrorCode.IDENT` 与 `ErrorCode.IDENT, 原文案`；`.getMessage()` 链式、`.formatted(..)` 单参、空参、字符串字面量、`MessageConstant` 常量、`String.format(...getMessage()...)` 全部判违规。
2. **两条负向自证用例**：`acceptsBareEnumArgsConstructorForms`（合法枚举形态判绿）与 `rejectsLegacySingleStringArgsConstructorForms`（6 个违规样本判红，含 `.getMessage()` 与空参）。
3. **4 处待裁决按 Owner 拍板 b 方案落地**：`BusinessActivityRelationSupport` 的 `:48/:165`（`BUSINESS_ACTIVITY_USER_DUPLICATE`）与 `:92/:135`（`BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST`）改为 `new BaseException(ErrorCode.PARAM_FORMAT_ERROR, 原 MessageConstant)`（50002，零新增码，文案整串保留）；门禁白名单**删除**（零豁免），新增 `BusinessActivityRelationSupportTest` 4 例在真实 service 方法路径断言 `code == 50002` 且文案保留。
4. **文档 MINOR 三处**：映射表补漏 `PaymentRecordServiceImpl:70`（合同不存在 → `CONTRACT_NOT_EXISTS` 31001）并删除原 #24 占位行；`release` 的 `CustomerPoolServiceIT` **不实引用删除**，如实标注「全仓无该 IT、真库回环缺失、登记待办池（另卡补 `CustomerPoolReleaseIT`）」；口径统一为 **95/95 收敛**。
5. 未做（按提示词边界）：第 4 节「白名单锁点位」仅在未执行第 2 节时必做，本笔已执行第 2 节故白名单直接清零。

### 11.2 证据（本轮实测）

| 项 | 结果 |
| --- | --- |
| 定向复跑（`BaseExceptionConvergenceTest` + `BusinessActivityRelationSupportTest`） | `Tests run: 10, Failures: 0, Errors: 0, Skipped: 0` / BUILD SUCCESS（`work/_reentry-targeted.log`） |
| `mvn -B -ntp clean verify` | surefire **1720/0/0**（275 报告）、failsafe **118/0/0/7**（31 报告）、checkstyle 0、spotless 1128 clean、spotbugs PASS、pmd 0、**BUILD SUCCESS**（`work/_reentry-verify.log`） |
| 基线 `--update`（树净） | `source-revision=0d07514`、275/1720/0 + 31/118/7，`EXIT=0`；校验模式 `EXIT=0`（`work/_reentry-baseline.log`） |
| `merge-gate.sh` | **10/10 PASS**，末行「merge-gate 通过」；typed-router 生成物已 checkout 还原（`work/_reentry-mergegate.log`） |
| 只增不减 | surefire 1697（master）→ **1720**（+23）；failsafe 118 不变；报告 270 → 275 |

### 11.3 新 tip 与提交

- 代码笔 `0d07514 fix(m1): tighten convergence guard and map 4 pending sites to 50002`
- 基线笔 `a8d136f test(baseline): update clean baseline to 0d07514`
- 本 handoff/tasks 证据笔（1 行 ASCII subject）紧随其后；三笔均单行 ASCII、线性无 merge，**未 merge、未 push**。
- 说明：因基线 `--update` 要求树净（`git diff-index --quiet HEAD`），docs 笔只能在基线笔与 merge-gate 之后落，故为 3 笔而非 2 笔——与首轮「docs → baseline → docs」同型。
