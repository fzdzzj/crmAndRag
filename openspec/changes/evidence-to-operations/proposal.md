# 变更提案 · evidence-to-operations（三合一：交换率重定价 + 前端权限派生 + 开启通道工具化）

## 0. 统一主题

上一卡 `retrieval-and-contract-hardening` 把三条断言变成了可执行证据，但三条证据各自停在一个「证据不能直接当决策 / 当界面 / 当操作」的位置。本卡只做一件事：把每条证据**再推进一步到它可以被使用的形态**。

| 出口 | 上一卡留下的状态 | 本卡推进的那一步 |
|---|---|---|
| G1 近重复机制 | 决策锚 `verdict=UNDECIDABLE-BY-DATA`，价目表已在锚里 | 把「泄漏率」拆成**有害的一半**与**无害的一半**，给出真正可拍板的价目表与免费档判据 |
| G2 契约权限 | `x-required-permission` 已进 279 个 operation | 让前端**从契约派生**按钮权限，取消手抄权限名 |
| G3 生产开关 | 门控关系与热生效已实证（§12 七条断言） | 把 §9 的人工核对单变成**可判定脚本 + 自测**，并防文档漂移 |

共同性质：三组**都不新增生产行为**。本卡**不改任何生产常量**（`threshold=0.9944` / `NEAR_DUPLICATE_FLOOR=0.9887` / 权重 `0.75|0.6|0.5` 逐字不动）、**不改冻结契约**（`frontend/openapi.yaml` 零 diff）、**零真外呼**、**零迁移**、**不打开任何开关**。

## 1. G1 · 近重复交换率重定价（零外呼，读现有锚）

### 1.1 现状锚点（实测，非转述）

- 决策锚 `docs/rag-quality/near-dup-mechanism-decision-v1.json`（12090 行）：`verdict = "UNDECIDABLE-BY-DATA"`；`baseline.outcome` = 保留 `70/120 = 0.5833`、泄漏 `3/276 = 0.01087`、`satisfiesC1=false`、`satisfiesC2=true`、`satisfiesC3=false`；`externalCalls = 0`。
- M1（200 点 floor 网格）帕累托前沿节选：`0.9935 → 53.3% / 0%`、`0.989 → 57.5% / 0.36%`、`0.98 → 67.5% / 1.81%`、`0.90 → 94.2% / 3.26%`（保留率 / 泄漏率）。
- **本卡要修的那个口径洞**：`C2` 的泄漏率把两类性质完全不同的负样本混在同一个分母里 ——
  - `same-class`（同一份出版物内的不同图，**100 对**）：被降权 = 把**可能相关**的一张图压下去 → **有害**；
  - `cross-class`（不同出版物的图，**176 对**）：被降权 = 把**噪声**压下去 → **无害甚至有益**。

  上一卡把 276 对合成一个 `leakage` 数字，于是「多泄漏 2 对」在决策表里与「多压掉 2 张相关图」等价 —— 这是 UNDECIDABLE 无法在数据层收口的**口径原因**，不是数据量不够。
- 数据可得性：`docs/rag-quality/image-threshold-calibration-v2.json`（3778 行，SHA-256 `82d24204fba8029673d18ea5685964cf4134d2c43e3da5746986881bf35bdc65`，与决策锚 `corpus.anchorSha256` 逐字相等）**逐对**存 `stratum` / `kind` / `cosine`；`strata` = same-image 144 / near-duplicate 120 / same-class 100 / cross-class 176（合计 540）。**拆项不需要任何新外呼。**
- 「`same-class` = 同文档不同图」不是猜的，可由语料取图数直接验算：`C(8,2)+C(4,2)+C(12,2) = 28+6+66 = 100`，与 `strata.same-class` **逐数相等**；`cross-class = 276 − 100 = 176`。这条恒等式是「同文档 ⇒ 可能相关」这一口径能用的前提（G1 任务 1.1.1 必须复核它）。

### 1.2 预注册判据 E1–E6（跑前写死，跑后禁改）

- **E1 拆分度量**：对每个 floor `f`：
  - `S_same(f)` = same-class 对中 `cosine ∈ [f, 0.9944)` 的数量 ÷ 100（被 ×0.5–0.75 降权的同文档图占比）；
  - `C_cross(f)` = cross-class 同区间数量 ÷ 176；
  - `R(f)` = 同源保留率（与上一卡同源、同一 200 点网格）。
