# extend-ai-draft-and-eval — 任务清单

> 提交粒度与本任务组对齐：每任务组一笔提交，分支 `feature/extend-ai-draft-and-eval` 自权威树 master（bd4664c）检出。
> 依据行格式：6 空格 + 实测证据（命令 + 关键数字/哈希）。未实际执行的步骤禁止打勾。
> 红线：零迁移 / 零权限语义新增（枚举映射仅用既有常量）/ 零真跑外呼（DASHSCOPE_API_KEY 置空）/ 零冻结契约 / surefire 基线 1265 只增不减 / controller 与前端零改动 / 计数断言 null 9→11、低权 11→13 为预期变化（依据行注明）。

## 任务组 1 · 跟进任务草稿链

- [x] 1.1 ActionTypeEnum 新增 `CREATE_CONTACT_TASK → PermissionOperates.TASK_CREATE_TASK`；确认链语义验证（requirePermission 自动生效，无映射拒绝/无权限 12002 既有行为不动）
      依据：git diff src/main/java/com/slz/crm/server/ai/enums/ActionTypeEnum.java 实测新增 CREATE_CONTACT_TASK→TASK_CREATE_TASK（PermissionOperates 401L 既有常量，零权限语义新增）；PendingActionGuards.requirePermission L21-31 按 actionType 映射（无映射 PARAM_REQUIRED 50008/无权限 PERMISSION_DENIED 12002），确认链三件零碰触；PendingActionNewDraftPermissionTest 覆盖新映射与两拒绝路径（见 1.4 依据）
- [x] 1.2 ContactTaskValidator Bean（必填校验/双通道解析/缺失字段清单，同既有 Validator 模式）+ ContactTaskExecutor Bean（落库走 ContactTaskService 既有 create 语义，中文 Javadoc「extend-ai-draft-and-eval 任务 x.x」）
      依据：新增 src/main/java/com/slz/crm/server/ai/validation/ContactTaskActionValidator.java 与 executor/ContactTaskActionExecutor.java（Javadoc 标注「extend-ai-draft-and-eval 任务 1.2」，另新增 pojo/dto/ai/AiContactTaskDraftPayloadDTO.java）；执行器委托 ContactTaskService.create（ContactTaskServiceImpl L49 既有 create 语义）；mvn -B -ntp test -Dtest=ContactTaskActionValidatorTest,ContactTaskActionExecutorTest 退出码 0（6 tests pass，0 failures）
- [x] 1.3 AiToolRegistry 注册 `createContactTaskDraft`（挂 null 同 7 草稿模式，description/inputSchema 对齐既有文案风格，含 pendingId 追问合并说明）
      依据：AiToolRegistry.registerDraftTools 实测新增 createContactTaskDraft 注册（permission=null，同既有 7 草稿无条件注入模式；description 含客户双通道与 pendingId 追问合并说明）；AiDraftToolExecutors.executeCreateContactTaskDraft 走 submitDraft→mergeDraft(pendingId)/submitDraft 双分支分发
- [x] 1.4 单测：正常落库（mock service）/ 参数缺失进入 DRAFTING 追问 / 无权限确认拒绝（requirePermission 12002）/ 双通道解析（taskId 已知 vs 名称解析）；AiToolRegistryContextTest 计数断言更新 null 9→11、低权 11→13（依据行注明预期变化）
      依据：mvn -B -ntp test -Dtest=ContactTaskActionValidatorTest,ContactTaskActionExecutorTest,PendingActionNewDraftPermissionTest,AiToolRegistryContextTest 退出码 0（16 tests pass，0 failures）：执行器 mock 落库 + 业务失败（2）、校验器双通道（companyId 已知/companyName 解析写回）+ 缺失追问（4）、守卫 12002/50008（2）、注册表上下文（8）；计数断言本笔更新 null 9→10、低权 11→12（中间态：本笔仅注册 1 新草稿，业务活动草稿随任务组 2 注册后终态 11/13，属卡明示预期变化，2.4 笔内复验终值）

