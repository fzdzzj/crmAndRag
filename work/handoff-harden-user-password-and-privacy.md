# Handoff · harden-user-password-and-privacy（改密校验与隐私脱敏加固合卡，B 档）

- 日期：2026-10-18
- 执行者：执行子 agent（ZCode，Owner 亲自派发）
- 权威工作树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- 分支：`feature/harden-user-password-and-privacy`，基于 master `63d9122` 检出
- 分支 tip：`2838060`
- 状态：**未 merge 未 push**，停步等 Owner 派发 B 档独立复核

## 1. 分支提交序列（3 笔）

| SHA | 说明 |
| --- | --- |
| `04c8c00` | feat(security): harden password change verification and activate mobile privacy desensitization |
| `180b700` | test(baseline): update clean baseline for harden-user-password-and-privacy |
| `2838060` | fix: bump frontend error code parity count to 99 for PASSWORD_ERROR 92028 |

## 2. 写集统计（63d9122..2838060，15 文件 +381/−28）

**主代码（7 文件）**
- 新建 `src/main/java/com/slz/crm/pojo/dto/UserUpdatePasswordDTO.java`（+17）：`@NotBlank oldPassword/newPassword`（中文消息）。
- 新建 `src/main/java/com/slz/crm/server/service/support/UserPasswordSupport.java`（+61）：静态支持类。
- `UserController.java`（+9/−4）：`POST /user/password` 改收 `@Valid @RequestBody UserUpdatePasswordDTO`，转调 `userService.updatePassword(dto, BaseUnit.getCurrentId())`。
- `UserService.java`（+8/−3）：接口签名 `boolean updatePassword(UserUpdatePasswordDTO dto, Long currentId)`。
- `UserServiceImpl.java`（+17/−12）：updatePassword 收敛为 3 行委托 `UserPasswordSupport.changePassword(...)`（防 PMD NCSS 破线）。
- `ErrorCode.java`（+3）：新增 `PASSWORD_ERROR(92028, "密码错误", "error.password.error")`。
- `PrivacyAspect.java`（+6/−2）：无 `PRIVACY_PHONE_VIEW` 分支补 `o.mobile();`，尾注改三字段口径。

**模块 B（2 文件）**
- `SalesLeadVO.java`（+16/−1）：`implements Privacy` + 覆写 `phone()`/`email()` 置 `***`。
- `SalesLeadServiceImpl.java`（+3）：`getDetail`/`queryPage` 挂 `@Privacy`。

**前端契约镜像与契约（2 文件）**
- `frontend/openapi.yaml`（+14/−1）：`/user/password` requestBody `$ref: "#/components/schemas/UserUpdatePasswordDTO"` + schema 声明（required + minLength 1）。
- `frontend/src/constants/error-code-map.ts`（+9）：92028 镜像（码表行 + meta 块）——**红线偏差**，见 §5。

**测试（3 文件）**
- 新建 `src/test/java/com/slz/crm/server/service/UserPasswordUpdateTest.java`（+119）：3 用例。
- 新建 `src/test/java/com/slz/crm/server/aspect/PrivacyMobileAndLeadTest.java`（+117）：3 用例。
- `frontend/src/constants/error-code-map.test.ts`（+1/−1）：镜像总数 98→99——**红线偏差**，见 §5。

**台账（1 文件）**
- `scripts/test-baseline.txt`：surefire.reports=266 / surefire.tests=1647 / surefire.skipped=0 / failsafe.reports=31 / failsafe.tests=118 / failsafe.skipped=7 / source-revision=04c8c00。

## 3. 实现决策

1. **PASSWORD_ERROR 取号**：卡面规格引用 `ErrorCode.PASSWORD_ERROR`，实测枚举不存在，且提案所称 12003 已被 `USER_STATUS_EXCEPTION` 占用。按 92xxx 业务逻辑段顺延取 **92028**（前值 92027 NOT_ENABLED），与同族 `PASSWORD_OR_EMAIL_ERROR(92025)` 语义归位。i18n 键 `error.password.error` 仅为枚举第三元（仓内无 .properties 消息文件，无资源需同步）。
2. **UserPasswordSupport 形态**：卡面指定路径 `server/service/support/`（跨包），而既有静态支持类（UserLoginSupport 等）位于 `service/impl` 且包私有；故落为 **public final 静态工具**（`changePassword(UserMapper, currentId, encryptedOld, encryptedNew)`），不引入新 Spring bean。防御性增强两处并如实披露：用户不存在抛 `ID_NOT_EXISTS("用户")`（镜像 resetUserPassword 先例，防 NPE）；更新 0 行抛 `ServiceException(UPDATE_FAILED)`（保留原实现行为）。
3. **最小更新实体**：落库用仅含 id+password 的新实体（镜像 resetUserPassword 惯例），不回写整行。
4. **OpenAPI 同步路径**：不手改 YAML，先改代码再 `bash scripts/check-openapi-consistency.sh --update` 真实导出覆盖，复跑 `OpenApiContractExportTest` 逐字比对 2/2 绿。契约形状与卡面预期一致（$ref + UserUpdatePasswordDTO schema）。
5. **isSelf 语义**：SalesLeadVO 经 Lombok 自动具备 `getOwnerId`/`getCreatorId`，命中 `PrivacyGetId.USER_ID_RELATED_FIELDS`，负责人/创建人本人查看不脱敏（测试用例 2/3 佐证）。

