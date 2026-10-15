# handoff-add-ai-customer-360-insight — 客户 360° AI 智能商业诊断与会话交互深化

> 分支 `feature/add-ai-customer-360-insight` 自权威树 `master@2b2b46e` 检出，7 笔任务组提交悬于 master。
> **严禁**直接 push / 在 master 提交；合入需 `git merge --no-ff --no-edit master` 后保留分支。

## 一、交付内容

### 后端（src/main/java/com/slz/crm/server/ai/insight/）
- `CustomerInsightCollectorSupport`：客户 360° 五维快照聚合（跟进活跃/商机停滞+审批驳回/合同回款履约/高优待办+联系人覆盖），直连 7 mapper selectList，零写路径。
- `CustomerInsightRuleEngine`：确定性健康分 0~100（活跃30/商机25/合同25/任务联系人20）+ 等级映射 + 规则风险预警。
- `CustomerInsightService`：数据层 DataScope 守卫 → 聚合 → 规则评分 → ModelProvider 结构化摘要/风险/建议；模型未装配/超时/外呼/解析异常**恒定回退规则文本，绝不抛 500**；纯只读。
- `CustomerInsightLlmPayloadSupport`：JSON 安全载荷构建协作类（剥离 localdatetime 序列化依赖 + 控类 NCSS）。
- `CustomerInsightHealthLevel/CustomerInsightSnapshot/CustomerInsightDimensionScore/CustomerInsightResult`。
- `server/controller/AiInsightController`：`GET /ai/insight/customer/{companyId}` 挂 `CUSTOMER_VIEW_COMPANY(117)`，DataScope 守卫跨部门无权 12002。

### AI 交互层
- `AiToolRegistry` 注册只读 `getCustomerInsight`（117 同锚）；`AiQueryToolExecutors.executeGetCustomerInsight` 委托诊断服务。
- 会话标题结果侧 `AiChatPromptService.clampTitleLength` ≤12 字（**提示词模板与迁移种子逐字节冻结未改**，只改结果侧）。

### 前端
- `AiAssistantDrawer.vue` 预置 4 枚快捷药丸（诊断客户健康度/汇总今日待办/统计本月商机/起草商业拜访纪要），点击一键填充并发送。
- `frontend/openapi.yaml` 由 `check-openapi-consistency.sh --update` 同步（+77 行仅新增 insight 端点与 schema），`pnpm gen:api` 已再生 SDK（生成物 gitignore）。

### 权限门禁登记
- `PermissionCoverageScanner.CONTROLLER_REGISTRY` 已登记 `AiInsightController`（否则 PermissionCoverageScannerTest 红）。

## 二、测试与门禁证据（实测）

- **surefire**：`mvn -B -ntp clean verify` → 1415 run / 0 fail / 0 error（基线 1391 → +24）。
- **failsafe**：116 run / 0 fail / 7 skip（本地 Docker 实测）。
- 新增单测：RuleEngine 8 / Collector 2 / Service 10（模型 Mock 结构化输出、异常/未装配/非法输出安全回退零 500、DataScope 反向 12002、客户不存在 20001）。既有更新：AiQueryToolExecutors +2、AiToolRegistryContext 权限面断言、AiChatPromptService +2。
- **四静态**：checkstyle 0 / spotbugs 0 / spotless check ✓ / pmd check 0 违规。
- **前端三检**：lint:check / type-check:check / vitest 179 pass。
- **回归基线**：树净 `check-test-baseline.sh --update` 写入 `scripts/test-baseline.txt`（surefire 217 报告 / 1415；failsafe 31 / 116 / skip 7；source-revision=1233cf6）。
- **merge-gate**：`scripts/merge-gate.sh` → unit / spotbugs / pmd / baseline / frontend-unit / hook / bijection / pmd-baseline **8/8 PASS**，退出码 0。

## 三、红线遵守

- 诊断异常恒定安全降级回退，零 500；零 Flyway 迁移、零新权限常量（复用 117）；零真跑外呼（纯 JVM mock，DASHSCOPE_KEY 未装配）；零业务数据写操作；分支在册未 push。

## 四、待决策 / 注意事项

1. **合入授权**：分支合入 master 前需 owner 显式授权执行 `git merge --no-ff`；未经授权严禁 push。
2. **WSL bash 陷阱**：本机 `bash` 走 WSL（Git Bash 在 `D:\git\Git\bin`），Git Bash 跑 bash 脚本须加 `D:\git\Git\bin;D:\git\Git\usr\bin` 到 PATH；`scripts/check-test-baseline.sh` 相对路径偶发解析到观察树 `D:\code\crmAndRag\target`，用绝对 `/d/code/.../scripts/xxx.sh` 调用可根治。
3. **标题 4~12 字**采结果侧夹紧（≤12 字），4 字下限不臆造补字——模型输出超短标题按原样落库。
4. **openapi 契约**：后续任何改动后重跑 `check-openapi-consistency.sh --update` + `pnpm gen:api`。
5. 交接快照：`work/handoff-add-ai-customer-360-insight.md`（本次在权威树生成并入 git 跟踪，与既有 handoff-*.md 同级）。