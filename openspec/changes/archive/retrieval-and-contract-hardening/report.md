# 执行报告 · retrieval-and-contract-hardening（三合一）

> 分支 `feature/retrieval-and-contract-hardening`（从 `master` @ `5bb8b05` 检出；首个提交 `50b7603 docs(card): 立项…`）
> 工作树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`｜档位 **A**｜零真外呼 · 零新依赖 · 零迁移 · 未打开任何生产开关 · 权限语义零变更
>
> **总判定：三组全部交付，无停步。** G1 = **UNDECIDABLE-BY-DATA（C5 第 ③ 支：关节不可达、两侧可达）**；G2 七条断言全绿且真证到"未重启"；G3 P1/P2/P4 绿、P3 三项绿 + `build` 项**环境级红（master 同红）**，列 §6 待决策项。

## 1. 分组判定一览

| 组 | 判定 | 核心结论 | 产物 |
| --- | --- | --- | --- |
| **G0** | 交付 | 三份锚点实测复述；分支与首提交就位；判据数值全部登记成常量 | tasks.md G0 四项补勾 |
| **G1** | **交付（UNDECIDABLE-BY-DATA）** | 三机制全网格 550 点无一点同时满足 C1∧C2；C2 侧最高保留 **67.5%**（M1 `floor=0.98`，L=1.81%）、C1 侧最低泄漏 **3.26%**（M1 `floor=0.90`，R=94.2%）⇒ 关节不可达、两侧可达，待"流失-泄漏"交换率定夺 | 5 测试类 + 1 脚本 + 决策 JSON/MD |
| **G2** | **交付** | 七条断言全绿；门控顺序、"白名单是唯一灰度粒度"、"运行期热生效 / 改回 false 即停"三句话全部变成可复跑实证 | 1 IT + runbook §12 |
| **G3** | **交付（P3 的 build 项环境级红）** | 279 operations 全部携带权限维（259 SECURED + 20 OPEN）；契约 vs 扫描器两条独立路径集合相等；前端生成物零变化 | customizer + `OpenApiConfig` 注册 + 契约重导出 + parity 测试 + spec-delta + 解冻登记 |
| **G4** | 交付 | 全量门禁与逐组照判（见 §4） | 本报告 + tasks.md 补勾依据 |

## 2. 三组照判

### 2.1 G1 · 近重复机制决策（verdict = UNDECIDABLE-BY-DATA）

| 判据 | 门槛 | 实测 | 结果 |
| --- | --- | --- | --- |
| C1 同源保留率 | ≥ 0.90 | 基线 70/120 = **0.583333**；三机制最高 113/120 = 0.941667（M1 `floor=0.90`） | 满足 |
| C2 负样本泄漏率 | ≤ 0.02 | 基线 3/276 = **0.010870**；所有 `R≥0.90` 点的最小 L = 9/276 = **0.032609** | **不满足** |
| C3 逐 kind 单调 | crop-12 ≤ crop-04 ≤ scale-070 ≤ scale-085（jpeg 可与 scale-085 互换） | 基线**不满足**（0 ≤ 9 ≤ 22，但 scale-070 22 > scale-085 21）；`floor=0.9935` 不满足（jpeg 17 < scale-070/085 21）；`floor=0.90` 成立（24/24/24/24/17） | 逐点记录（锚 `outcome.satisfiesC3`） |
| C4 外呼次数 | = 0 | `externalCalls=0` | 满足 |
| C5 决策 | 三选一 | **UNDECIDABLE-BY-DATA** | 命中第 ③ 支（关节不可达、两侧可达） |
| C6 全网格披露 | 200 / 25 / 全组合 | 200 / 25 / 325 逐点入锚 | 满足 |

**帕累托前沿（锚 `pareto`，文档 §3 摘录）**：

- M1（8 点）：`0.9935 → R 0.533333 / L 0`；`0.9890 → 0.575 / 0.003623`；`0.9815 → 0.666667 / 0.014493`；`0.9800 → 0.675 / 0.018116`；`0.9785 → 0.691667 / 0.021739`；`0.9740 → 0.716667 / 0.025362`；`0.9655 → 0.775 / 0.028986`；`0.9000 → 0.941667 / 0.032609`。
- M2（网格 `D=0..24` 共 **25 点**，帕累托前沿 13 点；下表节选 3 点）：`D=1 → 0.316667 / 0.054348`；`D=22 → 0.900000 / 0.224638`；`D=24 → 0.933333 / 0.282609`。
- M3（12 点，节选）：`(0,5) → 0.650000 / 0.057971`；`(0,21) → 0.900000 / 0.170290`；`(0,24) → 0.941667 / 0.282609`。

**一句话**：在 C2≤2% 上限内保留率上限 = **67.5%**（M1 `floor=0.98`，L=1.81%，较基线 +9.2pp）；要摸到 C1≥90% 须把泄漏放宽到 **3.26%**（`floor=0.90`，R=94.2%，门槛的 1.63 倍）；两条边不可同时成立 ⇒ **C5 第 ③ 支 UNDECIDABLE-BY-DATA（关节不可达、两侧可达），待交换率定夺**。

**自证①**：144 个 same-image 对重算 dHash 距离 **全 0**、余弦回读 **全 1**（144/144/144）⇒ 语料可复现，两卡数字可比。
**自证②**：决策文档每个数字都标了表号或锚字段路径，零手算数字。

### 2.2 G2 · 图片四开关实证（七条断言，同一测试方法内）

| 编号 | 场景 | 期望 | 实测 |
| --- | --- | --- | --- |
| A1 | B=false + 白名单空 + A=true | embed 0 / vision 0 / 切片 0 / 集合 0 | 0/0/0/0 ✅ |
| A2 | B=true + 白名单不命中 + A=true | embed 0 / 集合 0 | 0/0 ✅ |
| A2b | B=true + 命中 + A=false | 切片 = 3 / embed 0 / 集合 0 | 3/0/0 ✅ |
| A3 | B=true + 命中 + A=true | embed **== 3** / 集合 **== 3** | 3/3 ✅ |
| F1 | 运行期写 true → 再跑 | 第二轮 > 0 | 0 → 3 ✅ |
| F2 | 写回 false → 再跑 | 回到 0 | 0 ✅ |
| N1 | 反向自检 | A3 > A1 | 3 > 0 ✅ |

**"未重启"真证到**：单测试方法顺序执行 7 轮，三个关键实例（`PdfImageSliceIngestService` / `DynamicConfigService` /
`DynamicConfigCache`）在方法开头捕获、结尾 `assertSame`（`ImageVectorSwitchHotFlipIT` L245-247），日志
`[G2] 未重启证据：service@1264380765 config@581100929 cache@2091447767（7 轮共用，首尾 assertSame 通过）`；且本 IT 无 Spring 上下文可重建。
**配置只能经生产服务写入**：全部走 `DynamicConfigAdminService#updateValue`（越权闸 → 类型校验 → 版本乐见锁 → 写历史 → 写后失效），测试**不直接改 map**。
**技术路线**：不依赖 Docker/H2 —— `buildSlices` 是生产方法本体，配置持久化用 **mock mapper 行表**（proposal §2.3 的 "mock 持久化"）；
两张配置表的 H2 兼容性由既有 `DynamicConfigSchemaH2Test` 单独覆盖，本卡未重复；未触发"降级真库"。
**零真外呼**：`ModelProvider` 是计数替身（`embedImage` 计数即断言对象），测试类 grep 无外呼痕迹。

