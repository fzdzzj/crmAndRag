# 任务卡 · ui-permission-coverage-stocktake（卡 30）

> 权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`。分支 `feature/ui-permission-coverage-stocktake`（基点 = 当前 `master` `2d77460`）。
> 分档：**B 档**。成本：**零外呼**。
> 先读：`openspec/changes/ui-permission-coverage-stocktake/proposal.md`（R0–R5 / D1–D4）→ `.../tasks.md`（13 条）→ 本卡。

## §0 一句话

给前端权限小表补上**覆盖率门禁**：把「模板里用到的端点」与「表里登记的端点」「契约里的端点」三面结算，缺的补上、僵尸的只标注，并顺手把板子上指向不存在的存量文件核出来。

## §1 写集（3 新 + 1 可选追改，无其他写入者）

| 文件 | 动作 |
| --- | --- |
| `frontend/src/utils/__tests__/endpointPermissionCoverage.test.ts` | 新增（三集合断言） |
| `docs/ui-permission-coverage-v1.json` / `.md` | 新增（LF） |
| `frontend/src/constants/uiEndpointPermissions.ts` | **仅当实测有缺口**时追加（只增不删不重排） |
| `openspec/changes/ui-permission-coverage-stocktake/{proposal,tasks}.md` | 已由主 agent 写就，原样带进分支 |

**不许动**：`src/main`、`frontend/openapi.yaml`、契约、CI YAML、既有断言、`docs/active-handoff.md`。

## §2 核心事实（侦察已验，别重新发明）

1. 表有**编译期**护栏（`uiEndpointPermissions.ts:8-26`）：键错/名错都红 ⇒ **写错不是风险**。
2. 表是 `Partial` ⇒ **漏登合法**；运行期 fail-closed（`utils/endpointPermission.ts:34`）+ `Permission.vue:29` 是唯一生产调用点 ⇒ **漏登 = 功能静默消失**。这才是本卡要抓的。
3. CI 的 `frontend-quality` job 已跑 Vitest（`ci.yml:246`），`package.json` 有 `"test": "vitest run"` ⇒ 新增用例即进门禁，**不改 YAML**。
4. `used` 面从模板字面量提取；`permissionOfEndpoint` 只有一个调用点，别扩大扫描面到 axios 生成物（`src/api/axios` 是 `.gitignore` 排除的生成物）。

## §3 七个坑

1. **别把扫描面扩到全仓**：`Permission.vue` 的 `endpoint` prop 是唯一入口；`src/api/axios/**` 是生成物且被 gitignore，扫了会得到不稳定的噪声。
2. **`:endpoint` 动态绑定**：`v-bind` 形式的字符串字面量能解析，运行时拼接的不能 —— 后者进白名单，每项写 `文件:行号` + 理由；**不许** `if (解析失败) continue`。
3. **僵尸登记不删**：删一条会改变历史权限判断；本卡只标注。若你认为某条确实该死，写进锚的 `zombieCandidates` 并留理由，删除另卡。
4. **补齐的值只许来自契约**：值取 `EndpointPermissionMap[key].name`，OPEN 端点取 `null`；**不许**自拟权限名，也不许加模块前缀之外的变形（前缀语义见 `ContractPermission`）。
5. **vitest 读源码要用 `node:fs` + 绝对路径解析**（`fileURLToPath(import.meta.url)`），别用 `process.cwd()` 相对路径 —— CJK 路径 + Windows 下容易踩。
6. **锚 JSON 必须纯 LF**，且**末尾不得有空行**（卡 29 的复核就红在这条：`git diff --check` 报 `new blank line at EOF`）；生成器里自己断言。
7. **`pnpm` 命令在 `frontend/` 下跑**，不要在仓库根跑；根目录没有 workspace 配置。

## §4 判定口径

| verdict | 条件 |
| --- | --- |
| `COVERAGE-CLOSED` | `used ∖ registered` 为空**且**未改动 `uiEndpointPermissions.ts` |
| `GAPS-FIXED` | `used ∖ registered` 原本非空，已逐条补齐（须列出补了什么） |
| `INCONCLUSIVE` | 动态绑定无法解析且白名单不成立、或三集合有一个算不出来（附缺哪条） |

## §5 验收（顺序不可颠倒）

1. `frontend/` 下 `pnpm vitest run` 全绿（贴统计行），含新增用例。
2. 变异自证：临时删一条登记 ⇒ 新用例必红 ⇒ 还原绿，留日志。
3. `pnpm lint:check` + `pnpm type-check:check` 绿。
4. `git diff master -- src/main` 空；`git status` 只含写集；`git diff --check` 干净（**含尾部空行**）。

## §6 回报（≤300 字，细节留本卡 §7）

提交 SHA / 改动文件数 / vitest 统计行 / **三集合计数** / **`used∖registered` 逐条** / `verdict` / 白名单条数 / 存量核对结果 / 未解决项。

**「全覆盖 / 无缺口」这类词，必须同回合附三集合计数；否则不许写。**

## §7 细节留档位

执行者在本节追加：三集合清单、动态绑定白名单、补齐条目、存量核对表、任何偏离 proposal 的选择与理由。

---

## §7 执行留档（执行子 agent，2026-10-25）

**基点**：master `2d77460` → 分支 `feature/ui-permission-coverage-stocktake`（未合并未 push）。

### 三集合实测（门禁自身输出；逐条清单在锚 JSON `used`/`registered`/`contractDigest` 全量在档）

- `used` = **56** distinct（门控站点 64 处 + 1 个 `:allow` 逃生口 `sale/list.page.vue:37`；扫描 .vue 104 个，排除 `src/api/axios` 生成物）
  - **口径（M1 前订正）**：这 64 处**全部是 `:endpoint` 动态绑定字面量**（`<Permission :endpoint="'X'">`），静态 `endpoint="X"` 写法全仓 **0 处**——解析器两形态都覆盖，勿把「64 个站点」读成模板里有 64 个静态属性。
- `registered` = **57**（`Object.keys(UI)` 运行期 import 实测，非源码 grep）
- `contract` = **279**（SECURED 259 + OPEN 20；文本解析 `endpointPermission.gen.ts`，条目行数 279 = 解析数，格式漂移即 throw）
- `used∖registered` = **∅**（fail-closed 缺口为零）
- `registered∖contract` = **∅**（运行期断言实测，非编译期推理）
- `registered∖used` = **1**：`DELETE /contract`（`sales:SALES_UPDATE_CONTRACT`）——僵尸只标注（D3），已入测试 `ZOMBIE_CANDIDATES` 与锚 `zombieCandidates`，键复活即红、删除属另卡。

### 动态绑定与白名单

- `FinanceTable.vue` 4 处三元 `:endpoint`（**标签起始行** 153/164/174/184；`:endpoint` 属性本身落在 154/165/175/185——锚统一记标签起始行，内部同口径自洽）：双分支均为端点字面量，8 键全计入 used（锚 `dynamicBindingsResolved` 逐条在档）。
- **白名单 0 条**：无不可解析动态绑定；亦无既无 `endpoint` 又无 `allow` 的 `<Permission>`（解析器完整性守卫绿，`<PermissionGroup` 假阳性已被 `\b` 边界排除）。

### verdict：`COVERAGE-CLOSED`

`used∖registered` 实测为空 ⇒ §4 口径机械推导；`frontend/src/constants/uiEndpointPermissions.ts` **零改动**（C 节任务 7/8 因缺口为空不适用，未触发）。

### 门禁与变异自证

- 新增 `frontend/src/utils/__tests__/endpointPermissionCoverage.test.ts`（6 用例：used⊆registered（缺站点清单打印）/ registered⊆contract + 登记数下限 57（D3 只增不删）/ 动态绑定全量处置 + 白名单双向防过期 / 僵尸候选不过期 / 锚 JSON 逐字节复算 + 纯 LF 与无尾空行自检 / 锚 .md ≤40 行 + `permissionOfEndpoint` 契约外调用点守卫）。
- 变异自证：node 删除 `'DELETE /role'` 登记一行 ⇒ `pnpm vitest run` EXIT=1，三断言齐红，主断言输出 `存在 1 个 fail-closed 缺口：DELETE /role（模板站点：src/pages/(dashboard)/privilege/role.page.vue:57）`；`git checkout --` 还原后 EXIT=0 复绿。日志：`C:/Users/fzdzzj/AppData/Local/Temp/ui-perm-mutation-red.log` 与 `-green.log`（temp，不入库）。
- 全量：`pnpm vitest run` **24 文件 / 223 用例全绿**；`pnpm lint:check` EXIT=0；`pnpm type-check:check` EXIT=0。
- 锚重算：`UI_PERMISSION_COVERAGE_UPDATE=1 pnpm vitest run`（proposal「`-D` 开关」的等价机制），默认模式逐字节复算。

### 板 §3 第 7 条存量核对（R3，PowerShell Test-Path 实测）

| 路径 | 权威树 | 观察树 | 处置 |
| --- | --- | --- | --- |
| `docs/permission-matrix-audit.md` | 在（提交文件，零改动） | 在 | 历史快照，遵裁决不改写 |
| `work/_online-exam-backlog.md` | 不在 | **在（未跟踪）** | 板子补「观察树 work/」限定或摘除（主 agent M2 订正，本卡不改板） |
| `work/_paa-candidates-pool-v2.md` | 不在 | **在（未跟踪）** | 同上 |

**事实修正**：proposal §2「存量面」称两文件「实测都不存在」仅对**权威树**成立；观察树 `D:\code\crmAndRag\work\` 实存（与 AGENTS.md 工作树拓扑一致）。D1–D4 均未推翻。

### 偏离与坑

- 偏离 0；C 节未触发属测量结果非偏离。
- 坑：① JSDoc 里写 `src/**/*.vue`，其中 `*/` 会提前终止块注释（esbuild Transform 报 Unexpected "*"），改措辞避开；② eslint flat config 实际 lint `.ts` 测试文件（`--ext .js,.vue` 挡不住），`matchAll` 结果的 `as string` 被 `no-unnecessary-type-assertion` 打红，去断言即绿；③ `.ts` 不在 `.gitattributes` eol=lf 清单，工作树为 CRLF，node 变异脚本匹配行尾必须 `\r?\n`；④ 本次 Write 工具落盘实测未带 CR（两锚 `hadCrBefore=false`），仍执行 LF 归一并复验 `crAfter=false`、单一结尾换行。
