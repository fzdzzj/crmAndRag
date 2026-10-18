# 变更提案 · 自服务改密旧密校验与隐私脱敏全链路加固 (harden-user-password-and-privacy)

## 1. 现状与痛点

本卡为候选池 v2 的两条高价值安全防线合卡推进：

### 模块 A：自服务改密旧密校验与审计加固（B-2）
- **现状**：`POST /user/password` 控制器端点入参仅接收裸新密码（`@RequestBody String password`），服务层 `UserServiceImpl.updatePassword` 直接 Base64 解密并哈希覆盖，**完全不校验原密码**，且零日志审计；
- **痛点**：会话被劫持（如 XSS、CSRF 或离开电脑未锁屏）后，攻击者可直接调用该接口静默改密接管账号；
- **方案**：
  1. 定义 `UserUpdatePasswordDTO`（含 `oldPassword` 与 `newPassword`）；
  2. 控制器改为接收 `@Valid @RequestBody UserUpdatePasswordDTO dto`，并在 `frontend/openapi.yaml` 同步更新保持契约一致；
  3. 服务层（下沉至 `UserPasswordSupport` 防 `UserServiceImpl` PMD NCSS 破线）先根据 `currentUserId` 查出用户，通过 `PasswordHashUtil.matches(decryptedOld, stored)` 校验旧密码，不匹配抛 12003 / 密码错误；校验新旧密码不一致；新密码哈希入库；
  4. 记录安全审计日志。

### 模块 B：手机号脱敏死代码修复与销售线索 PII 补漏（B-6 / B-7）
- **现状**：
  1. `PrivacyAspect.privacyShield`（第 113-123 行）仅判断了 `PRIVACY_PHONE_VIEW`（调用 `o.phone()`）和 `PRIVACY_EMAIL_VIEW`（调用 `o.email()`），**漏掉了 `o.mobile()` 分支**！
  2. `Privacy` 接口、`CustomerContactVO` 与 `CustomerContactExcel` 均已实现了 `mobile()` 方法（置为 `***` 或 `********`），但因切面漏调成为死代码，导致联系人手机号全链路（查询、导出、AI 查询）裸奔；
  3. `SalesLeadVO` 承载核心售前线索联系方式（`phone`, `email`），但未实现 `Privacy` 接口，且 `SalesLeadServiceImpl` 查询端点未挂 `@Privacy` 注解。
- **方案**：
  1. 在 `PrivacyAspect.privacyShield` 中，无 `PRIVACY_PHONE_VIEW` 权限时，成对执行 `o.phone()` 与 `o.mobile()`；
  2. `SalesLeadVO` 实现 `Privacy` 接口并覆写 `phone()` 与 `email()` 脱敏逻辑；
  3. 在 `SalesLeadServiceImpl` 的 `getDetail` 与 `queryPage` 方法上补充挂载 `@Privacy` 注解，全面覆盖线索 PII 脱敏。

## 2. 红线与非目标

- 零数据库迁移（`src/main/resources/db/migration/` 零改动，下一可用号仍为 V43）；
- 零新增依赖（`pom.xml` 零改动）；
- 前端代码保持零改动，仅同步 `frontend/openapi.yaml` 保证前后端 OpenAPI 导出一致性门禁；
- 严禁触碰 `frontend/typed-router.d.ts`；
- B 档独立复核（安全加固与脱敏加固，全量回归与门禁对账）。
