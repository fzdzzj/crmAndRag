# 任务清单 · evidence-to-operations（三合一）

> **打勾纪律**：每次 `[x]` 必须在**该行下一行**写 6 空格缩进的「补勾依据（日期 · 实测数字/命令输出）」；没有依据的勾视为未完成。
> **写入面纪律**：每组只允许写本组 §写入面 列出的路径；跨组写 = 整卡作废。
> **停步纪律**：某组判据不达标 → 只停该组（回退该组写入面），其余组继续；禁止「一组红就整卡放弃」。

## 0. G0 · 立项与共用侦察（零外呼）

- [x] 0.1 记录两棵树同一提交：`git -C <权威树> rev-parse HEAD` 与 `git -C <权威树> rev-parse origin/master` 相等，`git status --porcelain` 为空（观察树只允许 `docs/archive/` 与 `docs/main-agent-logbook.md` 未跟踪）
      补勾依据（2026-10-23 · HEAD=`3edc49290b502eca474ae0c89f47393698b7a826`=origin/master；开工时 porcelain 仅 `?? openspec/changes/evidence-to-operations/` 与 `?? work/task-card-evidence-to-operations.md`，观察树未跟踪仅 `docs/archive/`+logbook）
- [x] 0.2 记录 G2 计数基线：`frontend/openapi.yaml` 的 `x-required-permission` = 259、`x-permission-status: OPEN` = 20、`operationId` = 0；`<Permission` 匹配 65 条 / 11 个 `.vue`
      补勾依据（2026-10-23 · grep 实测 259/20/0；组件用法 65 处/11 文件——粗 grep 66/12 的差 = ViewRoleModal.vue L25 `computed<PermissionGroup>` 误匹配；61 处字面 `:allow` + FinanceTable 4 处动态三元 + sale/list 1 处例外 = 65）
- [x] 0.3 记录体积基线：`frontend/bundle-budget.json` 的 `app` 桶 `measuredGzipBytes=197350` / `maxGzipBytes=201310`（余量 3960 B）
      补勾依据（2026-10-23 · cat frontend/bundle-budget.json 实测吻合；本机 pnpm build 因 esbuild Access denied 不可跑（master 同红，环境级），体积判定挂 CI Build Budget job）
- [x] 0.4 复算源锚 SHA-256：`docs/rag-quality/image-threshold-calibration-v2.json` 必须等于 `82d24204fba8029673d18ea5685964cf4134d2c43e3da5746986881bf35bdc65`（与决策锚 `corpus.anchorSha256` 一致）
      补勾依据（2026-10-23 · `sha256sum` 实测 = `82d24204…bdc65`，与登记值逐字相等；守卫测试 `NearDuplicateExchangeRateDecisionGuardTest` 常驻该校验）
