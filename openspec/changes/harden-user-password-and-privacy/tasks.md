# 任务清单 · 自服务改密旧密校验与隐私脱敏全链路加固 (harden-user-password-and-privacy)

<!-- id: harden-user-password-and-privacy -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 自服务改密旧密码校验与审计加固

- [x] 1.1 新增/更新 `UserUpdatePasswordDTO`（包含 `@NotBlank String oldPassword` 与 `@NotBlank String newPassword`），并在 `UserController.updatePassword` 接收该 DTO；控制器转调 `userService.updatePassword(dto, BaseUnit.getCurrentId())`。
      新建 `src/main/java/com/slz/crm/pojo/dto/UserUpdatePasswordDTO.java`（@NotBlank 中文消息）；`UserController` `/user/password` 改为 `@Valid @RequestBody UserUpdatePasswordDTO dto` 并转调 `userService.updatePassword(dto, BaseUnit.getCurrentId())`（`UserService` 接口签名同步更换）；实测 04c8c00 提交含 UserController.java:104-118 与 UserService.java:45-51。
- [x] 1.2 服务层实现原密码核验与审计（抽离至 `UserPasswordSupport` 避免 `UserServiceImpl` PMD NCSS 破线）：通过 `PasswordHashUtil.matches(decryptedOld, stored)` 校验旧密，不匹配抛 12003 / 密码错误；校验新旧密码不能相同；哈希新密码入库并输出安全审计日志。
      实测偏差说明：`ErrorCode.PASSWORD_ERROR` 不存在且 12003 已被 `USER_STATUS_EXCEPTION` 占用，按 92xxx 业务逻辑段顺延新增 `PASSWORD_ERROR(92028, "密码错误", "error.password.error")`（同族 92025 PASSWORD_OR_EMAIL_ERROR）；新建 `UserPasswordSupport.changePassword`（selectById + 双 Base64 解密 + matches 旧密 + Objects.equals 拒同密 + BCrypt hash 落库 + `log.info("用户自服务修改密码成功: userId={}")` 审计），UserServiceImpl 仅剩 3 行委托；实测 04c8c00 含两文件。返场修复（F-1，2026-10-18 B 档复核 MAJOR）：原实现 `BaseException(ErrorCode.PASSWORD_ERROR.getMessage())` 走 `BaseException(String)` 构造器 code=null、经 GlobalExceptionHandler 降级为 code=0，前端 92028 映射成死映射；已改 `throw new BaseException(ErrorCode.PASSWORD_ERROR)` 枚举构造器（527bebc 单行），`UserPasswordUpdateTest` 用例 2 补 `assertEquals(ErrorCode.PASSWORD_ERROR.getCode(), ex.getCode())` 实证 92028 透传；复验靶向 4 类 10/10 绿、spotbugs/pmd 0 违规、基线 surefire 1647 对账直接 EXIT=0（未动基线文件）、merge-gate 8/8 全 PASS GATE_EXIT=0（日志 work/_f1-mergegate.log）。

## 任务组 2 · 手机号脱敏死代码修复与销售线索 PII 补漏

- [x] 2.1 修复 `PrivacyAspect.privacyShield`：在无 `PRIVACY_PHONE_VIEW` 权限分支下，补齐调用 `o.mobile()`，激活 `CustomerContactVO` 与 `CustomerContactExcel` 的手机号脱敏逻辑。
      实测 PrivacyAspect.java 无权限分支现为 `o.phone(); o.mobile();` 成对调用（04c8c00），尾注同步改为 phone/mobile/email 三字段口径；PrivacyMobileAndLeadTest 用例 1 实证 contact.getMobile() == "***"。
- [x] 2.2 `SalesLeadVO` 实现 `com.slz.crm.pojo.ao.Privacy` 接口，覆写 `phone()`（置为 `***`）与 `email()`（置为 `***`）脱敏方法。
      实测 SalesLeadVO 类声明 `implements Privacy` 并覆写 `phone()`/`email()` 均 `this.x = "***"; return true;`（04c8c00）；isSelf 经 Lombok getOwnerId/getCreatorId 反射自动生效（ownerId=1、当前用户 9001 时正常脱敏，PrivacyMobileAndLeadTest 三用例覆盖）。
- [x] 2.3 在 `SalesLeadServiceImpl.getDetail` 与 `queryPage` 查询方法上挂载 `@Privacy` 注解，全面纳管销售线索联系方式脱敏。
      实测 SalesLeadServiceImpl.java 两方法均已挂 `@Privacy` 并补 import `com.slz.crm.server.annotation.Privacy`（04c8c00）；SalesLeadServiceTest/SalesLeadWriteEndpointValidationTest 既有用例全绿无回归（surefire 1647 全绿背书）。

