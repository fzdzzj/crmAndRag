# 任务清单 · token-usage-admin-view

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 权限与迁移
- [x] 1.1 `PermissionOperates` 增 `AI_TOKEN_USAGE_VIEW(810L, "查看AI用量")`（AI 段 800–899，810 已实测为下一个可用号）
      补勾依据（2026-10-22 落笔前 `grep -rn "810L|AI_TOKEN_USAGE" src` 零命中；改后 `mvn -B -ntp clean verify` 编译含该枚举）
- [x] 1.2 `V43__ai_token_usage_permission_seed.sql`：`INSERT IGNORE` 权限行 810 + **仅授 roleId=1 一行**（超管角色，供前端 `getMyPermissionGrouped` 数据面；业务角色零授权）；文件头写目的/影响表/回滚注意（回退 = 快照，不提供 DROP；回退需同时清理 810/role 1 行）
      补勾依据（2026-10-22 B1 修正后重跑 `mvn -B -ntp clean verify`：`FlywayMigrationIT.aiTokenUsagePermissionSeededForSuperAdminOnly` 断言 810 仅 role_id=1 一行、业务角色零授权；`PermissionControllerIT.adminMyPermissionContainsTokenUsageView` 断言超管 getMyPermission 含 810）
- [x] 1.3 `PermissionCoverageScanner.CONTROLLER_REGISTRY` 登记新 controller（漏登记 `PermissionCoverageAuditIT` 直接红）
      补勾依据（2026-10-22 verify 中 `PermissionCoverageAuditIT` 绿；登记表 34+2+1=37，扫描数=登记数）

## 2. 后端只读查询
- [x] 2.1 `PlatformTokenUsageMapper` 增按用户聚合查询：`WHERE create_time >= start AND create_time < end`（可选 `usage_type`）+ `GROUP BY user_id_ref`，返回 prompt/completion/total 求和、`COUNT(*)`、`SUM(success = 0)`、`MAX(create_time)`，按 total 降序
      补勾依据（2026-10-22 verify 绿；`AiTokenUsageControllerIT.byUserAggregatesWithinHalfOpenWindow` 断言 902=300/901=160/system=15/999=10/unknown=2 与排序）
