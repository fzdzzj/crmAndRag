# 变更提案 · reserve-sweep-1（储备四项合一：授权页 UX + 权限类型生成 + 近重复定标清单 + 开关核对单）

> 复核档位自 declare：**B 档**（判据：前端交互 + 生成物 + 文档，零迁移 / 零权限语义变更 / 零新常量 / 零冻结契约）。
> **例外升档**：若 Owner 派发时**同时**授权 S3 真跑（外呼视觉 embedding），本卡升 **A 档**（判据「真跑」），复核走独立四判据。

## 1. 为什么四项能合成一张卡

Owner 要求「1234 合为一张」。四项确实共享同一批交付面（前端权限域 + 图像质量文档），合并后**一次评审、一次门禁、一次合并**，省掉三张卡的开销。但四项性质不同，卡内按「可完成度」排序并**明确硬边界**，避免一张卡被一个外部依赖拖死：

| # | 储备项 | 性质 | 本卡边界 |
|---|---|---|---|
| S1 | 授权页 UX | 纯前端 | 完整交付 |
| S2 | 权限字面量类型生成 | 前后端生成物 | 完整交付 |
| S3 | 近重复权重定标 | **真跑 + 新语料 + 外呼成本** | 交付**统计管线 + 操作单 + 缺口报告**；**权重不改**，真跑默认不做 |
| S4 | 生产开关运行期核对 | Owner 执行，非代码 | 交付**核对/回滚清单**；开关**不打开** |

## 2. 现状锚点（已实测）

- **授权页现状**（`frontend/src/components/role/EditPermissionModal.vue`，306 行）：已接入 `useQueryAllPermissions()` + `buildPermissionCategories()`（`utils/permissionCatalog.ts:24`），10 模块全展开、无搜索、无「只看已选」、无模块级全选、面板标题不显示已选计数 —— 120 条 / 10 面板下的定位成本高。
- **类型现状**（`frontend/src/types/permission.ts`）：本卡前一卡把 `PermissionName` 由「冻结快照推导」放宽为 `string`；`PermissionOperates` 现有 **120** 个常量，是字面量类型的唯一权威。
- **定标现状**：`ImageThresholdCalibrationIT` 是锚 JSON `docs/rag-quality/image-threshold-calibration-v1.json` 的唯一写入口（`ImageThresholdCalibrator:212` 装配），受 `RAG_VISION_EMBED_REAL=1` + key 控制；近重复三档在 90 对上落带 = `same 18 / high 0 / mid 0 / low 1 / cross 71`（`docs/rag-quality/image-near-duplicate-bands-v1.md` §3）⇒ `NEAR_HIGH/MID` **零样本**，0.75/0.6 未校准。
- **开关现状**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`（`DynamicConfigKeyRegistry:343`、`ConfigKeyTierPolicy:87`、`PdfImageSliceIngestService:64` 消费），灰度口径见 `docs/multimodal-gray-rollout.md`，键位档见 `docs/dynamic-config-keys.md`。

## 3. 交付（四阶段）

**S1 授权页 UX（前端）**
- 搜索：按「中文描述 / 权限名 / ID」过滤，空格分词取交集；命中高亮不做（保持简单）。
- 「只看已选」开关；模块面板标题显示「模块名 已选/总数」；模块级「全选 / 清空」。
- 无匹配空态；提交链路（toAdd / toRemove）语义**零改动**。
- 全部过滤/计数/批量选择逻辑抽成 `utils/permissionCatalog.ts` 纯函数，vitest 覆盖。

**S2 权限类型生成（生成物 + 单测，不改 CI yml）**
- 新增 `frontend/src/types/permission.gen.ts`：由 `PermissionOperates` 生成 `PermissionName`（120 个字面量）与 `PermissionModuleName`（10 个字面量）—— **只出类型，零运行时代码**（不占体积预算）。
- 生成器 = 一个 surefire 测试 `PermissionTypeCatalogGenTest`：默认**校验**仓库里的 `permission.gen.ts` 与枚举逐字一致（不一致即红）；`-Dpermission.types.update=true` 时**重写**该文件（与定标锚「只允许由测试重写」同惯用法）。
- `types/permission.ts` 恢复由生成物 re-export `PermissionName`，`SideBarNav.vue` / `Permission.vue` 的权限名改用字面量口径。

**S3 近重复定标（默认只出清单，权重不动）**
- 新增可复跑统计命令（脚本读锚 JSON 输出各带样本数与分位），把 `image-near-duplicate-bands-v1.md` §3 的统计**脚本化**，避免下次手算。
- 新增 `docs/rag-quality/image-near-duplicate-sampling-v2.md`：**扩样操作单** —— 需要哪类语料（同图变体：缩放 / 裁剪 / 重压缩 / 同页重复扫描）、每带 ≥30 的目标、如何用既有 `RAG_VISION_EMBED_REAL=1` 通道重跑、成本估算口径。
- **硬边界**：不得修改任何权重常量，不得改锚 JSON；真跑需 Owner 另行授权。

**S4 生产开关核对单（文档）**
- `docs/multimodal-gray-rollout.md` 增「开前核对 / 开后可观测 / 回滚」三节：核对键当前值（配置中心只读查询 / DB）、灰度面（摄取侧 vs 检索侧）、回滚 = 改回 `false` 后需清理已入库图片向量（含影响范围与判断依据）。
- **硬边界**：不执行任何开关写入，不改默认值。

## 4. 非目标

- 不做权限树拖拽排序、不做虚拟滚动（120 条尚不需要）、不做跨模块批量。
- 不新增后端端点、不改 `/permission/list` 契约、不改 `PermissionModule`。
- 不改权重常量、不改锚 JSON、不跑真跑（除非 Owner 单独授权）、不打开任何生产开关。
- 零迁移、零新权限常量、零新配置键、零新依赖、零契约解冻。

## 5. 风险与取舍

| 风险 | 处置 |
|---|---|
| 四合一卡被 S3 外部依赖拖死 | S3 默认只出「管线 + 操作单 + 缺口」；权重要动必须重跑真跑并单独授权 |
| 「只看已选」与父权限父子联动冲突 | 不引入联动语义（现状也没有）：勾父不自动勾子，保持既有提交语义 |
| 生成物与枚举漂移 | 校验用 surefire 测试（非生成脚本）→ 忘跑生成就让 `mvn test` 红 |
| `permission.gen.ts` 撑大前端体积 | 只出 `type`，零运行时代码；以 `frontend/bundle-budget.json` 门禁兜底 |
| S4 文档写成「可开」暗示 | 明确写：本卡不打开、默认值不动、打开属 Owner 运行期决定 |

## 6. 验收

`tasks.md` 逐项；门禁 = `mvn -B -ntp clean verify` + 基线 + `merge-gate.sh` + 前端 `lint:check` / `type-check:check` / `build` + `check-openapi-consistency.sh` EXIT=0（本卡不动契约，应零 diff）。
