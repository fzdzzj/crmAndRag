# 交接 · wire-built-unfinished-infra（已建未接线基建接线合卡，B 档，本批三卡 3/3）

> 分支 `feature/wire-built-unfinished-infra`，自 master@454a68d 检出；完工停步：未 merge、未 push。
> 执行树：权威树 D:\code\crmAndRag-merge-add-knowledge-admin-api（观察树仅读 v2 池锚点，未触碰）。

## 1 · 提交清单（线性 5 笔 + 物料笔）

- `f1b2683` feat(ai): wire token budget gate into chat stream entry (gray-off default)（AiChatResumeSupport + AiChatServiceImpl + TokenBudgetProperties + 2 测试文件）
- `2ac6f4a` feat(security): wire content security into chat prompt and document ingestion（AiChatPromptService + DocumentIngestionService + AiChatServiceImpl catch + 3 测试文件）
- `bd1b9f3` chore(config): remove dead ai.model.* dynamic keys (registry+tier+doc)（registry/tier/契约 javadoc/application.yml 注释 + 5 测试文件 + docs/dynamic-config-keys.md）
- `59a7d59` chore(baseline): bump surefire baseline 1573 to 1583 (10 wiring gate tests)（scripts/test-baseline.txt，source-revision=bd1b9f3 净树 stamp）
- `e766b19` refactor(ai): extract wiring helpers to satisfy pmd ncss/complexity gates（**门禁收敛笔**：首轮 merge-gate pmd 红 3 条——AiChatPromptService NCSS 153/150、AiChatServiceImpl NCSS 151/150 + doStreamChat CC 15/15，均为接线增量顶破既有阈值；处置=安全评估逻辑拆 `AiChatContentSecuritySupport`（server/ai 包内新支持类，PromptService 只留字段+一行委托）、限流+预算两守卫合并 `rejectIfThrottled`（doStreamChat 守卫链零增分支）、拦截呈现拆 `rejectContentBlocked`（catch 体一行化）；行为零变，三相关测试类 24/30/4 复绿 + pmd:check BUILD SUCCESS 复证。笔数披露：实质笔 5 = 3 接线/清理 + 1 门禁收敛 + 1 基线，物料笔单列）
- 物料笔（docs-only）：openspec/changes/wire-built-unfinished-infra/（proposal 零改动入库 + tasks 勾选依据行）+ work/task-card-wire-built-unfinished-infra.md + 本 handoff（work/ 按 .gitignore 否定规则入库）

## 2 · 三处接线点与语义口径（任务 1.1/1.2）

### 2.1 TokenBudget 预算闸（v2 池 C-7，全仓零调用 → 接入聊天流入口）

- **接线点**：`AiChatResumeSupport.rejectIfBudgetExceeded`（静态 helper，依赖经参数传入）；经合并入口 `rejectIfThrottled`（限流 ‖ 预算，PMD CC 收敛笔引入，顺序=限流先、预算后）挂入 `AiChatServiceImpl.doStreamChat`——位于 resume 完成分支之后，续传重放不误伤，位于 saveMessage/占位/注册流之前，拒绝零残留。
- **检查口径**：`new TokenBudgetRequest(USER, String.valueOf(userId), TokenUsageType.CHAT, 0L)`——按用户分池、仅对话类型；estimatedTokens 按请求 record 契约「未知传 0」，即拦截「当前周期已超限」；**先查后扣**：扣 = 流终 `recordTokenUsage` 既有口径，零改动。
- **超限行为（复用配额既有错误语义）**：`eventWriter.sendError(emitter, decision.reason(), "Token 预算已用尽，请稍后再试（建议 N 秒后重试）") + emitter.complete()`——与 RATE_LIMITED 同构的既有 SSE 错误事件通道；code 取决策 reason = `TOKEN_BUDGET_EXCEEDED`（即 DynamicConfigKeyRegistry 对 business.quota.maxTokensPerSession 宣称的超限标识），retryAfterSeconds 进消息文案。零新增 ErrorCode 常量。
- **灰度口径**：`TokenBudgetProperties.enabled` 默认 true→false（v2 池候选表「C-7 默认关灰度」落地；这是接线姿态非内部逻辑——check/evaluate/scope 分池实现零改动）。生产启用置 `platform.token-budget.enabled=true`，开启后每次流式对话增加日/月两次计量 SUM 查询（关态短路零查询）。TokenBudgetServiceTest 两例原依赖默认开启，已显式 `setEnabled(true)`（行为断言不变）。
- **两向单测**：`AiChatResumeSupportBudgetGateTest` 4 例（mock 零外呼）——超限拒绝（code=TOKEN_BUDGET_EXCEEDED、消息含建议秒数、complete 调用）；预算内放行（请求形状 USER/"7"/chat/0 断言 + 零响应通道交互）；服务/userId 缺席空跳过；sendError 返回 false 不影响拒绝判定。
- **缺席语义披露**：闸依赖 `@Autowired(required=false)` + 判空——生产 Spring bean（@Service）恒在即生效；手工装配单测缺席时空跳过（house style，与 knowledgeRetrievalService 等同构）。

