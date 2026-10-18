# 交接 · harden-user-self-update（自服务提权漏洞修复，A 档）

> 分支 `feature/harden-user-self-update`，自 master@f15e83e 检出；完工停步：未 merge、未 push。
> 执行树：权威树 D:\code\crmAndRag-merge-add-knowledge-admin-api（主协作树未触碰）。
> 漏洞语义：`POST /user/update/my` 原与服务层管理员通道 `updateUser` 共用，普通登录用户可构造请求体携带 `roleId`/`deptId`/`status` 完成自提权/跨部门/改状态（侦察台账 B-1 最高危）。

## 1 · 提交清单（分支 2 笔 + 物料 1 笔）

| # | commit | 内容 |
|---|--------|------|
| 1 | 3f3db1b | feat(user): harden self-service user update against privilege escalation |
| 2 | e56228b | test(baseline): update clean baseline for harden-user-self-update |
| 3 | （物料笔） | openspec 三件套勾选 + 本 handoff + task-card |

全部提交信息单行内联 ASCII（`git commit -m`），无多行、无中文。

## 2 · 写集统计（vs master@f15e83e）

| 文件 | 变更 | 说明 |
|---|---|---|
| `src/main/java/com/slz/crm/server/service/UserService.java` | +11 | 声明 `void updateMyInfo(UserDTO userDTO, Long currentUserId)` |
| `src/main/java/com/slz/crm/server/service/impl/UserServiceImpl.java` | +24 | 实现：fail-closed 拦截 + 白名单更新构造（拆至 Support）+ UPDATE_FAILED 兜底 |
| `src/main/java/com/slz/crm/server/service/impl/UserSelfUpdateSupport.java` | +41（新增） | 纯静态白名单 wrapper 构造支持类（PMD NcssCount 拆分，沿 `UserDeptSupport` 先例） |
| `src/main/java/com/slz/crm/server/controller/UserController.java` | +4/−1 | `updateUserMy` 改调 `updateMyInfo(userDTO, BaseUnit.getCurrentId())`；`POST /user/update` 未动 |
| `src/test/java/com/slz/crm/integration/permission/OpenEndpointRegistry.java` | +4/−1 | `/user/update/my` 登记理由改卡面精准表述 |
| `src/test/java/com/slz/crm/server/service/UserSelfUpdateSecurityTest.java` | +167（新增） | 五用例专项安全测试（纯 JVM Mockito） |
| `scripts/test-baseline.txt` | +4/−4 | clean verify 实测写入：surefire 1641 / failsafe 118+7 跳 |
| 合计 | 7 文件 +255/−6 | 代码笔 6 文件 +251/−2 |

## 3 · 实现决策（复核关注点）

1. **fail-closed 先于一切 DB 访问**：`updateMyInfo` 第一行即判 `roleId/deptId/status` 任一非空 → `BaseException(ErrorCode.PERMISSION_DENIED, "自服务禁止修改角色、部门或状态等特权信息")`（12002）；单测以 `verifyNoInteractions(userMapper)` 证明拦截先于任何 SQL。
2. **白名单即安全边界（第二层防御）**：即使拦截失效，SET 子句也只可能含 `realName/phone/email/updateTime`；目标行 `eq(UserEntity::getId, currentUserId)` 强制绑定上下文注入 ID，请求体 `id`（DTO 内存在该字段）不被信任——正向用例以 `containsValue(42L)` + `!containsValue(999L)` 双向断言。
3. **`updateTime` 强制刷新**：无论 DTO 是否携带白名单字段，`set(UserEntity::getUpdateTime, LocalDateTime.now())` 恒在，语义与 `updateUser` 对齐。
4. **`i == 0` → UPDATE_FAILED 兜底**：沿用 `updateUser` 同款；若 `currentUserId` 为 null（理论上 JWT 闸后不可达），`eq(id, null)` 零命中落入该兜底，保持 fail-closed。
5. **`@CacheEvict(value = "userName", key = "#currentUserId")`**：与 `updateUser` 的 `key = "#id"` 缓存语义对齐（自服务改的是自己的 realName，需逐出 userName 缓存防脏读）。此为卡面未明说、按既有语义补齐的一致性决策，请复核重点过目。
6. **`UserSelfUpdateSupport` 拆分动机**：PMD `NcssCount` classReportLevel=150（`pmd-rules.xml` 实测），`UserServiceImpl` 拆分前实测 NCSS 153 超限；拆分后 pmd:check 全绿。Support 类 javadoc 显式警示：新增字段进 SET 前必须过自服务准入评审，严禁改反射/BeanUtils 全量拷贝。
7. **超管通道零改动**：`updateUser`（含 roleId=1 终极管理员唯一性防护、deptId 校验）一字未动；全仓 `grep -rn "\.updateUser(" src/main/java` 仅剩 `UserController.java:143` 管理员端点一处调用。

## 4 · 验证流水线实测（按卡序留痕）