### 2.3 G3 · 契约权限生成化（P1–P4）

| 判据 | 结果 |
| --- | --- |
| **P1** 每个 operation 恰有 (a)(b) 之一 | **0 例外**（279 = 259 SECURED + 20 OPEN） |
| **P2** 两条独立路径集合相等 | **相等**（契约反推 vs `PermissionCoverageScanner`+`OpenEndpointRegistry`，SECURED 259 / OPEN 20 全等） |
| **P3** 生成物 diff + 前端四项 | `pnpm gen:api` 生成物 **零变化**；`lint:check` ✅ / `type-check:check` ✅ / `test` ✅(213) / **`build` ❌ 环境级（master 同红）** |
| **P4** `src/main` 精确 2 文件 | ✅ `OpenApiConfig.java`（改 +12）+ `OpenApiPermissionCustomizer.java`（新增） |

- operation 总数 **279**｜SECURED **259**｜OPEN **20**（`[G3][P1]` / `[G3][P2]` 行）
- 契约：11028 → **11825** 行（+797 行全是 `x-` 扩展：259×3 + 20×1），`x-required-permission` 259 处、`x-permission-status` 20 处
- 归一化：只"去尾部 `/`" + **已登记的一项**（扫描器 `ANY` → springdoc 展开的 7 方法常量集，spec-delta §3 第 2 条）
- **口径偏差两处（不影响语义）**：① proposal §3.2 写 `getCode()`，实际枚举是 Lombok `@Getter` 的 **`getId()`**（契约字段名仍 `code`）；
  ② tasks.md 1.2.3 点名 `PerceptualHashSupportTest` 文件，但写入面并集未列该文件 → dHash 单测**并入** `NearDuplicateMechanismAnalyzerTest`（覆盖不减、不越面）。

