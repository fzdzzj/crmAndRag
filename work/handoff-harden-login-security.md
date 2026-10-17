# Handoff · harden-login-security（登录链路安全加固包，B 档）

> 生成：2026-10-17 · 执行子 agent · 权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`
> 分支：`feature/harden-login-security`（自 master@aa86f57 检出）

## 1. 交付摘要

| 项 | 描述与状态 |
|---|---|
| H-8 白名单精准匹配 | 彻底移除 `JWTInterceptor` 中的 `contains("/login")`，严格对齐 `WebMvcConfiguration.publicPaths()`；补充伪造子串拦截单测 | ✅ |
| H-10 登录防爆破限流 | 新增 `LoginAttemptService`（Caffeine 5 次试错锁定 15 分钟），在 `UserServiceImpl#login` 接入账号与客户端 IP 双维度限流阻断 | ✅ |
| H-9 登出黑名单 | 新增 `TokenBlacklistService`（Caffeine TTL 1h），新增 `POST /user/logout` 端点并在 `OpenEndpointRegistry` 登记入放行清单 | ✅ |
| H-9 状态即时感知 | `JWTInterceptor` 前置阻断离职（`roleId=2` 或 `status=2`，12007）与冻结（`roleId=0` 或 `status=0`，12006）用户，fail-closed | ✅ |
| 静态门禁与复杂度 | 抽取 `UserLoginSupport` 与 `UserDeptSupport`，PMD 0 违规（NCSS < 150，CyclomaticComplexity 达标），SpotBugs 0 缺陷 | ✅ |
| 契约与前端 SDK | 执行 `check-openapi-consistency.sh --update` 同步 `POST /user/logout`，并执行 `pnpm gen:api` 重新生成前端 API SDK | ✅ |
| 回归基线对账 | `mvn -B -ntp clean verify` 净树测定后，单次执行 `scripts/check-test-baseline.sh --update` 写入最新基线（只增不减） | ✅ |
| 聚合门禁 | 本地执行 `scripts/merge-gate.sh` 8/8 全 PASS | ✅ |

## 2. 实测证据（基线/门禁）

- **全量净树构建验证（mvn clean verify）**：
  - Surefire：261 报告，1620 用例全部通过（0 失败，0 错误，0 跳过）；
  - Failsafe：31 报告，118 用例（0 失败，0 错误，7 跳过）；
  - 针对性真库 IT：`PermissionsInterceptorStatusIT`（3 用例）与 `DynamicConfigTierAclIT`（4 用例）全绿；
  - 纯静态覆盖审计：`PermissionCoverageAuditIT` 绿灯 PASS。
- **基线台账单次更新**：
  - 执行命令：`D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update`；
  - 基线文件：`scripts/test-baseline.txt`；
  - 数据变化：`surefire.reports` 257 → 261（净增 4 个测试套件报告），`surefire.tests` 1588 → 1620（净增 32 个测试用例），`failsafe` 维持 31/118/7。
- **聚合门禁核验（scripts/merge-gate.sh）**：
  - `[unit]` PASS（1620 用例全绿）；
  - `[spotbugs]` PASS（BugInstance=0, Error=0）；
  - `[pmd]` PASS（violations=0, files=0）；
  - `[baseline]` PASS（reports>=261, tests>=1620, skipped<=0）；
  - `[frontend-unit]` PASS（21 文件 / 197 用例通过）；
  - `[hook]` PASS（pre-commit 转发器在位）；
  - `[bijection]` PASS（12 条豁免与 12 条 High 一一对应）；
  - `[pmd-baseline]` PASS（实测 0 == 登记 0 == pom 阈值 0）。
  - 最终结论：`merge-gate 通过：所有已执行的子门禁绿`。

## 3. 决策留痕

1. **单副本无状态组件契约**：严格复用项目既有的 `com.github.ben-manes.caffeine` 依赖构建本地缓存，零引入 Redis，零增加外部中间件依赖。
2. **状态感知兼容与 fail-closed 策略**：
   - 在 `JWTInterceptor` 中校验用户状态时，针对历史库表中离职用户可能表现为 `roleId=2` 或 `status=2`、冻结用户表现为 `roleId=0` 或 `status=0` 的多形态哨兵值，统一优先阻断离职（`ErrorCode.USER_IS_QUIT`，12007），随后阻断冻结（`ErrorCode.USER_IS_FROZEN`，12006），其他非正常在职状态统一阻断为 `USER_STATUS_EXCEPTION`（12003），确保与 `PermissionsInterceptor` 行为严格等价并防任何绕过。
3. **PMD 严格门禁消解**：
   - 提取 `UserLoginSupport` 承接密码比对与防爆破调用，将 `UserServiceImpl` 的 NCSS 行数控制在 150 行阈值内；
   - 采用单一返回（`final var result; return result;`）遵循仓内 `OnlyOneReturn` 门禁规范。
4. **前端文件卫生**：
   - `vitest` 与 `pnpm gen:api` 执行后改写了 `frontend/typed-router.d.ts` 的行尾，已立即执行 `git checkout -- frontend/typed-router.d.ts` 彻底还原，确保工作树未受任何污染。

## 4. 树净与提交披露

- **零 DB 迁移**：未新增任何 `Vxx.sql`，下一可用号仍为 V43。
- **无破坏改动**：前端仅更新 `frontend/openapi.yaml` 及配套 SDK 生成物，未碰触 `typed-router.d.ts`。
- **未 push / 未 merge**：所有操作均在分支 `feature/harden-login-security` 内完成，严格遵守纪律，未执行 `git push`，未合入 master，完工停步等待 Owner 复核。