| 步骤 | 命令 | 实测结果 |
|---|---|---|
| 1 定向靶向 | `mvn -B -ntp test -Dtest="UserSelfUpdateSecurityTest,PermissionCoverageAuditIT"` | `Tests run: 7, Failures: 0, Errors: 0`（专项 5/5 + 权限审计 2/2） |
| 2 静态检查 | `mvn -B -ntp spotbugs:check pmd:check` | SpotBugs `BugInstance size is 0`；PMD 首跑红（NcssCount 153>150）→ 拆 Support 后 BUILD SUCCESS |
| 3 全量回归 | `mvn -B -ntp clean verify` | EXIT=0，9 分 13 秒（Docker 29.6.2 在线）；surefire `1641/0/0/0`（1636+5 只增不减）、failsafe `Tests run: 118, Failures: 0, Errors: 0, Skipped: 7`（7 跳 = 真实外网 opt-in，与卡面预期逐项一致） |
| 4 基线写入 | `bash scripts/check-test-baseline.sh --update`（Git Bash） | `surefire.tests=1641`、`surefire.skipped=0`、`failsafe.tests=118`、`failsafe.skipped=7`、`source-revision=3f3db1b`（无 -dirty，符合"先提交后更新基线"） |
| 5 聚合门禁 | `bash scripts/merge-gate.sh` | `GATE_EXIT=0`，八门全 PASS：unit / spotbugs / pmd / baseline / frontend-unit / hook / bijection / pmd-baseline |
| 6 噪声消除 | `git checkout -- frontend/typed-router.d.ts` | 已还原，工作树仅剩本卡 openspec 物料与 task-card |

日志留痕：`work/_hus-verify.log`（全量 verify）、`work/_hus-mergegate.log`（聚合门禁）。

## 5 · 踩坑记录（复核与后续卡参考）

1. **PMD NcssCount 类阈值 150**：`pmd-rules.xml` 中 `NcssCount` classReportLevel=150 / methodReportLevel=75，`UserServiceImpl` 原本贴线（本卡前约 126），新增 ~25 NCSS 方法即破线。拆静态 Support 类是本项目既定处置（`UserDeptSupport` 先例）。
2. **MP wrapper WHERE 渲染带括号**：`LambdaUpdateWrapper.getSqlSegment()` 实测渲染 `(id = #{ew.paramNameValuePairs.MPGENVAL5})`——断言需 `startsWith("(id")` 而非 `startsWith("id")`；且 SET 子句参数编号先于 WHERE 段（MPGENVAL5 为 eq 参数）。
3. **spotless javadoc 折行**：google-java-format 对 javadoc 行宽与 `{@link}` 断行有强 opinion，两轮 `spotless:apply` 才收敛；同包冗余 import 会被自动移除（`DataConvertService` 在 `com.slz.crm.server.service` 包内测试无需 import）。
4. **pre-commit 前端检查对纯后端提交也触发**：两笔提交均跑了 `pnpm lint:check + type-check:check`（全过），纯后端卡耗时增加属正常。

## 6 · 红线自证（实测命令口径）

- 零数据库迁移：`git diff master --stat -- src/main/resources/db/migration` 输出为空（无 V43，最高仍 V42）；
- 零新增依赖：`git diff master --stat -- pom.xml` 输出为空；
- 零契约变更：`git diff master --stat -- frontend/openapi.yaml` 输出为空；`UserDTO` 结构零改动；
- `frontend/typed-router.d.ts`：merge-gate 后已 checkout 还原，最终零 diff；
- 提交纪律：2 笔代码/基线提交均单行 ASCII；物料笔同规约；
- 停步纪律：**未执行 `git merge`，未执行 `git push`**，分支停于 `e56228b`（+物料笔），等待 Owner 派发 A 档独立复核。

## 7 · 复核建议入口

- 安全语义：`UserServiceImpl#updateMyInfo`（fail-closed 位置）+ `UserSelfUpdateSupport#buildWhitelistWrapper`（白名单边界）+ 决策 5（@CacheEvict 补齐是否认可）；
- 测试有效性：`UserSelfUpdateSecurityTest` 五用例与 proposal §2.4 五条覆盖逐条对表；
- 基线口径：`scripts/test-baseline.txt` surefire.tests=1641 与 `work/_hus-verify.log` 汇总行互证。

## 8 · 闭环终态记录（2026-10-18）

- **合并提交**：`2bfe2f3`（merge --no-ff feature/harden-user-self-update 入 master，3 笔全入主干）；
- **基线提交**：`0a952a8`（test(baseline): update clean baseline to 2bfe2f3）；
- **远端 CI**：GitHub Actions Run `39102043542` 6/6 全绿（Frontend 197 / OpenAPI / fail-fast / static-analysis / thread-pool-metrics / build-and-gate 1641 单测 + 118 集成测试）；
- **测试基线**：Surefire 264 份报告 / 1641 用例 / 0 失败（+1 套件，+5 用例），Failsafe 31 份报告 / 118 用例 / 7 skipped，`source-revision=2bfe2f3`；
- **复核裁决**：A 档独立复核 🟢 GO（CRITICAL 0 / MAJOR 0 / MINOR 0，报告落盘 `work/archive/closed-cards/review-harden-user-self-update-a.md`），全链闭环合入主干。