## 任务组 3 · OpenAPI 契约同步与双模块专项测试

- [x] 3.1 同步更新 `frontend/openapi.yaml` 中 `/user/password` 的 requestBody 声明为 `UserUpdatePasswordDTO` 模式，确保 `OpenApiContractExportTest` 门禁 100% 通过。
      实测经 `bash scripts/check-openapi-consistency.sh --update` 真实导出覆盖：requestBody `$ref: "#/components/schemas/UserUpdatePasswordDTO"` + schemas 区新增 oldPassword/newPassword（required、minLength 1）；复跑 `mvn -B -ntp test -Dtest=OpenApiContractExportTest` 2/2 绿（逐字比对通过）。
- [x] 3.2 编写/扩充专项测试：
      实测两文件均新建于 04c8c00。
  - 改密专项（`UserPasswordUpdateTest`）：验证正确旧密修改成功入库、错误旧密拦截并拒绝、新旧密相同拒绝；
      实测 3/3 绿：哈希断言 `PasswordHashUtil.matches(NEW_RAW, captured)` + isBcrypt；错旧密 `BaseException "密码错误"` 且 updateById never；同密 `BaseException "新密码不能与原密码相同"` 且不发 SQL。
  - 脱敏专项（`PrivacyMobileAndLeadTest`）：验证无 `PRIVACY_PHONE_VIEW` 时联系人 `mobile` 与线索 `phone`/`email` 正确脱敏，有权限时不脱敏。
      实测 3/3 绿：无权限三 VO 全 "***"；有 PHONE_VIEW 无 EMAIL_VIEW 时 mobile/phone 保留 email "***"；双权限全保留。

## 任务组 4 · 门禁与基线对账

- [x] 4.1 全量测试通过，`mvn -B -ntp clean verify` 全绿后以 `bash scripts/check-test-baseline.sh --update` 单次写入最新基线（Surefire 用例数在 1641 基础上只增不减）。
      实测 `clean verify` VERIFY_EXIT=0：surefire 1647/0/0/0（=1641 基线 +6 新增，只增不减）、failsafe 118 跑 0 败 7 跳（7 项需真实外网，与卡面一致）；基线台账 scripts/test-baseline.txt 由 --update 单次写入 surefire.reports=266 / surefire.tests=1647 / failsafe.tests=118 / failsafe.skipped=7 / source-revision=04c8c00（无 -dirty），提交 180b700。静态门禁 spotbugs BugInstance=0、pmd 未超台账。
- [x] 4.2 本地运行 `bash scripts/merge-gate.sh` 确保 8/8 全 PASS，还原 `frontend/typed-router.d.ts` 噪声，产出 `work/handoff-harden-user-password-and-privacy.md` 并停步待 B 档独立复核。
      实测 merge-gate 8/8 全 PASS（unit/spotbugs/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline）GATE_EXIT=0，日志 work/_hp-mergegate2.log；typed-router.d.ts 已 `git checkout --` 还原（工作树仅剩卡片物料）；handoff 已产出；未 merge 未 push，停步等 Owner 派发 B 档独立复核。

## 红线偏差披露（供 B 档复核裁定）

1. **前端写集扩展**：卡面红线"前端代码零改动，仅同步 frontend/openapi.yaml"实际写集含 `frontend/src/constants/error-code-map.ts`（+9 行镜像）与 `frontend/src/constants/error-code-map.test.ts`（1 行计数 98→99）。原因：规格 1.2 显式要求抛 `ErrorCode.PASSWORD_ERROR`（枚举原本不存在，必须新建），而 CI 阶段 1 门禁 `ErrorCodeMapParityTest` 与前端 Vitest 单测（frontend-unit 门禁）强制前后端错误码镜像两侧同步；该 TS 文件是后端枚举的手工契约镜像（与 openapi.yaml 同性质的契约层，非 UI 代码）。备选方案（复用 92025 或裸字符串消息）违背卡面显式规格或降低 API 契约质量，未采纳。三次前端 pre-commit（eslint+vue-tsc）实测全绿。
2. **错误码取号**：卡面提案"12003/密码错误"与代码现状冲突（12003=USER_STATUS_EXCEPTION），按 92xxx 段顺延取 92028，语义族与 PASSWORD_OR_EMAIL_ERROR(92025) 一致。
3. **follow-up**：`pnpm gen:api` SDK 再生成属前端代码改动，按红线留待 Owner 决策（契约门禁本身不校验 SDK）。