## 任务组 2 · 业务活动草稿链

- [x] 2.1 ActionTypeEnum 新增 `CREATE_BUSINESS_ACTIVITY → PermissionOperates.SALES_CREATE_BUSINESS_ACTIVITY`
      依据：git diff src/main/java/com/slz/crm/server/ai/enums/ActionTypeEnum.java 实测新增 CREATE_BUSINESS_ACTIVITY→SALES_CREATE_BUSINESS_ACTIVITY（PermissionOperates 222L 既有常量，零权限语义新增）；BusinessActivityController POST / 同锚 L47 @RequirePermission(SALES_CREATE_BUSINESS_ACTIVITY) 实测在案
- [x] 2.2 BusinessActivityValidator + BusinessActivityExecutor Bean（落库走 BusinessActivityService 既有 create 语义；裁决假设 5：仅主实体 create，参与人/关联联系人子操作不入卡）
      依据：新增 src/main/java/com/slz/crm/server/ai/validation/BusinessActivityActionValidator.java 与 executor/BusinessActivityActionExecutor.java（Javadoc 标注「extend-ai-draft-and-eval 任务 2.2」，另新增 pojo/dto/ai/AiBusinessActivityDraftPayloadDTO.java）；执行器委托 BusinessActivityService.create（BusinessActivityServiceImpl L51 既有 create 语义），仅主实体字段映射、不触碰 addContacts/addUsers（裁决假设 5）
- [x] 2.3 AiToolRegistry 注册 `createBusinessActivityDraft`（挂 null 同模式）
      依据：AiToolRegistry.registerDraftTools 实测新增 createBusinessActivityDraft 注册（permission=null 同既有 7 草稿无条件注入模式；description 含商机双通道与 pendingId 追问合并说明）；AiDraftToolExecutors.executeCreateBusinessActivityDraft 走 submitDraft→mergeDraft(pendingId)/submitDraft 双分支分发
- [x] 2.4 单测：同任务组 1.4 结构四例；计数断言复验（null 11、低权 13 已在 1.4 更新则本格仅复跑不重复改）
      依据：mvn -B -ntp test -Dtest=BusinessActivityActionValidatorTest,BusinessActivityActionExecutorTest,PendingActionNewDraftPermissionTest,AiToolRegistryContextTest 退出码 0（16 tests pass，0 failures）：执行器 mock 落库 + 业务失败（2）、校验器双通道（opportunityId 已知/opportunityName 解析写回）+ 缺失追问（4）、守卫 12002/50008（3）、注册表上下文（8）；计数断言本笔复验终值 null 11、低权 13（1.4 已按预期变化声明，2.3 注册落库后终值确认）

## 任务组 3 · AiEval 评测集同步（19→30+）

- [x] 3.1 B 批 6 查询工具用例：queryContactTask / queryTaskComment / queryBusinessActivity / getContractDetail / getOpportunityDetail / queryStageApproval 各 ≥1 例（utterance 贴真实销售话术，expectedTool/expectedParams 对齐实现 schema）
      依据：cases.json 实测新增 6 例（contact-task-by-assignee、task-comment-by-id、business-activity-by-company、contract-detail-by-id、opportunity-detail-by-id、stage-approval-pending），expectedParams 对齐 AiToolRegistry schema（queryContactTask.assigneeId、queryTaskComment.taskId 必填、queryStageApproval.approvalStatus 枚举 0/1/2/3、getContractDetail/getOpportunityDetail 按 ID 详情）
- [x] 3.2 新 2 草稿工具用例：createContactTaskDraft / createBusinessActivityDraft 各 ≥1 例 + 追问合并链用例（pendingId 补参）≥1 例
      依据：cases.json 实测新增 create-contact-task-draft、create-business-activity-draft、create-business-activity-draft-follow-up（pendingId=pending-2001 补参，追问合并链用例，expectedParams 对齐新草稿 schema 的 companyName/opportunityName 双通道与 activityDuration/remark）
