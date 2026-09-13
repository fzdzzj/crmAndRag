# Tasks — audit-permission-matrix

> 执行契约见 `openspec/git-workflow.md`（分支/提交粒度/合并/基线 bump）；本提案当前基线：master surefire 602、failsafe 无 Docker 下限 12。
> 全程 ¥0 外发、零运行期行为变更、无 Docker 依赖。

## 1. 审计设施：端点扫描器

- [x] 1.1 `PermissionCoverageScanner`（`src/test/java/com/slz/crm/integration/permission/`）：`ClassPathScanningCandidateComponentProvider` 扫 `@RestController`/`@Controller`（base package `com.slz.crm`），读注解元数据（不解析源码）；controller 清单登记为常量（摸底 26 个，扫描数 ≠ 登记数即报错——新 controller 必须同步登记，防漏审）
- [x] 1.2 拼接类级 + 方法级 mapping（`@RequestMapping` 类级前缀 + 方法级 `@GetMapping`/`@PostMapping`/`@PutMapping`/`@DeleteMapping`）→ 端点记录（HTTP 方法、路径、类#方法、是否有 `@RequirePermission` 及其枚举值）
- [x] 1.3 扫描器单测（surefire，≥4 条）：对摸底已知事实断言——`PermissionController` 恰 3 处注解（list / addORDeletePermissionsToRole / getByRole）；`AssistController` 22 个方法级端点全零注解；`UserController` 类下既有注解端点被正确提取枚举值；`HealthController`（JWT 排除路径内）被扫出且零注解
- [x] 1.4 类级 `@RequirePermission` 不生效的口径确认（`@Target(METHOD)`）：扫描器只认方法级注解，类级注解若出现记录为 WARN（当前无此情况，防今后误用）

## 2. 审计设施：比对器 + 开放端点登记清单

- [x] 2.1 `PermissionCoverageComparator`（纯函数）：输入端点清单 + 登记清单，输出分级——SECURED（已挂注解）/ CRITICAL（写语义 POST/PUT/DELETE 零注解未登记）/ WARN（读语义 GET 零注解未登记）/ INTENTIONAL_OPEN（登记为有意开放）
- [x] 2.2 比对器单测（surefire，≥4 条）：构造夹具分别命中四档分类 + 全 SECURED 夹具产出零 finding + 登记后 CRITICAL 转 INTENTIONAL_OPEN
- [x] 2.3 `OpenEndpointRegistry`（测试域常量）：两区登记——INTENTIONAL_OPEN（`getMyPermission`、`/auditor`、JWT 层排除的 `/login`、`/health`、`/public/**`，每条附理由）；PENDING_DECISION（首轮把摸底 40 个零注解端点全部登记，理由="待权限映射拍板"，来源 close-permission-read-gap 之后的审计发现）

## 3. 永久门禁 IT + 首轮审计报告

- [x] 3.1 `PermissionCoverageAuditIT`（failsafe，`*IT.java`，无 Docker 依赖本地与 CI 均真跑）：CRITICAL 非空 → fail 并逐项输出类名、方法、HTTP 方法、路径；controller 扫描数 ≠ 登记数 → fail；WARN / PENDING_DECISION 不失败（显式知情制）
- [x] 3.2 首轮真跑，全矩阵落盘 `docs/permission-matrix-audit.md`：26 controller × 227 端点四档分布（实测数字为准）；40 零注解端点逐一列 HTTP 方法/路径/操作语义/建议权限映射（DataStatistics/Report → 复用 501/502/503 并查证 `init_data.sql` 各角色种植面；Assist/AiChat/AiAction → 方案A 新增 800 段 AI 权限常量 vs 方案B 产品决策登录即可用并转 INTENTIONAL_OPEN）
- [x] 3.3 记录两项待拍板缺陷进报告：① 冻结/离职用户绕过（`PermissionsInterceptor` L61-70 状态检查仅在有注解时执行，零注解端点冻结用户可用）；② 报表权限 501/502/503 存在于枚举却零端点引用（权限常量↔端点漂移）
- [x] 3.4 报告显著标注："建议映射未经用户拍板，禁止直接落地"；正确性自然校验：`PermissionController` 3 注解端点必须落在 SECURED 档

## 4. 回归与收尾

- [x] 4.1 `mvn -B -ntp test` 全绿（surefire 602 → 602+N 实测计数）；`ci.yml` 基线同步（口径A surefire 数字、`check_baseline`、错误提示行；口径B failsafe 无 Docker 下限 12 → 13 及 CI 推算数字，本 IT 无 Docker 依赖必真跑）
- [x] 4.2 `docs/migration-runbook.md` §6 复核：确认本 IT 无 Docker 依赖下本地 `mvn verify` 行为与预期一致（新增 1 个必跑 IT）
- [x] 4.3 `HANDOFF.md` 更新（权限矩阵门禁上线、40 端点拍板输入就绪）；AGENTS.md 权限机制章节补一行门禁指针
- [x] 4.4 git 收尾：`feature/audit-permission-matrix`，提交按任务组（`type(scope): 中文描述`），亲验 `mvn -B -ntp test` + `git status` 干净后 `--no-ff` 合入 master，汇报带 commit hash + 40 端点映射表摘要，**停下等用户对映射表拍板**（落地属下一提案）
