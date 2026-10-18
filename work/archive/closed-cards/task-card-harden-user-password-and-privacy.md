# 任务卡 · harden-user-password-and-privacy（改密校验与隐私脱敏加固合卡，B 档）

> 目标：闭环 B-2 自服务改密旧密码校验与审计 + B-6/B-7 手机号脱敏死代码激活与销售线索 PII 补漏，消除账号静默接管与手机号全链路泄漏隐患。

## 边界与红线

- 零数据库迁移：`src/main/resources/db/migration/` 零改动（下一可用号仍为 V43）；
- 零新增依赖：`pom.xml` 零修改；
- 前端代码零修改：`frontend/openapi.yaml` 仅同步 `/user/password` DTO 结构，确保前后端契约一致；
- 严禁触碰 `frontend/typed-router.d.ts`（若被前端工具再生成，立即 checkout 还原）；
- 提交规范：所有 commit 信息必须使用 `-m "xxx"` 内联 ASCII 简短单行语句。

## 核心锚点

- **改密模块**：
  - 定义 `UserUpdatePasswordDTO`（`oldPassword`, `newPassword`）；
  - `UserController.updatePassword` 切换入参 DTO 并转调 `userService.updatePassword`；
  - 抽离 `UserPasswordSupport`（避免 `UserServiceImpl` PMD NCSS 破线），通过 `PasswordHashUtil.matches` 校验旧密，不匹配抛错；校验新旧不重复并哈希入库，记录审计日志；
- **脱敏模块**：
  - `PrivacyAspect.privacyShield` 在无 `PRIVACY_PHONE_VIEW` 权限时补调 `o.mobile()`；
  - `SalesLeadVO` 实现 `Privacy` 接口覆写 `phone()` 与 `email()`；
  - `SalesLeadServiceImpl` 的 `getDetail` 与 `queryPage` 挂载 `@Privacy` 注解；
- **专项测试**：分别编写改密旧密校验测试与手机号/线索脱敏测试。

## 验收要求

- 改密与脱敏专项测试全部通过；
- `mvn -B -ntp clean verify` 全绿（Docker Testcontainers 118 IT 全过）；
- `bash scripts/merge-gate.sh` 8/8 全绿，Surefire 用例数在 1641 基础上只增不减；
- 产出 `work/handoff-harden-user-password-and-privacy.md` 并完成 `tasks.md` 全勾与 6 空格实测依据行。

## 交付纪律

- 分支：`feature/harden-user-password-and-privacy`（从最新 `master` 检出，完工工作树干净）；
- 完工严格停步待复核：严禁私自 `git merge` 或 `git push`！
