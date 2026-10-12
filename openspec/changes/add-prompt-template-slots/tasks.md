# tasks — add-prompt-template-slots

> 红线：聊天主链路不断流（模板态任何异常 WARN+回退静态=旧路径）；回归锁双断言（无标记模板逐字节旧路径 + V37 模板态与 V35 模式产出逐字节等价）；零新增 ErrorCode；既有迁移 V1-V36 禁改；零外呼（DASHSCOPE_API_KEY 置空）；surefire 只增不减；四静态与全量 test 禁并发顺序跑；新代码中文 Javadoc 标注「add-prompt-template-slots 任务 x.x」；PMD 超阈拆 Support 对齐先例并 §0 登记；merge-gate 8/8。

## 任务组 1 · 槽位机制与 render 集成

- [x] 1.1 槽位标记语法 `{{#slot:key}}`：抽取/检测/替换实现（与 `{{var}}` 变量、`{{> code}}` 引用正交互不误匹配）；slot 值=整段成品直接替换，未提供=替换为空串；slot 不参与 variables 校验（rejectUndefined 不检 slot key）
- [x] 1.2 RenderDTO 增可选字段 `slots`（Map<String,String>，缺省 null/空表行为等价）；render 链集成：嵌套展开→变量校验/替换→槽位替换的次序排定（槽位在变量替换后执行，避免 slot 值中的 {{var}} 被二次替换——次序以实测定，§0 登记）；旧调用（无 slots）零行为变化（回归锁用例）
- [x] 1.3 PMD 超阈处置：槽位逻辑超阈即拆独立 Support（对齐先例），§0 登记
- 红测先行：标记检测/注入/空值消除/正交性（var 与 code 不误匹配）/旧调用零变化，留痕 work/ 日志域

## 任务组 2 · AiChat 双模式迁移与 V37（D4/D5）

- [x] 2.1 buildSystemPrompt 双模式：模板态含任一 `{{#slot:` 标记 → 注入模式（slots 四件 map：currentIntent/confirmedFacts/historySummary/clarify，值含标题与 \n\n 间距，条件空则不置入 map）；无标记（含回退静态）→ 旧追加路径逐字节保留
- [x] 2.2 V37__aichat_prompt_slot_seed.sql：UPDATE aichat-system-prompt content 尾部按现顺序加四槽位标记（version+1、history UPDATE 快照 v2、头注释 V21 起格式）；aichat-session-title 零改动；FlywayMigrationIT 登记 V37（迁移数 22→23）
- [x] 2.3 回归锁双断言：无标记模板走旧追加路径逐字节不变；V37 模板态系统提示词与旧模式产出逐字节等价（含三动态段全有/部分有/全无三态 + 澄清有无两态）
- [x] 2.4 promptVersion/promptSource 观测口径回归：V37 标记态 promptVersion=含标记骨架 sha256、SSE 观测键零变化

## 任务组 3 · 契约、测试与门禁

- [x] 3.1 openapi.yaml 同步 RenderDTO slots 可选字段（check-openapi-consistency.sh --update）；本地 pnpm gen:api（生成物 gitignore 不入库）
- [x] 3.2 AiChatPromptServiceTest 增强全绿（标记态注入/旧模板兼容/回退态/槽位空值）；ServiceTest/ControllerIT 槽位用例全绿
- [x] 3.3 全量 mvn -B -ntp test 0 失败（surefire 只增）；四静态顺序跑；三守卫；check-test-baseline 只读；merge-gate 8/8
- [x] 3.4 提交按任务组分笔 type(scope): 中文描述；tasks 全勾 + §0 留痕；停步回报（不合并不 push）

## §0 执行留痕

- 分支：`feature/add-prompt-template-slots`（权威树 master `3c59120` 检出）
- 各笔提交：
  - `07a997b` docs(openspec)：三件套（proposal/tasks）入分支为 tracked 正版
  - `404e08b` feat(server)：{{#slot:key}} 槽位机制接入 render 链 + RenderDTO slots + MINOR② 并集去重 + ServiceTest
  - `468d396` feat(server)：AiChat 双模式槽位注入 + V37 槽位种子 + 回归锁双断言 + Flyway 登记
  - `731866b` chore(openapi)：同步 RenderDTO slots 可选字段（--update）
  - `91047a8` refactor(server)：动态段装配收敛入 PromptSystemPromptSegments 压类规模保 PMD NCSS + OnlyOneReturn
- 槽位替换次序实测结论：render 链「嵌套展开 → 变量校验/替换 → 槽位替换」。SlotSupport.replaceSlots 置于
  RenderSupport.replacePlaceholders 之后，sanitize 保证 slot 值中可能出现 {{var}} 字面不被变量替换二次加工
  （正交性用例 renderSlotOrthogonalToVariables 断言「{{val}} 在 slot 值中原样保留」实测通过）。
- 偏差披露：
  1. 红测先行用临时旁路 replaceSlots（renderContent 不调用）捕获「功能未接入」基线 39 红6；接入后全绿，
     证据留痕主树 work/add-prompt-template-slots/red-first-evidence.md。MINOR② 去重为独立接入点，红测阶段即绿。
  2. `slots=null` 语义定为「未做槽位注入 → 标记原样保留供注入模式判定」，`slots=任意 Map（含空表）` 定为「本次注入 →
     消费所有标记（未提供 key→空串）」——这是 AiChat 双模式「先判定再注入」落地必需，避免检测渲染把标记消成空串。
  3. AiChat 侧类级 NCSS 超 classReportLevel=150 → 拆 PromptSystemPromptSegments（对齐先例），pmd 0。
  4. merge-gate 默认跳过 [it]（需 --with-verify）；IT 白名单（PromptTemplateControllerIT + FlywayMigrationIT）
     已单独 failsafe 真跑绿（Docker 在线实跑）。
- 门禁 raw 关键行：全量 surefire `Tests run: 1173, Failures: 0`（基线 1160 只增 13）；IT 白名单 15/0
  （ControllerIT 6、FlywayMigrationIT 9）；check-test-baseline 只读通过（surefire 1173≥1151、skipped 0）；
  checkstyle 0 / spotbugs 0 / spotless 0 / pmd 0；merge-gate 8/8 全绿。