- **E2 恒等式与跨锚一致性**：逐 `f`，`kept(S_same) + kept(C_cross) + dropped = 276`；且 `R(f)` 与上一卡锚**逐点相等**（任一不等即红，防两份锚各说各话）。
- **E3 免费档判据**：若 ∃`f* < 0.9887` 使 `R(f*) > R(0.9887)` **且** `S_same(f*) ≤ S_same(0.9887)`，则称 `f*` 为免费档，取其中 `R` 最大者为推荐档。
- **E4 交换率**：无免费档时，逐档给出 `ΔR / ΔS_same` 与「这一档多压掉的同文档图数」，不得只给两端。
- **E5 全网格披露**：200 点 floor 网格逐点入锚（`R` / `S_same` / `C_cross` / 逐 kind 保留数）。
- **E6 结论三选一**：`FREE-TIER-FOUND` / `EXCHANGE-RATE` / `UNDECIDABLE-BY-DATA`，禁造第四种；锚内必须带 `decisionOwner = "OWNER"` 与「本卡不改常量、不拍板」字段。

### 1.3 假设登记（必须进锚，禁止隐含）

- **A1** 同文档 ⇒ 可能相关。这是**假设**，不是真值；真值需查询-相关性标注，本卡不做。
- **A2** 被降权的同文档图**只有与查询同时进入同一 top-k 才有害**；本卡不测共现，故 `S_same` 是**有害量上界**，必须如实标注。
- **A3** 跨文档降权视为无害（噪声抑制），只计数、不记代价。

### 1.4 写入面（G1）

新增：`src/test/java/com/slz/crm/quality/NearDuplicateExchangeRateAnalyzer.java`、`NearDuplicateExchangeRateDecisionTest.java`（唯一写入入口）、`NearDuplicateExchangeRateDecisionGuardTest.java`、`docs/rag-quality/near-dup-exchange-rate-v1.json`、`docs/rag-quality/near-dup-exchange-rate-v1.md`、`scripts/rag-quality/near-dup-exchange-rate-report.sh`。
改：`docs/active-handoff.md`（结论引用）。
**不碰**：`ImageSimilarityGate`、`NearDuplicateMechanismAnalyzer/DecisionTest`、上一卡的锚与脚本。

### 1.5 与上一卡锚的关系

新锚独立文件，头部记 `sourceAnchor` 路径 + `sourceAnchorSha256`，**不覆盖**旧锚；两份锚的一致性由 E2 逐点校验兜住。

## 2. G2 · 前端按钮权限从契约派生

### 2.1 现状锚点（实测）

- `frontend/openapi.yaml`（11825 行）已有 `x-required-permission: { code, name }` **259 处**、`x-permission-status: OPEN` **20 处**，合计 **279** 个 operation 带标记。
- **`operationId` 实测为 0 个**（`grep -c '^\s*operationId:'` = 0）→ 端点唯一标识只能是 **`METHOD /path`**，生成键必须用这个形状。
- 前端现状：`<Permission>` 共 **65 条匹配 / 11 个 `.vue` 文件**（含 4 处多行 `:allow="`），权限名**全部手抄**；`permission.gen.ts` 只提供 120 个名字的类型联合（零运行时）。
- 体积余量：`frontend/bundle-budget.json` 的 `app` 桶 `maxGzipBytes=201310` / `measuredGzipBytes=197350`，**余量仅 3960 B（≈2%）** → 生成物**不得**引入 279 条运行时常量表。

### 2.2 方案（类型全量 + 运行期极小表）

1. **生成（type only）** `frontend/src/types/endpointPermission.gen.ts`：`EndpointKey` = `'METHOD /path'` 字面量联合；`EndpointPermissionMap` = 每个 key 到 `{ code: N; name: 'X' }` 或 `{ status: 'OPEN' }` 的映射**类型**。零运行时代码（type 被擦除，不占体积预算）。
2. **运行期小表** `frontend/src/constants/uiEndpointPermissions.ts`：只登记 UI 真正要门控的端点，形状用**自键映射**钉死：
   `const UI = { 'POST /task': 'TASK_CREATE_TASK', … } as const satisfies { [K in keyof typeof UI]: EndpointPermissionMap[K] extends { name: infer N } ? N : never }`
   → 端点写错、或权限名与契约不符，**编译期即红**。
3. **组件** `Permission.vue` 增 `endpoint` prop（`allow` 保留为受控逃生口），解析优先级 `endpoint > allow`；未登记端点 **fail-closed（不显示）** 并 `console.warn` 一次。
4. **站点替换**：65 条匹配逐条把 `:allow=['x:NAME']` 换成 `:endpoint="'METHOD /path'"`；端点由 `NAME` 在生成物里**反查**得到（必须唯一；不唯一时取该按钮实际调用的那个，并在提交说明列出）。

### 2.3 预注册验收判据 P1–P4

