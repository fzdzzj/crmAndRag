# Handoff · unify-vector-ops-endpoints（向量运维端点统一收口，B 档，三卡序列 1/3）

> 生成：2026-10-16 · 执行子 agent · 权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`
> 分支：`feature/unify-vector-ops-endpoints`（自 master@1c314b7 检出）

## 1. 交付摘要

| 项 | 状态 |
|---|---|
| 统一入口 `POST /knowledge/admin/sync/{entity}`（五实体枚举校验，非法 400） | ✅ |
| 统一入口 `POST /knowledge/admin/sync/all`（五实体逐个委托，返回实体名→计数映射） | ✅ |
| 权限复用：两端点均 `@RequirePermission(KNOWLEDGE_ADMIN_MANAGE=900)`，零新增常量 | ✅ |
| `PermissionCoverageScanner.CONTROLLER_REGISTRY` | 已含 KnowledgeAdminController（L333-334），无需登记 |
| openapi 契约同步（--update）+ `pnpm gen:api` | ✅ |
| 前端运维卡片（五实体入口 + 最近同步结果，现成返回结构，无新表/新端点） | ✅ |
| Playwright 检查点 | ✅ `frontend/docs/test-checkpoints.md` 追加 4 条 |
| 单测（枚举/委托/权限锚，全 mock 零真实重嵌入） | ✅ 8 用例 |
| merge-gate | ✅ 8/8；Docker 在线加跑 `--with-verify` 9/9 |

## 2. 实测证据（基线/门禁）

- 基线：`mvn -B -ntp test` 实测 **surefire 1480**（基线 1472 → **只增 +8**，即本卡 8 个新用例）；`bash scripts/check-test-baseline.sh --update` 真实写入 `scripts/test-baseline.txt`（surefire.reports=226 / tests=1480 / skipped=0；failsafe 31/118/7 不变）。
- merge-gate 默认 8/8 全绿：`[unit] [spotbugs] [pmd] [baseline] [frontend-unit] [hook] [bijection] [pmd-baseline]`。
- Docker 在线（Server 29.6.2）加跑 `--with-verify` **9/9 全绿**：追加 `[it]` failsafe 118（Skipped=7，与基线一致）。
- 前端三检：`pnpm lint:check` ✅ · `pnpm type-check:check` ✅ · `pnpm test` ✅（21 文件 / **197 用例**，基线 188 → +9：卡片 5 + 钩子 4）。
- raw 日志：`work/review-merge-gate-8of8.log` / `work/review-merge-gate-9of9.log` / `work/review-openapi-update.log` / `work/review-pmd-check.log` / `work/review-spotless-apply.log`（均 gitignored）。

## 3. 决策留痕

1. **旧散落端点处置（任务 1.2）**：按 diff 最小原则**全部保留、零改动**——四业务 Controller 的 `/reingestAll`
   （BusinessActivity/ContactTask/Contract/SalesOpportunity）与本 controller 既有 `/knowledge/customer/reingest`
   均不做 `@Deprecated`、不改 openapi 描述。理由：① 后端向后兼容零风险，前端既有调用面不受影响；② 四个业务
   Controller 的权限语义（各自业务写权限）与 admin 面（900）不同，删除/标注会影响他人卡面；③ 统一入口
   `/sync/*` 在 openapi 中已明确为推荐运维路径，前端运维卡片只消费新入口。若 Owner 后续要求标注弃用，另起
   最小 diff 卡执行。
2. **entity 枚举命名**：wireName 用 `customer/contract/contactTask/businessActivity/salesOpportunity`
   （与各实体服务 ENTITY_TYPE 及前端卡片键一一对应）；`/sync/all` 与 `/sync/{entity}` 共存由 Spring 路径
   精确匹配保证（字面量路由优先）。
3. **返回结构**：新增统一 `SyncResult(total,success,failure)` record（与各实体 `ReingestResult` 同构），
   `/sync/all` 返回 `Map<String, SyncResult>`（键序=枚举声明序，LinkedHashMap）；不为监控新建任何表/端点，
   纯委托不改各实体回埋服务实现。
4. **PMD 修正**：初版 `SyncEntity.from` 与 `syncEntity` 双 return 触发 `OnlyOneReturn`（实测 2 条超基线），
   已按单出口重构后复测 0 违规（pmd-baseline 台账零上调，`--update` 拒绝上调的语义未触碰）。

## 4. HEAD / 树净 / 提交披露（禁止虚报）

- **HEAD**：`39620af8f4b131e6b805a9c2df19c3e381062752`（`git rev-parse HEAD` 实测，完工时点）。
- **git log master..HEAD**：线性（本卡全部提交自 master 单线检出，无 merge），5 个提交：
- **树净**：提交后 `git status --porcelain` 仅剩**他人卡面遗留未跟踪文件**（`openspec/changes/add-queryuser-dept-isolation/`、
  `openspec/changes/fix-review-mechanical-check-true/`、`work/task-card-add-queryuser-dept-isolation.md`、
  `work/task-card-fix-review-mechanical-check-true.md`、`work/task-card-wire-customer-insight-panel.md`，
  共 5 项，与本卡无关、开工前已在案）；本卡范围文件全部入库（含 openspec 三件套、handoff、task-card）。
- **typed-router.d.ts**：vitest/gen:api 再生后已 `git checkout --` 还原，提交零 diff（`git status --porcelain -- frontend/typed-router.d.ts` 为空实测）。
- **未 merge / 未 push**：✅ 完工停步等 Owner，未执行任何 merge/push。
- **成本红线**：本卡未发起任何真实 `/sync/*` 调用（单测全 mock 向量服务；前端卡片仅渲染入口与结果占位，
  点击后走确认弹窗，真实执行须 Owner 显式授权）。

## 5. 待 Owner 决策

- 旧散落端点是否后续加 `@Deprecated` + openapi 弃用描述（本卡按 diff 最小保留，见 §3.1）。
- 本卡首次真实 `/sync/*` 执行授权（DashScope 成本敏感，未在本卡内执行）。

## 6. 提交清单（线性，自 master 检出）

1. `feat(server)`：KnowledgeAdminController 统一回埋入口 + 单测（控制器、2 个单测文件、test-baseline 可并入基线提交）
2. `chore(contract)`：openapi --update 契约同步（frontend/openapi.yaml）
3. `feat(frontend)`：运维卡片组件 + 页面接入 + 钩子 + 用例 + Playwright 检查点
4. `test(baseline)`：回归基线 1472→1480（--update 真实写入）
5. `docs(ops)`：勾选 tasks.md + 落 handoff + 任务卡入库

（最终提交顺序与哈希以 `git log master..HEAD` 实测为准，见下方回填。）
