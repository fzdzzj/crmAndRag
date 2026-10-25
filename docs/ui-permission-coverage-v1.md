# ui-permission-coverage · v1 结论锚（卡 30，基点 master 2d77460）

- **verdict：COVERAGE-CLOSED** —— `used ∖ registered = ∅`，本卡未改动 `uiEndpointPermissions.ts`（无补登记）。
- 三集合计数（2026-10-25 实测）：**used 56**（门控站点 64 + `:allow` 逃生口 1）/ **registered 57** / **contract 279**（SECURED 259 + OPEN 20）；扫描 .vue 模板 104 个（排除 `src/api/axios` 生成物）。
- `used ∖ registered`（fail-closed 缺口）：∅。
- `registered ∖ contract`：∅（vitest 运行期断言实测，非编译期推理）。
- `registered ∖ used`（僵尸，D3 只标注不删）：`DELETE /contract` = `sales:SALES_UPDATE_CONTRACT` —— 模板 0 站点（contract.page.vue 无删除入口），删除属另卡。
- 动态绑定：FinanceTable.vue 4 处三元（153/164/174/184，双分支均为字面量，8 键全计入 used）；**白名单 0 条**；`:allow` 仅 sale/list.page.vue:37（D1 面外，PermissionSiteAuditTest 白名单在册）。
- 门禁：`frontend/src/utils/__tests__/endpointPermissionCoverage.test.ts` 六用例（used⊆registered / registered⊆contract / 动态绑定全量处置+白名单双向校验 / 僵尸候选不过期 / 锚 JSON 逐字节复算 / .md≤40 行+唯一调用点守卫）。
- 锚重算：`cd frontend && UI_PERMISSION_COVERAGE_UPDATE=1 pnpm vitest run`（默认模式逐字节复算，写模式为 proposal `-D` 开关的等价机制）。

## 板 §3 第 7 条存量核对（R3，Test-Path 实测；板子由主 agent 在 M2 订正，本卡不改板）

| 路径 | 实测（权威树 / 观察树） | 处置建议 |
| --- | --- | --- |
| `docs/permission-matrix-audit.md` | 在 / 在（提交文件，零改动） | 历史快照，遵裁决不改写 |
| `work/_online-exam-backlog.md` | **不在** / 在（未跟踪） | 板子补「观察树 work/」限定，或摘除 |
| `work/_paa-candidates-pool-v2.md` | **不在** / 在（未跟踪） | 同上 |

> 修正 proposal §2「存量面」侦察：两 work 文件在观察树 `D:\code\crmAndRag\work\` 实存（原侦察只测了权威树）；「指向不存在」仅对权威树成立。