- **P1 覆盖相等**：生成物 key 数 = 带任一 `x-` 权限标记的 operation 数（上一卡实测 279），且与权威扫描器计数相等；默认模式（不带 `-Dpermission.endpoints.update=true`）**逐字节校验**，改一字即红。
- **P2 类型安全**：`pnpm type-check:check` 绿；**反向自证** —— 故意把 `uiEndpointPermissions.ts` 里一个权限名改错，必须编译失败，并留痕。
- **P3 站点清零**：`frontend/src/**/*.vue` 中 `<Permission` 带字面量 `:allow=` 的匹配数 = **0**；例外 ≤3 处且每处写明理由（动态 `allow`）。门禁用 surefire 测试扫描（不依赖 Node）。
- **P4 三门绿 + 体积不破**：`pnpm lint:check` / `pnpm type-check:check` / `pnpm exec vitest run` 全绿；`bundle-budget.json` 的 `app` 桶不得因本组上升（type-only 生成物应零增量，超限即停步该组）。

### 2.4 写入面（G2）

新增：`frontend/src/types/endpointPermission.gen.ts`（生成物，提交）、`frontend/src/constants/uiEndpointPermissions.ts`、`frontend/src/utils/endpointPermission.ts`、`frontend/src/utils/__tests__/endpointPermission.test.ts`、`src/test/java/com/slz/crm/unit/permission/EndpointPermissionCatalogGenTest.java`、`src/test/java/com/slz/crm/unit/permission/PermissionSiteAuditTest.java`。
改：`frontend/src/components/common/Permission.vue` + 11 个 `.vue` 站点、`frontend/docs/test-checkpoints.md`（追加检查点）。
**不碰**：`frontend/openapi.yaml`、`src/main/java/**`、`frontend/typed-router.d.ts`。

## 3. G3 · 图片开关「开启通道」工具化

### 3.1 现状锚点

- `rag.retrieval.image-vector-ingest.enabled`：常量 `PdfImageSliceIngestService.IMAGE_VECTOR_ENABLED_KEY`；登记在 `DynamicConfigKeyRegistry`（默认 `false`）与 `ConfigKeyTierPolicy`（`COST`）。
- `docs/multimodal-gray-rollout.md`（220 行）：§9 开前核对 = 人工 curl/SQL；§10 观测 = 表；§11 回滚 = 文字；§12 = 上一卡热生效实证（七条断言）。
- **缺口**：§9 的判定（「现在是关的」/「只开 A 不动链路」）**没有任何可执行判定**；§1 / §9.1 里四键的默认值与档位是**手抄**，注册表改了文档不会红。

### 3.2 交付三条

1. **`scripts/rag-quality/image-vector-ingest-preflight.sh`**：子命令 `preflight` / `watch`；输入 = 四键快照（`--snapshot F`，TSV/JSON；或 `--base-url/--token` 直连管理端只读接口）；输出 = 逐键判定 + `GO`/`NO-GO` + **原因码**。退出码语义：`0`=GO、`1`=NO-GO（合法结论，不是故障）、`2`=输入错误。原因码必须覆盖结构性陷阱 **`A_WITHOUT_B`**（A=true 而 B=false / 白名单空 ⇒ 链路完全不动，§2 与 §12 已实证）。
2. **`scripts/tests/image-vector-ingest-preflight-selftest.sh`**：六个 fixture 用例覆盖 `GO` / `A_WITHOUT_B` / `B_OFF` / `NO_ALLOWLIST` / `ALREADY_ON` / 缺键 `exit 2`，逐案断言退出码与原因码。
3. **`MultimodalRolloutKeysDocConsistencyTest`**（surefire）：把 §1 / §9.1 中四键的**键名 + 默认值 + 档位**与 `DynamicConfigKeyRegistry` / `ConfigKeyTierPolicy` 实测对齐，不一致即红。

文档：`docs/multimodal-gray-rollout.md` 追加 **§13 开启通道工具化**（用法 + 边界 + 与 §9/§10/§11 的对应关系），§1–§12 **逐字不动**。

### 3.3 预注册判据 Q1–Q4

- **Q1** 自测六案全绿；**变异自证**：故意改坏一个原因码字符串，自测必须红（证自测真的在看输出）。
- **Q2** 真实快照判定：四键全关 + 白名单空 → `NO-GO reason=B_OFF`；`B=true / 白名单非空 / A=false` → `GO`。两案留痕。
- **Q3** 文档一致性反向自证：把 §9.1 中 `image-vector-ingest.enabled` 的默认值手改成 `true`，测试必须红，改回后绿。
- **Q4** 硬边界：本组**不打开任何开关、不改任何默认值、不改注册表、不改 `ConfigKeyTierPolicy`**。

### 3.4 写入面（G3）

新增：`scripts/rag-quality/image-vector-ingest-preflight.sh`、`scripts/tests/image-vector-ingest-preflight-selftest.sh`、`src/test/java/com/slz/crm/platform/config/MultimodalRolloutKeysDocConsistencyTest.java`。
改：`docs/multimodal-gray-rollout.md`（**只追加 §13**）。

