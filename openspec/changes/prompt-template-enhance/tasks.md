# tasks — prompt-template-enhance

> 红线：新端点全部挂 @RequirePermission（revert=809，feedback=808）；已合入迁移禁改；surefire 只增不减（当前 1124）；全卡零真实外呼（DASHSCOPE_API_KEY 置空防线）；实体↔DDL 零漂移；FlywayMigrationIT 登记 V34。

## 任务组 1 · DDL 与实体

- [x] 1.1 V34__prompt_template_feedback.sql 建表（头部注释 V21 起格式）+ PromptTemplateFeedbackEntity（@AutoTable 对齐）+ PromptTemplateFeedbackMapper（MP BaseMapper）
- [x] 1.2 FlywayMigrationIT EXPECTED_VERSIONS 登记 V34（19→20）

## 任务组 2 · 后端实现

- [x] 2.1 revert：Service.revert(id, targetVersion, expectedVersion)——history 取目标版本 content+variables 作新版本落档（version 递增 + history operation=REVERT 记 sourceVersion）；乐观锁 92001 / 目标版本不存在 92008；红测先行（三态）
- [x] 2.2 feedback：Service.addFeedback(id, version, score, comment)——version 存在性校验、score 1-5 越界拒绝、comment ≤512；listFeedback 分页倒序
- [x] 2.3 Controller 三端点：POST /{id}/revert（809）、POST /{id}/feedback（808）、GET /{id}/feedback/list（808）+ DTO/VO + 中文 Javadoc 标注「prompt-template-enhance 任务 x.x」
- [x] 2.4 openapi.yaml 契约同步（check-openapi-consistency.sh --update）+ 本地 pnpm gen:api（不入库）

## 任务组 3 · 测试与门禁

- [x] 3.1 PromptTemplateServiceTest 增强：revert 三态 + feedback 三态（记录/越界/版本不存在），全绿
- [x] 3.2 PromptTemplateControllerIT 增强：revert 端到端含审计链验证（REVERT 后 history 链完整含源版本）+ feedback 记录查询 + 权限正反向（VIEW 无 809 → revert 12002）
- [x] 3.3 全量门禁：mvn -B -ntp test 0 失败；四静态 0（顺序跑禁并发）；三守卫；check-test-baseline 只读（白名单 IT 后如需 --update 先全量口径刷新，一次只增）；merge-gate 8/8；SchemaDrift/PermissionCoverage 面向新增全绿
- [x] 3.4 停步回报：分支自证、各笔 stat、门禁 raw、revert 审计链证据（history 列表展示 REVERT 笔与 sourceVersion）

## §0 执行留痕

- 红测先行：revert 三态 + feedback 三态新用例先红后绿——实现 stub 抛 92000 探针时 `PromptTemplateServiceTest Tests run:21,Failures:4,Errors:2`（6 新用例全红，断言 92001/92008/50002 拦截缺失实现），恢复实现后全绿 21/0/0。
- 门禁 raw：surefire 全量 `Tests run:1131,Failures:0,Errors:0`（基线 1124→1131，只增）；failsafe 全量真跑 `Tests run:104,Failures:0,Errors:0,Skipped:6`（含 PromptTemplateControllerIT 5、FlywayMigrationIT 6、SchemaDriftAuditIT 1、PermissionCoverageAuditIT 2）。
- 基线处置：白名单 IT 后全量真跑 failsafe 刷新口径，`check-test-baseline.sh` 只读裁决 PASS（surefire 1131≥1124、failsafe 104≥101、跳 6≤6），无需 --update，baseline 文件未动。
- 四静态：checkstyle 0 / spotbugs 0（BugInstance size is 0）/ spotless 0 / pmd 0；BIJECTION_OK（12↔12）、PMD_BASELINE_OK（0==0）。
- 三守卫：CLEAN（工作树仅本卡写集）/ WRITE_SET_OK（HarnessGovernanceGuardTest 3 绿）/ LINE_ENDINGS_OK（GitAttributesContractGuardTest 3 绿，新文件全 LF）。
- merge-gate 默认序 8/8 GATE_EXIT=0：unit 1131 / spotbugs / pmd / baseline / frontend-unit 179 / hook / bijection / pmd-baseline 全 PASS。
- 偏差披露：①revert sourceVersion 经「扩 history 列」落在 V34 中给 prompt_template_history 加可空 source_version 列（卡 D2 二选一取「扩历史列」，因 operator 为 BIGINT 不可承载、V32 已合入禁改）；②服务类 size 超 PMD NCSS 阈值，组2 拆分 PromptTemplateFeedbackSupport/PromptTemplateHistorySupport 协作类收敛（对齐 RenderSupport 先例）；③ControllerIT seedToken 种子做 role_name/email 按 id 唯一化并清理权限以避免同方法多角色种子冲突（纯测试夹具修正，不影响契约）。
