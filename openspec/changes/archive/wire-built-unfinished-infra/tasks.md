# 任务清单 · 已建未接线基建接线合卡 (wire-built-unfinished-infra)

<!-- id: wire-built-unfinished-infra -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 三处接线/清理

- [x] 1.1 通读 v2 池暗线①相关条目（TokenBudget 预算闸 / ContentSecurityService 零调用 / ai.model.* 死键）取全锚点；实测接线点，handoff 留痕接线方案与语义口径
      C-7 锚点实测：TokenBudgetService.check() 全仓零调用、TokenBudgetProperties.enabled 默认 true、六类计量与四 scope 分池齐备；实际生效仅 AiChatResumeSupport 频次限流。
      J-9 锚点实测：ContentSecurityService.evaluate/wrapUntrustedContent/落库全实现零调用，规则=注入模式(HIGH/BLOCK)+疑似凭据(MEDIUM/DEGRADE)+兜底 SAFE/ALLOW，落库经 ObjectProvider 缺席自动跳过。
      C-8 锚点勘误（关键）：application.yml/.env.example 从未有 dotted ai.model.* 键（.env 仅 SLZ_AI_* 静态注入路径且全字段消费中）；死键真实藏身处 = DynamicConfigKeyRegistry 六 def + ConfigKeyTierPolicy 三档封闭集 6 条目，执行按 C-8 建议路径 2「从注册表摘除」而非字面「清 yml」。
      依据：`work/handoff-wire-built-unfinished-infra.md` §2。
- [x] 1.2 TokenBudget 预算闸接入 AI 链路消费点（超限复用配额既有错误语义）；ContentSecurity 两处接线（摄取 + 对话入口，命中/放行两向单测）；ai.model.* 死键清理（yml + .env.example）
      预算闸：rejectIfBudgetExceeded（USER/CHAT/estimatedTokens=0 先查后扣）经 rejectIfThrottled 合并入口挂 doStreamChat（resume 后、saveMessage 前）；超限= SSE sendError(TOKEN_BUDGET_EXCEEDED)+complete 与频次限流同构，retryAfterSeconds 进消息；enabled 默认 true→false 落地「默认关灰度」（生产启用 platform.token-budget.enabled=true）；单测两向 AiChatResumeSupportBudgetGateTest 4 例。
      内容安全：对话入口 AiChatContentSecuritySupport.secureUserContent（evaluate+wrap，BLOCK 抛 ContentRiskBlockedException → doStreamChat 专项 catch 发 CONTENT_BLOCKED 不并入 LLM_ERROR；DEGRADE 包 untrusted_content；ai_message 存原文口径不变）+ 摄取入口 DocumentIngestionService.ingest 存储前缓冲评估（BLOCK 零文件写零落库、DEGRADE/ALLOW 继续）；两处均 required=false 字段注入+判空，三兼容构造器零动；单测两向 PromptServiceTest +3 / DocumentIngestionServiceTest +2 / AiChatServiceImplTest 端到端 +1。
      死键清理：registry 六 def 摘除（ai.model 命名空间保留白名单）、tier 34/25/7→32/23/5、census 66→60、yml 误导注释改静态绑定口径、.env.example 实测零命中未改、docs/dynamic-config-keys.md 60 键口径+摘除记录行、测试样例键换血（minScore/fusion.mode/rateLimit.perMinute）；grep 证净=活代码仅剩 Spring 框架 import 误命中 + V6 冻结注释（禁改红线）。
      依据：`work/handoff-wire-built-unfinished-infra.md` §2.1/§2.2/§2.3。

## 任务组 2 · 门禁与物料

- [x] 2.2 基线只增（实测为准）`--update`；merge-gate 8/8（Docker 在线 9/9）；typed-router 零 diff；产出 `work/handoff-wire-built-unfinished-infra.md`（真实披露：接线点清单、语义口径、死键清单）
      全量 mvn test 1583/0/0/0 EXIT=0（254/1573→255/1583，+10 恰为本卡新增）；check-test-baseline.sh 先证 CHECK-EXIT=0 再 --update 写入 255/1583（source-revision=bd1b9f3 净树 stamp）。
      首轮 merge-gate --with-verify EXIT=1：IT 本体 118/0/7 全绿，红因纯为 verify 绑定 pmd:check 3 条（PromptService NCSS 153/150、AiChatServiceImpl NCSS 151/150 + doStreamChat CC 15/15，接线增量顶破既有阈值）——收敛笔 e766b19 拆 AiChatContentSecuritySupport/rejectIfThrottled/rejectContentBlocked，行为零变三测试类 24/30/4 复绿。
      终轮 merge-gate --with-verify（HEAD=e766b19，Docker 29.6.2 实测在线，work/_wire-infra-mergegate2.log）EXIT=0 9/9 全 PASS：[unit]1583 / [spotbugs]0 / [pmd]0==0 / [it]118/0/0/7 / [baseline] / [frontend-unit]197/197 / [hook] / [bijection]12↔12 / [pmd-baseline]0==0；typed-router.d.ts 行尾噪声按门禁 NOTE 还原后零 diff。
      依据：`work/handoff-wire-built-unfinished-infra.md` §1/§4（含首轮红因与收敛笔真实披露）。
