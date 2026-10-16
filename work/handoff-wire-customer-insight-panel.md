# 交接快照 · wire-customer-insight-panel（CustomerInsightPanel 双宿主接线，B 档轻）

> 生成时间：2026-10-16（本机）
> 工作树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（权威树，master 所在）
> 真实状态口径：§1–§5 为交付当时实测（HEAD=`e4d156f`，未 merge）；§6 之后按合并后事实更新（已 `merge --no-ff` + 已 push，见 §1）。

## 1. Git 事实

| 项 | 实测值 | 实测命令 |
| --- | --- | --- |
| 分支 | `feature/wire-customer-insight-panel` | `git rev-parse --abbrev-ref HEAD` |
| 基点（master） | `70027c6`（= `origin/master`；与任务卡预期一致，非 70027c8） | `git log --oneline -1 master` |
| 本卡 HEAD | `e4d156f2e1b0ec56e9cd61b92e8c9517e3aefa72` | `git rev-parse HEAD` |
| 分支提交（线性） | 2 笔，`git log master..HEAD` 无分叉 | `git log --format=%H%x20%s master..HEAD` |
| 已 merge | `git merge --no-ff` 生成 merge 笔 `0358bcd`（`0358bcdec0c2f3aa487bcb3a08456979bc90411f`），master 由 `70027c6` 前进至 `0358bcd`；feature 分支保留未删 | `git log --oneline -1 master` |
| 已 push | `0358bcd` 已推送 `origin/master`（`70027c6..0358bcd`，仅 merge 一笔） | `git rev-parse master origin/master` |

两笔提交：

- `43333b2` `feat(ai): 接线 CustomerInsightPanel 双宿主（客户详情弹窗 + AI 助手诊断联动）`
  —— 生产代码 2 文件 `+84/-3`，附本卡 `openspec/changes/wire-customer-insight-panel/{proposal.md,tasks.md}`（+41）。
- `e4d156f` `test(ai): 补 CustomerInsightPanel 双宿主接线渲染用例`
  —— 2 个测试文件 `+249`。

`git diff --stat 70027c6..HEAD`：6 files changed, 374 insertions(+), 3 deletions(-)。

**合并落地（本轮，接续上文）**：Owner 授权闭环后 `git checkout master` + `git merge --no-ff feature/wire-customer-insight-panel`，产出 merge 笔 `0358bcd`（subject 同卡名），合并内容与上方 `6 files changed, 374 insertions(+), 3 deletions(-)` 逐字一致（无冲突自动合并）；随即 `git push origin master` 推送范围 `70027c6..0358bcd`（仅 merge 一笔）。feature 分支保留未删，未 `reset`、未对已推送提交做任何改写。

**一处曾发生的本地历史重写（主动披露）**：提交 2 首版为 `e8141b7`，其新增用例中出现 1 条本卡引入的 ESLint warning（`vue/component-definition-name-casing`，因在测试内 `app.component('a-select-option', ...)` 注册小写名）。该 warning 已修掉（改注册 PascalCase 名 `ASelectOption`，同时消除 Vue 组件解析告警），随后对**未推送的本分支顶端提交**执行了 `git commit --amend`，得 `e4d156f`。全程 2 笔提交、线性，且改写发生在任何 push 之前（当时 `master` 与远端均未触碰）；后续合入只经 `--no-ff` merge，未对已推送提交做任何 `amend`/`reset`。

## 2. 改动内容（纯前端，零后端/迁移/权限/openapi）

