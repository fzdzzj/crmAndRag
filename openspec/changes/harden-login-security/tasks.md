# 任务清单 · 登录链路安全加固包 (harden-login-security)

<!-- id: harden-login-security -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 白名单精准匹配与拦截器收敛 (H-8)

- [x] 1.1 修复 `JWTInterceptor` 路径放行逻辑：移除 `contains("/login")`，改为精确匹配 `/login`（与 `WebMvcConfiguration.publicPaths()` 对齐）；补充伪造子串（如 `/customer/login-bypass`）阻断反向测试。
      实测依据：JWTInterceptor 彻底移除 contains("/login") 改为 equals/startsWith 精确判定；JWTInterceptorTest.pseudoLoginPathsMustBeBlockedWhenTokenAbsent 参数化用例（6 个伪造路径）全部绿灯 PASS。

## 任务组 2 · 基于 Caffeine 的登录防爆破限流 (H-10)

- [x] 2.1 构建 `LoginAttemptService`：使用 Caffeine 本地缓存，维护 Email 账号与客户端 IP 双维度失败计数器（5 次失败锁定 15 分钟）。
      实测依据：创建 LoginAttemptService，支持账号与客户端 IP 独立及双重防爆破与快进时钟测试；LoginAttemptServiceTest 5 个单元测试全部绿灯通过。
- [x] 2.2 接入 `UserServiceImpl.login`：密码校验失败记录递增，达到上限抛出包含锁定提示的业务异常；登录成功清除失败计数；补齐单测。
      实测依据：UserServiceImpl#login 接入防爆破检查与试错提示，抽取 UserLoginSupport；UserServiceTest 10 个测试用例全部通过，覆盖试错计数、锁定拦截与登录成功清理。

## 任务组 3 · 登出失效与状态即时感知 (H-9)

- [x] 3.1 增加 Token 黑名单机制：`JWTInterceptor` 校验 Token 是否在 Caffeine 登出黑名单中；增加 `POST /user/logout` 端点完成登出入黑名单。
      实测依据：创建 TokenBlacklistService（TTL 1h），新增 POST /user/logout 开放端点并在 OpenEndpointRegistry 登记；UserControllerLogoutTest 与 TokenBlacklistServiceTest 均通过。
- [x] 3.2 `JWTInterceptor` 增加用户状态前置阻断：查询 `UserEntity` 后阻断冻结（status=0）与离职（status=2）用户，抛出标准错误码。
      实测依据：JWTInterceptor 提取 checkUserStatus 并支持 status 与 roleId 哨兵值校验，fail-closed 阻断；PermissionsInterceptorStatusIT 与 DynamicConfigTierAclIT 真库测试全部绿灯 PASS。

## 任务组 4 · 门禁与基线对账

- [x] 4.1 全量测试通过，`mvn -B -ntp clean verify` 全绿后以 `bash scripts/check-test-baseline.sh --update` 单次写入最新基线（确保 reports>=257 tests>=1588）。
      实测依据：mvn -B -ntp clean verify 全量通过（Failsafe 118 用例 0 失败）；单次执行 scripts/check-test-baseline.sh --update 写入最新基线（Surefire 261 报告 / 1620 用例，较原 257/1588 净增 4 报告 32 用例）。
- [x] 4.2 本地运行 `bash scripts/merge-gate.sh` 确保 8/8 全 PASS，产出 `work/handoff-harden-login-security.md`。
      实测依据：本地执行 scripts/merge-gate.sh 取得 8/8 全 PASS（unit, spotbugs, pmd, baseline, frontend-unit, hook, bijection, pmd-baseline），产出 work/handoff-harden-login-security.md。