### 2.2 ContentSecurityService 两处接线（v2 池 J-9，全仓零调用 → 两处接线即上线）

- **接线点 A（AI 对话入口）**：`AiChatContentSecuritySupport.secureUserContent`（server/ai 包内接线支持类，PMD 收敛笔自 PromptService 拆出）被 `AiChatPromptService.buildMessages` 调用，对**进入模型上下文的改写问句**（rewrittenQuery）执行 `evaluate(USER_INPUT, sessionId, content)`（落 platform_content_security_event 审计行 + Micrometer 计数）→ `wrapUntrustedContent` 按服务已实现语义处置：BLOCK 抛 `ContentRiskBlockedException`；DEGRADE（疑似凭据）以 `<untrusted_content risk="medium">…</untrusted_content>` 降级包装；ALLOW 原文逐字节不变。
- **拦截的 SSE 呈现**：`doStreamChat` 新增 `catch (ContentRiskBlockedException)` 位于通用 `catch (Exception)` 之前 → `AiChatResumeSupport.rejectContentBlocked`（WARN + `sendError(emitter, "CONTENT_BLOCKED", reason)` + complete）——复用既有 SSE 错误事件语义明示拦截原因，不并入 LLM_ERROR。BLOCK 发生在 saveMessage 之前 → ai_message 不落该轮用户消息（原始消息口径不受 wrap 影响——ai_message 存原文、prompt 用包装文本，两态分离与短问改写既有口径一致）。
- **接线点 B（知识摄取入口）**：`DocumentIngestionService.ingest` 在授权+类型校验后、**任何存储/落库动作前**：`readAllBytes` 缓冲全文 → `evaluate(DOCUMENT, kbId, UTF-8 解码全文)` → `isBlocked()` 抛 `ContentRiskBlockedException`（零文件写、零 uploaded_file 行、零 chunk 行）；DEGRADE/ALLOW 继续原摄取链（文档级不 wrap——块级包装属 RETRIEVAL_CHUNK 范畴，另案）。store 改收缓冲字节流，下游 sha256/解析链不变。
- **依赖注入形态披露**：两服务均为 `@Autowired(required=false)` 字段 + 判空空跳过（PromptService 与仓内 conversationMemoryService 同构；DocumentIngestionService 特殊——它有 3 个兼容构造器且测试全直连，字段注入使三构造器签名零动、既有测试零改）。生产上下文 bean 恒在即生效；evaluate 的落库经 ObjectProvider，mapper 缺席自动跳过（单测零落库）。
- **两向单测**：`AiChatPromptServiceTest` +3（注入 BLOCK 抛 ContentRiskBlockedException / 凭据 DEGRADE 包装断言标签前后缀+原文 / 正常 ALLOW 逐字节不变）；`DocumentIngestionServiceTest` +2（BLOCK 断言 store/insert/insertBatch 三 never 零持久化 / 凭据 DEGRADE 仍入库 1 chunk + store 调用）；`AiChatServiceImplTest` +1 端到端（真实 buildMessages 链非 spy 绕行：注入消息 → CONTENT_BLOCKED + complete + saveMessage never）。
- **已知余量**：`KnowledgeAdminService.upload` 既有 `catch(Exception) → RuntimeException("upload failed")` 会把拦截包成通用上传失败（用户不见原因，审计事件已在 platform_content_security_event 留痕含 reason/matchedRule）；明示化属异常映射改造，未纳入本卡。

### 2.3 ai.model.* 死键清理（v2 池 C-8）——卡面口径勘误与证据

