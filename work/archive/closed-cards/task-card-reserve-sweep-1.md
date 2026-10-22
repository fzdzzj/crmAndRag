# 任务卡 · reserve-sweep-1（储备四项合一）

## 目标（一句话）
一张卡收掉四项长期储备：**S1** 授权页 120 条目录的搜索/只看已选/模块全选、**S2** 权限字面量类型由枚举生成（恢复编译期收窄）、**S3** 近重复权重定标管线与扩样操作单（**不改权重**）、**S4** 生产开关开/回滚核对单（**不打开开关**）。

## 现状锚点（已实测，勿再猜）
- `EditPermissionModal.vue`（306 行）已接 `useQueryAllPermissions()` + `buildPermissionCategories()`；10 模块全展开，**无搜索 / 无只看已选 / 无模块全选 / 标题无已选计数**。`CreateRoleModal.vue` 同源改造过，交互自有（含「以既有角色为模板」）。
- `frontend/src/utils/permissionCatalog.ts`（51 行）现有 `buildPermissionCategories` / `subPermissionsOf`，带 `orphanSub` 兜底（父未匹配的子权限不丢）。
- `types/permission.ts` 的 `PermissionName` 现为 `string`（前一卡为去 fixture 依赖而放宽）；权威 = `PermissionOperates` **120** 常量 + `PermissionModule` **10** 展示名。
- 定标锚 `docs/rag-quality/image-threshold-calibration-v1.json` 的唯一写入口 = `ImageThresholdCalibrationIT`（`ImageThresholdCalibrator:212` 装配），受 `RAG_VISION_EMBED_REAL=1` + key 控制；v1 落带 = `same 18 / high 0 / mid 0 / low 1 / cross 71`（`image-near-duplicate-bands-v1.md` §3）。
- 开关 `rag.retrieval.image-vector-ingest.enabled` 默认 `false`（`DynamicConfigKeyRegistry:343`、`ConfigKeyTierPolicy:87`、`PdfImageSliceIngestService:64` 消费）。
- 迁移号 **零变更**（现行最高 V43）。

## 硬约束（违反即退回）
1. **四类零改动**：`ImageSimilarityGate` 权重常量、定标锚 JSON、`image-near-duplicate-bands-v1.md`、`DynamicConfigKeyRegistry` / `ConfigKeyTierPolicy` 默认值 —— 每类都要交 `git diff master -- <文件>` 的空输出作自证。
2. **不跑真跑、不开开关**（除非 Owner 派发时另行授权）；S3 只出管线 + 操作单。
3. `permission.gen.ts` **只出 `type`**，零运行时代码；校验必须默认执行（忘跑生成就让 `mvn test` 红），重写只由 `-Dpermission.types.update=true` 触发。
4. 授权页**提交语义零改动**：`toAdd` / `toRemove` 计算与批量提交逻辑不动；不引入「勾父自动勾子」。
5. 零迁移、零新权限常量、零新配置键、零新依赖、零契约解冻；`frontend/openapi.yaml` 应零 diff。
6. **严禁在观察树（`D:\code\crmAndRag`）产生任何文件**；生成物 `typed-router.d.ts` / `dist` / `.tmp-build` 不提交。

## 交付面（写集）
- 前端：`src/types/permission.gen.ts`（新）、`src/types/permission.ts`、`src/utils/permissionCatalog.ts`、`src/utils/__tests__/permissionCatalog.test.ts`、`src/components/role/EditPermissionModal.vue`、`src/components/role/CreateRoleModal.vue`、`src/layout/components/SideBarNav.vue`、`src/components/common/Permission.vue`
- 后端测试：权限类型生成校验测试（新，surefire）
- 脚本/文档：`scripts/rag-quality/near-duplicate-band-stats.mjs`（新）、`docs/rag-quality/image-near-duplicate-sampling-v2.md`（新）、`docs/multimodal-gray-rollout.md`（增三节）
- openspec：`openspec/changes/reserve-sweep-1/`（已由主 Agent 落盘，首个 commit 提交）

## 验收（自证）
- `mvn -B -ntp clean verify` BUILD SUCCESS；`check-test-baseline.sh --update` + `check-test-baseline.sh` EXIT=0；`merge-gate.sh` 全绿；`check-openapi-consistency.sh` EXIT=0。
- 前端：`lint:check` / `type-check:check` / `build` 全绿；vitest ≥8 例新增。
- 统计脚本必须复现 v1 落带数字 `18/0/0/1/71`。

## 停步条件
跑绿即停步：**不 merge、不 push、不删分支**，回报分支名 / commit 列表 / 门禁尾行 / S3 真跑与否 / 四类零改动自证 / 未解决项。