| 文件 | 性质 | 说明 |
| --- | --- | --- |
| `frontend/src/components/client/CompanyEditModal.vue` | 宿主 1 接线 | 只读「查看客户」视图内 `<a-divider>客户诊断</a-divider>` + `<CustomerInsightPanel :company-id="currentCompanyId" :key="insightKey">`；`created` → `message.success` + `insightKey` 自增（重挂 Panel 刷新诊断）；沿用弹窗既有 `a-divider`/`a-*` 布局风格 |
| `frontend/src/components/ai/AiAssistantDrawer.vue` | 宿主 2 接线 | 抽屉内新增诊断面板区（`v-if="insightCompanyId != null"`，带「收起」）；`routeCompanyId`（路由 query `filters.id`）优先、对话内最近 `CUSTOMER` 业务来源兜底合成 `contextCompanyId`；抽屉可见且上下文明确即展示，点「📊 诊断客户健康度」药丸额外联动；`created` → `antdMessage.success` + `sessionMessages.refetch()` |
| `frontend/src/components/ai/__tests__/AiAssistantDrawerInsight.test.ts` | 新增测试 | 2 例：上下文明确时展示 Panel 且透传 companyId；`created` 给反馈并刷新会话 |
| `frontend/src/components/client/__tests__/CompanyEditModalInsight.test.ts` | 新增测试 | 2 例：只读查看时展示 Panel 且透传 companyId；`created` 给反馈并重挂刷新 |
| `openspec/changes/wire-customer-insight-panel/proposal.md`、`tasks.md` | 立项物料入库 | `tasks.md` 已勾选（3/3）并附 6 空格实测依据行 |

**`frontend/src/components/ai/CustomerInsightPanel.vue` 本体零改动**：接线所需的 prop（`companyId`）与 emit（`created`）契约原样可用，无需默认值/契约微调，故无该文件的留痕事项（非遗漏，属有意为之）。

## 3. 锚点定位（实测）

- 宿主 1 = `CompanyEditModal.vue`：`frontend/src/pages/(dashboard)/client/list.page.vue` 的「查看」（`EyeOutlined` / 行点击）走 `editModalRef?.openView(record)`，即本组件 `isReadonly=true` 的只读视图（`currentCompanyId` 仅只读时赋值）。
- 宿主 2 = `AiAssistantDrawer.vue`：由 `frontend/src/layout/components/Header.vue` 全局挂载；快捷指令药丸已含 `📊 诊断客户健康度`（现抽为常量 `INSIGHT_PILL` 复用，避免文案漂移）。

## 4. 门禁实测（全部在 HEAD=e4d156f 上重跑）

| 门禁 | 结果 | 关键数字 |
| --- | --- | --- |
| `pnpm lint:check` | exit 0 | **0 problems**（本卡新增文件不再引入任何 warning/error） |
| `pnpm type-check:check` | exit 0 | `vue-tsc --noEmit` app + config 两档均过 |
| `pnpm test` | 全绿 | **20 files / 188 tests passed**（基线 18 files / 184 tests，本卡 +2 files / +4 tests） |
| `bash scripts/merge-gate.sh` | **8/8 PASS**，exit 0 | `surefire 报告=223 Tests run=1457 Failures=0 Skipped=0`（基线 1457 **持平**，未 `--update`）；`failsafe 报告=31 Tests run=118 Skipped=7`（本步未跑 [it]，读既有报告） |
| `bash scripts/merge-gate.sh --with-verify` | **9/9 PASS**，exit 0 | 追加 `[it]` 真跑：`mvn -B -ntp verify` → failsafe 118 / 7 skipped 全绿；`BIJECTION_OK`（12 豁免 ↔ 12 High）；`PMD_BASELINE_OK`（0==0==0） |

`scripts/test-baseline.txt` **未改动**（surefire 1457 持平，无需 `--update`）。

Docker 实测在线（`docker info --format '{{.ServerVersion}}'` → `29.6.2`），故按仓根 `AGENTS.md` 要求实际跑了 `--with-verify`，未把「没跑 [it]」记成环境所限。

原始日志（`work/*` 被 `.gitignore` 收敛，不入库）：

- `work/review-wire-customer-insight-panel-frontend-lint.log`（lint 0 problems）
- `work/review-wire-customer-insight-panel-frontend-typecheck.log`
- `work/review-wire-customer-insight-panel-frontend-test.log`（20 files / 188 tests）
- `work/review-wire-customer-insight-panel-merge-gate.log`（8/8，HEAD=e4d156f）
- `work/review-wire-customer-insight-panel-merge-gate-with-verify.log`（9/9，HEAD=e4d156f）

## 5. 测试非空洞性验证（变异自检）

临时移除宿主 1 的 `@created="onInsightActionCreated"` 后再跑 `CompanyEditModalInsight.test.ts` → 第二例失败（`expected "vi.fn()" to be called with arguments: [StringContaining "PD-1"]`，调用次数 0），确认用例确实锚在被接线行为上；随后已还原该行。测试文件的提交内容为**已还原的接线段 + 有效断言**。

