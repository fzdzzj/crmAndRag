# 任务卡 · harden-login-security 登录链路安全加固包

## 1. 目标与范围 (Scope)
- 彻底修复 `JWTInterceptor` 路径放行漏洞（移除 `contains("/login")`，精确放行 `/login` 与公开路径，H-8）；
- 引入基于 Caffeine 本地缓存的 `LoginAttemptService`，实现 Email 账号与 IP 双维度登录试错防爆破限流（5 次失败锁定 15 分钟，H-10）；
- 支持 `POST /user/logout` 登出 Token 加入 Caffeine 黑名单主动失效，并在 `JWTInterceptor` 前置拦截冻结与离职用户（H-9）。

## 2. 约束与红线 (Constraints)
- 零数据库迁移（DDL/DML），下一可用迁移号仍为 V43；
- 零新增外部依赖，严禁强引入 Redis，纯基于仓内已有 Caffeine 与内存组件；
- 严禁触碰 `frontend/typed-router.d.ts`；
- 所有改动需配齐正反向单元测试（白名单伪造阻断、限流锁定与自动解除、黑名单拦截、冻结用户拦截）。

## 3. 验收标准 (Acceptance)
- 目标单测真跑全绿；
- `mvn -B -ntp clean verify` 全量通过；
- 单次执行 `bash scripts/check-test-baseline.sh --update` 更新基线（在 257/1588 基础上只增不减）；
- `bash scripts/merge-gate.sh` 8/8 PASS（退出码 0）；
- 输出 `work/handoff-harden-login-security.md` 并停步，等待复核。