- **实测勘误**：application.yml 与 .env.example **从未存在** dotted `ai.model.*` 键（.env.example 仅 `SLZ_AI_*` → `platform.ai.model.*` 静态绑定注入路径，活的且全字段消费中；yml 静态块 8 键与 ModelProviderProperties POJO 一一对应）。死键真实藏身处 = `DynamicConfigKeyRegistry` 六个 def（provider/chatModel/visionModel/embeddingModel/temperature/maxTokens）+ `ConfigKeyTierPolicy` 三档封闭集 6 条目。执行按 C-8 建议路径 2「从注册表摘除」落地，而非卡面字面的「清 yml」；yml 侧唯一真实残留 = 「可被 Lane E 动态配置覆盖」误导注释，已改为静态绑定口径（零风险清理的 yml 侧实质内容）。**.env.example 实测零命中，未改。**
- **摘除面**：registry 六 def 全删（`"ai.model"` 命名空间保留于 NAMESPACES 白名单——V6 DDL 约定沿用、供未来桥接复用，测试 namespacesComplete 十一命名空间断言不动）；tier 封闭集 34/25/7→32/23/5（javadoc 同步）；census 测试 66→60；契约接口 `DynamicConfigService` javadoc 摘 ai.model 示例键并留变更记录行。
- **测试样例键换血**（死键被测试当样例使用，摘除后必须换）：RegistryTest DOUBLE 样例 temperature→`rag.retrieval.minScore`（同为 0.0~1.0，边界/越界语义同构）、枚举样例 provider→`rag.retrieval.fusion.mode`（同为白名单 STRING）、INTEGER 越界 maxTokens→`business.rateLimit.perMinute`（1~10000）；ServiceImplTest get 样例同构换 minScore；AdminServiceTest rollback/delete/update 三例与 TierAclIT 两例 COST 档样例 chatModel→`business.rateLimit.perMinute`（同为超管专写档，ACL 语义不变）。
- **悬空引用证据（`grep -rn 'ai\.model\.[a-zA-Z]'`，排除 `platform.ai.model.*` 静态前缀）**：
  - 活代码 2 命中均非本域键：`AiChatStreamLifecycle.java:16` = Spring 框架 import（`org.springframework.ai.model.tool.*`）；`V6__dynamic_config.sql:13` = **冻结迁移历史注释**（禁改红线，仅文件头约定说明）。
  - docs 3 命中均历史/记录性：`perf-baseline.md`、`TASK-07/{timeout-budget-summary,handoff}.md`（历史任务文档）+ `dynamic-config-keys.md` 摘除记录行（有意保留的处置留痕）。
  - `.env.example`：零命中。

## 3 · 边界执行自查（非目标）

死基建三表（platform_lifecycle_event / batch_task / batch_file_result）未触碰；TokenBudget/ContentSecurity 内部实现零改动（properties 默认值翻转与字段注入为接线姿态，已披露）；零迁移（未占任何 Vxx 号段）/ 零权限常量 / 零真实外呼（ContentSecurity 纯本地 contains 规则；预算闸只查本地计量表）/ 零前端。

## 4 · 门禁与基线（实测）

- 全量 `mvn -B -ntp test`：**1583 / 0 失败 / 0 错误 / 0 跳过，EXIT=0**（基线 254 报告/1573 测试 → 255/1583，+10 恰为本卡新增：预算闸 4 + Prompt 3 + 聊天链路 1 + 摄取 2）
- `bash scripts/check-test-baseline.sh`：先跑校验 `CHECK-EXIT=0`（reports>=254 tests>=1573 通过）→ `--update` 写入 255/1583（source-revision=bd1b9f3 净树 stamp，文件 10 行单块）
- 首轮 merge-gate --with-verify（Docker 29.6.2 实测在线）：**EXIT=1**——IT 本体 118/0/7 全绿，红因纯为 verify 阶段绑定的 pmd:check（3 条 NcssCount/CyclomaticComplexity），见 §1 e766b19 收敛笔；其余段 [unit][spotbugs][baseline][frontend-unit 197/197][hook][bijection 12↔12] 全过
- 终轮 merge-gate --with-verify（HEAD=e766b19，Docker 29.6.2 在线，日志 work/_wire-infra-mergegate2.log）：**EXIT=0，9/9 全 PASS**——[unit] 1583/0/0/0 / [spotbugs] 0 / [pmd] 实测 0==台账 0 / [it] 118/0/0/7 / [baseline] / [frontend-unit] 197/197 / [hook] / [bijection] 12↔12 / [pmd-baseline] 0==0
- typed-router.d.ts：零 diff（vitest 行尾噪声按门禁 NOTE `git checkout --` 还原后零改动）

## 5 · 另案余量（不阻塞本卡）

- RETRIEVAL_CHUNK 块级内容安全包装（检索片段进 prompt 前 evaluate+wrap）——v2 池另有候选，未纳入。
- reingest 存量重放入口不复评估（管理员恢复路径；新摄取已闸，存量是否补审待 owner）。
- 预算闸 estimatedTokens 恒 0（仅拦截「已超限」；预估式闸需 prompt token 估算，另案）；取消路径 token 不回冲（C-10）未动。
- upload 异常通道不区分拦截原因（见 2.2 已知余量）。

## 6 · 闭环终态记录（2026-10-17）

- **合并提交**：`a83c746`（merge --no-ff feature/wire-built-unfinished-infra 入 master，6+1 笔全入主干）；
- **远端 CI**：GitHub Actions Run `39024314105` 6/6 全绿（OpenAPI / Frontend 197 / build-and-gate 1583 单测 / fail-fast / static-analysis / thread-pool-metrics）；
- **测试基线**：Surefire 255 份报告 / 1583 用例 / 0 失败（+10 恰为本卡单测），Failsafe 31/118/7 skipped，`source-revision=bd1b9f3`；
- **复核裁决**：B 档独立复核 🟢 GO（报告落盘 `work/review-wire-built-unfinished-infra-b.md`），5 项 MINOR 按披露接受，本批三卡全链闭环收官。

