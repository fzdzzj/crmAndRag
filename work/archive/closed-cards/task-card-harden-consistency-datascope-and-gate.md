# 任务卡 · harden-consistency-datascope-and-gate（一致性、数据权限与门禁加固，A 档）

> 目标：闭环知识库删除向量与切片物理双清（C-11）、收敛 AI 查询工具 DataScope 与详情类属主断言（B-4/B-5）、补齐聚合门禁 checkstyle/spotless 与 CI 同源（G-6）。

## 边界与红线

- 零数据库迁移：`src/main/resources/db/migration/` 零改动（下一可用迁移版本仍为 V43）；
- 零新增依赖：`pom.xml` 零改动；
- 零破坏性契约变更：`openapi.yaml` 零 diff；
- 严禁触碰 `frontend/typed-router.d.ts`（若被生成工具改动立即 checkout 还原）；
- 提交规范：所有 commit 信息必须使用 `-m "xxx"` 内联 ASCII 简短单行语句。

## 核心锚点

- **模块 A（知识删除一致性）**：`KnowledgeAdminService.deleteFile` 联动调用 `vectorStore.deleteByDocumentId` 与 `chunkMapper.deletePhysicallyByDocumentId`，消除已删文档向量残留与检索分叉；
- **模块 B（AI 工具权限与属主断言）**：在 `BusinessRecordAccessService` 补齐合同与任务读断言，在 `ContractServiceImpl`、`ContractOrderItemServiceImpl`、`TaskCommentServiceImpl` 入口落地拦截，无权读抛出 12002 `PERMISSION_DENIED`；`AiQueryToolExecutors` 强化调用方用户上下文校验；
- **模块 C（门禁与 CI 同源）**：`scripts/merge-gate.sh` 引入 `checkstyle:check` 与 `spotless:check` 门禁并更新头注，`scripts/tests/merge-gate-selftest.sh` 补齐自测断言；
- **专项测试**：补齐删除双清单元/集成测试，以及详情工具越权访问拦截（12002）与正常访问测试。

## 验收要求

- 专项测试全部通过；
- `mvn -B -ntp clean verify` 全绿（Docker Testcontainers 118 IT 全过）；
- `bash scripts/merge-gate.sh` 全门禁全 PASS，Surefire 用例数在 1647 基础上只增不减；
- 产出 `work/handoff-harden-consistency-datascope-and-gate.md` 并完成 `tasks.md` 各项勾选与 6 空格实测证据行。

## 交付纪律

- 分支：`feature/harden-consistency-datascope-and-gate`（从最新 `master` 检出，完工工作树干净）；
- 完工严格停步待复核：严禁私自 `git merge` 或 `git push`！
