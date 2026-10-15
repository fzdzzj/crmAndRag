# §0 留痕 —— add-unified-business-semantic-retrieval（统一业务语义检索与全站增量异步化）

> 分支 `feature/add-unified-business-semantic-retrieval`（自权威树 master@3926237 检出，HEAD=`8f4377d`）。
> 两件套正版首笔提交 `849d999` 落入 `openspec/changes/add-unified-business-semantic-retrieval/`，
> 后续每任务组一提交。全部 5 个任务组完成，四静态 + merge-gate 全绿，回归基线 1342→1391（surefire）。

## 0.1 团队成员与分支

- 权威树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（feature 分支检出，无 push）。
- 分支提交（master..HEAD，顺序）：
  - `849d999` 任务组1 · 任务卡三件套（proposal/tasks/spec-delta）入库 + 受控解冻登记
  - `8d83f35` 任务组1 · `contracts-frozen.md` 2026-10-16 解冻条目 + `SourceReference` Javadoc 补记 CONTRACT/CONTACT_TASK
  - `7e3a302` 任务组3 · 合同/跟进任务摄取服务 + 文本拼装支持类 + `POST /contract/reingestAll`、`POST /contactTask/reingestAll` + openapi.yaml 同步
  - `9734265` 任务组2 · 全站增量异步化（Customer/BusinessActivity hook 升级 + 新增 Contract/ContactTask hook + 六服务接线）
  - `bc3f334` 任务组4 · BusinessRetrievalService 五业务实体多路召回 + AiAssistantDrawer.vue 来源判断与深度跳转
  - `f75c5fe` 任务组5 · 权限反向矩阵 + 摄取/文本/hook 单测 + 存量服务测试适配
  - `8f4377d` 任务组5 · 回归基线 --update（surefire 1342→1391、reports 208→214、failsafe 31/116/7 不变，source-revision=f75c5fe 树净）
  - `（本提交）` 任务组5 · tasks 勾全格 + 交接报告落盘

## 1. 任务组 1 · 契约解冻与 spec-delta

- `openspec/changes/add-unified-business-semantic-retrieval/specs/knowledge-rag/spec-delta.md`：声明
  `SourceReference.sourceType` 支持 `CONTRACT` 与 `CONTACT_TASK`（documentId=合同/任务主键、filename=合同名称/任务标题、
  chunkId=`CONTRACT:{主键}`/`CONTACT_TASK:{主键}`），字段映射表 + 2 个 Scenario（业务实体来源下发、五实体编号顺延）。
