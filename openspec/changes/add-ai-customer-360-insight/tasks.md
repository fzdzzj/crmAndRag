# add-ai-customer-360-insight — 任务清单

> 提交粒度与任务组对齐：每任务组一笔提交，分支 `feature/add-ai-customer-360-insight` 自权威树 master 检出。
> 依据行格式：6 空格 + 实测证据（命令 + 关键数字/哈希）。未实际执行的步骤禁止打勾。

## 任务 1 · 多实体数据聚合与规则诊断引擎（数据与规则层）

- [x] 1.1 实现 `CustomerInsightCollectorSupport`：在权限内聚合客户基础画像、跟进活动频率、商机停滞天数、合同与回款履约、高优待办任务与联系人覆盖多维快照
      `src/main/java/.../ai/insight/CustomerInsightCollectorSupport.java`，collected 直连 7 mapper selectList 零写路径
- [x] 1.2 实现 `CustomerInsightRuleEngine`：量化确定性健康分（0~100）与健康等级（EXCELLENT/HEALTHY/WARNING/CRITICAL），提取底层规则风险预警清单
      `mvn -B -ntp test -Dtest=CustomerInsightRuleEngineTest` → Tests run 8 Failures 0；健康分 69/77/83/42 边界与等级逐分对齐

## 任务 2 · LLM 智能洞察服务与安全降级（模型与服务层）

- [x] 2.1 实现 `CustomerInsightService`：通过 `ModelProvider` 生成一句话商业摘要、成单风险点与下一步销售拜访建议；模型异常或未配置时安全降级为规则文本，绝对不抛 500
      `mvn -B -ntp test -Dtest=CustomerInsightServiceTest` → Tests run 10，降级路径 llmEnhanced=false 零 500
- [x] 2.2 实现 `AiInsightController` 端点 `GET /ai/insight/customer/{companyId}`：挂 `@RequirePermission(CUSTOMER_VIEW_COMPANY)`，复用 DataScope 守卫拦截无权限访问
      DataScope 反向 catchThrowableOfType(BaseException).getCode()=12002；controller 已登记 PermissionCoverageScanner

## 任务 3 · AI Tool 注册与会话标题提炼（AI 交互层）

- [x] 3.1 在 `AiToolRegistry` 注册只读查询工具 `getCustomerInsight` 并同锚 `CUSTOMER_VIEW_COMPANY`（117），支持大模型自然语言识别并触发诊断
      AiToolRegistryContextTest 权限面断言通过；AiQueryToolExecutorsTest 委托诊断 service
- [x] 3.2 实现会话标题智能提炼：在首轮对话完成后，提炼 4~12 字简洁业务标题并异步更新 `aiSessionService.updateTitle`
      AiChatPromptService.clampTitleLength 结果侧 ≤12 字；AiChatPromptServiceTest 2 条夹紧断言绿（提示词模板冻结未改种子）

## 任务 4 · 前端交互与快捷药丸（前端层）

- [x] 4.1 在 `AiAssistantDrawer.vue` 预置高频业务快捷指令药丸（Prompt Starter Pills），点击一键填充/发送
      `pnpm lint:check` + `pnpm type-check:check` + `pnpm vitest run` 全绿（179 pass）
- [x] 4.2 若新增端点触发 OpenAPI 契约漂移，运行 `bash scripts/check-openapi-consistency.sh --update` 同步 `frontend/openapi.yaml`
      openapi diff +77 行仅新增 /ai/insight/customer/{companyId}；`pnpm gen:api` 已再生 SDK，OpenApiContractExportTest 绿

## 任务 5 · 单元测试与门禁收尾（门禁层）

- [x] 5.1 数据聚合与规则引擎单测：覆盖不同跟进间隔、商机逾期、未回款款项的健康分确定性量化与评级
- [x] 5.2 诊断服务单测与降级测试：Mock `ModelProvider` 验证结构化输出，以及模型异常时安全回退零 500 验证
- [x] 5.3 权限反向单测：归属人及上级正常获取诊断，无权限同事访问返回 12002 `PERMISSION_DENIED`
- [x] 5.4 前端质量门禁：前端单元测试与类型检查全绿（`pnpm lint:check` + `pnpm type-check:check` + `pnpm vitest run` 179 pass）
- [x] 5.5 `mvn -B -ntp test` 全绿，surefire 数量只增不减（基线 1391→1415），树净时单次 `--update` 基线
      `mvn -B -ntp clean verify` surefire 1415/0、failsafe 116/7；树净 `--update` 写入 baseline（source-revision=94f64be→1233cf6）
- [x] 5.6 四静态（checkstyle/spotbugs/spotless/pmd）顺序跑全绿 + 三守卫 + `scripts/merge-gate.sh` 退出码 0
      checkstyle 0/spotbugs 0/spotless check/PMD 0；`scripts/merge-gate.sh` → unit/spotbugs/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline 8/8 PASS
