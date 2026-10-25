# tasks · ui-permission-coverage-stocktake（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（实测命令 + 实测值 + 文件行号 + 关键输出行）。**禁止**写「已完成」了事。勾选三态：`[x]`/`[-]`/`[ ]`。
判据 R0–R5 与决策 D1–D4 见 `proposal.md`，不得改动；要改先停手上报。
> **补勾执行者 = 主 agent（M1 前代笔，2026-10-25）**：执行子 agent 证据齐全但未落勾选（复核 MINOR 1）；下列依据均由主 agent 在 M1 前独立复测填入，执行者原始记录见 task-card §7。

## §0 · 硬约束

- 零外呼；**不改** `src/main` / `frontend/openapi.yaml` / 契约 / CI YAML / 既有断言 / `docs/active-handoff.md`。
- `uiEndpointPermissions.ts` 只许**追加**，不许重排/重命名/删除。

## A · 三集合对账（R1）

- [x] 1. 落 `registered` 集合（57 条，逐条 key + 权限名）与 `contract` 集合（生成物全量键，含 OPEN），计数实测
      依据：本轮 `Select-String "^\s+'[A-Z]+ /"` 实测 `uiEndpointPermissions.ts` = **57** 条、`endpointPermission.gen.ts` = **279** 条（其中 `status: 'OPEN'` 实际条目 20 条，另 1 处为文件头注释；259+20=279）；`registered⊆contract` 断言绿，逐条 key/值在锚 JSON `registered` 全档。
- [x] 2. 落 `used` 集合：扫描 `frontend/src/**/*.vue` 的 `<Permission endpoint="…">` 与 `:endpoint="'…'"`，逐条给 `文件:行号`
      依据：本轮 `pnpm vitest run` EXIT=0（`Test Files 24 passed (24)` / `Tests 223 passed (223)`，14.24 s），其中 `endpointPermissionCoverage.test.ts` 按锚逐字节复算 ⇒ used **56** 键 / 门控站点 64 处 + 1 个 `:allow`（`sale/list.page.vue:37`）；56 键逐站点 `文件:行号` 在锚 JSON `used` 全档；静态 `endpoint="X"` 写法全仓 **0 处**。
- [x] 3. 三个差集逐一落锚：`used∖registered`（缺口）/ `registered∖contract`（应恒空，运行期断言）/ `registered∖used`（僵尸，只标注不删）
      依据：锚 `docs/ui-permission-coverage-v1.md:5-7` 三行——`used∖registered`=**∅**、`registered∖contract`=**∅**（运行期断言实测，非编译期推理）、`registered∖used`=**1**（`DELETE /contract`）；`ZOMBIE_CANDIDATES` 防过期断言（`test.ts:275`）绿。
- [x] 4. 动态绑定 `:endpoint` 的不可解析项：显式白名单（每项 `文件:行号` + 理由），**不许静默跳过**
      依据：`test.ts:36` `DYNAMIC_BINDING_WHITELIST = []`（**0 条**），双向防过期断言（`test.ts:262` 起）绿；`bareSites`/`unresolved` 守卫实测 0 条；复核已逐行核对无 `continue`/`catch {}` 式跳过；`FinanceTable.vue` 4 处三元 8 键全部解析计入（锚 md:8）。

## B · 门禁（R2）

- [x] 5. 新增 `frontend/src/utils/__tests__/endpointPermissionCoverage.test.ts`：断言 `used ⊆ registered`，并在冲突时打印缺失端点与模板位置
      依据：新增 **308 行**（`git diff --stat 2d77460..HEAD`）、**6** 个 `it`；断言文本在 `test.ts:240`（「存在 N 个 fail-closed 缺口…补登记值只许取契约 name / OPEN 的 null」）+ 逐条站点明细。
- [x] 6. `pnpm vitest run` 真跑绿（贴统计行）；用**一次变异**自证有鉴别力：临时删一条登记 ⇒ 该用例必红 ⇒ 还原
      依据：本轮统计行 `Test Files 24 passed (24)` / `Tests 223 passed (223)`，EXIT=0；鉴别力由**复核侧独立变异**复证（换键删 `'POST /user/find'`）：EXIT=1、3 断言齐红、报错指向 `privilege/user.page.vue:47`；`git checkout --` 还原后 EXIT=0、小表 sha256 前 8 位复现 `8c877fb1`。

## C · 缺口补齐（R5，仅当有缺口）

- [-] 7. 逐条补齐 `used∖registered`，值只取契约 `name`（或 OPEN 的 `null`）；diff 只增不删，行数与缺口数对齐
      依据：条件不成立——`used∖registered`=∅（第 3 条）；`git diff 2d77460..HEAD -- frontend/src/constants/uiEndpointPermissions.ts` = **0 行**（sha 前后同为 `8c877fb1`），无补齐条目可列。
- [-] 8. 补齐后 `pnpm type-check:check` 与 `pnpm lint:check` 均绿
      依据：条件不成立（C 节未触发，同第 7 条）；该两项由 M1 之后的 CI `frontend-quality` job（Frontend Test / Lint / Type-check / Build Budget）实跑，本卡判据不依赖它们。

## D · 存量核对（R3）

- [x] 9. 板 §3 第 7 条逐项 `Test-Path` 实测，落 `(路径, 在/不在, 处置)` 三列；`docs/permission-matrix-audit.md` 零改动
      依据：本轮双树实测——权威树 `Test-Path`：`docs/permission-matrix-audit.md`=True、`work/_online-exam-backlog.md`=False、`work/_paa-candidates-pool-v2.md`=False；观察树后两项 work 文件=True（6592 B / 70691 B，未跟踪）；`git diff 2d77460..HEAD -- docs/permission-matrix-audit.md` = **0 行**。三列表在锚 md:12-18。
- [x] 10. 结论写明「板子订正由主 agent 在 M2 完成」，执行者不改板
      依据：锚 md:12 标题括注 + task-card §7 双树表；`git diff 2d77460..HEAD -- docs/active-handoff.md` = **0 行**（符合 D4）。

## E · 锚与收尾

- [x] 11. 锚 `docs/ui-permission-coverage-v1.json`（**LF**，写模式 `-D` 开关或等价机制，默认逐字节复算）+ `.md`（≤40 行）
      依据：JSON 尾 4 字节 `125,10,125,10`（`}`+LF，无尾空行）、CR 计数 **0**、sha256 前 8 位 `01f8ef36`；`.md` **20 行**（≤40）、尾字节 `227,128,130,10`（`。`+LF）、CR **0**；写模式为 `UI_PERMISSION_COVERAGE_UPDATE=1`（锚 md:10 记载的 env 等价机制），默认模式逐字节复算绿。
- [x] 12. `verdict` 三选一由测量值机械推导；`GAPS-FIXED` 必须逐个列出补了什么
      依据：锚 md:3 = `COVERAGE-CLOSED`，与 `used∖registered`=∅ + 小表零 diff（sha 前后 `8c877fb1`）机械一致；`GAPS-FIXED` 在本口径下不可达，已记 proposal §8（留记录不改生成器）。
- [x] 13. `git status` 只含写集；`git diff master -- src/main` 空；`git diff --check` 干净；外呼 **0** 已对账
      依据：`git diff 2d77460..HEAD -- src/main` = **0 行**、`-- .github/workflows` = **0 行**、`-- docs/active-handoff.md` = **0 行**；`git diff --check 2d77460..HEAD` EXIT=0；`--name-status` 6 文件全为 `A`（无 M/D）；外呼 0（vitest 纯本地、无用例触网）。