## 6. 红线合规（终态：合并 + push 后）

- **`frontend/typed-router.d.ts` 零 diff**：`pnpm test` 与 merge-gate 的 `[frontend-unit]` 每轮均触发 `unplugin-vue-router` 重写该文件（重写内容含他人在途页面 `sale/lead.page.vue` 的 `salesLeadList` 路由，属仓内既有漂移，非本卡引入），每轮均已 `git checkout -- frontend/typed-router.d.ts` 还原；终态 `git status --porcelain -- frontend/typed-router.d.ts` 为空。
- **树净**：终态（合并 + push 后，权威树 master@`0358bcd`）`git status --porcelain=v1 --untracked-files=all` 仅剩两项，均为 `.gitignore` 否定规则白名单化的留痕（按本仓惯例留 untracked，不入库）：
  - `?? work/task-card-wire-customer-insight-panel.md`（立项卡）
  - `?? work/handoff-wire-customer-insight-panel.md`（本文件）
- **合并与 push 均在 Owner 显式授权范围内**：本轮只执行 `git checkout master` / `git merge --no-ff` / `git add docs+work` / `git commit` / `git push origin master`（推送范围分别仅 merge 一笔、docs 一笔），feature 分支保留未删；未 `reset`、未 `amend` 已推送提交、未 `--no-verify`。
- 零后端（`git diff --stat 70027c6..HEAD -- src/` 为空）、零迁移（`-- src/main/resources/db` 为空）、零权限常量、零 openapi 契约改动。

## 7. 假设与主观决策（需 Owner 知悉）

1. **宿主 1 的「按需刷新」= 重挂 Panel**：`created` 只携带 `pendingId`，且转待办/转活动不改变弹窗内的公司资料或联系人列表，因此把「刷新」落成 `insightKey` 自增重挂 Panel（重新拉取诊断），而非刷新无关的联系人表。若 Owner 认为此处应改为触发全局待确认列表刷新，属可调项。
2. **宿主 2 的「上下文客户明确」判定**：优先路由 query `filters.id`（客户列表页 `jumpToBusiness('CUSTOMER')` 的既有跳转口径），其次取对话消息里最近一条 `CUSTOMER` 业务来源主键（复用既有 `businessSources` / `businessSourceId` 解析，含历史前缀剥离）。两者都取不到时点药丸只给 `antdMessage.info` 提示、不展示 Panel，原「发消息」行为不变。
3. **`frontend/docs/test-checkpoints.md` 的 Playwright 检查点（原偏差已消除）**：交付当时按卡面红线（纯前端 `frontend/src/`）未动 `frontend/docs/`，与 `frontend/AGENTS.md` 的 Critical Rules 要求存在张力，已在复核报告里作为 MINOR 上报。**本轮 Owner 授权闭环时已补齐**：同风格追加「wire-customer-insight-panel」小节 2 条（宿主 1 客户详情弹窗只读视图 `转待办/转活动` 成功有反馈；宿主 2 AI 助手抽屉诊断面板转化成功有反馈并刷新会话），§6.1 欠账归零。
4. **测试内 `modal`/`drawer` 依赖全部走 vi.mock 桩**（ant-design-vue、vue-router、各 hooks），只断言接线行为（Panel 是否渲染 / companyId 是否透传 / `created` 是否给反馈与刷新），不覆盖真实网络与后端——符合本卡「宿主渲染 Vitest 1-2 例」的可选口径。

## 8. 停步点

本卡已闭环：交付（`43333b2` → `e4d156f`，2 笔线性，此前停在「未 merge、未 push」等 Owner 派发复核）→ B 档复核 🟢 GO / SAFE TO MERGE → Owner 授权闭环：`merge --no-ff`（merge 笔 `0358bcd`）+ `push origin master` → 补 2 条 Playwright 检查点（消除 §6.1 欠账）+ 本文件哈希修正 + `docs/active-handoff.md` 整体覆盖 → docs 笔 + push → 观察树进位 + 归档。feature 分支按纪律保留未删。