## 3. `git diff --stat master` 逐组分账

| 组 | 文件 | 类型 |
| --- | --- | --- |
| **G1**（8） | `src/test/java/com/slz/crm/quality/{PerceptualHashSupport,NearDuplicateMechanismAnalyzer,NearDuplicateMechanismAnalyzerTest,NearDuplicateMechanismDecisionTest,NearDuplicateMechanismDecisionGuardTest}.java` | 新增 5 |
| | `scripts/rag-quality/near-dup-mechanism-report.sh` | 新增 |
| | `docs/rag-quality/near-dup-mechanism-decision-v1.json`（351520 B）、`…-v1.md` | 新增 2（JSON 为生成物） |
| **G2**（2） | `src/test/java/com/slz/crm/knowledge/document/ImageVectorSwitchHotFlipIT.java` | 新增 |
| | `docs/multimodal-gray-rollout.md` | 改（**+52 / −0**，只追加 §12） |
| **G3**（7） | `src/main/java/com/slz/crm/server/config/OpenApiPermissionCustomizer.java` | 新增 |
| | `src/main/java/com/slz/crm/server/config/OpenApiConfig.java` | 改（+12 / −0） |
| | `frontend/openapi.yaml` | 改（生成物，+797 / −0） |
| | `src/test/java/com/slz/crm/contract/OpenApiPermissionContractParityTest.java` | 新增 |
| | `src/test/java/com/slz/crm/contract/OpenApiContractExportTest.java` | 改（+18 / −0，只追加一条断言） |
| | `openspec/changes/…/specs/api-contract/spec-delta.md` | 新增 |
| | `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` | 改（+1 / −0） |
| **卡**（2） | `openspec/changes/retrieval-and-contract-hardening/{proposal.md,tasks.md,report.md}`、`work/task-card-…md` | 卡文件 |

**零 diff 复核（逐条实测为空）**：`pom.xml`、`src/main/resources/db/migration`、`scripts/test-baseline.txt`、
`docs/permission-matrix-audit.md`、`docs/dynamic-config-keys.md`、`frontend/typed-router.d.ts`、`frontend/src`、
`ImageSimilarityGate.java`、`NearDuplicateCorpus.java`、`RequirePermission.java`、`PermissionOperates.java`、
`src/test/java/com/slz/crm/integration/permission`、`DynamicConfigKeyRegistry.java`、`ConfigKeyTierPolicy.java`。

## 4. 门禁原始结论

命令按 task-card 的收尾单一序列执行；长输出落 `target/`：

