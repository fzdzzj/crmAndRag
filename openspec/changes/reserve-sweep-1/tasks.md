# 任务清单 · reserve-sweep-1

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。
> 档位：**B 档**（零迁移 / 零权限语义 / 零新常量 / 零冻结契约）；若 Owner 派发时另授权 S3 真跑则升 A 档。
> 顺序纪律：**S2 → S1 → S3 → S4**（S2 先落字面量类型，S1 才有收窄的类型可用）。

## 1. S2 权限字面量类型生成（生成物 + 校验单测）
- [x] 1.1 新增 `frontend/src/types/permission.gen.ts`（生成物，提交）：`PermissionName` = `PermissionOperates` 全部 **120** 个枚举名联合、`PermissionModuleName` = `PermissionModule` 全部 **10** 个展示名联合；顺序 = 枚举声明顺序；**只出 `type`，零运行时代码**（不占体积预算）
      补勾依据（2026-10-23 · 由测试生成：`mvn -B -ntp test -Dtest=PermissionTypeCatalogGenTest -Dpermission.types.update=true` → 输出「已重写 …permission.gen.ts」，Tests run: 2/0 失败；文件 143 行，`PermissionName` **120** 成员（`CUSTOMER_EXCEL_ADD` … `KNOWLEDGE_ADMIN_MANAGE`）、`PermissionModuleName` **10** 成员（`客户管理模块权限` … `其他模块权限`），仅 2 处 `export type`，无 const/function/import）
- [x] 1.2 新增校验测试（surefire，默认**校验**；`-Dpermission.types.update=true` 时**重写**生成物）：把枚举/模块的实际值与仓库文件逐字比对，不一致打印首个差异并失败；与定标锚「只允许由测试重写」同惯用法
      补勾依据（2026-10-23 · 类 `src/test/java/com/slz/crm/unit/permission/PermissionTypeCatalogGenTest.java`（2 个用例：逐字比对 + 零运行时代码）。默认 `mvn -B -ntp test -Dtest=PermissionTypeCatalogGenTest` → Tests run: 2, Failures: 0, BUILD SUCCESS；故意把文件里 `CUSTOMER_EXCEL_ADD` 改成 `CUSTOMER_EXCEL_ADDX` 后同命令 → Failures: 1，报「生成物与枚举不一致，首个差异在第 11 行：期望 … / 实际 …」；随后已还原）
- [x] 1.3 `frontend/src/types/permission.ts` 改为从生成物 re-export `PermissionName`；`SideBarNav.vue` / `components/common/Permission.vue` 保持既有调用方式但恢复字面量收窄；`pnpm type-check:check` 绿
      补勾依据（2026-10-23 · `types/permission.ts` re-export 生成物 + 新增 `QualifiedPermissionName = \`${string}:${PermissionName}\``，`PermissionNameArray = Array<PermissionName | QualifiedPermissionName>`（运行时 `permissions_name` 是 `module:NAME` 形态，故放行前缀、收窄枚举段）；`Permission.vue` 的 `allow` 走同一类型，`SideBarNav.vue` 的 810 常量改为 `const TOKEN_USAGE_VIEW: PermissionName`。`pnpm type-check:check`（vue-tsc app + config 两段）无输出、EXIT=0；全仓 60+ 处 `:allow="['module:NAME']"` 零改动即通过）

## 2. S1 授权页 UX（前端）
- [x] 2.1 `frontend/src/utils/permissionCatalog.ts` 增纯函数：`filterCategories(categories, { keyword, onlySelected, selectedIds })`（关键词按空格分词取交集，匹配中文描述 / 权限名 / ID；`onlySelected` 只留含已选权限的项）、`moduleSelectionCounts(categories, selectedIds)`（每模块 已选/总数）、`idsOfCategory(category)`（模块级全选/清空的 id 集合来源）；**不得改 `buildPermissionCategories` / `subPermissionsOf` 既有语义**
      补勾依据（2026-10-23 · 文件 51 → 187 行：`filterCategories(categories, options: PermissionFilterOptions): PermissionCategory[]`、`moduleSelectionCounts(categories, selectedIds: readonly number[]): ModuleSelectionCount[]`、`idsOfCategory(category | undefined): number[]`，另导出 `PermissionFilterOptions` / `ModuleSelectionCount`；`buildPermissionCategories` 与 `subPermissionsOf` 函数体逐字未动（`git diff` 中无这两段改动））
- [x] 2.2 vitest 覆盖 ≥8 例：分词交集 / 中文描述命中 / 权限名命中 / ID 命中 / 只看已选 / 模块计数 / 模块全选不影响他模块 / 孤儿子权限（`orphanSub`）可被搜索命中
      补勾依据（2026-10-23 · `pnpm vitest run src/utils/__tests__/permissionCatalog.test.ts` → Test Files 1 passed，**Tests 16 passed**（原 5 + 新增 11：无关键词原样返回、空格分词交集、中文描述命中、权限名命中、ID 命中且父作容器、模块名整块命中、只看已选、只看已选且空、无匹配、每模块计数、模块全选 id 集合互不影响），EXIT=0；merge-gate 的 `[frontend-unit]` 亦 22 文件 / 213 用例全通过）