- [x] 0.5 记录 G3 注册表基线：四键在 `DynamicConfigKeyRegistry` 的静态默认值与 `ConfigKeyTierPolicy` 的档位，逐键写进 `work/` 证据文件
      补勾依据（2026-10-23 · enabled=BOOLEAN/false、kb-allowlist=STRING/""、max-images-per-document=INTEGER/"5"(1~20)、image-vector-ingest.enabled=BOOLEAN/false，四键全 COST；已写入 work/evidence-evidence-to-operations.md（work/* gitignore 内，本地留痕））

## 1. G1 · 近重复交换率重定价（零外呼，读现有锚）

### 1.1 度量拆分与判定

- [x] 1.1.1 新增 `NearDuplicateExchangeRateAnalyzer`：读校准锚逐对 `stratum`/`kind`/`cosine`，对 200 点 floor 网格逐点算 `R(f)` / `S_same(f)` / `C_cross(f)` / 逐 kind 保留数（E1）
      补勾依据（2026-10-23 · 分析器落盘 `src/test/java/com/slz/crm/quality/NearDuplicateExchangeRateAnalyzer.java`；200 点全整数定点网格 0.90→0.9995 步长 0.0005，保留判定与上一卡 `tierOfCosine` 同语义并在 Javadoc 披露）
- [x] 1.1.2 落 E2 恒等式：逐点 `kept(S_same)+kept(C_cross)+dropped == 276`；且 `R(f)` 与上一卡决策锚**逐点相等**，任一不等即抛
      补勾依据（2026-10-23 · 恒等式在 `evaluate()` 内建抛错，200/200 全过；跨锚逐点相等由 DecisionTest 断言（首跑曾暴露 floor≥0.9945 段平台语义差，已按上一卡同语义修正后逐点相等））
- [x] 1.1.3 落 E3 免费档判定：`f* < 0.9887` 且 `R(f*) > R(0.9887)` 且 `S_same(f*) <= S_same(0.9887)`，满足者取 `R` 最大
      补勾依据（2026-10-23 · `freeTier()` 实测 = none：same-class 0.988662 卡在基线 floor 下方一步，网格上所有 f<0.9887 的点 S_same≥0.03>0.02，条件全灭）
- [x] 1.1.4 落 E4 交换率：无免费档时逐档输出 `ΔR/ΔS_same` 与多压掉的同文档图数
      补勾依据（2026-10-23 · 200 档 ΔR/ΔS_same/extraSuppressedSameDoc/exchangeRate 全量入锚 `grid.items`；firstImprovingFloor=0.988（+1 同源变体/+1 同文档），bestValueFloor=0.982（ΔR/ΔS_same=8.333333，披露非推荐）；C_cross 全网格恒 1/176）
- [x] 1.1.5 落 E6 三值判定：`FREE-TIER-FOUND` / `EXCHANGE-RATE` / `UNDECIDABLE-BY-DATA`，禁第四种
      补勾依据（2026-10-23 · verdict() 三支按序短路（E3→EXCHANGE-RATE→UNDECIDABLE），实测 verdict=EXCHANGE-RATE；守卫测试断言三选一且与网格重算一致）

### 1.2 锚与守卫

- [x] 1.2.1 `NearDuplicateExchangeRateDecisionTest` 为**唯一写入入口**：默认校验模式；`-Dnear.dup.exchange.write=true` 才写 `docs/rag-quality/near-dup-exchange-rate-v1.json`
      补勾依据（2026-10-23 · 锚 4442 行已由写模式产出；默认模式与已提交锚逐字节一致（锚内无 volatile 字段）；复算脚本 `scripts/rag-quality/near-dup-exchange-rate-report.sh` 实跑 EXIT=0）
- [x] 1.2.2 锚内必带：`decisionVersion` / `sourceAnchor` / `sourceAnchorSha256` / `productionConstants`（逐字抄现值）/ `assumptions`（A1–A3 原文）/ `decisionOwner="OWNER"` / `externalCalls=0` / 200 点全网格 / 结论与推荐档
      补勾依据（2026-10-23 · 字段全在：decisionVersion=NEAR-DUP-EXCHANGE-RATE-1.0、sourceAnchorSha256=上一卡锚实算 SHA、corpusAnchorSha256=82d24204…、productionConstants 由守卫逐值比对、assumptions A1–A3 逐字回显、decisionOwner=OWNER、externalCalls=0、grid 200 点、verdict=EXCHANGE-RATE + exchangeSummary）
- [x] 1.2.3 `NearDuplicateExchangeRateDecisionGuardTest`：源锚 SHA-256 漂移即红、`externalCalls != 0` 即红、`decisionOwner != OWNER` 即红、`verdict` 越界即红、缺 `assumptions` 即红
      补勾依据（2026-10-23 · GuardTest 7 用例全绿：双源锚 SHA 实算比对、externalCalls/decisionOwner/decisionScope、verdict 三选一 + 网格重算一致性、assumptions/criteria 逐字、productionConstants 逐值、每点比率-计数自洽、网格完整性 + 基线钉 70/2/1/273）
- [x] 1.2.4 守卫测试自证：手工把锚里 `verdict` 改成一个非法值，测试必须红；改回后绿（留痕）
      补勾依据（2026-10-23 · 第一轮 sed 命中锚内 exchangeSummary.verdict（同名内嵌字段在顶层之前）守卫绿属正确；第二轮按行号改顶层 verdict→"GO" → GuardTest `verdictInRangeAndConsistentWithGrid:76 verdict 只能三选一，实测: GO`（Tests run: 7, Failures: 1）→ 还原后 7/7 绿）

### 1.3 文档与复算脚本

- [x] 1.3.1 `docs/rag-quality/near-dup-exchange-rate-v1.md`：价目表（≥8 档）+ 免费档/交换率结论 + 假设 A1–A3 + 「未定项：档位由 Owner 拍板，改常量须新卡」
      补勾依据（2026-10-23 · md 落盘：12 档价目表（含基线与 0.9885 纯亏步）每个数字来自报告表 2/4、结论 EXCHANGE-RATE、A1–A3 逐字、§6 未定项写明档位待 Owner 拍板/改常量须新卡/查询-相关性标注缺口）
- [x] 1.3.2 `scripts/rag-quality/near-dup-exchange-rate-report.sh`：写模式跑写入入口，`tee target/near-dup-exchange-rate-report.txt`，打印 `verdict` 与推荐档；退出码 0=复算成功（含 NO-GO 类结论），非 0=复算本身失败
      补勾依据（2026-10-23 · 脚本实跑 EXIT=0，固定表格落盘 target/near-dup-exchange-rate-report.txt（[G1E] 表 1/4~4/4），打印 verdict=EXCHANGE-RATE externalCalls=0 firstImprovingFloor=0.988 bestValueFloor=0.982）
- [x] 1.3.3 脚本实跑一次并留痕（输出目录 + 关键三行）
      补勾依据（2026-10-23 · 见 work/evidence-evidence-to-operations.md §G1：表 1/4 基线行、表 3/4 判定行、锚路径+verdict 行三段原文留痕）

### 1.4 G1 照判

- [x] 1.4.1 逐条给出 E1–E6 的判定与依据（一行一条，数字取自锚）
      补勾依据（2026-10-23 · md §4 表：E1 满足 / E2 满足（200 点恒等式+跨锚逐点） / E3 不满足（免费档不存在，判定输入非故障） / E4 满足（200 档价目全披露） / E5 满足 / E6 满足（verdict=EXCHANGE-RATE））
- [x] 1.4.2 硬边界自证：`git diff --stat` 证明 `ImageSimilarityGate.java` 与上一卡三份锚/脚本零改动
      补勾依据（2026-10-23 · `git diff --stat -- ImageSimilarityGate.java near-dup-mechanism-decision-v1.{json,md} image-threshold-calibration-v2.json near-dup-mechanism-report.sh` 输出为空）

## 2. G2 · 前端按钮权限从契约派生

### 2.1 类型生成器

- [x] 2.1.1 新增 `EndpointPermissionCatalogGenTest`：用 `snakeyaml` 解析 `frontend/openapi.yaml`，遍历 `paths.*.<method>`，键 = `'METHOD /path'`（方法大写、path 原样）
      补勾依据（2026-10-23 · 测试落盘 `src/test/java/com/slz/crm/unit/permission/EndpointPermissionCatalogGenTest.java`，3 用例绿；键形实测如 `'GET /permission/list'`）
- [x] 2.1.2 每个键取值：有 `x-required-permission` → `{ code, name }`；有 `x-permission-status` → `{ status: 'OPEN' }`；两者都无 → 不生成
      补勾依据（2026-10-23 · 生成物逐键带 `{ code: N; name: 'X' }` 或 `{ status: 'OPEN' }`，同时带两者在解析期直接抛错）
- [x] 2.1.3 写出 `frontend/src/types/endpointPermission.gen.ts`：**只出 type**（`EndpointKey` 联合 + `EndpointPermissionMap`），文件头写明来源与重写命令；`-Dpermission.endpoints.update=true` 才重写，默认逐字节校验
      补勾依据（2026-10-23 · 生成物 294 行提交；默认模式逐字校验绿 + 零运行时代码用例绿；重写只由 `-Dpermission.endpoints.update=true` 触发）
- [x] 2.1.4 计数断言：生成 key 数 = 带任一 `x-` 权限标记的 operation 数（并与 259+20 的口径对齐，若两者不互斥则以实测数为准并在文件头注明）
      补勾依据（2026-10-23 · P1 双口径相等 keys=279（SECURED 259 + OPEN 20，与扫描器独立计数集合相等，文件头注明口径））

### 2.2 运行期小表与解析器

- [x] 2.2.1 `frontend/src/constants/uiEndpointPermissions.ts`：只登记 UI 门控端点，用自键映射 `satisfies` 钉死「端点 → 权限名」与生成物一致
      补勾依据（2026-10-23 · 57 项小表经 `defineUiPermissionTable`（Partial<{[K in EndpointKey]: ContractPermission<K>}> 泛型约束）钉死；实测提案的自引用 satisfies 写法会把 keyof 宽化致条件类型塌 never，已改显式泛型并在注释留痕）
- [x] 2.2.2 `frontend/src/utils/endpointPermission.ts`：纯函数 `permissionOfEndpoint(key)`，未登记 → `undefined`（fail-closed）；`OPEN` → `null`（视为放行）
      补勾依据（2026-10-23 · 三元语义 + 未登记 console.warn 一次（模块级去重）实现落盘）
- [x] 2.2.3 `frontend/src/utils/__tests__/endpointPermission.test.ts`：覆盖已登记 / 未登记 / `OPEN` / 空值四类
      补勾依据（2026-10-23 · 4 用例全绿（vitest 217/217 内）：已登记返回带前缀权限名、未登记 undefined+warn 一次、OPEN 登记 null 放行（类型约束在编译期钉死）、空值/无效键 undefined）

### 2.3 组件与站点替换

- [x] 2.3.1 `Permission.vue` 增 `endpoint` prop（类型 `EndpointKey`），优先级 `endpoint > allow`；未登记端点不渲染并 `console.warn` 一次
      补勾依据（2026-10-23 · 组件改完：endpoint 命中走 `permissionOfEndpoint`（undefined→false 不渲染，null→true 放行，string→全等比较），未命中回退 allow 逃生口；warn 在 util 层去重）
- [x] 2.3.2 65 条站点逐条替换为 `:endpoint`，端点由原权限名在生成物里**反查**得到（唯一）；反查不唯一的在提交说明列出取用依据
      补勾依据（2026-10-23 · 60 处字面 + FinanceTable 4 处动态三元 + sale/list 1 处例外 = 65；逐站点行号精确替换（同权限名不同按钮映射不同端点，禁按名盲替）；取用依据（查看类按钮按数据源/语义端点、反查不唯一逐点选实际调用者）写进 G2 提交说明与 work 证据文件）
- [x] 2.3.3 例外登记：动态 `allow` 站点 ≤3 处，每处在 `work/` 证据文件写明「为何不能用端点派生」
      补勾依据（2026-10-23 · 恰 1 处：sale/list「查看」=SALES_VIEW_SALE_OPPORTUNITY_ONLY_MY 契约 0 候选，理由已写进 PermissionSiteAuditTest 白名单（可复审）与 work 证据文件；FinanceTable 4 处动态 allow 全部转成动态 :endpoint 三元（字面量联合仍编译期可查），未占用例外名额）

### 2.4 门禁与反向自证

- [x] 2.4.1 新增 `PermissionSiteAuditTest`：扫描 `frontend/src/**/*.vue`，`<Permission` 带字面量 `:allow=` 的匹配数必须为 0（例外按文件白名单 + 理由，≤3）
      补勾依据（2026-10-23 · 测试绿：104 个 .vue 扫描、白名单外 0 处 :allow=、白名单内 1 处（≤3）且防例外过期（白名单登记但文件无 :allow= 也红））
- [x] 2.4.2 反向自证 P2：把 `uiEndpointPermissions.ts` 里任一权限名改错 → `pnpm type-check:check` 必须失败；改回后绿（两次输出都留痕）
      补勾依据（2026-10-23 · 红证：`GET /company/custom` 的值改错为 CUSTOMER_QUERY_CONTACT → `error TS2322: Type '"customer:CUSTOMER_QUERY_CONTACT"' is not assignable to type '"CUSTOMER_QUERY_COMPANY" | `${string}:CUSTOMER_QUERY_COMPANY` | undefined'`；还原后 type-check 双 tsconfig 全绿）
- [x] 2.4.3 P4：`pnpm lint:check` / `pnpm type-check:check` / `pnpm exec vitest run` 三门绿；`bundle-budget.json` 的 `app` 桶零增量
      补勾依据（2026-10-23 · 三门绿（vitest 217/217）；bundle-budget.json 零改动（不在写入面、生成物 type-only 零运行时、小表 57 项为有意新增运行期），真实增量由 CI Build Budget job 定案（本地 esbuild Access denied 环境级不可跑，master 同红）——CI 条件项）

### 2.5 G2 照判

- [x] 2.5.1 逐条给出 P1–P4 判定与依据
      补勾依据（2026-10-23 · P1 双口径 279 相等；P2 反向自证红绿双向留痕；P3 站点清零 104 文件/白名单 1；P4 三门绿+体积挂 CI）
- [x] 2.5.2 硬边界自证：`frontend/openapi.yaml`、`src/main/java/**`、`frontend/typed-router.d.ts` 零 diff
      补勾依据（2026-10-23 · git status 实测三者零改动（typed-router.d.ts 曾被前端工具链重生成一次，已 checkout 恢复——该文件由工具自动重写属已知坑，非本卡手改））

## 3. G3 · 图片开关「开启通道」工具化

### 3.1 脚本

- [x] 3.1.1 `scripts/rag-quality/image-vector-ingest-preflight.sh`：`preflight` / `watch` 两子命令；`--snapshot F` 读四键快照，或 `--base-url/--token` 走管理端只读接口
      补勾依据（2026-10-23 · 两子命令 + TSV/扁平 JSON 双形态 + 管理端 GET /platform/config/items?namespace=rag.retrieval 解析（ConfigItemView 的 key/value 字段对）实现落盘）
- [x] 3.1.2 退出码语义写进脚本头：`0`=GO、`1`=NO-GO（合法结论）、`2`=输入错误；原因码至少含 `GO` / `A_WITHOUT_B` / `B_OFF` / `NO_ALLOWLIST` / `ALREADY_ON` / `BAD_INPUT`
      补勾依据（2026-10-23 · 六原因码按序短路全实现，退出码 0/1/2 与判定规则注释在脚本头）
- [x] 3.1.3 `A_WITHOUT_B` 判定必须在输出里写明「链路完全不动（§2/§12 已实证）」，不得只给原因码
      补勾依据（2026-10-23 · 输出行含原文「链路完全不动（§2/§12 已实证）」，自测案 2 以该句为断言目标）

### 3.2 自测

- [x] 3.2.1 `scripts/tests/image-vector-ingest-preflight-selftest.sh`：六 fixture 用例，逐案断言退出码 + 原因码（GO / A_WITHOUT_B / B_OFF / NO_ALLOWLIST / ALREADY_ON / 缺键 exit 2）
      补勾依据（2026-10-23 · 实测 7/7 绿（六案 + watch --times 1 单轮一致性），TSV/JSON 双形态覆盖）
- [x] 3.2.2 变异自证 Q1：临时把脚本里 `A_WITHOUT_B` 改成别的字符串 → 自测必须红；改回绿（留痕）
      补勾依据（2026-10-23 · 首轮变异 reason=A_WITHOUT_BROKEN 未红——暴露自测 grep 子串匹配过松，已收紧为行尾锚定（A_WITHOUT_BROKEN 不再命中）后复测：变异 → `2-A_WITHOUT_B: FAIL`（passed=6 failed=1）→ 还原 → 7/7 绿）

### 3.3 文档一致性

- [x] 3.3.1 `MultimodalRolloutKeysDocConsistencyTest`：解析 `docs/multimodal-gray-rollout.md` §1 表与 §9.1，取四键的键名/默认值，与 `DynamicConfigKeyRegistry` 静态默认 + `ConfigKeyTierPolicy` 档位比对
      补勾依据（2026-10-23 · 3 用例绿；口径勘误：四键表实际在文档 §2（提案写 §1 系节号漂移），测试按实际内容定位并在 Javadoc 声明——§2 表 + §9.1 手抄行（文档内折行三行，按起点行+后两行拼接解析）双处比对注册表默认/类型/档位）
- [x] 3.3.2 反向自证 Q3：把 §9.1 的 `image-vector-ingest.enabled` 默认值改成 `true` → 测试红；改回绿（留痕）
      补勾依据（2026-10-23 · 改值后 `docPreflightDefaultsLineMatchesRegistry` FAILURE（Tests run: 3, Failures: 1）→ git 还原（diff 清零）→ 3/3 绿）

### 3.4 文档

- [x] 3.4.1 `docs/multimodal-gray-rollout.md` **只追加 §13**：用法、退出码、原因码、与 §9/§10/§11 的对应关系、「本卡不打开任何开关」
      补勾依据（2026-10-23 · §13 落盘：三条命令、退出码 0/1/2、六原因码及短路顺序、§9.1/§9.3/§10/§11 对应关系、两行「不打开任何开关/不改默认值/真值源零 diff」）
- [x] 3.4.2 §1–§12 逐字不动自证：`git diff -U0 docs/multimodal-gray-rollout.md` 只出现新增行
      补勾依据（2026-10-23 · diff -U0 实测删除行=0、新增行=30）

### 3.5 G3 照判

- [x] 3.5.1 逐条给出 Q1–Q4 判定与依据
      补勾依据（2026-10-23 · Q1 自测 7/7+变异红绿；Q2 GO 案与 B_OFF 案留痕（fixture 1/3，即「B=true/白名单非空/A=false→GO」「四键全关+白名单空→B_OFF」）；Q3 反向自证红绿；Q4 硬边界=registry/tier/四键零 diff）
- [x] 3.5.2 硬边界自证：注册表 / `ConfigKeyTierPolicy` / 四键默认值零 diff
      补勾依据（2026-10-23 · `git diff --stat` 对 DynamicConfigKeyRegistry.java / ConfigKeyTierPolicy.java / ImageSimilarityGate.java / frontend/openapi.yaml 输出为空）

## 4. G4 · 全量门禁与照判回报

### 4.1 门禁

- [x] 4.1.1 `mvn -B -ntp clean verify` BUILD SUCCESS（surefire/failsafe 计数只增不减）
      补勾依据（2026-10-24 · BUILD SUCCESS 13:35 min；surefire 337 报告/2092 用例 F0 E0 S0、failsafe 44 报告/145 用例 S13（上轮 332/2077+44/145，只增不减：+5 报告 +15 用例 = 本卡 5 个新 surefire 测试类））
- [x] 4.1.2 `bash scripts/check-test-baseline.sh` EXIT=0（**只在全量 `clean verify` 之后跑**，避免读到定向 `-Dtest=` 的残缺报告）
      补勾依据（2026-10-24 · clean verify 后 check EXIT=0（337/2092 已过旧下限 332/2077）；随即 `--update` 重锚 surefire 337/2092 S0 + failsafe 44/145 S13；提交后干净树上重做 `--update` 使 source-revision 无 `-dirty`（落 commit 5））
- [x] 4.1.3 `bash scripts/check-openapi-consistency.sh` EXIT=0（契约零 diff 的机器证明）
      补勾依据（2026-10-24 · 脚本输出「前后端契约一致：frontend/openapi.yaml 与后端 swagger 导出逐字相同」，EXIT=0）
- [x] 4.1.4 前端三门：`pnpm lint:check` / `pnpm type-check:check` / `pnpm exec vitest run` 全绿
      补勾依据（2026-10-24 · LINT=0 / TYPECHECK=0 / vitest 217/217（23 文件，含本卡 endpointPermission 4 例）；typed-router.d.ts 工具重生成后 checkout 恢复）

### 4.2 分组照判与回报

- [x] 4.2.1 三份分组判定（G1 / G2 / G3）分别出表，再给一份总判定；任一停步必须写清「停了哪组、其余组状态」
      补勾依据（2026-10-24 · G1 照判=§1.4（E1–E6 全落，verdict=EXCHANGE-RATE）/ G2 照判=§2.5（P1–P4 全过）/ G3 照判=§3.5（Q1–Q4 全过）；**三组全部交付、零停步**）
- [x] 4.2.2 交付前自检：生产常量零 diff、契约零 diff、零迁移、零真外呼、无新增依赖
      补勾依据（2026-10-24 · ImageSimilarityGate/openapi.yaml/db-migration 目录零改动（git status 无）、pom.xml 零改动（零新依赖）、全卡未设 RAG_*_REAL 未读 .env 未出网、docs/permission-matrix-audit.md 未动）
- [x] 4.2.3 回报 ≤300 字：产出路径 / 行数 / 校验结果 / 未解决项 / 待决策项
      补勾依据（2026-10-24 · 回报见执行子 agent 最终答复（按任务卡 §9 格式）；细节全在本 tasks.md 各补勾依据与 work/evidence-evidence-to-operations.md）

## 5. 铁律（违反任一条 = 整卡作废）

1. 不改生产常量：`ImageSimilarityGate` / `NEAR_DUPLICATE_FLOOR` / `0.9944` / `0.75|0.6|0.5`。
2. 不改冻结契约：`frontend/openapi.yaml` 与 `target/openapi/openapi.yaml` 零 diff。
3. 零真外呼：不设 `RAG_*_REAL`、不读 `.env` 真键、不访问外网。
4. 零迁移：`src/main/resources/db/migration` 零新增。
5. 不新增第三方依赖（G2 用已有 `snakeyaml`；前端零新增 dep）。
6. 不改已合入脚本语义（`scripts/merge-gate.sh`、`scripts/check-test-baseline.sh`、既有 `scripts/tests/*.sh`）。
7. `docs/permission-matrix-audit.md` 禁改写。
8. 分组回退只回退本组写入面，禁止顺手改别组文件。
