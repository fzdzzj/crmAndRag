# 任务卡 · harden-user-self-update（自服务提权漏洞修复，A 档）

> 目标：彻底杜绝 `POST /user/update/my` 自服务提权与部门越权漏洞（侦察台账 B-1，A 档），限定自服务白名单字段更新并对特权字段实行 Fail-Closed 拦截。

## 边界与红线

- 零数据库迁移：`src/main/resources/db/migration/` 零改动（下一可用号仍为 V43）；
- 零新增依赖：`pom.xml` 零修改；
- 零破坏性契约变更：`openapi.yaml` 零 diff，不修改 `UserDTO` 接口结构；
- 严禁触碰 `frontend/typed-router.d.ts`（若被生成工具改动立即 checkout 还原）；
- 提交规范：所有 commit 信息必须使用 `-m "xxx"` 内联 ASCII 简短单行语句。

## 核心锚点

- `UserService.java` & `UserServiceImpl.java`：声明并实现 `updateMyInfo(UserDTO userDTO, Long currentUserId)`，遇 `roleId`/`deptId`/`status` 显式非空直接抛 12002 `PERMISSION_DENIED`，更新语句仅构造 `realName`, `phone`, `email` 白名单；
- `UserController.java`：`updateUserMy` 改调 `userService.updateMyInfo`，管理员 `updateUser` 保持既有行为；
- `OpenEndpointRegistry.java`：同步修订 `/user/update/my` 的 `INTENTIONAL_OPEN` 登记理由；
- 专项测试：编写单测/集成测试全面覆盖白名单更新成功与三向越权拦截（`roleId`/`deptId`/`status`），以及管理员特权更新不受影响。

## 验收要求

- 专项测试全部通过；
- `mvn -B -ntp clean verify` 全绿（Docker Testcontainers 118 IT 全过）；
- `bash scripts/merge-gate.sh` 8/8 全绿，Surefire 用例数在 1636 基础上只增不减；
- 产出 `work/handoff-harden-user-self-update.md` 并完成 `tasks.md` 8 项勾选与 6 空格实测证据行。

## 交付纪律

- 分支：`feature/harden-user-self-update`（从最新 `master` 检出，完工工作树干净）；
- 完工严格停步待复核：严禁私自 `git merge` 或 `git push`！
