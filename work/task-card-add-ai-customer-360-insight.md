# 任务卡 add-ai-customer-360-insight：客户 360° AI 智能商业诊断与会话交互深化

## 1. 任务背景与目标
- **对应卡**：`openspec/changes/add-ai-customer-360-insight/`（`proposal.md` + `tasks.md`）
- **变更档位**：B 档（常规产品代码 + 测试；无新库表/零迁移/权限同锚既有常量，零真跑外呼）
- **核心目标**：实现客户 360° 多实体数据聚合与双层诊断引擎（确定性规则健康分 + LLM 智能风险与拜访建议）；新增 `GET /ai/insight/customer/{companyId}` 端点并挂 117 权限；在 `AiToolRegistry` 注册 `getCustomerInsight` 工具；实现会话首轮问答后会话标题智能提炼与自动更新；前端抽屉预置快捷药丸指令。

## 2. 权威上下文与输入
- **必读规格**：`openspec/changes/add-ai-customer-360-insight/{proposal.md,tasks.md}`
- **先例参考**：`LlmSelfRagReflector.java`（ModelProvider 结构化调用与安全回退）、`AiSessionServiceImpl.java`（updateTitle）、`AiToolRegistry.java`（查询工具注册与权限同锚）

## 3. 边界与硬约束
- **代码范围**：`src/main/java/com/slz/crm/server/ai/`、`com.slz.crm.server.controller`、`AiAssistantDrawer.vue` 及对应测试。
- **红线禁区**：
  1. 诊断异常恒定安全降级回退为规则内容，绝不阻断接口、绝不抛 500；
  2. 零 Flyway 迁移脚本（严禁改动 `src/main/resources/db/`）；
  3. 零新权限常量（复用既有 `CUSTOMER_VIEW_COMPANY` = 117）；
  4. 零真跑外呼（纯 JVM mock 测试，DASHSCOPE_API_KEY 置空）；
  5. 保持分支 `feature/add-ai-customer-360-insight` 在册，严禁直接在 master 提交或 push。

## 4. 验收门禁要求
- **构建测试**：`mvn -B -ntp test` 全绿，surefire 数量只增不减（基线 1391）；
- **前端门禁**：`pnpm lint:check` + `pnpm type-check:check` + `pnpm vitest run`（179 测试全绿）；
- **安全隔离**：DataScope 跨部门无权访问反向用例返回 12002 `PERMISSION_DENIED`；
- **基线更新**：在树净状态单次通过 `bash scripts/check-test-baseline.sh --update` 更新基线；
- **聚合门禁**：四静态全绿 + `bash scripts/merge-gate.sh` 退出码 0。

## 5. 产出与交接
- 产出交接快照：`work/handoff-add-ai-customer-360-insight.md`。
