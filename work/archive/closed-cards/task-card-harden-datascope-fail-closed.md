# 目标约束型任务卡 · harden-datascope-fail-closed

> 适用角色：执行子 Agent | 评审档位：**A 档** | 基点：`c40b56a`

## 1. 目标
加固 `QueryWrapperAspect.java`，彻底根治数据权限注入异常时的 Fail-Open 越权漏洞，转向 Fail-Closed 阻断：
1. 拦截带 `QueryWrapper` / `LambdaQueryWrapper` 查询托管表时，若 `dataScopeService` 发生任何异常，严禁静默吞咽，重抛为 `BaseException(ErrorCode.PERMISSION_DENIED, "数据权限过滤失败，拒绝查询")`（12002）阻断请求；
2. 环绕拦截自动转发带 Wrapper 方法时，若反射调用异常，严禁回退执行 `joinPoint.proceed()` 原无条件查询，必须重抛异常阻断；
3. 保留 `currentUser == null` 系统调度/异步任务白名单，补充明确审计日志；
4. 实现 `QueryWrapperAspectTest.java`（6 用例），实现反向阻断与安全放行全覆盖。

## 2. 约束与红线
- 零 DB 迁移（`src/main/resources/db/migration/` 零修改）；
- 零新增 Maven 依赖（`pom.xml` 零修改）；
- 零新增错误码与权限常量（复用既有 12002 `PERMISSION_DENIED`）；
- 严禁触碰 `frontend/typed-router.d.ts`；
- 基线 Surefire 从 1620 起算只增不减（预计 1626+），Failsafe 保持 118/7；
- 门禁通过 `mvn -B -ntp clean verify` 与 `bash scripts/merge-gate.sh`（8/8 PASS）；
- 严禁执行 `git push` 或 `git merge`。

## 3. 验收标准
- 详细规格见 `openspec/changes/harden-datascope-fail-closed/proposal.md`；
- 任务清单见 `openspec/changes/harden-datascope-fail-closed/tasks.md`，完成后附 6 空格依据；
- 产出 `work/handoff-harden-datascope-fail-closed.md` 汇报自测证据。
