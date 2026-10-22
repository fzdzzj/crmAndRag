# 任务清单 · reserve-sweep-1

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。
> 档位：**B 档**（零迁移 / 零权限语义 / 零新常量 / 零冻结契约）；若 Owner 派发时另授权 S3 真跑则升 A 档。
> 顺序纪律：**S2 → S1 → S3 → S4**（S2 先落字面量类型，S1 才有收窄的类型可用）。

## 1. S2 权限字面量类型生成（生成物 + 校验单测）
- [ ] 1.1 新增 `frontend/src/types/permission.gen.ts`（生成物，提交）：`PermissionName` = `PermissionOperates` 全部 **120** 个枚举名联合、`PermissionModuleName` = `PermissionModule` 全部 **10** 个展示名联合；顺序 = 枚举声明顺序；**只出 `type`，零运行时代码**（不占体积预算）
      补勾依据（填写：两条联合各多少个成员、文件行数）
- [ ] 1.2 新增校验测试（surefire，默认**校验**；`-Dpermission.types.update=true` 时**重写**生成物）：把枚举/模块的实际值与仓库文件逐字比对，不一致打印首个差异并失败；与定标锚「只允许由测试重写」同惯用法
      补勾依据（填写：测试类路径 + 默认绿 + 故意改一个字后变红的实测）
- [ ] 1.3 `frontend/src/types/permission.ts` 改为从生成物 re-export `PermissionName`；`SideBarNav.vue` / `components/common/Permission.vue` 保持既有调用方式但恢复字面量收窄；`pnpm type-check:check` 绿
      补勾依据（填写：type-check 尾行）

## 2. S1 授权页 UX（前端）
- [ ] 2.1 `frontend/src/utils/permissionCatalog.ts` 增纯函数：`filterCategories(categories, { keyword, onlySelected, selectedIds })`（关键词按空格分词取交集，匹配中文描述 / 权限名 / ID；`onlySelected` 只留含已选权限的项）、`moduleSelectionCounts(categories, selectedIds)`（每模块 已选/总数）、`idsOfCategory(category)`（模块级全选/清空的 id 集合来源）；**不得改 `buildPermissionCategories` / `subPermissionsOf` 既有语义**
      补勾依据（填写：新增函数签名 + 行数）
- [ ] 2.2 vitest 覆盖 ≥8 例：分词交集 / 中文描述命中 / 权限名命中 / ID 命中 / 只看已选 / 模块计数 / 模块全选不影响他模块 / 孤儿子权限（`orphanSub`）可被搜索命中
      补勾依据（填写：用例数与尾行）
- [ ] 2.3 `EditPermissionModal.vue`：搜索框 + 「只看已选」开关 + 面板标题「模块名 已选/总数」+ 模块级「全选 / 清空」+ 无匹配空态提示；**提交链路（toAdd / toRemove / 批量提交）零改动**，勾父不联动勾子（维持现状语义）
      补勾依据（填写：改动行数 + 提交链路零改动的 diff 证据）
- [ ] 2.4 `CreateRoleModal.vue` 复用同一套纯函数（其「以既有角色为模板」逻辑保留，不重写交互语义）
      补勾依据（填写）
- [ ] 2.5 `pnpm lint:check` + `pnpm type-check:check` + `pnpm build`
      补勾依据（填写：三条尾行；若 `pnpm build` 撞宿主 esbuild 已知错误，独立复现一次后按 CI 定案）

## 3. S3 近重复定标管线（**权重与锚零改动**）
- [ ] 3.1 新增 `scripts/rag-quality/near-duplicate-band-stats.mjs`（node，零依赖）：读 `docs/rag-quality/image-threshold-calibration-v1.json` → 用与 `ImageSimilarityGate` 相同的 Δ/边界公式（`low = floor + Δ/3`、`high = floor + 2Δ/3`）落带 → 输出各带样本数、分位、跨带极值；**必须复现 v1 文档数字 `same 18 / high 0 / mid 0 / low 1 / cross 71`**，不一致即非零退出并打印对比
      补勾依据（填写：脚本输出全文尾行 + 与 v1 文档逐数一致）
- [ ] 3.2 新增 `docs/rag-quality/image-near-duplicate-sampling-v2.md`：扩样操作单 —— 目标（每带 ≥30）、语料类型（同图变体：缩放 / 裁剪 / 重压缩 / 同页重复扫描；异图对照：跨出版物版式图）、重跑前置（`RAG_VISION_EMBED_REAL=1` + key + `ImageThresholdCalibrationIT` 是锚唯一写入口）、成本估算口径（复用 `docs/rag-quality/production-gray-cost-v1.json`）、产出落盘与权重改动判定门槛
      补勾依据（填写）
- [ ] 3.3 硬边界自证：`git diff master -- src/main/java/com/slz/crm/knowledge/retrieval/ImageSimilarityGate.java docs/rag-quality/image-threshold-calibration-v1.json` **零输出**；`docs/rag-quality/image-near-duplicate-bands-v1.md` 不改（历史结论）
      补勾依据（必须附命令与空输出）

## 4. S4 生产开关核对单（文档，不打开开关）
- [ ] 4.1 `docs/multimodal-gray-rollout.md` 增三节：**开前核对**（`rag.retrieval.image-vector-ingest.enabled` 当前值读取方式、灰度面 = 摄取侧 vs 检索侧、成本基线链接）、**开后可观测**（该链路可看的指标 / 日志锚点）、**回滚**（改回 `false` 后已入库图片向量的处置与判断依据）
      补勾依据（填写：新增小节标题与行数）
- [ ] 4.2 自证「未打开开关、默认值未动」：`git diff master -- src/main/java/com/slz/crm/platform/config/DynamicConfigKeyRegistry.java src/main/java/com/slz/crm/platform/config/ConfigKeyTierPolicy.java` 零输出；文档中显式写明「本卡不打开、默认值不动、打开属 Owner 运行期决定」
      补勾依据（填写）

## 5. 验证与收口
- [ ] 5.1 `mvn -B -ntp clean verify` BUILD SUCCESS + `bash scripts/check-test-baseline.sh --update` + `bash scripts/check-test-baseline.sh` EXIT=0 + `bash scripts/merge-gate.sh` 全绿 + `bash scripts/check-openapi-consistency.sh` EXIT=0（本卡不动契约，应零 diff）
      补勾依据（填写：surefire / failsafe 计数、基线写入值、各门禁尾行）
- [ ] 5.2 停步未 merge 未 push，回报分支名 / commit 列表 / 门禁尾行 / **S3 是否真跑（默认否）** / 权重·锚·开关三类零改动的自证书 / 未解决项
      补勾依据（填写）
