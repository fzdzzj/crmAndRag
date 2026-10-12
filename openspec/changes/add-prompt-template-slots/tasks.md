# tasks — add-prompt-template-slots

> 红线：聊天主链路不断流（模板态任何异常 WARN+回退静态=旧路径）；回归锁双断言（无标记模板逐字节旧路径 + V37 模板态与 V35 模式产出逐字节等价）；零新增 ErrorCode；既有迁移 V1-V36 禁改；零外呼（DASHSCOPE_API_KEY 置空）；surefire 只增不减；四静态与全量 test 禁并发顺序跑；新代码中文 Javadoc 标注「add-prompt-template-slots 任务 x.x」；PMD 超阈拆 Support 对齐先例并 §0 登记；merge-gate 8/8。

## 任务组 1 · 槽位机制与 render 集成

- [ ] 1.1 槽位标记语法 `{{#slot:key}}`：抽取/检测/替换实现（与 `{{var}}` 变量、`{{> code}}` 引用正交互不误匹配）；slot 值=整段成品直接替换，未提供=替换为空串；slot 不参与 variables 校验（rejectUndefined 不检 slot key）
- [ ] 1.2 RenderDTO 增可选字段 `slots`（Map<String,String>，缺省 null/空表行为等价）；render 链集成：嵌套展开→变量校验/替换→槽位替换的次序排定（槽位在变量替换后执行，避免 slot 值中的 {{var}} 被二次替换——次序以实测定，§0 登记）；旧调用（无 slots）零行为变化（回归锁用例）
- [ ] 1.3 PMD 超阈处置：槽位逻辑超阈即拆独立 Support（对齐先例），§0 登记
- 红测先行：标记检测/注入/空值消除/正交性（var 与 code 不误匹配）/旧调用零变化，留痕 work/ 日志域

## 任务组 2 · AiChat 双模式迁移与 V37（D4/D5）

- [ ] 2.1 buildSystemPrompt 双模式：模板态含任一 `{{#slot:` 标记 → 注入模式（slots 四件 map：currentIntent/confirmedFacts/historySummary/clarify，值含标题与 \n\n 间距，条件空则不置入 map）；无标记（含回退静态）→ 旧追加路径逐字节保留
- [ ] 2.2 V37__aichat_prompt_slot_seed.sql：UPDATE aichat-system-prompt content 尾部按现顺序加四槽位标记（version+1、history UPDATE 快照 v2、头注释 V21 起格式）；aichat-session-title 零改动；FlywayMigrationIT 登记 V37（迁移数 22→23）
- [ ] 2.3 回归锁双断言：无标记模板走旧追加路径逐字节不变；V37 模板态系统提示词与旧模式产出逐字节等价（含三动态段全有/部分有/全无三态 + 澄清有无两态）
- [ ] 2.4 promptVersion/promptSource 观测口径回归：V37 标记态 promptVersion=含标记骨架 sha256、SSE 观测键零变化

## 任务组 3 · 契约、测试与门禁

- [ ] 3.1 openapi.yaml 同步 RenderDTO slots 可选字段（check-openapi-consistency.sh --update）；本地 pnpm gen:api（生成物 gitignore 不入库）
- [ ] 3.2 AiChatPromptServiceTest 增强全绿（标记态注入/旧模板兼容/回退态/槽位空值）；ServiceTest/ControllerIT 槽位用例全绿
- [ ] 3.3 全量 mvn -B -ntp test 0 失败（surefire 只增）；四静态顺序跑；三守卫；check-test-baseline 只读（如需 --update 先 DASHSCOPE_API_KEY="" 全量真跑刷新口径，一次只增）；merge-gate 8/8
- [ ] 3.4 提交按任务组分笔 type(scope): 中文描述；tasks 全勾 + §0 留痕；停步回报（不合并不 push）

## §0 执行留痕

（执行方回填：分支、各笔提交、偏差披露、门禁 raw 关键行、槽位替换次序实测结论）
