# tasks — add-prompt-template-admin

> 红线：新端点全部挂 @RequirePermission（零裸奔零 INTENTIONAL_OPEN）；CONTROLLER_REGISTRY 同步登记；已合入迁移禁改；surefire 只增不减（当前 1110）；全卡零真实外呼（DASHSCOPE_API_KEY 置空防线同前卡）；实体↔DDL 零漂移（SchemaDriftAuditIT 面向新表不产 CRITICAL）。

## 任务组 1 · DDL 与实体

- [x] 1.1 V32__prompt_template.sql：prompt_template + prompt_template_history 建表（含头部注释：用途/影响表/授权策略/回退说明，V21 起格式），实体 PromptTemplateEntity / PromptTemplateHistoryEntity（@AutoTable + 索引注解）与 DDL 对齐
- [x] 1.2 PermissionOperates 新增 PROMPT_TEMPLATE_VIEW(808) / PROMPT_TEMPLATE_MANAGE(809)，中文注释标注「add-prompt-template-admin tasks 1.2」
- [x] 1.3 V33__prompt_template_permission_seed.sql：注册 808/809 + INSERT...SELECT 授权业务角色（role_id NOT IN (0,1,2)）+ 种子 2 模板（agent-executor-brief / agent-reviewer-brief，V33 内，status=0）

## 任务组 2 · 后端实现

- [x] 2.1 PromptTemplateMapper（MP BaseMapper）+ PromptTemplateService：创建（version=1 + 历史落 CREATE）/ 更新（乐观锁 + version 递增 + 历史 UPDATE）/ 删除（同事务先 history 后主表）/ 启停用（不改 version + 历史 STATUS）/ 分页（code/name/category/status 过滤）
- [x] 2.2 渲染：render(code, variables)——启用态模板 + required 校验 + 未定义变量拒绝 + {{var}} 替换，返回组装文本与 version；坏 variables JSON 创建/更新即拒
- [x] 2.3 PromptTemplateController（/promptTemplate）：page/{id}/create/update/delete/status/history/render 八端点，VIEW 四个 + MANAGE 四个，Result 统一包装
- [x] 2.4 PermissionCoverageScanner.CONTROLLER_REGISTRY 登记 + 新类中文 Javadoc 标注卡名与任务号

## 任务组 3 · 测试与门禁

- [x] 3.1 红测先行：Service 渲染三态 + 乐观锁冲突红测留痕，再实现转绿
- [x] 3.2 PromptTemplateServiceTest 全绿（渲染三态/版本递增/乐观锁/code 唯一/坏 JSON/启停用不改版/级联删）
- [x] 3.3 PromptTemplateControllerIT（Testcontainers MySQL）：CRUD + 历史 + 渲染端到端 + 权限正反向
- [x] 3.4 全量门禁：mvn -B -ntp test 0 失败；四静态 0；三守卫；merge-gate 全绿；基线 --update 只增不减；PermissionCoverageAuditIT / SchemaDriftAuditIT 面向新表全绿
- [x] 3.5 停步回报：分支自证、各笔 stat、门禁 raw、权限矩阵登记证明（grep @RequirePermission + registry）

## 任务组 4 · 无（本卡无真跑节点，零外呼）

## §0 执行留痕

- 红测先行：PromptTemplateServiceTest 渲染三态+乐观锁先红（Tests run: 14, Failures: 4, Errors: 1）后绿（14/0/0），证据见 `work/add-prompt-template-admin-log.md`。
- 门禁 raw：全量 surefire 1124/0/0/0；failsafe 101/0/0/6（提示词模板 IT 3/0、权覆 2/0、schema 漂移 1/0、Flyway 19 迁移）；四静态 0 + BIJECTION_OK + PMD_BASELINE_OK；三守卫 CLEAN/WRITE_SET_OK 27/LINE_ENDINGS_OK；merge-gate 8/8 全 PASS；基线 --update 一次：surefire 188/1124/0、failsafe 28/101/6（只增不减）。
- 偏差披露：①校验异常复用既有 ErrorCode（不新增错误码，避免逼 frontend error-code-map 同步）；②variables 双表 DDL 用 TEXT（规避 String↔json NEW 漂移）；③frontend/openapi.yaml 需 check-openapi-consistency.sh --update 契约同步（OpenApiContractExportTest 硬门禁，属最小契约同步越界，frontend SDK 的 pnpm gen:api 留待授权）；④PMD 抽离 PromptTemplateRenderSupport/VoAssembler 协作类归零；⑤FlywayMigrationIT.EXPECTED_VERSIONS 登记 V32/V33。
