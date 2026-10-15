# 任务卡 add-business-activity-semantic-retrieval：商业活动语义检索与权限过滤

## 1. 任务背景与目标
- **对应卡**：`openspec/changes/add-business-activity-semantic-retrieval/`（`proposal.md` + `tasks.md`）
- **变更档位**：B 档（常规业务代码 + 测试；无新库表/零迁移/权限复用，契约解冻走受控增量登记）
- **核心目标**：将商业活动（`business_activity`）沟通要点与富文本接入 Qdrant `crm_entity` 向量检索，同锚 `SALES_VIEW_BUSINESS_ACTIVITY` 四档权限过滤，并在 AI 对话中实现双业务实体多路召回与来源标注。

## 2. 权威上下文与输入
- **必读规格**：`openspec/changes/add-business-activity-semantic-retrieval/{proposal.md,tasks.md}`
- **先例参考**：`CustomerIngestionService.java`、`BusinessRetrievalService.java`、`CustomerSemanticHookSupport.java`

## 3. 边界与硬约束
- **代码范围**：`src/main/java/com/slz/crm/knowledge/ingestion/`、`src/main/java/com/slz/crm/server/service/impl/`、`BusinessActivityController.java` 及对应测试。
- **红线禁区**：
  1. 零 Flyway 迁移脚本（严禁改动 `src/main/resources/db/`）；
  2. 零新权限常量（复用 `SALES_VIEW_BUSINESS_ACTIVITY` 系列）；
  3. 零真跑外呼（纯 JVM mock 测试，DASHSCOPE_API_KEY 置空）；
  4. 保持分支 `feature/add-business-activity-semantic-retrieval` 在册，严禁直接在 master 提交。

## 4. 验收门禁要求
- **构建测试**：`mvn -B -ntp test` 全绿，surefire 数量只增不减（基线 1290）；
- **权限反向**：四角色权限过滤测试全绿（归属人/本部门/跨部门/管理员）；
- **基线更新**：在树净状态单次通过 `bash scripts/check-test-baseline.sh --update` 更新基线；
- **聚合门禁**：四静态全绿 + `bash scripts/merge-gate.sh` 退出码 0。

## 5. 产出与交接
- 产出交接快照：`work/handoff-add-business-activity-semantic-retrieval.md`。