- [x] 2.3 `EditPermissionModal.vue`：搜索框 + 「只看已选」开关 + 面板标题「模块名 已选/总数」+ 模块级「全选 / 清空」+ 无匹配空态提示；**提交链路（toAdd / toRemove / 批量提交）零改动**，勾父不联动勾子（维持现状语义）
      补勾依据（2026-10-23 · 306 → 406 行，diff 108 行新增：`AInputSearch` + `Checkbox(只看已选)` + 头部 `countOf(module).selected/total` + 「全选/清空」链接（`@click.stop` 不触发折叠）+ 三种空态文案；提交链路自证 `git diff -U0 master -- frontend/src/components/role/EditPermissionModal.vue` 中匹配 `const toAdd|const toRemove|Promise.allSettled|deletePermissions\(|addPermissions\(` 的行数 = **0**；无勾父带子逻辑）
- [x] 2.4 `CreateRoleModal.vue` 复用同一套纯函数（其「以既有角色为模板」逻辑保留，不重写交互语义）
      补勾依据（2026-10-23 · 原 34 行手写关键词过滤被替换为 `filterCategories(...).map(...)`，并复用 `moduleSelectionCounts` / `idsOfCategory`，加「只看已选」与模块级全选/清空（427 → 477 行）；`git diff master` 中 `applyTemplatePermissions|addPermissions\(|deleteRole\(` 命中 **0** 行 ⇒ 模板复制与创建+绑定+回滚链路零改动）
- [x] 2.5 `pnpm lint:check` + `pnpm type-check:check` + `pnpm build`
      补勾依据（2026-10-23 · `lint:check` EXIT=0（无输出）；`type-check:check` EXIT=0（vue-tsc app + config 无输出）；`pnpm build` **EXIT=1 —— 宿主 esbuild 已知错误**：日志 `[vite:esbuild-transpile] remove C:\Users\…\Temp\esbuild-…: Access is denied`，发生在「✓ 4151 modules transformed」之后、rendering chunks 阶段；独立复现一次仍同错，另把 TMP/TEMP 改指 `frontend/.tmp-build` 再跑仍同错（错误路径随之变成 `.tmp-build\esbuild-…`）⇒ 与本次改动无关的宿主清理权限问题，按 `docs/active-handoff.md:59` 由 CI 的 `frontend-quality`/build job 定案（本地不具备定案条件））

## 3. S3 近重复定标管线（**权重与锚零改动**）
- [x] 3.1 新增 `scripts/rag-quality/near-duplicate-band-stats.mjs`（node，零依赖）：读 `docs/rag-quality/image-threshold-calibration-v1.json` → 用与 `ImageSimilarityGate` 相同的 Δ/边界公式（`low = floor + Δ/3`、`high = floor + 2Δ/3`）落带 → 输出各带样本数、分位、跨带极值；**必须复现 v1 文档数字 `same 18 / high 0 / mid 0 / low 1 / cross 71`**，不一致即非零退出并打印对比
      补勾依据（2026-10-23 · `node scripts/rag-quality/near-duplicate-band-stats.mjs` → 90 对；落带 `SAME_IMAGE 18 / NEAR_HIGH 0 / NEAR_MID 0 / NEAR_LOW 1 / CROSS_IMAGE 71`；按语料 programmatic 9/0/0/0/36、real-pdf 9/0/0/1/35；分位 CROSS n=71 min 0.0909 / median 0.5249 / max 0.9779；跨带极值 floor 以下最高 0.9778706021580416（table-1↔table-2）、NEAR_LOW 唯一 0.9887359400788237；尾行 `结果：复现 v1 落带 18/0/0/1/71 ✓（EXIT=0）`，EXIT=0；不一致时 `process.exit(1)` 并逐数打印实际≠基线）
- [x] 3.2 新增 `docs/rag-quality/image-near-duplicate-sampling-v2.md`：扩样操作单 —— 目标（每带 ≥30）、语料类型（同图变体：缩放 / 裁剪 / 重压缩 / 同页重复扫描；异图对照：跨出版物版式图）、重跑前置（`RAG_VISION_EMBED_REAL=1` + key + `ImageThresholdCalibrationIT` 是锚唯一写入口）、成本估算口径（复用 `docs/rag-quality/production-gray-cost-v1.json`）、产出落盘与权重改动判定门槛
      补勾依据（2026-10-23 · 新增 107 行、8 节：§2 每带 ≥30 对门槛（不足则维持现值）、§3 四类同图变体 + 两类异图对照与 ≈120 张图折算、§4 `RAG_VISION_EMBED_REAL=1`+key 与 `test-compile failsafe:integration-test failsafe:verify -Dit.test=ImageThresholdCalibrationIT -Drag.image.threshold.out=…v2.json`（不覆盖 v1 锚）、§5 成本=图数×2 次视觉 embedding 调用（复用 production-gray-cost 的口径）、§6 产出落盘、§7 权重改动判定门槛、§8 本卡未真跑）
