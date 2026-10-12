# proposal — aichat-prompt-template-integration（AiChat 运行时模板引用集成）

## Why

提示词模板管理已闭环（add-prompt-template-admin V32/V33 十一端点 + prompt-template-enhance V34 revert/feedback），但 AiChat 运行时消费面缺失：`AiChatPromptService` 仍从类路径静态文件加载（`prompts/system-prompt.txt` + `prompts/session-title.txt`，`@PostConstruct` 一次性读入，`promptVersion` = content sha256 前 8 字节）。模板库改了 AiChat 用不上——owner「后台现成配置、数据库记录」的定向只落了管理面。

## What Changes

1. **运行时模板态**：`AiChatPromptService` 每次构建消息时按固定 code 经 `PromptTemplateService.render`（空变量集）渲染：
   - `aichat-system-prompt`（系统提示词）/ `aichat-session-title`（会话标题提示词）
   - render 自带启用态校验（status≠0 拒绝）→ 停用即触发回退
2. **回退链**：模板不存在 / 禁用 / 渲染异常 → JUL WARN（含 code 与原因）+ 回退类路径静态文件，聊天主链路不断流；语义 = 「停用模板回到出厂提示词」
3. **V35 种子**：2 模板入 `prompt_template`（内容与现 txt 逐字节一致，category=AI_CHAT，无必填变量），开箱即用且模板态与静态态行为等价
4. **promptVersion 口径不变**（sha256(content) 前 8 字节）：模板态与回退态内容一致时版本一致，`AiChatStreamFinalizer` 流终观测面无缝；模板编辑后自然变化
5. **动态段不动**：意图/事实/摘要/澄清的 Java 追加逻辑（`buildSystemPrompt` L83-102）保持不变——模板只承载静态骨架，最小侵入

## 拍板点（D1-D6）

| # | 决策 | 建议 | 备选 |
|---|------|------|------|
| D1 | 集成面 | system-prompt + session-title 双模板化（同构一次接入） | 仅 system-prompt（title 留后续） |
| D2 | 回退语义 | WARN + 静态回退（聊天可用性优先，模板误删/误停不断流） | fail fast（模板库为唯一真相源） |
| D3 | 查询策略 | 每请求实时 render（变更即时生效，零缓存复杂度；单行索引查询开销可忽略） | 启动缓存 + 变更失效钩子（对齐 KbRetrievalStrategyService 先例） |
| D4 | 变量注入 | 本卡零变量（种子=现文本，行为逐字节等价；{{var}} 能力留给后续卡按需开） | 首批即开 {{userName}}/{{today}} 等 |
| D5 | 版本口径 | sha256(content) 前 8 字节不变（观测面同源可比） | code+version+content 复合哈希 |
| D6 | 观测增强 | 流终跟踪行补「模板态/回退态」标记（一行 JUL/跟踪字段，排障直读） | 不加（回退 WARN 已可排障） |

## Impact

- 写集预估 6-8 tracked files：AiChatPromptService + V35 + FlywayMigrationIT + 对应测试 + 三件套
- 无新端点 / 无新权限 / openapi 零变化（纯内部集成，PermissionCoverage 与 CONTROLLER_REGISTRY 零增量）
- 无新表（复用 prompt_template）→ SchemaDrift 零增量
- 门禁预期：surefire 只增（模板态/回退态/等价锁新用例）；failsafe 仅 FlywayMigrationIT 登记 +1
