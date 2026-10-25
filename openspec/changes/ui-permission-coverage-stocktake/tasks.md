# tasks · ui-permission-coverage-stocktake（判据以此文件为准）

勾选规则：每勾一条，必须在条目下方缩进 **6 空格**写「补勾依据」（实测命令 + 实测值 + 文件行号 + 关键输出行）。**禁止**写「已完成」了事。勾选三态：`[x]`/`[-]`/`[ ]`。
判据 R0–R5 与决策 D1–D4 见 `proposal.md`，不得改动；要改先停手上报。

## §0 · 硬约束

- 零外呼；**不改** `src/main` / `frontend/openapi.yaml` / 契约 / CI YAML / 既有断言 / `docs/active-handoff.md`。
- `uiEndpointPermissions.ts` 只许**追加**，不许重排/重命名/删除。

## A · 三集合对账（R1）

- [ ] 1. 落 `registered` 集合（57 条，逐条 key + 权限名）与 `contract` 集合（生成物全量键，含 OPEN），计数实测
- [ ] 2. 落 `used` 集合：扫描 `frontend/src/**/*.vue` 的 `<Permission endpoint="…">` 与 `:endpoint="'…'"`，逐条给 `文件:行号`
- [ ] 3. 三个差集逐一落锚：`used∖registered`（缺口）/ `registered∖contract`（应恒空，运行期断言）/ `registered∖used`（僵尸，只标注不删）
- [ ] 4. 动态绑定 `:endpoint` 的不可解析项：显式白名单（每项 `文件:行号` + 理由），**不许静默跳过**

## B · 门禁（R2）

- [ ] 5. 新增 `frontend/src/utils/__tests__/endpointPermissionCoverage.test.ts`：断言 `used ⊆ registered`，并在冲突时打印缺失端点与模板位置
- [ ] 6. `pnpm vitest run` 真跑绿（贴统计行）；用**一次变异**自证有鉴别力：临时删一条登记 ⇒ 该用例必红 ⇒ 还原

## C · 缺口补齐（R5，仅当有缺口）

- [ ] 7. 逐条补齐 `used∖registered`，值只取契约 `name`（或 OPEN 的 `null`）；diff 只增不删，行数与缺口数对齐
- [ ] 8. 补齐后 `pnpm type-check:check` 与 `pnpm lint:check` 均绿

## D · 存量核对（R3）

- [ ] 9. 板 §3 第 7 条逐项 `Test-Path` 实测，落 `(路径, 在/不在, 处置)` 三列；`docs/permission-matrix-audit.md` 零改动
- [ ] 10. 结论写明「板子订正由主 agent 在 M2 完成」，执行者不改板

## E · 锚与收尾

- [ ] 11. 锚 `docs/ui-permission-coverage-v1.json`（**LF**，写模式 `-D` 开关或等价机制，默认逐字节复算）+ `.md`（≤40 行）
- [ ] 12. `verdict` 三选一由测量值机械推导；`GAPS-FIXED` 必须逐个列出补了什么
- [ ] 13. `git status` 只含写集；`git diff master -- src/main` 空；`git diff --check` 干净；外呼 **0** 已对账
