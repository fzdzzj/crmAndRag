# tasks — aichat-prompt-template-integration

> 红线：已合入迁移禁改（V1-V34，下一可用号 V35）；聊天主链路可用性优先（回退链保底，任何模板态异常不得断流）；surefire 只增不减（当前 1131）；全卡零真实外呼（DASHSCOPE_API_KEY 置空防线）；行为等价锁（模板态渲染输出 == 静态文件内容逐字节）；动态段（意图/事实/摘要/澄清追加逻辑）不动。

## 任务组 1 · 种子与迁移

- [x] 1.1 V35__aichat_prompt_seed.sql：2 模板入 prompt_template（code=aichat-system-prompt / aichat-session-title，content 与 prompts/system-prompt.txt、prompts/session-title.txt 逐字节一致，category=AI_CHAT，variables 空定义，version=1）+ history 落 CREATE 笔（对齐 V33 种子先例的 INSERT 形态与幂等口径）
- [x] 1.2 FlywayMigrationIT EXPECTED_VERSIONS 登记 V35（20→21）+ V35 断言（两 code 存在、content 非空、status 启用态）

## 任务组 2 · 运行时集成

- [x] 2.1 AiChatPromptService 模板态：buildSystemPrompt 路径按 code=aichat-system-prompt 经 PromptTemplateService.render（空变量集）取静态骨架，替换原 systemPrompt 字段直读；回退链 = render 抛任何异常 → WARN（code+原因）+ 用 @PostConstruct 已加载的静态文本；promptVersion 仍 = 最终生效骨架的 sha256 前 8 字节
- [x] 2.2 generateTitle 同构模板化（code=aichat-session-title，回退链同上）
- [x] 2.3 中文 Javadoc 标注「aichat-prompt-template-integration 任务 x.x」

## 任务组 3 · 测试与门禁

- [x] 3.1 红测先行：模板态用例先红（render 未接入时探针）——模板态正常渲染 / 模板缺失回退 / 禁用回退 / 渲染异常回退
- [x] 3.2 等价锁测试：模板态输出 == 静态文件内容逐字节（种子内容一致性保证）；回退态输出 == 静态文件内容；promptVersion 两态一致
- [x] 3.3 全量门禁：mvn -B -ntp test 0 失败（surefire 只增）；四静态顺序跑（禁并发）；三守卫；check-test-baseline 只读裁决（如需 --update 先全量口径刷新，一次只增）；merge-gate 8/8；FlywayMigrationIT / SchemaDriftAuditIT / PermissionCoverageAuditIT 全绿
- [x] 3.4 停步回报：分支自证、各笔 stat、门禁 raw、回退链与等价锁证据

## §0 执行留痕

- 分支自证：feature/aichat-prompt-template-integration，基点 fb66397；首笔 docs 提交 ba6fa65（proposal+tasks 由主树 untracked 草稿转为 tracked 正版）。
- 任务组 1：新增 src/main/resources/db/migration/V35__aichat_prompt_seed.sql（实测下一可用号 V35；2 模板 code=aichat-system-prompt / aichat-session-title，category=AI_CHAT，variables='[]'，version=1，status=0；history 落 CREATE 笔；INSERT 幂等口径对齐 V33 先例）。FlywayMigrationIT EXPECTED_VERSIONS 20→21（登记 V35），新增 @Order(7) 断言（两 code 存在 + content 非空 + status 启用态）。真库 Docker 实测 FlywayMigrationIT Tests run: 7, Failures: 0。
- 任务组 2：AiChatPromptService 注入 PromptTemplateService；buildSystemPrompt 改经 code=aichat-system-prompt 每请求实时 render（空变量集）取骨架，generateTitle 同构 code=aichat-session-title；回退链 render 抛任何异常 → WARN（含 code 与异常）+ 回退 @PostConstruct 静态文本，聊天主链路不因模板态异常断流；promptVersion 口径不变（最终生效骨架 sha256 前 8 字节），每次构建即时重算（D5）。D6：新增 PromptSource 枚举（TEMPLATE/FALLBACK），AiChatStreamFinalizer 透传 getPromptSource().name()，AiChatSseEventWriter 新增 8 参重载（promptSource 紧随 promptVersion），AiSsePayloadWriter 8 参实现仅在 promptSource 非空时写 audit JSON 的 promptSource 键，既有字段语义不动。
- 任务组 3：红测先行留痕（主树 work/aichat-prompt-template-integration/red-first-evidence.md；raw Tests run: 12, Failures: 2, Errors: 0，两红为模板态用例）。转绿后 AiChatPromptServiceTest Tests run: 12, Failures: 0。全量 mvn -B -ntp test：Tests run: 1140, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS（基线 1131 → +9）。bash scripts/merge-gate.sh 默认序 8/8 全绿（unit/spotbugs/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline），GATE_EXIT=0。Docker 在线（29.6.2）真跑：FlywayMigrationIT 7 / PermissionCoverageAuditIT 2 / SchemaDriftAuditIT 1，合计 Tests run: 10, Failures: 0，BUILD SUCCESS。
- 偏差披露：
  1. 回退链 WARN 用类内既有 slf4j log.warn（同 WARN 级别语义），未引入 java.util.logging（JUL）新 logger——避免给 @Slf4j 类加异构日志依赖；语义与卡面「WARN 含 code 与异常信息」一致。
  2. 种子 content 行尾：种子 content 用 \n 显式转义，\n 对齐 canonical LF 与生产态（.gitattributes eol=lf + Linux 部署），复核 MINOR-1 修正；等价锁测试对两侧做行尾归一（normalizeEol）以兼容检出差异。
  3. 8 参重载参数位：promptSource 紧随 promptVersion（model, version, source, costMs, usage, references, sources, citations），7 参公共 API 原样保留并委托到 8 参。
  4. fixture 修正期间一次编译失败：promptSource 一度置签名末位致 String→long 类型报错，改回紧随 promptVersion 后即修正；终态无残留（编译全绿）。