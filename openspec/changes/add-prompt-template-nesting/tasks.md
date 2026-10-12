# tasks — add-prompt-template-nesting

> 红线：零新增 ErrorCode（复用 92008/92027/PARAM_FORMAT_ERROR/PARAM_REQUIRED）；既有迁移 V1-V35 禁改；零外呼（跑测试前 DASHSCOPE_API_KEY 置空字符串）；surefire 只增不减；四静态与全量 test 禁并发顺序跑；新代码中文 Javadoc 标注「add-prompt-template-nesting 任务 x.x」；PMD NCSS 超阈拆协作类对齐 RenderSupport 先例并在 §0 登记；merge-gate 8/8。

## 任务组 1 · 展开引擎与 render 集成

- [ ] 1.1 NestingSupport：`{{> code}}` 抽取 + 递归展开（两遍法：先展开骨架、后变量替换）+ 展开栈防环 + 深度上限 8 常量；子模板缺失 92008 / 停用 92027 / 环与超深 PARAM_FORMAT_ERROR；引用取子模板当前启用版本
- [ ] 1.2 render 集成：展开后文本走既有变量校验与 replacePlaceholders；变量校验集 = 主模板 + 各层子模板 variables 并集（required 并集强制）；content 无 `{{> }}` 的模板走既有路径逐字节零变化（回归锁用例）
- [ ] 1.3 create/update 非阻断 WARN：content 中引用的 code 当前缺失时 JUL WARN（含模板 id 与缺失 code），不阻断保存
- 红测先行：ServiceTest 新用例基线先红留痕（展开/缺失/停用/环/深度/并集/回归锁），work/ 日志域

## 任务组 2 · V36 种子示范（D5）

- [ ] 2.1 V36__prompt_template_nesting_seed.sql：INSERT 公共子模板 agent-common-discipline（实读 V33 两骨架 content 比对抽取公共纪律段）+ UPDATE agent-executor-brief / agent-reviewer-brief content 嵌入 `{{> agent-common-discipline}}`（version+1、保留既有 {{var}} 占位不动）+ history 记 UPDATE 快照（INSERT...SELECT 先例）+ 头部注释 V21 起格式
- [ ] 2.2 FlywayMigrationIT 登记 V36（迁移数 21→22）；SchemaDriftAudit 无新增漂移
- [ ] 2.3 种子渲染回归：两骨架 render 输出含公共段展开结果；aichat-system-prompt / aichat-session-title render 输出逐字节不变

## 任务组 3 · 测试与门禁

- [ ] 3.1 ServiceTest 全绿（含 A→B→A 循环、深度上限触顶、子模板停用、前向引用保存放行+WARN）；ControllerIT render 端到端嵌套用例（808 VIEW）
- [ ] 3.2 全量 mvn -B -ntp test 0 失败（surefire 只增）；四静态顺序跑（checkstyle/spotbugs/spotless/pmd 各 0）；三守卫；merge-gate 8/8；check-test-baseline 只读（如需 --update 先 DASHSCOPE_API_KEY="" 全量真跑刷新口径，一次只增）
- [ ] 3.3 提交按任务组分笔 type(scope): 中文描述；tasks 全勾 + §0 留痕；停步回报（不合并不 push）

## §0 执行留痕

（执行方回填：分支、各笔提交、偏差披露、门禁 raw 关键行）