| 门禁 | 结论 | 证据 |
| --- | --- | --- |
| `mvn -B -ntp test` | **BUILD SUCCESS**，`Tests run: 2076, Failures: 0, Errors: 0, Skipped: 0` | `target/g4-mvn-test.log` |
| 本卡新增进 surefire 的用例 | **17**（Analyzer 6 + Decision 1 + Guard 7 + Parity 2 + Export 追加 1） | 同上各行 |
| `mvn -B -ntp test -Dtest=ImageVectorSwitchHotFlipIT` | **实跑绿（非跳过）**：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, 2.146 s` | `target/g2-it.log` |
| `bash scripts/rag-quality/near-dup-mechanism-report.sh` | **退出码 0**，`verdict=UNDECIDABLE-BY-DATA`、`externalCalls=0`，五张表落盘 | `target/near-dup-mechanism-report.txt` |
| `bash scripts/check-openapi-consistency.sh` | **绿**：「前后端契约一致：frontend/openapi.yaml 与后端 swagger 导出逐字相同」 | 见下 §4.1 |
| `pnpm gen:api` | 生成物 **零变化**（`git status frontend/` 只有 `openapi.yaml`） | — |
| `pnpm lint:check` / `type-check:check` / `test` | **绿**（0 / 0 / 22 文件 213 用例全过） | — |
| `pnpm build` | **红（环境级，非本卡引入）**：`[vite:esbuild-transpile] remove …\Temp\esbuild-*: Access is denied`；master 纯净工作树同红 | `target/g3-build*.log`、`target/master-build2.log` |
| `bash scripts/merge-gate.sh` | **全绿，退出码 0**：`PASS [unit] / [checkstyle] / [spotbugs] / [spotless] / [pmd] / [baseline] / [frontend-unit] / [hook] / [bijection] / [pmd-baseline]` 十项全 PASS + 「== merge-gate 通过：所有已执行的子门禁绿 ==」 | `target/g4-merge-gate2.log`（首跑 3 项红，见 §4.2 修正记录） |
| `bash scripts/check-test-baseline.sh` | **通过**（只读，未 `--update`）：surefire 332 报告 / 2076 用例；failsafe 43 报告 / 144 用例 / skip 13 | 见 §4.1 |

### 4.1 契约一致性与回归基线（原始输出）

```
前后端契约一致：frontend/openapi.yaml 与后端 swagger 导出逐字相同。

