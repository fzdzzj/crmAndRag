# proposal — extend-ai-draft-and-eval

> AI 助手写面扩展（2 草稿工具）+ AiEval 评测集同步（owner 2026-10-14 拍板「写面扩展+评测同步」合卡，B 档）。

## 1. 背景（2026-10-14 master bd4664c 立项实测）

extend-ai-query-tools 合并后 AI 工具面 25（18 查询 + 7 草稿）。两处实测缺口：

1. **写面与 B 批查询面不对称**：查询面已覆盖跟进任务（queryContactTask）、任务评论（queryTaskComment）、业务活动（queryBusinessActivity），但草稿域仅 7 类（客户/联系人/商机/合同/订单/回款/发票，AiDraftToolExecutors 实测）——AI 能「查到」销售动线却不能「记录」，跟进任务与业务活动是销售日常最高频写操作。
2. **评测集滞后于工具面**：`src/test/resources/ai-eval/cases.json` 实测 **19 用例**（12 查询 + 7 草稿），B 批 6 新查询工具 + 本卡 2 新草稿共 **8 工具零用例覆盖**；25 工具后 LLM 选择面变大（queryContactTask vs queryTaskComment、getContractDetail vs queryContract 等边界辨析），选择退化风险上升而无回归防护。

## 2. 立项取证（本轮实测）

- **确认链权限语义（关键）**：草稿工具挂 null 注册（起草自由）+ 落库确认时运行时校验——`PendingActionServiceImpl#executePendingAction` L202 `PendingActionGuards.requirePermission(actionType, userId, permissionService)` → `ActionTypeEnum.getRequiredPermission(actionType)` 按类型映射业务写权限，无映射直接拒绝（PARAM_REQUIRED）、无权限 12002（PERMISSION_DENIED）。零授权旁路。
- **扩展点（架构自带）**：`ActionTypeEnum` 类注释明示「扩展新操作类型只需在此枚举新增一项 + 新增对应 Validator/Executor Bean」——现有 7 映射（CREATE_ORDER→SALES_APPEND_ORDER 等）。
- **写权限锚（controller 实测）**：ContactTaskController `/create` → `TASK_CREATE_TASK`；BusinessActivityController `POST /` → `SALES_CREATE_BUSINESS_ACTIVITY`。两常量均在案。
- **评测加载**：`AiEvalCaseLoader`（src/test）读 classpath `ai-eval/cases.json`；用例 schema = id/utterance/expectedTool/expectedParams/tags。

## 3. 目标（owner 拍板：2 草稿全做 + 评测覆盖+辨析）

1. **createContactTaskDraft**（跟进任务草稿）：ActionTypeEnum 新增 `CREATE_CONTACT_TASK → TASK_CREATE_TASK`；Validator/Executor Bean 落库走 ContactTaskService 既有 create 语义；AiToolRegistry 注册挂 null（同 7 草稿模式，含 pendingId 追问合并链）。
2. **createBusinessActivityDraft**（业务活动草稿）：同构，`CREATE_BUSINESS_ACTIVITY → SALES_CREATE_BUSINESS_ACTIVITY`，落库走 BusinessActivityService 既有 create 语义。
3. **AiEval 用例集 19→30+**：8 零覆盖工具各 ≥1 例（B 批 6 查询 + 新 2 草稿）+ 边界辨析用例（getContractDetail vs queryContract「详情 vs 列表」、queryContactTask vs queryTaskComment「任务面 vs 评论面」、getOpportunityDetail vs queryOpportunity）+ 草稿追问合并链用例（pendingId）。

**零改动面**：controller 端点零新增零修改（PermissionCoverageScanner 注册表零改动）；前端零改动（草稿确认走既有 AiAction 前端，807 已授权全角色）；PermissionOperates 零新增。

## 4. 红线

- **零迁移**（不碰 db/migration）；**零权限语义新增**（ActionTypeEnum 新映射仅用既有常量，确认链既有机制自动生效）；**零真跑外呼**（全部测试纯 JVM mock，跑测试前 DASHSCOPE_API_KEY 置空）；**零冻结契约触碰**（contracts-frozen.md 不动，controller 零改动契约零变化）。
- surefire 基线 **1265** 只增不减（revision=276f4ae 基线笔在案，新基线笔按 --update 树净单次规约）。
- **计数断言预期变化（明示非偷改）**：AiToolRegistryContextTest null 用户 9→11、低权样本 11→13——2 新草稿挂 null 无条件注入，任务组 1/2 格内明示该预期并附实测。
- 新代码中文 Javadoc 标注「extend-ai-draft-and-eval 任务 x.x」；四静态与全量 test 禁并发顺序跑；merge-gate 全绿。

## 5. 裁决假设（owner 可否决）

1. 草稿工具挂 null 注册（同既有 7 草稿模式：起草自由，落库时确认链按 actionType 校验写权限）——「页面看得到的 AI 才拿得到」口径下草稿起草不设门，写门在确认。
2. 跟进任务草稿字段面以 ContactTaskDTO 既有字段为准（任务主题/关联联系人/时间等，双通道解析同既有模式）；业务活动同构（BusinessActivityDTO）。
3. 评测用例走既有 AiEval 测试模式（工具选择/参数断言，零真模型调用）；用例 id 命名沿用既有风格（kebab-case 语义前缀）。
4. AiToolRegistryContextTest 计数断言随 +2 同步更新并在依据行注明「预期变化」依据。
5. 业务活动「参与人/关联联系人」等复杂关联面（addContacts/addUsers 端点）不入本卡草稿——草稿仅覆盖主实体 create 语义，关联操作留后批。

## 6. 验收

- 任务组 1-4 全勾且每勾留痕（6 空格依据行）；2 新草稿工具在案（注册 + 映射 + Validator/Executor + 单测：正常落库/参数缺失追问/无权限确认拒绝/双通道解析）；cases.json ≥30 用例且 AiEval 测试全绿；AiToolRegistryContextTest 计数断言更新为 11/13 附预期变化依据；全量 test 1265→只增不减；四静态 0 / 三守卫绿 / merge-gate 全绿；handoff 落 work/handoff-extend-ai-draft-and-eval.md。