## 4. 全局硬边界（三组并集，违反即整卡作废）

1. **生产常量零 diff**：`ImageSimilarityGate` / `NEAR_DUPLICATE_FLOOR` / `0.9944` / `0.75|0.6|0.5`。
2. **冻结契约零 diff**：`frontend/openapi.yaml` 与 `target/openapi/openapi.yaml` 均零改动，`bash scripts/check-openapi-consistency.sh` 绿。
3. **零真外呼**：不设 `RAG_*_REAL`、不读 `.env` 真键、不访问外网。
4. **零迁移**：`src/main/resources/db/migration` 零新增。
5. 不改任何已合入脚本的语义（`scripts/merge-gate.sh`、`scripts/check-test-baseline.sh`、`scripts/tests/*.sh` 既有者）。
6. `docs/permission-matrix-audit.md` 是历史快照，**禁改写**。
7. 不新增第三方依赖（G2 生成器用已有的 `snakeyaml`；前端零新增 dep）。

## 5. 组间顺序与停步裁决（三合一最容易出事的地方）

- 顺序固定 **G1 → G2 → G3**，但**三组互不依赖**：任一组自身判据不达标 → 该组停步并回退该组写入面，**其余组照常完成**（停步不连坐）。
- G1 若结论落在 `UNDECIDABLE-BY-DATA`：**不是失败**，照常交付（价目表 + 上界声明），但必须在 `active-handoff.md` 写明「档位仍未定」。
- G2 若 P2 反向自证因环境无法留痕 → 降级为「正向 type-check 绿 + 生成物逐字节校验绿」并如实上报，**不得声称反向已验证**。
- G3 若 §9.1 的四键默认值在文档里**本来就不一致** → 先按 Q3 记录该不一致为发现，**允许修正文档**（这是本组唯一允许改 §9.1 的情形），并在报告里单列。

## 6. 交付物汇总

| 组 | 交付物 | 性质 |
|---|---|---|
| G1 | 交换率分析器 + 新锚 json/md + 复算脚本 + 守卫测试 | 证据/决策输入（B 档） |
| G2 | 端点权限类型生成物 + 运行期小表 + 解析器 + 组件改造 + 站点替换 + 双门禁 | 前端代码（B 档） |
| G3 | preflight/watch 脚本 + 自测 + 文档一致性测试 + §13 | 工具/文档（C 档偏 B） |

整卡复核档位 = **B 档**（`docs/review-intensity-policy.md`：无迁移、无冻结契约改动、无真跑外呼）。

## 7. 非目标

- 不改 `NEAR_DUPLICATE_FLOOR`（改档 = 新卡），不替 Owner 定交换率；
- 不打开任何开关（Owner 运行期动作）；
- 不做真视觉外呼、不新增依赖、不改 `openapi.yaml`、不加迁移；
- 不做查询-相关性标注语料（只在 G1 里写明缺口与后续操作单要点）；
- 不重构 `ImageSimilarityGate` 判定语义。

## 8. 风险与取舍

| 风险 | 控制 |
|---|---|
| G1 结论被误读为「已拍板」 | E6 + 锚内 `decisionOwner="OWNER"` + 文档写明「档位待拍」 |
| G2 站点替换引入权限错配 | `satisfies` 自键映射（编译期）+ P3 扫描 + 例外逐条理由 |
| 生成物撑大前端体积 | type-only（零运行时）；运行期只留 UI 端点小表；P4 硬判 |
| G3 脚本判定漂移 | 自测六案 + 变异自证 Q1 + 文档一致性测试 Q3 |
| 三组一次性大卡 | 逐组回退（§5）+ 分组出表（§9） |

## 9. 验收（整卡 + 分组）

- **整卡**：`mvn -B -ntp clean verify` BUILD SUCCESS；surefire/failsafe 计数只增不减；`bash scripts/check-test-baseline.sh` EXIT=0；`bash scripts/check-openapi-consistency.sh` EXIT=0。
- **G1**：E1–E6 逐条出表；锚 `externalCalls=0`；`verdict ∈ {FREE-TIER-FOUND, EXCHANGE-RATE, UNDECIDABLE-BY-DATA}`。
- **G2**：P1–P4 逐条出表；前端三门绿；`app` 桶零增量。
- **G3**：Q1–Q4 逐条出表；自测六案绿；开关零改动。
- 分组判定必须**分别出表**（三份），再给一份总判定；禁合并成一句。

## 10. 修订记录

| 版本 | 日期 | 变更 |
|---|---|---|
| v1.0 | 2026-10-24 | 立项初稿：三组判据 E1–E6 / P1–P4 / Q1–Q4 预注册；G1 口径洞与假设 A1–A3 登记；G2 因 `operationId=0` 与体积余量 3960 B 定为「类型全量 + 运行期小表」。 |
