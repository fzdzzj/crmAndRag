# 提案：全量端点权限矩阵审计（永久门禁 + 拍板输入，零行为变更）

> 变更 ID：`audit-permission-matrix` ｜ 能力域：`crm-permission` ｜ 序列：close-permission-read-gap 的自然延伸（无前置依赖，独立可执行）
> 来源：`close-permission-read-gap` 只闭合了权限目录读取一个点；"哪些端点该挂注解而没挂"从未全量审计过。本提案把单点闭合升级为全量门禁。

## Why

1. **授权机制的结构性风险至今未审计**：鉴权靠方法级 `@RequirePermission`，由 `PermissionsInterceptor#preHandle` 执行；**注解缺失时拦截器直接放行**（`PermissionsInterceptor` L56-59）。全仓 26 个 controller 共 227 个端点中，**5 个 controller 共 40 个方法级端点登录后零权限校验**（摸底实测，2026-09-13）：
   | Controller | 零注解端点数 | 职责 |
   |---|---|---|
   | AssistController | 22 | AI 助手全操作（申请/消息/附件增删） |
   | AiChatController | 8 | AI 会话/流式对话/图片上传 |
   | AiActionController | 4 | AI 待执行动作确认（创建客户/开票等敏感确认） |
   | DataStatisticsController | 4 | 数据统计（chartData/summary 等） |
   | ReportController | 2 | 合同/商业报表 |
   
   其中 `PermissionOperates` 报表权限（501/502/503）**存在于枚举却从未被任何端点引用**——权限常量与端点覆盖的漂移从未被系统性暴露。
2. **掩盖机制**：注解缺失 = 放行，没有编译错误、没有测试失败、没有任何信号。`close-permission-read-gap` 靠人工发现了一处；无门禁意味着下一个新 controller 零注解合入时 CI 依旧全绿。
3. **额外实证缺陷（摸底发现，需记录待拍板）**：`PermissionsInterceptor` 的用户状态检查（冻结 roleId=0 / 离职 roleId=2，L61-70）**只在注解存在时执行**——零注解端点连冻结/离职用户都能访问。
4. **本提案与 audit-entity-table-drift 完全同构**：审计设施 + 分级 + 永久门禁 + 登记制防再犯——该模式已在 schema 域验证过一次，本提案把它复制到权限域。

## What Changes

### 1. 权限覆盖扫描设施（测试域，纯函数核心可单测，无 Docker / 无 Spring 上下文依赖）
- `PermissionCoverageScanner`（`src/test/java/com/slz/crm/integration/permission/`）：
  - ClassPath 扫描 `@RestController` / `@Controller`（base package `com.slz.crm`，Spring `ClassPathScanningCandidateComponentProvider`，读注解元数据不解析源码）；
  - 拼接类级 + 方法级 mapping（`@RequestMapping`/`@GetMapping`/`@PostMapping`/`@PutMapping`/`@DeleteMapping`）→ 端点清单（HTTP 方法 + 路径 + 所属类#方法）；
  - 提取方法级 `@RequirePermission` 值 → 端点 × 权限矩阵。
- `PermissionCoverageComparator`（纯函数）分级：
  - **SECURED** = 已挂注解（合规，INFO 级记录）；
  - **CRITICAL** = 写语义端点（POST/PUT/DELETE）零注解且未登记；
  - **WARN** = 读语义端点（GET）零注解且未登记；
  - **INTENTIONAL_OPEN** = 登记于开放清单（有意匿名或有意登录即可用）。
- `OpenEndpointRegistry`（测试域常量）：**显式登记制**——有意开放的端点逐条登记（端点 + 理由），两类：
  - JWT 层已排除的匿名路径（`/login`、`/health`、`/public/**`，`WebMvcConfiguration#publicPaths`）；
  - 业务必需的登录即可用端点（`getMyPermission`、`/auditor`——沿用 close-permission-read-gap 的拍板结论）。
