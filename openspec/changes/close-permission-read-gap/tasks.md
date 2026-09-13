# Tasks — close-permission-read-gap

> 执行契约见 `openspec/git-workflow.md`；当前基线：master @ `5ce3614`，surefire **602**（本提案预期不变），failsafe 基线 12（无 Docker 下限，不动）。

## 1. 注解落地（src/main）

- [x] 1.1 `PermissionController#list()`（`@RequestMapping("/list")`）加 `@RequirePermission(PermissionOperates.SYSTEM_ASSIGN_PERMISSION)`；Javadoc 注明"close-permission-read-gap：复用 606（读写同权，用户已拍板），无 606 角色将收 12002"
- [x] 1.2 `PermissionController#getByRole()`（`@GetMapping("/getByRole")`）同上
- [x] 1.3 核对命令期望 **3 处命中**（list / addORDeletePermissionsToRole / getByRole）：
  `grep -n "@RequirePermission" src/main/java/com/slz/crm/server/controller/PermissionController.java`

## 2. IT 启用 + 正向兼容用例

- [x] 2.1 移除 `PermissionControllerIT` 两个 `@Disabled`（理由串的三项条件已全部满足）；类 Javadoc 的"未闭合缺口"长说明重写为已闭合记录（保留机制说明，删除停放理由）
- [x] 2.2 反向用例沿用现状：`TestRole.NORMAL`（roleId=3，无任何授权）对两接口断言 `$.code = 12002`（注：停放期从未真正跑过，`init_data.sql` 只种 sys_role id=1，反向用例需在 `normalUserToken()` 内先补种 roleId=3 角色，否则插入 sys_user 撞 fk_user_role）
- [x] 2.3 新增正向用例 `shouldAllowPermissionListForRoleGrantedAssignPermission`：用例内 jdbcTemplate 种子（sys_role id=2 + `permissions` id=606 + `role_permissions(2,606)` + sys_user roleId=2，参照 `normalUserToken()` 模式，用户 status=1、roleId≠1 避开超管硬放行）→ `/permission/list` 与 `/permission/getByRole` 均断言 `$.code = 1`；**不动共享 `init_data.sql`**（@Transactional 回滚，零波及其他 IT）
- [x] 2.4 本地 Docker 实测：`mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=PermissionControllerIT"` → 3 绿（2 反向 + 1 正向）

## 3. 文档与基线

- [x] 3.1 `AGENTS.md`「未闭合的授权缺口」章节闭合：按其自述核对路径改写（缺口已闭合、两 @Disabled 已移除），不动文件其余条款
- [x] 3.2 `ci.yml` 口径B 推算注释同步（PermissionControllerIT 由 2 disabled → 3 运行，推算 17 → 18，推算值标注非实测）；**基线 12 不动**（Docker 依赖类不进无 Docker 下限）
- [x] 3.3 `HANDOFF.md` 增补：权限读取缺口闭合（close-permission-read-gap 合入，复用 606）
- [x] 3.4 亲验 `mvn -B -ntp test` 全绿且合计 **602 不变**（零新增单测）；`git status` 干净（仅允许两个已知未跟踪文件）

## 4. Git 收尾

- [x] 4.1 分支 `feature/close-permission-read-gap`，提交按任务组（`fix(permission): 中文描述` 等），tasks.md 勾选随代码同提交
- [x] 4.2 亲验全绿后合入：`git checkout master; git merge --no-ff feature/close-permission-read-gap -m "Merge branch 'feature/close-permission-read-gap'：闭合权限目录读取缺口（复用606）"`；汇报带 commit hash、实测计数、AGENTS.md 闭合确认
