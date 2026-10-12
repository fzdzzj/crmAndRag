# proposal — add-prompt-template-slots（提示词模板分段槽位化）

> 定位：把 AiChat 系统提示词的动态段从 Java 硬编码迁移到模板槽位——「分为多少块、什么顺序、块措辞」进入模板体系可自定义；检索内容槽位化预留通用机制，知识库检索接线另卡。
> 背景：分段能力分卡已 owner 拍板（嵌套引用卡边界），本卡即「section 元数据 + 检索槽位」实体卡。

## Why

- 现状（实测）：`AiChatPromptService.buildSystemPrompt` L96-113 将动态段四件——【当前意图】（memory.intent）、【已确认事实】（memory.facts）、【历史摘要】（memory.summary）、澄清提示（shortQuestionRewriter.clarifyPrompt()）——**固定顺序硬编码追加**在骨架尾部；标题措辞、段顺序、段开关全在 Java 代码，模板只能管静态骨架（V35 三块纯静态：角色/能力/规则，无动态占位）。
- owner 需求：块数量/顺序/措辞可自定义；检索回来的内容未来固定占位（现无任何占位机制）。
- 事实澄清：memory.facts 是持久记忆（对话中确认的业务事实），**不是**知识库检索结果——AiChat 当前没有知识库检索注入链路，检索接线（触发条件/检索源/成本授权）是独立决策面，本卡只做通用槽位机制。

## What Changes

- 槽位标记机制（第三种占位语法，与 `{{var}}` 变量、`{{> code}}` 引用正交）：`{{#slot:key}}` 标记在 content 中声明动态段插入点，render 接受 `slots` 值表整段替换；**顺序=标记在文本中的位置（挪标记即调序），块数量=标记数（增删即开关）**，标题措辞可随值或随模板。
- `PromptTemplateRenderDTO` 增可选字段 `slots`（Map<String,String>，向后兼容，旧调用零变化）。
- AiChat 双模式迁移：模板含槽位标记 → AiChat 组装 slots 值表注入、停用尾部硬编码追加；无标记（含静态回退态）→ 保留旧追加路径逐字节不变。
- V37 迁移：aichat-system-prompt content 尾部按现顺序加四槽位标记（version+1 + history 快照）；aichat-session-title 不动。
- **回归锁红线**：V37 模板态系统提示词与旧模式产出**逐字节等价**（标题/顺序/间距全等）。
- 检索槽位：机制通用（任意 key 可注入），本卡不做知识库检索接线。

## 拍板点（D1-D8）

| # | 点 | 建议 | 说明 |
|---|---|---|---|
| D1 | 分段机制流派 | **标记流**（content 内联槽位标记） | 备选「元数据流」（sections JSON + order 字段）需新端点+重排引擎，侵入大；标记流编辑 content 即调序/开关/增块，零新端点，聊天链路风险最低 |
| D2 | 槽位语法与语义 | `{{#slot:key}}`；slot 值=**整段成品**（含标题与 \n\n 间距）；未提供=替换为空串；slot **不参与 variables 校验**（与 {{var}} 语义分离：变量=静态骨架必填校验，槽位=运行时可选注入） | 标题随值走（AiChat 侧组装，最小改动）；备选「标题进模板」需拆标题/值两半，措辞模板化需求低不取 |
| D3 | render API | RenderDTO 增可选 `slots` 字段；openapi 同步（--update）；旧调用（slots 缺省）零行为变化 | 零新端点/零新权限/零新增 ErrorCode |
| D4 | AiChat 迁移 | **双模式运行时判定**：模板态 content 含任一 `{{#slot:` 标记 → 注入模式（slots 四件 map，停用尾部追加）；无标记 → 旧追加路径逐字节保留（回退静态天然走旧路径，回退链红线零触碰） | 任一模式异常均按既有 WARN+回退链兜底 |
| D5 | V37 种子 | UPDATE aichat-system-prompt content 加四标记（currentIntent / confirmedFacts / historySummary / clarify，按现追加顺序）+ version+1 + history UPDATE 快照（v2）；FlywayMigrationIT 登记（22→23） | **回归锁**：V37 模板态与 V35 模式产出逐字节等价断言；aichat-session-title 零改动 |
| D6 | 检索槽位预留 | 机制通用零预留：本卡不加 `{{#slot:retrieval}}` 死标记进种子（空槽位=空段噪声）；知识库检索接线另卡拍板（触发条件/检索源/每次对话成本授权） | 接线卡时直接用本机制，零机制改动 |
| D7 | promptVersion/观测 | 口径不动：promptVersion=生效骨架 sha256（含标记文本，不含 slot 值——与现状「动态段不进 version」一致）；promptSource/promptVersion SSE 观测零改动 | 模板编辑后 version 随骨架重算的既有语义自然覆盖 |
| D8 | PMD/协作类 | 槽位标记抽取/检测/注入逻辑若致超阈 → 拆独立 Support（对齐 RenderSupport/NestingSupport 先例），§0 登记 | AiChat 侧改造控制在 buildSystemPrompt + 私有辅助方法 |

## 不做（边界）

- **知识库检索接线**（触发条件、检索数据源、RAG 上下文组装、真跑成本授权）——另卡；本卡机制已通用，接线卡零机制改动
- sections 元数据流（order/enabled 字段化重排引擎）、管理面新端点、frontend 页面
- 标题措辞模板化（标题随值走，D2）、`{{var}}` / `{{> code}}` 既有语义任何变更、嵌套卡 MINOR②去重（与本卡渲染链改动面交叠，届时顺带）
- contracts-frozen.md 零触碰；既有迁移 V1-V36 禁改；动态段数据来源（memory 三件 + 澄清生成）与生成逻辑逐字不动——只迁拼接位置
- 回退链红线不动：模板态任何异常 WARN+回退静态=旧路径（天然兼容）

## 影响面

- 写集预估：AiChatPromptService（主链路）+ NestingSupport（或新 SlotSupport）+ RenderDTO + V37 + FlywayMigrationIT + AiChatPromptServiceTest/PromptTemplateServiceTest/ControllerIT + openapi.yaml + 三件套 ≈ 9-10 tracked 文件
- 零外呼（DASHSCOPE_API_KEY 置空前缀）；零建表 DDL（V37 为 UPDATE 种子）；surefire 只增不减
- 最大风险面 = AiChat 聊天主链路：红测先行（标记态四件用例）+ 双模式回归锁（无标记模板逐字节旧路径 + V37 模板态逐字节等价）+ 回退链既有测试兜底