## 4. 验证证据（全部本轮实测）

| 关卡 | 结果 |
| --- | --- |
| 定向靶向（UserPasswordUpdateTest, PrivacyMobileAndLeadTest, OpenApiContractExportTest, PermissionCoverageAuditIT） | 10/10 绿 |
| 静态 `mvn spotbugs:check pmd:check` | SpotBugs BugInstance=0；PMD 未超台账 |
| `mvn -B -ntp clean verify` | VERIFY_EXIT=0；surefire **1647**/0/0/0（基线 1641 只增不减，+6 即两新测试类）；failsafe **118 跑 0 败 7 跳**（与卡面"跳过 7 项需真实外网"一致） |
| `bash scripts/check-test-baseline.sh --update` | 单次写入成功，source-revision=04c8c00 无 -dirty |
| `bash scripts/merge-gate.sh` | **8/8 全 PASS，GATE_EXIT=0**（unit / spotbugs / pmd / baseline / frontend-unit / hook / bijection / pmd-baseline） |
| 前端 pre-commit（eslint + vue-tsc，3 笔提交各过一次） | 全绿 |

日志留档（work/ 下 gitignore 收敛，本机可查）：
- `work/_hp-verify.log` —— 第一轮 clean verify（parity 红因实录，§5-1 的证据链起点）
- `work/_hp-verify2.log` —— 第二轮 clean verify 全绿（VERIFY_EXIT=0、1647/118 终值）
- `work/_hp-mergegate.log` —— 第一轮 merge-gate（frontend-unit 红因实录）
- `work/_hp-mergegate2.log` —— 第二轮 merge-gate 8/8 GATE_EXIT=0

**过程中暴露并修复的两个门禁缺口（均已绿）**：
1. 新增 ErrorCode 枚举触发 `ErrorCodeMapParityTest`（CI 阶段 1）——前端镜像表需同步，第一轮 verify 红 2 例即此；
2. 新增镜像又触发前端 Vitest 硬编码总数断言（frontend-unit 门禁）——`BACKEND_ERROR_CODE_NAMES` 98→99。

## 5. 红线偏差披露（请 B 档复核重点裁定）

1. **前端写集扩展**（最重要的偏差）：卡面红线"前端代码零改动，仅同步 frontend/openapi.yaml"，实际写集含 `frontend/src/constants/error-code-map.ts`（+9）与 `frontend/src/constants/error-code-map.test.ts`（1 行计数）。理由链：卡面规格 1.2 显式要求抛 `ErrorCode.PASSWORD_ERROR` ⇒ 枚举必须新建 ⇒ `ErrorCodeMapParityTest`（"前端映射层是后端枚举的手工镜像……本测试即失败"）与 frontend-unit 总数断言**强制**两侧同步，否则 clean verify 与 merge-gate 双双无法全绿。该 TS 文件为契约镜像层（与 openapi.yaml 同性质），非 UI 行为代码；备选（复用 92025 / 裸字符串消息）违背卡面显式规格或降低 API 契约质量，未采纳。
2. **错误码取号**：提案"12003/密码错误"与代码现状冲突（12003=USER_STATUS_EXCEPTION），取 92028。
3. **follow-up 未做**：`pnpm gen:api` SDK 再生成属前端代码改动，按红线留待 Owner 决策；契约门禁本身不校验 SDK，前端 SDK 与契约存在滞后窗口。

## 6. 红线自查

- db/migration 零改动 ✓（git log 63d9122..2838060 无该路径）
- pom.xml 零改动、零新增依赖 ✓
- `frontend/typed-router.d.ts` 零改动 ✓（门禁再生成后已 `git checkout --` 还原，最终 git status 干净）
- 提交信息全部单行内联 ASCII（`git commit -m`）✓
- 未执行 git merge / git push ✓
- 工作树无未跟踪污染（仅 openspec/ 与 work/ 卡片物料，work/_hp-*.log 由 .gitignore 收敛）✓

## 7. 停步声明

执行完毕，全部任务已勾选（openspec/changes/harden-user-password-and-privacy/tasks.md，每项附 6 空格缩进实测依据）。**未 merge、未 push**，等待 Owner 派发 B 档独立复核；复核通过后由 Owner 授权合并。