- `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 变更日志追记 2026-10-16 受控解冻条目（对齐 D3 先例）。
- `SourceReference.java` javadoc 补记 `CONTRACT`/`CONTACT_TASK` 取值（record 十字段零改动，SSE payload 零改动；PMD CommentSize 治理见 §5 C）。

## 2. 任务组 2 · 全站增量向量化异步化重构（消灭技术债）

- **存量升级**：`CustomerSemanticHookSupport` 与 `BusinessActivitySemanticHookSupport` 由 `runAfterCommit(scene, action)` 同步闸
  升级为 `runAfterCommitAsync(scene, executor, action)`——事务激活注册 afterCommit（提交后才派发）、无事务环境同样异步派发，
  向量动作一律经 `executor.execute`，HTTP 主线程提交后立即返回（微秒级）零等待；向量侧异常在异步任务内 WARN 不阻断业务。
- **新增 `ContractSemanticHookSupport` 与 `ContactTaskSemanticHookSupport`**：同一异步范式（`runAfterCommitAsync` + `reingestById`
  + `deleteById`）；跟进任务侧另加服务层便捷闸 `syncAfterCommit/syncAllAfterCommit/deleteAfterCommit/deleteAllAfterCommit`
  （依赖未装配或 ids 空整体跳过）——为 ContactTaskServiceImpl 控制在 PMD NcssCount（classReportLevel=150）之下（见 §5 D）。
- **六服务接线**：`CustomerCompanyServiceImpl` / `BusinessActivityServiceImpl` / `CustomerContactServiceImpl`
  （经 `CustomerContactReingestSupport`）/ `ContractServiceImpl` / `ContactTaskServiceImpl` 注入
  `@Qualifier("embeddingTaskExecutor")` 线程池，增删改提交后异步派发重嵌/失效；semantic 侧未装配（评测/单测注入 null）时跳过，
  绝不阻塞业务主流程。单测传入 `Runnable::run` 直接执行器保证 100% 确定性。

## 3. 任务组 3 · 合同与任务摄取服务及回埋端点

- **`ContractSemanticTextSupport` + `ContractIngestionService`**（`ENTITY_TYPE="CONTRACT"`，public 常量供检索侧同源引用）：
  拼装合同基础信息（编号/名称/总金额/状态/签约/生效/失效日期/客户/商机/负责人）**+ 订单产品明细附属段**（contract_order_item
  产品名/规格/单价/数量，id 升序上限 5）+ **回款履约附属段**（payment_record 金额/日期/方式/单号，id 升序上限 5）——
  单文档优雅嵌入防 Qdrant 碎片化、支撑「按产品找合同」。payload 键集：entityType / resourceId / ownerUserId(=owner_id) /
  ownerDeptId(owner 反查 sys_user.dept_id) / companyId / title，null 不入键。
- **`ContactTaskSemanticTextSupport` + `ContactTaskIngestionService`**（`ENTITY_TYPE="CONTACT_TASK"`）：拼装任务主题/类型/优先级/
  状态/起止时间/客户/商机/联系人/执行人 + **task_content 富文本描述**；payload 键集：entityType / resourceId / ownerUserId(=assignee_id) /
  ownerDeptId / companyId / opportunityId / title。
- 两服务均提供 `deleteBy{Contract,Task}Id`（documentId 口径删向量）与 `reingestAll`（分页游标重嵌，单条失败计数不中断，
  返回 total/success/failure）。
- **回埋端点**：`POST /contract/reingestAll`（挂既有 `SALES_CREATE_CONTRACT`）、`POST /contactTask/reingestAll`（挂既有
  `TASK_CREATE_TASK`）；契约漂移用 `bash scripts/check-openapi-consistency.sh --update` 回写 `frontend/openapi.yaml`。

## 4. 任务组 4 · 检索五实体全覆盖与前端引用深度跳转

- `BusinessRetrievalService` 扩展为**五业务实体**多路召回：`ENTITIES` 五枚（CUSTOMER_COMPANY→CUSTOMER、BUSINESS_ACTIVITY→
  BUSINESS_ACTIVITY、SALES_OPPORTUNITY→SALES_OPPORTUNITY、CONTRACT→CONTRACT、CONTACT_TASK→CONTACT_TASK）。
- 权限闸严格同锚 `DataScopeTablePermissions` 原生已有五档：`contract`→SALES_VIEW_CONTRACT_*、`contact_task`→TASK_VIEW_TASK_*
  （owner 锚定 owner_id / assignee_id）；NONE 该实体零外发、五实体皆 NONE 空返回零外发；SELF/TAGE/DEPT/DEPT_AND_CHILD + TAGE/共享
  补充召回沿用 `BusinessRetrievalScopeSupport` 纵深防御档位复核（该协作类零改动，仅传入的 entityLevels 变五面）。
- 来源判源按 payload `entityType` 分派五源（CONTRACT→合同名称、CONTACT_TASK→任务标题）；上下文 `【业务数据检索】` 统一编号
  `[n]` 顺延知识库片段。
- **前端 `AiAssistantDrawer.vue`**：业务来源判定由「单一 CUSTOMER_COMPANY: 前缀」改为 **sourceType 命中五业务实体**
  （CUSTOMER / BUSINESS_ACTIVITY / SALES_OPPORTUNITY / CONTRACT / CONTACT_TASK）——非客户实体不再误调 `/knowledge/chunk`（消除 404）；
  深度跳转：客户→`/client/list?filters.id=`、商机→`/sale/detail/{id}`、合同→`/sale/contract-details/{id}`、活动→`/contact/activity`、
  任务→`/contact/task?id=`；知识库文档仍走高亮抽屉；引用标签/行内 [n] 同口径。

## 5. 任务组 5 · 单元测试与门禁

### 5.1 单测矩阵（全部纯 JVM mock，零真跑外呼）

- `BusinessRetrievalServiceTest` 21 用例（↑ 自 16）：五实体来源判源（新增合同/任务）、**四角色矩阵五实体同轮**（归属人可查/
  本部门上级可查/跨部门同事绝不可查/管理员全可查）、合同与任务跨部门反向不可查、五实体皆 NONE 零外发、单实体 NONE 其他实体照常召回、
  TAGE/共享 union、DEPT_AND_CHILD 子树 union + TAGE 补充、编号接续、去重截断、null/空查询/embed 失败降级——全部 filter 断言
  适配五实体检索调用序（3→5/7/15 次 search 计数）。
- `ContractIngestionServiceTest` 9 + `ContactTaskIngestionServiceTest` 9：payload 键集 + UUID point、owner/关联实体缺失键缺省、
  明细/回款/task_content 附属段、deleteBy{Contract,Task}Id、reingestAll 成功/失败/空库零外呼。
- `ContractSemanticTextSupportTest` 6 + `ContactTaskSemanticTextSupportTest` 4：固定顺序拼接、附属段格式与上限、空白跳过、pointId/documentId 口径。
- `ContractSemanticHookSupportTest` 7 + `ContactTaskSemanticHookSupportTest` 7：**异步非阻塞隔离**——事务激活注册 afterCommit 且
  提交前零执行、afterCommit 仅派发不执行（主线程零等待）、无事务异步派发、直接执行器确定性、失败 WARN 不向上抛、reingestById/deleteById。
- `CustomerCompanyServiceImplTest` / `CustomerContactServiceImplTest`：存量 hook 断言注入直接执行器（Runnable::run）适配异步化。

### 5.2 门禁实测（权威树本机 Docker 在线）

- `mvn -B -ntp test` 全绿：**Tests run 1391, Failures 0, Errors 0, Skipped 0**（↑1342 净增 49，只增不减）。
- `mvn -B -ntp verify` 一轮：surefire 1391 全绿、failsafe **116 / 0 / 0 / 7** 全绿（Testcontainers MySQL 实测）；
  PMD 首轮 5 违规全部代码治理归零（见 §5 C/D）。
- 四静态：checkstyle **0 violations**；spotless **PASS**；spotbugs **BugInstance size 0**；pmd:check **0 violations**
  （pom `<maxAllowedViolations>=0`，台账 0，三处一致）。
- 三守卫：**PMD_BASELINE_OK**（实测 0 == 登记 0 == pom 阈值 0）/ **BIJECTION_OK**（12 豁免 == 12 High）/
  check-test-baseline 通过（surefire 214/1391 ≥ 208/1342、failsafe 31/116 ≥ 31/116、skipped 7 ≤ 7）。
- 基线 `--update`（树净单次）：surefire 1342→**1391**、reports 208→214、failsafe 31/116/7 不变；source-revision=**f75c5fe**（树净，无 -dirty）。
- 前端门禁：`pnpm lint:check` PASS、`pnpm type-check:check` PASS、`pnpm vitest run` **179 passed（17 files）**；
  `pnpm gen:api` 已重生成 SDK（输出不变，无跟踪面新物）；typed-router.d.ts 无玷污。
- `bash scripts/merge-gate.sh` 退出码 **0**，8/8 子门禁全 PASS：`[unit](1391)/[spotbugs]/[pmd]/[baseline]/[frontend-unit](179)/[hook]/[bijection]/[pmd-baseline]`
  （`[it]` 默认跳过，failsafe 报告由 verify 轮提供）。

## 5. 偏差披露

- **A.（后端环境）** 系统 `bash` 解析到 WSL（Ubuntu）且 VHDX 挂载失败，`check-test-baseline.sh` / `merge-gate.sh` /
  `check-openapi-consistency.sh` 等一律改用 Git Bash 绝对路径 `D:\git\Git\bin\bash.exe` 执行（脚本本身零改动）。
- **B.（前端契约）** 新增两个 reingestAll 端点必然使 `OpenApiContractExportTest` 漂移 → `bash scripts/check-openapi-consistency.sh
  --update` 后端导出回写 `frontend/openapi.yaml`（仅新增两端点及其 ReingestResult schema）；并已跑 `pnpm gen:api`（SDK 输出不变）。
- **C.（PMD CommentSize）** `SourceReference` 类级 javadoc 补记五业务来源后超 maxLines=20 → sourceType 参数段精简：
  保留五来源的卡名/日期/字段映射实质，spec 增量路径收敛为 `各卡 specs/knowledge-rag/spec-delta.md` 通用引用。
- **D.（PMD NcssCount / OnlyOneReturn）** 首轮 pmd 5 违规全部代码治理：`statusText` 两处改单出口（OnlyOneReturn）；
  `CustomerCompanyServiceImpl` runAfterCommit 改嵌套 if（150→149）；`ContactTaskServiceImpl` 将 runSemanticSync/reingestSemanticById
  收进 `ContactTaskSemanticHookSupport` 静态便捷闸（155→146），未使用类级 suppression（保持「全仓唯一 NcssCount 类级 suppression」
  的 CustomerContactServiceImpl 先例不变）。
- **E.（前端路由）** 提案文本写商机跳 `router.push('/sale/opportunity?id=...')`，但该路由不存在；按仓内既有先例
  （`index.page.vue` / `stage-approval.page.vue`）改用真实路由 `/sale/detail/{id}`。活动/任务分别跳 `/contact/activity`、
  `/contact/task?id=`（对齐 `index.page.vue` 的 task 深链模式）。
- **F.（执行环境）** 一次后台 `mvn test` 被 runner 误落在观察树 `D:\code\crmAndRag`（该树 work/ 存有 .class/.java.bak 残留，
  HarnessGovernanceGuardTest 红）——已核实为一次性 runner cwd 抖动，权威树重跑全绿；观察树仅 target/（gitignore）受污染，
  无跟踪文件改动。
- **G.** 零 Flyway 迁移、零新权限码（复用 SALES_CREATE_CONTRACT / TASK_CREATE_TASK / 既有五档视图权限）、零真跑外呼
  （DASHSCOPE_API_KEY 未设，单测全程 mock）、未 push。

## 6. 未决 / 建议

- 分支 `feature/add-unified-business-semantic-retrieval` 在册未合入（严禁 push）；owner 复核后可 `git merge --no-ff` 合入 master。
- `POST /contract/reingestAll` 挂 `SALES_CREATE_CONTRACT`（对齐商机/活动 reingestAll 的 CREATE 写权限先例；提案允许
  `SALES_VIEW_CONTRACT` 或对应写权限，本卡取写权限一致性）。
- 合同 payload 权限锚定 owner_id（负责人）为业务主归属（页面列表 SELF 档 userFields 同源）；若需进一步对齐
  creatorUserId 维度可仿商机未决项扩展，本卡按 proposal 键集未动。