- [x] 3.3 边界辨析用例 ≥3 例：getContractDetail vs queryContract（详情 vs 列表）、queryContactTask vs queryTaskComment（任务面 vs 评论面）、getOpportunityDetail vs queryOpportunity
      依据：cases.json 实测新增 6 例 discrimination 标签（3 对 × 2 方向）：contract-detail/list、task-comment/task-surface、opportunity-detail/list，同一实体面「详情 vs 列表/评论」话术对撞覆盖
- [x] 3.4 cases.json 用例 id 全局唯一（kebab-case 语义前缀）、AiEval 既有测试全绿、用例总数实测 ≥30
      依据：PowerShell UTF-8 读取实测 cases=34、unique_ids=34（id 全部 kebab-case 语义前缀）、24 工具全覆盖（含 8 新工具）；mvn -B -ntp test -Dtest=AiEvalCaseSetTest,AiEvalRunnerTest,AiEvalMetricsTest 退出码 0（4 tests pass）；AiEvalCaseSetTest 补断言 8 新工具覆盖（≥30 硬断言）

## 任务组 4 · 门禁与收尾

- [x] 4.1 全量 `mvn -B -ntp test` 0 失败（基线 1265 只增不减，实测数留痕）；若 surefire 净增则按规约树净单次跑 `check-test-baseline.sh --update` 并随本笔提交
      依据：mvn -B -ntp test（DASHSCOPE_API_KEY 置空）退出码 0，surefire Tests run=1280，Failures=0，Errors=0，Skipped=0（基线 1265，净增 15=新增 15 个测试方法：校验器 4+4、执行器 2+2、守卫 3）；按规约树净单次跑 bash scripts/check-test-baseline.sh --update 实测写入 surefire.reports=203/surefire.tests=1280/skipped=0、failsafe 116/7（source-revision=7450a2b），scripts/test-baseline.txt 随本笔提交
- [x] 4.2 四静态顺序跑全 0（checkstyle / spotbugs / spotless / pmd）+ 三守卫（pmd-baseline-check / spotbugs-exclude-staleness-check / check-test-baseline）
      依据：四静态顺序跑退出码全 0——checkstyle「You have 0 Checkstyle violations」、spotbugs「BugInstance size is 0」、spotless「988 files clean - 0 needs changes」、pmd 实测 violations=0；三守卫：pmd-baseline-check RESULT=PMD_BASELINE_OK（实测 0 == 登记 0 == pom 阈值 0）、spotbugs-exclude-staleness-check RESULT=BIJECTION_OK（12 条豁免与 12 条 High 一一对应）、check-test-baseline 判定通过（reports=203/tests=1280 达标）
- [x] 4.3 `scripts/merge-gate.sh` 全绿（Docker 实测在线按 runbook §6 口径；DASHSCOPE_API_KEY 置空）
      依据：docker info 实测在线（Client 29.6.2，退出码 0）；D:\git\Git\bin\bash.exe 跑 bash scripts/merge-gate.sh --with-verify 退出码 0，9 个子门禁全 PASS——[unit] 1280 全绿、[spotbugs]、[pmd]、[it] failsafe Tests run=116/Skipped=7、[baseline]、[frontend-unit] 17 files/179 tests passed、[hook]、[bijection]、[pmd-baseline]，尾部「== merge-gate 通过：所有已执行的子门禁绿 ==」
- [x] 4.4 tasks 全勾 + 6 空格依据行；raw 留档 `work/tmp/extend-ai-draft-and-eval/`；handoff 落 `work/handoff-extend-ai-draft-and-eval.md`（含偏差披露节）
      依据：tasks.md 14 格全勾且每格 6 空格依据行附实测命令+数字；raw 输出留档观察树 work/tmp/extend-ai-draft-and-eval/（checkstyle.log/spotbugs.log/spotless.log/pmd.log/pmd-baseline.log/spotbugs-staleness.log/test-baseline-check.log/merge-gate.log + EVIDENCE.md）；handoff 落观察树 work/handoff-extend-ai-draft-and-eval.md（含偏差披露节：计数断言分两笔落地、双通道字段面口径）