- [x] 3.3 硬边界自证：`git diff master -- src/main/java/com/slz/crm/knowledge/retrieval/ImageSimilarityGate.java docs/rag-quality/image-threshold-calibration-v1.json` **零输出**；`docs/rag-quality/image-near-duplicate-bands-v1.md` 不改（历史结论）
      补勾依据（2026-10-23 · `git diff master -- src/main/java/com/slz/crm/knowledge/retrieval/ImageSimilarityGate.java` → 空；`git diff master -- docs/rag-quality/image-threshold-calibration-v1.json` → 空；`git diff master -- docs/rag-quality/image-near-duplicate-bands-v1.md` → 空。三条命令均无任何输出行）

## 4. S4 生产开关核对单（文档，不打开开关）
- [x] 4.1 `docs/multimodal-gray-rollout.md` 增三节：**开前核对**（`rag.retrieval.image-vector-ingest.enabled` 当前值读取方式、灰度面 = 摄取侧 vs 检索侧、成本基线链接）、**开后可观测**（该链路可看的指标 / 日志锚点）、**回滚**（改回 `false` 后已入库图片向量的处置与判断依据）
      补勾依据（2026-10-23 · 88 → 168 行，新增 §9「开前核对」/§10「开后可观测」/§11「回滚」三节：§9.1 给出 `GET /platform/config/items/{key}`、`GET /platform/config/items?namespace=rag.retrieval` 与 `dynamic_config_item` 只读 SQL 三种读数方式 + `is_deleted=1 ⇒ 回落静态默认` 判定；§9.2 四键作用面表并点明「只开 image-vector 而方案 B 总开关 false ⇒ 链路完全不动」；§9.3 成本基线链接；§10 指标/日志/`knowledge_image` 集合/`imageSimilarityBand` 锚点判读；§11 回滚顺序 + 已入库向量按 KB 重建清理、禁止 drop 整集合、回滚验收与禁止项）
- [x] 4.2 自证「未打开开关、默认值未动」：`git diff master -- src/main/java/com/slz/crm/platform/config/DynamicConfigKeyRegistry.java src/main/java/com/slz/crm/platform/config/ConfigKeyTierPolicy.java` 零输出；文档中显式写明「本卡不打开、默认值不动、打开属 Owner 运行期决定」
      补勾依据（2026-10-23 · `git diff master -- …/DynamicConfigKeyRegistry.java …/ConfigKeyTierPolicy.java` → 空（零输出行）；文档 §9 开头明写「**本卡不打开任何开关、不改任何默认值**；打开动作属 **Owner 运行期决定**」，§11 第 5 条明写「禁止改代码、改默认值、回滚迁移」；全程未执行任何配置写入/开关操作）

## 5. 验证与收口
- [x] 5.1 `mvn -B -ntp clean verify` BUILD SUCCESS + `bash scripts/check-test-baseline.sh --update` + `bash scripts/check-test-baseline.sh` EXIT=0 + `bash scripts/merge-gate.sh` 全绿 + `bash scripts/check-openapi-consistency.sh` EXIT=0（本卡不动契约，应零 diff）
      补勾依据（2026-10-23 一次跑完：`mvn -B -ntp clean verify` → BUILD SUCCESS，EXIT=0；`bash scripts/check-test-baseline.sh --update` → EXIT=0，写入 `surefire.reports=325 tests=2032 skipped=0`、`failsafe.reports=42 tests=143 skipped=12`（原 324/2030，+1 报告 +2 用例 = 新增校验测试）；`bash scripts/check-test-baseline.sh` → EXIT=0，输出「回归基线门禁通过」；`bash scripts/merge-gate.sh` → EXIT=0，逐项 PASS：[unit] [checkstyle] [spotbugs] [spotless] [pmd] [it]（failsafe --with-verify）[baseline] [frontend-unit]（vitest 22 文件/213 用例）[hook] [spotbugs-staleness] [pmd-baseline]；`bash scripts/check-openapi-consistency.sh` → EXIT=0，输出「frontend/openapi.yaml 与生成 swagger 一致」；`git diff master -- frontend/openapi.yaml` → 空）
- [x] 5.2 停步未 merge 未 push，回报分支名 / commit 列表 / 门禁尾行 / **S3 是否真跑（默认否）** / 权重·锚·开关三类零改动的自证书 / 未解决项
      补勾依据（2026-10-23 · 分支 `feature/reserve-sweep-1`；commit：`1e14a52 docs(openspec): add reserve-sweep-1 card` + 本卡实现 commit；未 merge、未 push、未删分支；**S3 未真跑**：未设 `RAG_VISION_EMBED_REAL`、零外呼、零新样本；四类零改动自证 = ImageSimilarityGate 权重常量 / 定标锚 JSON / bands-v1 文档 / DynamicConfigKeyRegistry+ConfigKeyTierPolicy 四条 `git diff master` 全为空输出；未解决项 1 项：`pnpm build` 宿主 esbuild 临时目录清理 `Access is denied`（已独立复现 2 次，CI 定案））