surefire 测试：报告=332（默认口径），Tests run=2076，Failures=0，Errors=0，Skipped=0｜基线 reports>=328 tests>=2059 skipped<=0
failsafe 测试：报告=43（默认口径），Tests run=144，Failures=0，Errors=0，Skipped=13｜基线 reports>=43 tests>=144 skipped<=13
回归基线门禁通过（基线文件：scripts/test-baseline.txt）
```

### 4.2 首次 merge-gate 的三项红与修正（留痕）

首跑 `target/g4-merge-gate.log` 有 3 项红，全部由本卡自身可修项引起，已逐项修正后复跑：

1. `[pmd]` FAIL：`OpenApiPermissionCustomizer:11 Rule:CommentSize`（类 Javadoc 超 `maxLines=20`）→ **缩短类注释**（行为零变更）→ `mvn pmd:check` = 0 violations。
2. `[pmd-baseline]` FAIL：台账登记 0 条、实测 1 条（上面那条的连带）→ 随 1 修正 → `RESULT=PMD_BASELINE_OK（实测 0 == 登记 0 == pom 阈值 0）`（**未 --update、未改台账**）。
3. `[baseline]` FAIL：`surefire 口径外残留 1 份`——`ImageVectorSwitchHotFlipIT.txt`（正是门禁清单要求的 `mvn test -Dtest=ImageVectorSwitchHotFlipIT` 留下的）→ 删该 `.txt`/`.xml` 后重跑通过（脚本提示的标准处置；**未改 `scripts/test-baseline.txt`**）。

> **副作用留痕**：`merge-gate.sh`（以及 `pnpm build`）会经路由类型生成步骤把 `frontend/typed-router.d.ts` 写脏。本卡每次跑完都
> `git checkout -- frontend/typed-router.d.ts` 还原，终态零 diff；复核若复跑门禁，跑完请同样还原（该文件是他人在途改动，严禁提交）。

## 5. 硬边界自证

| 边界 | 实测 |
| --- | --- |
| 零真外呼 | G1/G2/G3 新增文件 grep `DASHSCOPE|System.getenv|RestTemplate|WebClient|HttpClient|HttpURLConnection|Socket` = **0 命中**（唯一大小写不敏感命中是 parity 测试里与既有 `OpenApiContractExportTest` **逐字相同**的占位属性 `spring.ai.dashscope.api-key=sk-openapi-contract-placeholder`，无凭据、无调用）；未跑任何 `RAG_*_REAL=1` |
| `pom.xml` / 迁移 / `scripts/test-baseline.txt` | 零 diff |
| 未开生产开关 / 未改默认值 | `PdfImageSliceIngestService`、`DynamicConfigKeyRegistry`、`ConfigKeyTierPolicy` 零 diff；runbook §12 明写本卡未开任何开关 |
| 权限语义零变更 | `RequirePermission` / `PermissionOperates` / `PermissionsInterceptor` / `OpenEndpointRegistry` / `PermissionCoverageScanner` 零 diff |
| 历史快照与前端源码 | `docs/permission-matrix-audit.md`、`docs/dynamic-config-keys.md`、`frontend/typed-router.d.ts`、`frontend/src/**` 零 diff（`build` 曾写脏 `typed-router.d.ts`，已 `git checkout --` 还原） |
| 写入面 | `git status --porcelain` 只有并集表内文件（§3） |

## 6. 未解决 / 需指导侧决策项

1. **`pnpm build` 环境级红（唯一非绿门禁）**：`vite:esbuild-transpile` 在删除 `%TEMP%\esbuild-*` 时报 `Access is denied`，本机**连续 3 次**复现（含把 `TMP/TEMP` 指向 `target/tmp`）；**master 纯净工作树（`5bb8b05` + `gen:api`）实测同红** ⇒ 与 G3 无关、与契约无关，改契约侧无法变绿，故**未按 proposal §5 回退 G3**。请复核/owner 裁定：接受"环境级红 + master 同红"证据，还是要求换机器/换 TEMP 卷复跑后再合并。
2. **`PerceptualHashSupportTest` 文件缺失（口径偏差）**：tasks.md 1.2.3 点名该文件，但 proposal §1.4 与 task-card 并集表都未列它。为不越写入面，dHash 四类断言并入 `NearDuplicateMechanismAnalyzerTest`。若 require 独立文件，请扩写入面后我再拆。
3. **`getCode()` vs `getId()`（口径偏差）**：proposal §3.2 写 `PermissionOperates#getCode()`，实际枚举只有 Lombok `@Getter` 的 `getId()`。契约字段名仍为 `code`（数字），语义一致。若需严格对齐文案，请裁定是改 proposal 措辞还是给枚举加 `getCode()` 别名（后者会动 `src/main` 语义面，本卡未做）。
4. **P2 的第二项归一化**（`ANY` → 7 方法常量集）：已按 tasks.md 3.4.4 登记进 spec-delta §3 并在本报告声明；实测只影响 `/permission/list`。若指导侧认为"归一化只允许去尾部 `/`"必须字面执行，则 P2 需改为"ANY 端点单列豁免表"——这会让判据变松，建议保持现状。
5. **G1 定夺后续**：第 ③ 支已量出两条边（C2 侧 67.5% @ `floor=0.98` / C1 侧 3.26% @ `floor=0.90`），定夺需"流失-泄漏"交换率与泄漏真实伤害（查询-相关性真值）；其余翻案数据（业务负样本口径 / crop-12 语义裁决 / 换 embedding 底座另立 B 档卡）见决策文档 §5。
