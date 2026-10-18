# 任务清单 · 用户自服务信息更新防提权越权加固 (harden-user-self-update)

<!-- id: harden-user-self-update -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 服务层自服务专用防线与端点解耦

- [x] 1.1 `UserService` 声明并在 `UserServiceImpl` 实现专用方法 `void updateMyInfo(UserDTO userDTO, Long currentUserId)`：对特权字段（`roleId`, `deptId`, `status`）实行 fail-closed 校验，任何特权字段非空直接抛出 `BaseException(ErrorCode.PERMISSION_DENIED, "自服务禁止修改角色、部门或状态等特权信息")`（12002）；更新构造严格限制仅 `realName`, `phone`, `email` 白名单。
      实测：`UserServiceImpl#updateMyInfo` fail-closed 拦截先于一切 DB 访问，白名单 wrapper 构造拆至 `UserSelfUpdateSupport`（PMD NcssCount classReportLevel=150 口径，`UserServiceImpl` 实测 153 超限后拆分达标）；专项测试 3 个反向用例断言 `getCode()==12002` 且 `verifyNoInteractions(userMapper)` 证明零 SQL 触达。
- [x] 1.2 `UserController.updateUserMy`（`POST /user/update/my`）解耦：由原调用 `updateUser` 切换为调用 `userService.updateMyInfo(userDTO, BaseUnit.getCurrentId())`，保持管理员 `POST /user/update` 既有行为不变。
      实测：`grep -rn "\.updateUser(" src/main/java` 全仓生产调用仅剩管理员端点 `UserController.java:143` 一处；`POST /user/update` 行为零改动（`git diff master` 该方法零 hunk）。

## 任务组 2 · 开放端点登记表精准化与权限审计对账

- [x] 2.1 修订 `OpenEndpointRegistry.java` 中 `POST /user/update/my` 的登记说明，精准反映白名单加固约束（`"自服务：用户改自己基本信息（仅限姓名/手机/邮箱，严禁篡改角色/部门/状态，登录即可用）"`），确保权限覆盖扫描门禁全绿。
      实测：登记理由已按卡面原文修订；`PermissionCoverageAuditIT` 定向跑与 `clean verify` 双跑均实测 `Tests run: 2, Failures: 0, Errors: 0`。

## 任务组 3 · 专项安全与防越权测试覆盖

- [x] 3.1 编写 `UserSelfUpdateSecurityTest`（或相应集成测试/单元测试）：
  - 覆盖正常自服务修改姓名/手机/邮箱（白名单属性）成功更新入库；
  - 覆盖恶意自服务请求包含 `roleId` 时严格抛出 12002 `PERMISSION_DENIED` 拦截且角色未变；
  - 覆盖恶意自服务请求包含 `deptId` 时严格抛出 12002 `PERMISSION_DENIED` 拦截且部门未变；
  - 覆盖恶意自服务请求包含 `status` 时严格抛出 12002 `PERMISSION_DENIED` 拦截且状态未变；
  - 覆盖管理员通过 `POST /user/update` 修改用户特权字段的正常能力不受影响。
      实测：新建 `src/test/java/com/slz/crm/server/service/UserSelfUpdateSecurityTest.java` 五用例（纯 JVM Mockito），正向用例捕获 wrapper 断言 SET 列恰为 real_name/phone/email/update_time、WHERE 起始 `(id` 且参数含 currentUserId(42)、不含伪造 id(999)；`mvn -B -ntp test -Dtest="UserSelfUpdateSecurityTest,PermissionCoverageAuditIT"` 实测 `Tests run: 5, Failures: 0, Errors: 0`（合计 7/7 绿）。

## 任务组 4 · 门禁与基线对账

- [x] 4.1 全量测试通过，`mvn -B -ntp clean verify` 全绿后以 `bash scripts/check-test-baseline.sh --update` 单次写入最新基线（Surefire 用例数在 1636 基础上只增不减）。
      实测：`mvn -B -ntp clean verify` EXIT=0（9 分 13 秒，日志 `work/_hus-verify.log`）：surefire `Tests run: 1641, Failures: 0, Errors: 0, Skipped: 0`（=1636+5，只增不减）、failsafe `Tests run: 118, Failures: 0, Errors: 0, Skipped: 7`（7 跳为真实外网 opt-in）；先提交代码（3f3db1b）后 `--update` 实测写入 `scripts/test-baseline.txt`：`surefire.tests=1641`、`source-revision=3f3db1b`（无 -dirty）。
- [x] 4.2 本地运行 `bash scripts/merge-gate.sh` 确保 8/8 全 PASS，还原 `frontend/typed-router.d.ts` 噪声，产出 `work/handoff-harden-user-self-update.md` 并停步待 A 档独立复核。
      实测：`bash scripts/merge-gate.sh` 实测 `GATE_EXIT=0`（日志 `work/_hus-mergegate.log`）：unit / spotbugs / pmd / baseline / frontend-unit / hook / bijection / pmd-baseline 八门全 PASS；门禁后 `git checkout -- frontend/typed-router.d.ts` 还原，工作树仅剩本卡 openspec 物料；handoff 已产出；未 merge、未 push，停步待 Owner 派发 A 档独立复核。
