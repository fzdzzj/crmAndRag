# AGENTS.md — crmAndRag 执行约束

适用于本目录及其所有子目录。目标：多步任务自己推进到干完，只在真正需要用户拍板时停下。

## 规则 1 · 多步任务必须先 `update_plan`

- 任务需要 2 个以上动作（读 → 改 → 验证 算一条链）时，第一个动作就是调用 `update_plan`，列出步骤，每步 5-7 词。
- 每完成一步立刻调用 `update_plan`：该步标 `completed`，下一步标 `in_progress`。全程只能有一个 `in_progress`。
- 没实际执行完的步骤不准标 `completed`。
- 中途换路线，再调一次 `update_plan` 并写 `explanation` 说明原因。
- 例外：纯问答、或一条命令就能答完的事，不建计划。

## 规则 2 · 严禁用"我接下来去做 X"结束 turn

- 一个 turn 只有两种情况可以结束：任务真的做完了；确实被用户决策卡住（见下）。
- 禁止发出预告句后不调工具。以下句式后面必须紧跟同 turn 内的工具调用，否则就别写这句：
  `明白，继续推进。先看…` / `现在我去…` / `接下来我会…` / `让我先…` / `下一步是…`
- 如果你已经说出下一步要跑什么，就在本 turn 把它跑掉。不要把它留给用户回一句"继续"。
- 禁止用问句结尾征求"可以继续吗 / 要我做吗"。以下操作才需要事先确认：
  删除或覆盖数据、`git push` / `reset` / 改写历史、改数据库结构、新增依赖、外发网络请求、任何不可逆动作。
- 其余情况选可逆的方案直接做，并在最终汇报里写明你做了什么假设。

## 规则 3 · 结论必须来自本轮实际输出

- 说"X 为空 / 不存在 / 失败了"之前，本 turn 必须有对应的工具输出支撑。上一轮的推断不算本轮验证。
- 写完文件必须在同 turn 读回来或列目录确认它真的存在，否则不算写完。
- 写大文件（如 `application.yml`）用一次 `apply_patch` 写整份，不要分段拼接，避免分段写入未持久化。

## 收尾自检（每次想结束 turn 前逐条过）

1. 计划里还有 `in_progress` 或 `pending` 吗？有 → 继续干，别结束这个 turn。
2. 我这句话是不是预告？是 → 立刻补上工具调用。
3. 我是不是在没跑工具的情况下断言了结果？是 → 先跑再答。

## 项目路线（只指路，不复制内容）

### 构建与校验

- `mvn -B -ntp test` —— surefire，只跑 `**/*Test.java`（排除 `**/*IT.java`），不需 Docker，不访问外网。
- `mvn -B -ntp verify` —— 再追加 failsafe，跑 `**/*IT.java` 与 `**/*IntegrationTest.java`，其中 Testcontainers MySQL 系列**需本地 Docker**。
- 两者与 `.github/workflows/ci.yml` 的三段门禁同源（阶段1 surefire → 阶段2 failsafe → 阶段3 回归基线阈值），命令以那里为准。
- 坑：`-Dit.test=...` 会**覆盖** pom 里 failsafe 的 `<includes>`。写成 `-Dit.test=!XxxIT` 不是“排除一个”，而是让 failsafe 把全量单测再跑一遍。
- 本地无 Docker 时 `mvn verify` 的真实结果、哪些用例会跳、哪些会直接报错，读 `docs/migration-runbook.md` 第 6 节。**先读它再下“已验证”结论**。

### 环境与迁移入口

- `docs/migration-runbook.md`：`.env.example` → `.env`、`SPRING_PROFILES_ACTIVE=prod`、Flyway 号段归属（V1 基座 / V2x Lane A / V3x Lane B / V4x Lane C / V5x Lane D / V6x Lane E）。
- 库结构唯一真相源 = `src/main/resources/db/migration`；**禁改已合入脚本**（Flyway 校 checksum），改错出 `V(n+1)__fix_xxx.sql`。
- 回退：**不提供 DROP 回滚**，回退 = 恢复迁移前的数据库快照（runbook 第 4.1 步的 dump）。

### 权威上下文 owner

都在 `spec/changes/add-crm-rag-fusion-platform/` 下，改对应领域前先读：

- `proposal.md` —— 变更范围与目标
- `design-decisions.md` —— 架构决策及其取舍
- `contracts-frozen.md` —— 已冻结的接口/契约，改动需先解冻
- `db-table-coordination.md` —— 跳 lane 库表归属，谁建谁用

### 模块边界（`src/main/java/com/slz/crm`）

- `server` —— CRM 业务（controller/service/mapper）与 AI 助手（`server/ai`）
- `knowledge` —— 知识库/RAG：文档摄取、向量化、检索、存储
- `platform` —— 平台治理：配额、token、审计、对账、韧性、健康、安全、跟踪、生命周期、动态配置
- `common` —— 注解、枚举、工具、异常、过滤器、结果包装
- `pojo` —— entity / dto / vo / ao / excel
- `quality` —— RAG 质量评测（benchmark 与评分）

## 已闭合的授权缺口（close-permission-read-gap，已合入）

- **已闭合**：`GET /permission/list` 与 `GET /permission/getByRole` 已加 `@RequirePermission(PermissionOperates.SYSTEM_ASSIGN_PERMISSION)`（取值复用 606，读写同权，用户已拍板，不新增 608 常量），任何登录用户不再能枚举全量权限清单。
- **机制（保留说明）**：鉴权靠方法级注解 `com.slz.crm.common.annotation.RequirePermission`（`@Target(METHOD)`，打在类上不生效），由 `PermissionsInterceptor#preHandle` 执行（`WebMvcConfiguration#addInterceptors` 注册）；**注解缺失时拦截器直接放行**，校验不过才抛 `ErrorCode.PERMISSION_DENIED`（code 12002）。
- **当前状态**：`PermissionController` 三处 `@RequirePermission(PermissionOperates.SYSTEM_ASSIGN_PERMISSION)`（list / addORDeletePermissionsToRole / getByRole）。
- **测试**：`src/test/java/com/slz/crm/integration/controller/PermissionControllerIT.java` 两个 `@Disabled` 已移除并启用（2 反向 + 1 正向共 3 绿，本地 Docker 实测）。
- **不放开**：`getMyPermission`（自查）、`/auditor`（审批人下拉）仍为业务必需的开放接口，不在收紧范围。
- **覆盖门禁（audit-permission-matrix，已合入）**：`src/test/java/com/slz/crm/integration/permission/PermissionCoverageAuditIT` 永久门禁（纯 JVM 静态扫描，无 Docker，本地与 CI 均真跑）——每个端点强制三选一：方法级 `@RequirePermission` / `OpenEndpointRegistry` INTENTIONAL_OPEN 登记 / PENDING_DECISION 登记，写语义裸奔端点直接红；**新增 controller 必须同步登记 `PermissionCoverageScanner.CONTROLLER_REGISTRY`**，否则门禁红。首轮审计报告 `docs/permission-matrix-audit.md`（27×208 端点矩阵 + 57 零注解端点映射建议，**映射待用户拍板，落地另立提案**）。

  ```bash
  grep -n "@RequirePermission" src/main/java/com/slz/crm/server/controller/PermissionController.java
  ```