- controller 数登记：26 个 controller 类名登记为常量；扫描数 ≠ 登记数即失败（新 controller 必须同步登记，防漏审——同 schema 审计的 53 实体登记制）。

### 2. 永久门禁 IT（防再犯，本提案的核心价值）
- `PermissionCoverageAuditIT`（failsafe，命名 `*IT.java`，**无 Docker 依赖**——纯 JVM 静态扫描，本地与 CI 均真跑不跳过）：
  - CRITICAL 非空 → fail，逐项输出类名、方法、HTTP 方法、路径；
  - WARN / 零注解端点必须出现在 `OpenEndpointRegistry` 的 **PENDING_DECISION** 区（首轮审计把 40 个现状端点登记进去，理由="待权限映射拍板"）；
  - 今后新端点：挂注解（SECURED）或显式登记（开放/待拍板），否则 CI 直接红——"注解缺失=静默放行"的结构性掩盖从此有信号。
- 门禁只管**覆盖**，不管注解值选得对不对（值正确性靠既有 `*ControllerIT` 反向用例保障）。

### 3. 首轮审计报告（拍板输入，本提案的交付物）
- 全量差异落盘 `docs/permission-matrix-audit.md`：
  - 26 controller × 227 端点全矩阵（SECURED 172 / INTENTIONAL_OPEN 若干 / 零注解 40，实测数字以首跑为准）；
  - 40 个零注解端点**逐个给出建议权限映射**：DataStatistics/Report → 复用既有 501/502/503（需查证 `init_data.sql` 种植面，报告里列明各角色现状）；Assist/AiChat/AiAction → 两个方案二选一（新增 AI 模块 800 段常量 vs 产品决策"登录即可用"并登记 INTENTIONAL_OPEN）；
  - 记录冻结/离职用户绕过缺陷（interceptor 状态检查位置问题）——修复属行为变更，只记录进"待拍板清单"；
  - OPTIONS 放行（CORS 预检，合理）确认不需处理。
- **报告落盘后停下汇报用户**：40 端点的注解落地属行为变更（角色访问面变化），**不在本提案预授权范围内**——用户对映射表拍板后另立提案执行（同"评估先行"原则，参照 schema 审计把 WARN/INFO 留待授权的先例）。

### 4. 收尾
- `mvn -B -ntp test` 全绿（surefire 602 → 602+N，实测计数）；failsafe 因本 IT 无 Docker 依赖，**无 Docker 下限基线 12 → 13**（`ci.yml` 口径B 同步推算）；`HANDOFF.md` 更新。

## Impact

- **新增**：测试域扫描器 + 比对器 + 登记清单 + `PermissionCoverageAuditIT`；`docs/permission-matrix-audit.md`。
- **修改**：`ci.yml`（failsafe 基线数字）、`HANDOFF.md`。
- **不改**：任何运行期业务行为、任何 `@RequirePermission` 注解、`PermissionsInterceptor`、`WebMvcConfiguration` 路由与排除配置、`init_data.sql`。全程零行为变更、零外发（¥0）。

## 风险

- 静态扫描拼路径与 Spring 运行期路由可能有细微差异（consumes/produces/多路径）——审计只关心"有没有注解"，路径仅用于报告展示与登记匹配；发现拼接歧义记录进报告再定夺。
- 40 端点登记为 PENDING_DECISION 后门禁对其放行——这是设计意图（显式知情制），不是漏洞；新端点无登记才会红。
- 首轮报告的"建议映射"若被误当"已拍板"执行 → 报告内显著标注"未经用户拍板，禁止直接落地"。

## Non-Goals

- **不挂任何新注解、不新增权限常量、不改 interceptor**（40 端点的鉴权落地属下一提案，等映射表拍板）。
- 不处理冻结/离职用户绕过（记录待拍板）。
- 不审计数据权限（DataScope AOP 面）——本提案只管功能权限（方法级注解）覆盖。
- 不做生产权限表（`role_permission` 实数据）比对（本地无生产数据）。