- [x] 2.2 新 VO / DTO：分页结果 + 用户维度还原（剥 `user:` 前缀；`system`→系统、`unknown`→未知来源、查不到→已删除用户(#id)）+ 占比 `sharePct`
      补勾依据（2026-10-22 单测 `AiTokenUsageControllerTest.byUserRestoresDisplayDimensionsAndShares` 5 绿；IT 断言「已删除用户(#999)」「未知来源」「系统」与 sharePct 61.6）
- [x] 2.3 新 `AiTokenUsageController`：`GET /ai/token-usage/by-user`（startTime/endTime 缺省近 30 天、上限 366 天；usageType 可空；pageNum≥1；pageSize 1..200）与 `GET /ai/token-usage/summary`（总 token / 总调用 / 按 usage_type 分组）；每端点挂 `@RequirePermission(AI_TOKEN_USAGE_VIEW)`
      补勾依据（2026-10-22 verify 绿；OpenAPI 契约导出含两路径，`frontend/openapi.yaml` 同步 +175 行）
- [x] 2.4 参数校验：区间倒置、未来时间、pageSize 越界 → 明确业务码与文案（复用既有校验风格，不新增错误码枚举项除非确无可用者）
      补勾依据（2026-10-22 `TokenUsageQueryTest` 8 绿 + IT `rejectsInvalidQueryParameters` 5 组 → 96007；复用 `PlatformErrorCode.VALIDATION`）
- [x] 2.5 真 MySQL IT：种 2 个真实用户 + `user:system` + `user:unknown` 明细 → 断言聚合值 / 排序 / 分页 / 区间边界（`< end` 不含端点）/ usageType 过滤
      补勾依据（2026-10-22 `mvn -B -ntp clean verify` failsafe 142 tests 0 failures；`AiTokenUsageControllerIT` 7 用例全绿，起始行含/终点行不含/起点前 1 秒不含均有断言）
- [x] 2.6 权限 IT：无 810 的普通登录用户 → 12002 拒绝；授 810 的角色 → 200 且数据结构正确
      补勾依据（2026-10-22 同 IT：`rejectsUserWithoutPermission` code 12002、`allowsRoleWithPermission` code 1）

## 3. 前端「AI 用量」页
- [x] 3.1 新页 `frontend/src/pages/(dashboard)/token-usage/`（时间区间选择 + 总览卡片 + 用户表格 + 分页），复用仓内既有表格/日期组件与 `useQuery*` 封装风格
      补勾依据（2026-10-22 `(dashboard)/token-usage/index.page.vue`；ant-design-vue Card/DatePicker/Table/Statistic 复用，hooks 走 `@tanstack/vue-query`）
- [x] 3.2 `frontend/src/constants/pathTitleMap.ts` 增 `token-usage` 标题；`frontend/src/layout/components/SideBarNav.vue` 按现有 `menuPermissionPrefixes` / 特殊可见性约定加条目（准则：持有 810 才可见）
      补勾依据（2026-10-22 pathTitleMap 增「AI 用量」；SideBarNav 特判 `AI_TOKEN_USAGE_VIEW`（兼容 `*:AI_TOKEN_USAGE_VIEW`），语义与 privilege/org 特判同档）
- [x] 3.3 页面显式注记：`embedding` / 视觉联合 `embedding` 端点不返 usage、token 恒 0，故必须看调用次数（链接 `docs/token-metering.md` §6）
      补勾依据（2026-10-22 页面 Alert 文案 + §1/§6 出处；调用次数与失败次数并列为表格列与头部卡片）
- [x] 3.4 hooks/接口层：新增 `frontend/src/hooks/useTokenUsage.ts`（查询封装）+ 类型定义，不引新依赖
      补勾依据（2026-10-22 hook 用既有 `axiosInstance`（openapi SDK 为生成物、本卡不重生成）；零依赖变更，`pnpm lint:check` + `pnpm type-check:check` 绿）
- [ ] 3.5 `pnpm lint:check` + `pnpm type-check:check` + `pnpm build` 全绿；`typed-router.d.ts` 等生成物不提交
      补勾依据（2026-10-22：lint:check 与 type-check:check 绿；**`pnpm build` 未绿** ——
      宿主环境 esbuild 删除临时文件被拒：`[vite:esbuild-transpile] remove C:\...\Temp\esbuild-<hex>: Access is denied`，
      3 次重试（默认 TEMP / 工作区 TEMP / 输出重定向）均同错；已将输出重定向与最小 `esbuild.transform` 探针排除代码因素。
      等价验证：`pnpm exec vite build --minify false --target esnext`（跳过同插件）`✓ built in 26.69s`，说明打包图无代码错误。
      生成物 `frontend/typed-router.d.ts` 已 `git checkout --` 还原，未提交）
- [x] 3.5 附：`frontend/openapi.yaml` 由 `scripts/check-openapi-consistency.sh` 的导出机制同步（新增 175 行，纯增量），否则 `OpenApiContractExportTest` 必红
      补勾依据（2026-10-22 `Compare-Object` 仅 175 行 `=>` 新增、0 删除；未跑 `pnpm gen:api`，避免提交 SDK 生成物）

## 4. 文档
- [x] 4.1 `docs/token-metering.md` 增一节「超管按用户查看（810）」：接口口径、时间区间与分页上限、零 token 的正确读法
      补勾依据（2026-10-22 `docs/token-metering.md` §7 新增，含端点/上限/权限/用户维度还原/0 token 读法）
- [ ] 4.2 权限清单类文档若含 8xx 全量枚举（`docs/permission-matrix-audit.md` 等）同步补 810 一行；无枚举则不动
      补勾依据（2026-10-22 未动：`docs/permission-matrix-audit.md` 的 800-807 枚举位于历史审计报告段落，且不在本卡写集；
      留待 owner 决定是否追加 810 一行，避免改写历史审计快照）

## 5. 验证与收口
- [x] 5.1 `mvn -B -ntp clean verify` BUILD SUCCESS + `bash scripts/check-test-baseline.sh --update` + `bash scripts/check-test-baseline.sh` EXIT=0 + `bash scripts/merge-gate.sh` 全绿
      补勾依据（2026-10-22：verify `BUILD SUCCESS` 14:51，failsafe 142 tests 0 failures/12 skipped，spotbugs BugInstance 0；
      基线 `surefire 2004 / failsafe 142`，check EXIT=0；merge-gate 全 PASS：unit/checkstyle/spotbugs/spotless/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline）
- [x] 5.2 停步未 merge 未 push，回报分支名 / commit 列表 / 门禁输出尾行 / 未解决项；A 档独立复核四判据通过后由 Owner 授权 merge
      补勾依据（2026-10-22 分支 `feature/token-usage-admin-view` 自 `13be738` 检出；未 merge/push/删分支）
