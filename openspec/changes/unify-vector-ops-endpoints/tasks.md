# 任务清单 · 向量运维端点统一收口 (unify-vector-ops-endpoints)

<!-- id: unify-vector-ops-endpoints -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 后端统一入口

- [x] 1.1 `KnowledgeAdminController` 新增 `POST /knowledge/admin/sync/{entity}`（entity ∈ 五实体枚举校验，非法 400）与 `POST /knowledge/admin/sync/all`，内部委托既有回埋服务（Grep `reingestAll` 定位五处），权限复用该 controller 既有常量；`PermissionCoverageScanner.CONTROLLER_REGISTRY` 已含则确认、未含则登记
      - 依据：`src/main/java/com/slz/crm/server/controller/KnowledgeAdminController.java` L174-235（统一入口段），五处 `reingestAll` 服务定位：`BusinessActivityIngestionService.java:248` / `SalesOpportunityIngestionService.java:122` / `CustomerIngestionService.java:156` / `ContractIngestionService.java:237` / `ContactTaskIngestionService.java:203`（Grep `reingestAll` 于 `src/main/java` 实测 18 命中含 controller 委托 5 处）；两新端点均挂 `@RequirePermission(PermissionOperates.KNOWLEDGE_ADMIN_MANAGE)`（复用 900，零新增，`PermissionOperates.java` 未改）；`PermissionCoverageScanner.CONTROLLER_REGISTRY` 已含 KnowledgeAdminController（`PermissionCoverageScanner.java` L333-334），无需登记。
      - 校验：`KnowledgeAdminSyncEndpointTest`（8 用例全绿）+ `KnowledgeAdminStrategyEndpointTest`（4 用例全绿，构造签名同步更新），`mvn -B -ntp test -Dtest=KnowledgeAdminSyncEndpointTest,KnowledgeAdminStrategyEndpointTest` 12/12。
- [x] 1.2 旧散落端点处置：按 diff 最小原则保留或 `@Deprecated` + openapi 注明统一入口，handoff 留痕决策
      - 依据：四业务 Controller 散落端点（`BusinessActivityController.java:259` / `ContactTaskController.java:203` / `ContractController.java:129` / `SalesOpportunityController.java:153` 的 `/reingestAll`）与本 controller 既有 `/knowledge/customer/reingest`（L168）按 diff 最小原则**全部保留、零改动**（向后兼容，不动他人权限面）；决策详情见 `work/handoff-unify-vector-ops-endpoints.md` §3。
      - 校验：`git diff --stat` 无上述四个业务 Controller 任何改动。

## 任务组 2 · 契约与前端

- [x] 2.1 openapi `--update` + `pnpm gen:api`；知识管理页新增向量运维卡片（五实体入口 + 最近同步结果展示，以现成返回结构为准）；Playwright 检查点追加
      - 依据：`bash scripts/check-openapi-consistency.sh --update` 通过（"契约一致"），`frontend/openapi.yaml` 仅新增 `/knowledge/admin/sync/{entity}` 与 `/knowledge/admin/sync/all` 两路径及 `ResultSyncResult`/`ResultMapStringSyncResult` 两 schema（diff +62/-0）；`pnpm gen:api` 再生 `src/api/axios`（gitignored，不提交）产出 `postKnowledgeAdminSyncByEntity`/`postKnowledgeAdminSyncAll`（`sdk.gen.ts` L751/L753）；卡片组件 `src/components/knowledge/KnowledgeSyncCard.vue` 接入 `src/pages/(dashboard)/knowledge/index.page.vue`（五实体入口 + 全部同步 + 最近同步结果，以现成 `SyncResult{total,success,failure}` 返回结构为准，未建新表/新端点）；Playwright 检查点已追加 `frontend/docs/test-checkpoints.md`（unify-vector-ops-endpoints 节，4 条）。
      - 校验：`KnowledgeSyncCard.test.ts` 5 用例 + `useKnowledge.test.ts` 新增 4 用例全绿；`pnpm type-check:check` / `pnpm lint:check` 通过；全量 `pnpm test` 21 文件 197 用例绿（基线 188 → +9）。

## 任务组 3 · 测试与门禁

- [x] 3.1 单测：entity 枚举校验（合法/非法 400）、委托调用断言（verify 各实体服务被调）、权限锚；单测一律 mock 向量服务，**零真实重嵌入**
      - 依据：`src/test/java/com/slz/crm/unit/controller/KnowledgeAdminSyncEndpointTest.java` 8 用例——五实体合法 2xx + 计数透传（`syncCustomerDelegates` 等 5 例）、非法 entity 400 且 `verifyNoInteractions` 五服务、`syncAllDelegatesToAllFiveServices` 逐个 `verify(...).reingestAll()`、`syncEndpointsCarryRequirePermission900` 反射断言两端点挂 `@RequirePermission(900)`；单测全 mock 各实体回埋服务（`mock(...)`），无 EmbeddingService/向量库装配，零真实重嵌入。
      - 校验：`mvn -B -ntp test -Dtest=KnowledgeAdminSyncEndpointTest` 8/8；全量 surefire 1480（基线 1472 → +8 只增）。
- [x] 3.2 `mvn -B -ntp test` 基线 1472 只增后 `--update`；merge-gate 8/8（Docker 在线 9/9）；前端三检；typed-router 零改动；产出 `work/handoff-unify-vector-ops-endpoints.md`（真实披露 HEAD/基线/树净/未 merge 未 push）
      - 依据：`bash scripts/check-test-baseline.sh --update` 实测写入 `scripts/test-baseline.txt`（surefire.reports=226 / surefire.tests=1480 / failsafe 31/118/7）；`bash scripts/merge-gate.sh` 8/8 全绿（unit/spotbugs/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline），Docker 29.6.2 在线加跑 `--with-verify` 9/9；前端三检 = `pnpm lint:check` + `pnpm type-check:check` + `pnpm test` 全绿；`frontend/typed-router.d.ts` 再生即 `git checkout --` 还原（vitest 行尾噪声），分支提交零 diff；handoff 见 `work/handoff-unify-vector-ops-endpoints.md`。
      - 校验：`git status --porcelain -- frontend/typed-router.d.ts` 空；`git log master..HEAD` 线性且提交零 diff 于 typed-router（提交前最终复核）。
