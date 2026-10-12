# tasks — aichat-prompt-template-integration

> 红线：已合入迁移禁改（V1-V34，下一可用号 V35）；聊天主链路可用性优先（回退链保底，任何模板态异常不得断流）；surefire 只增不减（当前 1131）；全卡零真实外呼（DASHSCOPE_API_KEY 置空防线）；行为等价锁（模板态渲染输出 == 静态文件内容逐字节）；动态段（意图/事实/摘要/澄清追加逻辑）不动。

## 任务组 1 · 种子与迁移

- [ ] 1.1 V35__aichat_prompt_seed.sql：2 模板入 prompt_template（code=aichat-system-prompt / aichat-session-title，content 与 prompts/system-prompt.txt、prompts/session-title.txt 逐字节一致，category=AI_CHAT，variables 空定义，version=1）+ history 落 CREATE 笔（对齐 V33 种子先例的 INSERT 形态与幂等口径）
- [ ] 1.2 FlywayMigrationIT EXPECTED_VERSIONS 登记 V35（20→21）+ V35 断言（两 code 存在、content 非空、status 启用态）

## 任务组 2 · 运行时集成

- [ ] 2.1 AiChatPromptService 模板态：buildSystemPrompt 路径按 code=aichat-system-prompt 经 PromptTemplateService.render（空变量集）取静态骨架，替换原 systemPrompt 字段直读；回退链 = render 抛任何异常 → JUL WARN（code+原因）+ 用 @PostConstruct 已加载的静态文本；promptVersion 仍 = 最终生效骨架的 sha256 前 8 字节
- [ ] 2.2 generateTitle 同构模板化（code=aichat-session-title，回退链同上）
- [ ] 2.3 中文 Javadoc 标注「aichat-prompt-template-integration 任务 x.x」

## 任务组 3 · 测试与门禁

- [ ] 3.1 红测先行：模板态用例先红（render 未接入时探针）——模板态正常渲染 / 模板缺失回退 / 禁用回退 / 渲染异常回退
- [ ] 3.2 等价锁测试：模板态输出 == 静态文件内容逐字节（种子内容一致性保证）；回退态输出 == 静态文件内容；promptVersion 两态一致
- [ ] 3.3 全量门禁：mvn -B -ntp test 0 失败（surefire 只增）；四静态顺序跑（禁并发）；三守卫；check-test-baseline 只读裁决（如需 --update 先全量口径刷新，一次只增）；merge-gate 8/8；FlywayMigrationIT / SchemaDriftAuditIT / PermissionCoverageAuditIT 全绿
- [ ] 3.4 停步回报：分支自证、各笔 stat、门禁 raw、回退链与等价锁证据

## §0 执行留痕

（执行方填写）
