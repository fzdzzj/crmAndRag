# Tasks — guard-knowledge-admin-vector-cost

> 本案仅实现默认关闭的费用入口保护，**全程 ¥0**。执行前读 `AGENTS.md`、`frontend/AGENTS.md`、本案 proposal/spec、`add-knowledge-admin-api` 任务组 6 与 `openspec/git-workflow.md`；以当前 master、真实门禁脚本为准，不复制历史测试数字。任何真实模型、生产开关、依赖、迁移、推送都必须停下取得 owner 明确授权。

## 1. 后端默认关和输入边界

- [x] 1.1 在现有 `rag.retrieval` 命名空间登记 `admin-vector.enabled=false`，更新 `docs/dynamic-config-keys.md`；缺服务/键/值时按 false，确认只有超管能通过既有管理入口写该键。
- [x] 1.2 `KnowledgeAdminService.retrievalTest` 只针对 `useVector=true` 执行服务端开关、单 KB `kbId` 必填、`topK` 1–10（省略默认 5）检查；禁用/参数无效用既有可识别错误码显式拒绝，拒绝在任何模型调用前发生；稀疏路径语义不变。
- [x] 1.3 继续按现有用户与指定 KB 收敛授权；无授权 KB 不进入检索，也不能绕过开关和工作量上限。

## 2. 端点专属频率保护

- [x] 2.1 在 `RequestQuotaService` / `QuotaDimension` / `QuotaProperties` 增加与现有 USER 隔离的管理端真向量用户窗口，静态配置默认 3 次/分钟；沿用既有 Caffeine/Micrometer，无新依赖、无跨实例全局上限承诺。
- [x] 2.2 有效请求在模型调用前占一次额度；禁用、参数拒绝、无授权 KB 不消耗额度；超限复用 `RATE_LIMIT_EXCEEDED`，不得返回成功空候选。

## 3. 界面和文档

- [x] 3.1 只在 `useVector=true` 时说明服务端默认关、必须选择 KB 且可能产生模型费用；前端提交前校验 kbId 与 topK，服务器仍为唯一裁决者。按 `frontend/AGENTS.md` 同步测试检查点，不引入新端点/依赖。
- [x] 3.2 文档明确每 JVM/每用户的窗口不是金额上限，真实试点与生产开启继续走 owner 授权；不宣称其他检索动态键已注册。

## 执行记录

- 分支：`codex/guard-knowledge-admin-vector-cost`；未合并、未 push、未归档。
- 真实模型/embedding/reingest/基准集/生产开关均未执行；未修改迁移、900 权限或依赖。
- `mvn -B -ntp -Dtest=KnowledgeAdminServiceTest test`：11/11 通过，含配置读取异常 fail-closed 用例。
- `D:\git\Git\bin\bash.exe -lc "mvn -o -B -ntp clean verify"`：surefire 756/756 通过；failsafe 66/66 通过，6 个按环境门控跳过；真实模型开关显式为 0。
- `D:\git\Git\bin\bash.exe -lc "scripts/check-test-baseline.sh --update"`：基线更新为 surefire 756、failsafe 66、跳过 6。
- `pnpm type-check:check`：通过；`pnpm -C frontend test`：16 个文件、163/163 通过。
- `D:\git\Git\bin\bash.exe -lc "scripts/merge-gate.sh"`：通过；unit、SpotBugs、PMD、baseline、frontend-unit、hook、bijection、pmd-baseline 全部 PASS。

## 4. ¥0 验收和交付

- [x] 4.1 单测覆盖默认关、配置缺失、参数拒绝、无授权、同用户第 4 次拒绝、不同用户独立计数、合法请求仍正确传递 userId/kbScope、稀疏路不受影响；被拒绝路径断言检索/模型接口零交互。
- [x] 4.2 跑受影响 Java 测试、静态门禁与前端 `pnpm type-check:check`/相关单测；合并前按 `openspec/git-workflow.md` 真跑 `scripts/merge-gate.sh`，涉及 failsafe 时依 runbook 确认 Docker；测试基线仅脚本从干净真实报告更新。记录未跑项与原因，禁真实外呼。
- [x] 4.3 仅纳入本案文件、核对 diff 与原工作树他人变更；分支/合并按项目工作流、不得 push。旧 `add-knowledge-admin-api` 任务组 6 仍在途，不因本案代码通过而自动勾选或归档。
