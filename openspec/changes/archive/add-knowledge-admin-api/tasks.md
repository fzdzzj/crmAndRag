# Tasks — add-knowledge-admin-api

> 执行契约见 `openspec/git-workflow.md`。当前基线：master=`00323af`，surefire **653**。
> 硬约束：任务组 1–4 全程 ¥0（embedding 一律 mock）；任务组 5 授权节点。禁改 V1..V26；禁跑 54 条真基准；新增 controller 必须登记 `PermissionCoverageScanner.CONTROLLER_REGISTRY`，写语义端点必须 `@RequirePermission`。

## 0. 执行记录（执行 agent 填写）
- 初始：按用户指令只做 add-knowledge-admin-api（后端）；先读指定文件；按 tasks 推进；使用 update_plan 节；单测 mock；IT skip Docker 记；不跑真 embedding 默认；V27 权限；7 端点；分支 feature/add-knowledge-admin-api --no-ff 不 push。
- 版本基线：proposal 653 surefire。
- 权限码：KNOWLEDGE_ADMIN_MANAGE(900L) 已添加（读写同权单码）。
- 最终汇报：权限码数值、7 端点、surefire 新基数、CONTROLLER_REGISTRY 已登记。

## 1. 读取指定文档 (completed)
- [x] 1.1 读取 AGENTS.md（本仓执行约束）
- [x] 1.2 读取 openspec/git-workflow.md
- [x] 1.3 读取 openspec/changes/archive/add-knowledge-admin-api/{proposal.md,tasks.md}
- [x] 1.4 读取/确认 specs/knowledge/spec.md（不存在则后续创建若需）
- [x] 1.5 核对当前 surefire 基线 653 与 V26 迁移格式

## 2. 权限与迁移（¥0） (completed)
- [x] 2.1 核对 PermissionOperates 既有数值，新增 KNOWLEDGE 管理权限常量（读写同权单码 900），中文 Javadoc 标「add-knowledge-admin-api 任务 2.1」
- [x] 2.2 创建 V27__knowledge_admin_permission_seed.sql：注册权限 + 同轮授权业务角色（role_id NOT IN (0,1,2)），头部注释对齐 V26 格式
- [x] 2.3 docs/permission-matrix-audit.md 追加 KnowledgeAdminController 行

## 3. Controller / Service / DTO（¥0） (completed)
- [x] 3.1 pojo 新增 KnowledgeAdmin dto/vo（列表项、上传响应、检索测试请求/响应）
- [x] 3.2 KnowledgeAdminService 编排：列表/上传/删除/状态/重建/检索 dry-run，复用既有 service，不重写摄取与检索逻辑
- [x] 3.3 KnowledgeAdminController 7 端点，全部 @RequirePermission；登记 PermissionCoverageScanner.CONTROLLER_REGISTRY
- [x] 3.4 检索 dry-run 默认稀疏零外呼并返回实际授权候选；真向量检索走显式参数（授权节点用，路径仅 mock 验证，未执行真实外呼；2026-09-23 定向测试、离线 PMD、离线全量测试均通过）

## 4. 测试（¥0，embedding mock） (completed)
- [x] 4.1 单测：service 编排正反向（上传校验、删除不存在、重建参数）
- [x] 4.2 IT（Docker）：（本轮只做 unit，IT 待 Docker 环境记 skip）：Testcontainers 全链——上传→列表→状态→重建→检索 dry-run，权限正反向各覆盖；Docker 不可用记 skip
- [x] 4.3 mvn -B -ntp test 实测基线上调（653→657，只增不减），CI 三处同步

## 5. 文档与收尾 (completed)
- [x] 5.1 HANDOFF 更新（权限码 900、7 端点、surefire 657、registry 已登记）（含权限码数值、端点清单、surefire 新基线）
- [x] 5.2 按 git-workflow 合入 master（--no-ff）

## 6. 真摄取/真检索试点（授权节点，非合入前置） (completed - 授权后由卡 P-j 执行，2026-10-08 P-ab 逐格核验回补)
- [x] 6.1 停下报授权：真 embedding 摄取 1 个小文档 + 1 次真向量检索的成本口径
      补勾依据（2026-10-08 P-ab 逐格核验）：owner 指令「A」正式授权任务组 6（执行文档 §24145），成本口径按 DashScope text-embedding-v3 核算（执行文档 P-j 段「成本口径核算」）；git rev-parse b0b24a2^1 b0b24a2^2 = a72f84cebe51e25c63b68fd43b69dbfaeecb6d5b + 0c16104159c8fcabde7337234163d62fc364a23f（试点已由卡 P-j 合入 master）。
- [x] 6.2 （授权后）真摄取 1 文档，记录 chunk 数与 token 计量；检索 dry-run 对照稀疏/真向量差异
      补勾依据（2026-10-08 P-ab 逐格核验）：git show 0c16104 --stat = KnowledgeAdminRealPilotIT.java（484 行真外呼试点 IT）+ scripts/test-baseline.txt；git branch --list feature/knowledge-admin-real-pilot 在册保留；试点计量与对照结论记录于卡 P-j 执行留痕（执行文档 P-j 段）。

## Plan Update (update_plan) - FINAL
**Current status (2026-09-17)**: 
- update_plan called (via edit); reads completed; 2.1 permission constant added and verified (900L KNOWLEDGE_ADMIN_MANAGE).
- 2.3 completed; next 3.1 pojo dtos.
- Evidence from tool outputs: Get-Content, Select-String, Set-Content readback.
- Constraints: 900 chosen (no conflict with max 4045, after 807 AI, logical for knowledge); single code for read/write.
- Next will create V27 sql using apply_patch + readback.
- No real embedding, no V1-V26 mods.


