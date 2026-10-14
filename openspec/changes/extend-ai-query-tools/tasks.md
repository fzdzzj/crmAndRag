# extend-ai-query-tools — 任务清单

> 提交粒度与本任务组对齐：每任务组一笔提交，分支 `feature/extend-ai-query-tools` 自权威树 master 检出。
> 依据行格式：6 空格 + 实测证据（命令 + 关键数字/哈希）。未实际执行的步骤禁止打勾。

## 任务 1 · 注册表扩展（AiToolRegistry）

- [x] 1.1 `getPermittedToolCallbacks` 追加 6 个 `add()` 注册：queryContactTask（TASK_VIEW_TASK）/ queryTaskComment（TASK_VIEW_TASK）/ queryBusinessActivity（SALES_VIEW_BUSINESS_ACTIVITY）/ getContractDetail（SALES_VIEW_CONTRACT）/ getOpportunityDetail（SALES_VIEW_SALE_OPPORTUNITY）/ queryStageApproval（SALES_VIEW_SALE_OPPORTUNITY_STAGE）；description 与 inputSchema 对齐既有工具文案风格（含查空放宽引导）
      依据：git diff src/main/java/com/slz/crm/server/ai/AiToolRegistry.java 实测确认追加 6 处注册，权限锚与描述对齐既有工具风格
- [x] 1.2 若单方法超长触发 PMD（ExcessiveMethodLength 等），按域拆私有方法（等价搬移零行为变化），拆分后 `AiToolExecutorSplitTest` 等既有防呆测试全绿
      依据：mvn -B -ntp pmd:check 退出码 0，AiToolRegistry 拆分后 NCSS 达标，AiToolExecutorSplitTest (2 tests pass) 实测全绿
- [x] 1.3 `AiToolRegistryContextTest` 补断言：持有对应权限的用户可见 6 新工具；null 用户仍 9 个、低权样本用户仍 11 个（新工具全挂权限，既有计数断言预期不变，若实测变化需在依据行说明原因）
      依据：mvn -B -ntp test -Dtest=AiToolRegistryContextTest 退出码 0（8 tests pass），null 用户 9 / 低权样本 11 计数不变，有权用户对应 6 工具均可见断言全绿

## 任务 2 · 执行器实现与单测（AiQueryToolExecutors）

- [x] 2.1 `executeQueryContactTask`：对齐 ContactTaskService 查询方法，参数含 assigneeId/companyId/contactId/opportunityId/分页
      依据：AiQueryToolExecutors.executeQueryContactTask 调 ContactTaskService.query，单测 executeQueryContactTask_parsesArgumentsAndReturnsPageRecords 验证参数解析与分页
- [x] 2.2 `executeQueryTaskComment`：taskId 必填，分页可选
      依据：AiQueryToolExecutors.executeQueryTaskComment 调 TaskCommentService.getByTaskId，单测覆盖正常返回与 taskId 为空抛错路径
- [x] 2.3 `executeQueryBusinessActivity`：对齐 BusinessActivityService 查询方法
      依据：AiQueryToolExecutors.executeQueryBusinessActivity 调 BusinessActivityService.getDetailById 与 businessActivityQuery，单测覆盖详情与列表两条路径
- [x] 2.4 `executeGetContractDetail`：contractId 必填，调 ContractService 详情方法
      依据：AiQueryToolExecutors.executeGetContractDetail 调 ContractService.getDetailById，单测覆盖详情正常返回与 contractId 为空抛错路径
- [x] 2.5 `executeGetOpportunityDetail`：opportunityId 必填，调 SalesOpportunityService 详情方法
      依据：AiQueryToolExecutors.executeGetOpportunityDetail 调 SalesOpportunityService.getOpportunityDetailById，单测覆盖详情正常返回与 opportunityId 为空抛错路径
- [x] 2.6 `executeQueryStageApproval`：调 SalesStageApproval 既有列表查询 service
      依据：AiQueryToolExecutors.executeQueryStageApproval 调 SalesStageApprovalService.getPage，单测覆盖状态列表与单状态解析两种路径
- [x] 2.7 新增执行器行为单测（纯 JVM mock service）：每工具至少覆盖正常返回 + 参数解析路径；分页裁剪/查空提示与既有工具模式一致
      依据：mvn -B -ntp test -Dtest=AiQueryToolExecutorsTest 退出码 0（12 tests pass，纯 JVM 无外部依赖，覆盖正常/异常/trimPageResult 裁剪路径）

## 任务 3 · execution.md 遗留收口（owner 拍板随卡带上）

- [ ] 3.1 观察树 `docs/main-agent-execution.md` 的未提交修改内容经权威树提交收口（将观察树 diff 应用到权威树同名文件后随任务组提交，或首笔独立提交）；收口后观察树 checkout 合并结果时工作树净
  - 依据：

## 任务 4 · 门禁与基线收尾

- [ ] 4.1 `mvn -B -ntp test` 全绿，surefire 数量只增不减（基线 1252），与 `scripts/test-baseline.txt` 比对
  - 依据：
- [ ] 4.2 四静态顺序跑（checkstyle / spotbugs / spotless / pmd）全绿 + `pmd-baseline-check.sh` + `spotbugs-exclude-staleness-check.sh` 通过（如有新违规按台账规约处理，只许下调）
  - 依据：
- [ ] 4.3 三守卫门禁 + `scripts/merge-gate.sh` 全绿（Docker 实测在线，failsafe IT 含 Testcontainers MySQL 按 runbook §6 口径）
  - 依据：
- [ ] 4.4 复核档位 B 档证据链留档：raw 输出落 `work/tmp/extend-ai-query-tools/`（或复核侧目录），门禁结论可复现
  - 依据：
