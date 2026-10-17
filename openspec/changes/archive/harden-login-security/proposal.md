# 提案 · 登录链路安全加固包 (harden-login-security)

<!-- id: harden-login-security -->
<!-- status: proposal -->
<!-- reviewer: Owner 派发 / B 档独立复核 -->

## 1. 背景与动机

根据候选池 `work/_paa-candidates-pool-v2.md` 第 48 项（H-8、H-9、H-10 登录链路安全缺陷家族）：
1. **H-8 子串放行绕过**：`JWTInterceptor.java:37` 存在 `request.getRequestURI().contains("/login")` 校验，任何 URI 只要包含 `/login` 子串即可绕过 JWT 鉴权放行；
2. **H-10 登录无试错限流**：`UserServiceImpl.java:48-58` 密码验证错误时无任何频次限制，系统面临字典暴力破解风险；
3. **H-9 JWT 登出无失效与状态感知滞后**：用户退出登录后已签发 Token 持续有效至 1h TTL 结束；且 JWT 拦截器中查得用户后未对用户状态（冻结/离职）进行前置阻断拦截。

## 2. 方案设计与架构边界

1. **白名单精确化 (H-8)**：
   - 彻底移除 `request.getRequestURI().contains("/login")`；
   - 与 `WebMvcConfiguration.publicPaths` 对齐，仅放行精确匹配路径（如 `POST /login`）及静态/公开资源（如 `/public/**`）；
   - OPTIONS 请求维持快速放行。
2. **基于 Caffeine 的登录试错防爆破限流 (H-10)**：
   - 契合单副本部署契约，复用既有 `com.github.ben-manes.caffeine` 依赖；
   - 封装 `LoginAttemptService`，支持账号（Email）与客户端 IP 双维度计数；
   - 默认策略：连续失败 5 次锁定 15 分钟（`expireAfterWrite(15, TimeUnit.MINUTES)`）；
   - 登录成功即刻清除失败计数；锁定期间直接抛出包含明确剩余冷却时间的业务异常；
3. **登出黑名单与状态感知 (H-9)**：
   - 新增 `POST /user/logout` 端点，将当前请求 Token 注入 Caffeine 登出黑名单（TTL 1 小时，与 JWT 过期时间一致）；
   - `JWTInterceptor` 解析 Token 后先查黑名单，命中则抛出 `TOKEN_INVALID` (12003)；
   - `JWTInterceptor` 在查出 `UserEntity` 后阻断冻结（status=0，12006）和离职（status=2，12007）用户，fail-closed。

## 3. 红线与非目标

- **红线 1**：零数据库迁移（DDL/DML），下一可用号仍为 V43；
- **红线 2**：零新增外部依赖，严禁强引入 Redis 或外部中间件，严格复用已有 Caffeine 依赖；
- **红线 3**：零破坏性前端改动，既有 `/login` 契约不变，仅在超限或冻结时返回标准 ErrorCode。
