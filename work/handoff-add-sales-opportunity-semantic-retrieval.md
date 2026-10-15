# §0 留痕 —— add-sales-opportunity-semantic-retrieval（销售商机语义检索接入与异步增量保障）

> 分支 `feature/add-sales-opportunity-semantic-retrieval`（自权威树 master 检出，HEAD=`7055369`）。
> 两件套正版首笔提交 `496f49c` 落入 `openspec/changes/add-sales-opportunity-semantic-retrieval/`，
> 后续每任务组一提交。全部 4 个任务组完成，四静态 + merge-gate 全绿，回归基线 1314→1342。

## 0.1 团队成员与分支

- 权威树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（master 检出，无 push）。
- 分支提交（master..HEAD，顺序）：
  - `496f49c` 任务组1 · 任务卡两件套正版入库 + SourceReference `SALES_OPPORTUNITY` 受控解冻登记
  - `cc080da` 任务组2 · 销售商机语义摄取（SalesOpportunityIngestionService + SemanticTextSupport + runAfterCommitAsync 异步增量钩子 + reingestAll 端点）
  - `01c7c4f` 任务组3 · BusinessRetrievalService 三业务实体多路召回（sales_opportunity 权限闸 + 来源判源 SALES_OPPORTUNITY）
  - `d73c71d` 任务组4 · 商机检索权限反向测试 + 摄取/异步钩子 Mock 单测 + openapi.yaml 同步（POST /sales/reingestAll）+ SourceReference 注释精简（PMD CommentSize 归零）
  - `7055369` 任务组4 · 回归基线 --update（surefire 1314→1342，source-revision=d73c71d 树净重打戳）
  - `（本提交）` 任务组4 · tasks 勾全格 + 交接报告落盘

## 1. 任务组 1 · 契约解冻与 spec-delta

- `openspec/changes/add-sales-opportunity-semantic-retrieval/specs/knowledge-rag/spec-delta.md`：声明
  `SourceReference.sourceType` 支持 `SALES_OPPORTUNITY`（documentId=商机主键、filename=商机名称、
  chunkId=`SALES_OPPORTUNITY:{商机主键}`），字段映射表 + 4 个 Scenario（对齐 add-customer / add-business-activity 先例）。
- `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 变更日志追记 2026-10-15 受控解冻条目（对齐 B1/D3 先例）。
- `SourceReference.java` javadoc 补记 `SALES_OPPORTUNITY` 取值（record 十字段零改动，SSE payload 零改动；注释在任务组4 为 PMD CommentSize 归零做了精简，实质保留）。
- 任务卡两件套（proposal.md / tasks.md / work 卡）正版落盘 `openspec/changes/add-sales-opportunity-semantic-retrieval/`。

## 2. 任务组 2 · 异步增量机制与商机语义摄取

- **`SalesOpportunitySemanticTextSupport`**（knowledge/ingestion 包，纯静态）：拼文本口径「商机名称、关联客户、主要联系人、商机阶段、
  预估金额、机会来源、预计结单日期、需求描述、跟进负责人」；阶段码→文字描述（0种子/1潜在商机/2确认商机/3储备项目/4立项签约/5关闭）、
  金额 `toPlainString` 防科学计数法、日期 `yyyy-MM-dd`；`documentId=chunkId=SALES_OPPORTUNITY:{id}`；`pointId` UUID v3 确定性派生。
- **`SalesOpportunityIngestionService`**（`ENTITY_TYPE="SALES_OPPORTUNITY"`，public 常量供检索侧同源引用）：`ingest` 拼关联客户/联系人/
  负责人段（mapper 缺失跳过），`EmbeddingService.embed` → `CrmEntityVectorStore.upsert`。payload 键集：entityType / resourceId /
  ownerUserId(=owner_id 跟进销售) / ownerDeptId(owner 反查 sys_user.dept_id) / companyId / title，null 不入键。
  `deleteByOpportunityId`（documentId 口径删向量）；`reingestAll` 分页游标重嵌（单条失败计数不中断，返回 total/success/failure）。
- **`SalesOpportunitySemanticHookSupport`**（server/service/impl 包）：**真正异步** `runAfterCommitAsync(scene, executor, action)`——
  事务激活时注册 afterCommit，提交后经 `executor.execute` 异步派发；无事务环境同样异步派发；HTTP 主线程提交后立即返回（微秒级），
  零等待向量嵌入耗时；向量侧异常在异步任务内 WARN 不阻断业务。`reingestById`（实体缺失或软删转删向量）；`deleteById`（逻辑删除提交后直删向量）。
- **`SalesOpportunityServiceImpl`**：注入 `SalesOpportunityIngestionService` + `@Qualifier("embeddingTaskExecutor")` 线程池；
  `create`/`update` 成功后异步重嵌（回查完整实体防 DTO 丢键）、`delete`（@Transactional，afterCommit 后异步失效向量）；
  semantic 侧未装配（评测/单测注入 null）时跳过，绝不阻塞业务主流程。既有事务语义零改动（create/update 无 @Transactional 的原状保留，
  无事务路径由钩子异步派发兜底）。
- **`SalesOpportunityController`**：新增 `POST /sales/reingestAll`，挂 `@RequirePermission(PermissionOperates.SALES_CREATE_SALE_OPPORTUNITY)`。

## 3. 任务组 3 · 业务数据检索服务多实体融合

- `BusinessRetrievalService` 扩展为**三业务实体**多路召回：`ENTITIES` 三枚（CUSTOMER_COMPANY→CUSTOMER、BUSINESS_ACTIVITY→
  BUSINESS_ACTIVITY、SALES_OPPORTUNITY→SALES_OPPORTUNITY，documentPrefix=`SALES_OPPORTUNITY:`，filename 键 title）。
- 每实体独立查 `getHighestDataScopeLevel(user, 资源类型)`（销售商机面 `sales_opportunity`，owner 锚定 owner_id，DataScopeTablePermissions
  原生已登记）：NONE 该实体零外发；三实体皆 NONE 空返回零外发（hasAnyScope 前置）。ALL 仅 entityType filter；SELF/TAGE ownerUserId；
  DEPT ownerDeptId；DEPT_AND_CHILD 子树 union；TAGE 补充召回（TAGE/DEPT/DEPT_AND_CHILD 恒开）+ 共享补充召回（全部非 ALL 恒开）。
- **档位复核（纵深防御）**：SELF/TAGE 档召回结果合并前逐命中按落库 payload 复判归属（`BusinessRetrievalScopeSupport` 既有通用实现，零改动）。
- 来源判源 `toSourceReference` 按命中 payload `entityType` 分派三源；上下文 `【业务数据检索】` 片段统一编号 `[n]`
  （numberStart 起接续知识库），去重取高分后按相似度降序截断 topK。
- AiChat 集成回归：`AiChatKnowledgeRetrievalServiceTest` 13 全绿（业务零命中/端口未就绪与纯知识库路径逐字节一致，编号顺延）。

## 4. 任务组 4 · 单元测试与门禁

### 4.1 单测矩阵（全部纯 JVM mock，零真跑外呼）

- `BusinessRetrievalServiceTest` 20 用例（↑ 自 18）：归属人 SELF 可检（三实体 filter 断言 + source 逐项）、SELF 跨部门同事绝不可查
  （正向反向，mock 整轮返回同事活动/商机但 owner 复核丢弃）、SELF 商机归属人可检（SALES_OPPORTUNITY source 判源）、DEPT 上司可检下属、
  管理员 ALL 全可检、**三实体四角色矩阵**（归属人/本部门上级/跨部门同事/管理员逐角色断言）、三实体皆 NONE 零外发、单实体 NONE 其他实体照常召回、
  TAGE/共享 union 不漏、DEPT_AND_CHILD 子树 union + TAGE 补充、编号接续、去重截断、null/空查询/embed 失败降级。
  - 向量库 mock 按调用序喂结果（`stubSearchSequence` 链式 `thenReturn(首个)+thenReturn(rest)`）；Mockito 同参数桩「最后注册生效」坑
    在 TAGE 桩处显式注释留痕。
- `SalesOpportunityIngestionServiceTest` 10 用例：payload 键集 + UUID point、owner 空/查无此人键缺省、关联客户/联系人查无记录语义降级、
  全空抛异常、deleteByOpportunityId、reingestAll 成功/失败计数/空库零外呼。
- `SalesOpportunitySemanticTextSupportTest` 8 用例：全字段固定顺序、空白跳过、全空空串、阶段码映射、金额 toPlainString、日期口径、
  documentId/pointId 确定性、null 实体抛异常。
- `SalesOpportunitySemanticHookSupportTest` 8 用例：**异步非阻塞隔离**——事务激活注册 afterCommit 且提交前零执行、afterCommit 仅派发
  不执行（主线程零等待）、无事务异步派发、直接执行器（Runnable::run）确定性、失败 WARN 不向上抛、reingestById 实体缺失/软删转删、
  deleteById 直删向量。

### 4.2 门禁实测（work/tmp/add-sales-opportunity-semantic-retrieval/mvn-verify.log + merge-gate.log）

- `mvn -B -ntp clean verify`（本机 Docker 在线）退出码 0：surefire **1342 / 0 / 0 / 0**（↑1314 净增 28，只增不减）、
  failsafe **116 / 0 / 0 / 7**（本卡零迁移，数量不变）。
- 四静态（verify 内顺序实测）：checkstyle **0 violations**；spotless **1001 files clean / 0 needs changes**；
  spotbugs **BugInstance size is 0**；pmd:check **0 violations**（pom `<maxAllowedViolations>=0`，台账 0）。
- 三守卫：**PMD_BASELINE_OK**（实测 0 == 登记 0 == pom 阈值 0）/ **BIJECTION_OK**（12 豁免 == 12 High）/ check-test-baseline 通过
  （surefire 208/1342 ≥ 208/1342、failsafe 31/116 ≥ 31/116、skipped 7 ≤ 7）。
- 基线 `--update`（树净单次）：surefire 1314→**1342**、reports 205→208、failsafe 31/116/7 不变；source-revision=**d73c71d**（树净，无 -dirty）。
- `bash scripts/merge-gate.sh` 退出码 **0**，8/8 子门禁全 PASS：`[unit]/[spotbugs]/[pmd]/[baseline]/[frontend-unit](179 vitest)/[hook]/[bijection]/[pmd-baseline]`
  （`[it]` 默认跳过，failsafe 报告由 clean verify 提供）；无 typed-router.d.ts 玷污。

## 5. 偏差披露

- **A.（契约同步）** `OpenApiContractExportTest` 因新增 `POST /sales/reingestAll` 红：本卡虽「零改前端」，但 `frontend/openapi.yaml`
  是前后端契约门禁的钉死物（springdoc 导出比对），新增端点必然漂移 → 用 `bash scripts/check-openapi-consistency.sh --update`
  后端导出回写 `frontend/openapi.yaml`（+33/-22 行，仅新增该端点 + `ReingestResult` schema）。其余 `frontend/` 零改动；未跑
  `pnpm gen:api`（生成的 SDK 目录不在 git 跟踪面，仓库以手写 api 层为准，提交里无需 SDK 变更物）。
- **B.（Windows 环境）** 系统 `bash` 解析到 WSL（Ubuntu）且 VHDX 挂载失败，`check-test-baseline.sh` / `merge-gate.sh` /
  `check-openapi-consistency.sh` 等一律改用 Git Bash 绝对路径 `D:\git\Git\bin\bash.exe` 执行（脚本本身零改动）。merge-gate 内
  `[unit]` 等 Maven 子命令与 Git Bash 无环境耦合，语义不变。
- **C.（PMD 注释治理）** `SourceReference` 类级 javadoc 在补记 `SALES_OPPORTUNITY` 后超 PMD CommentSize（maxLines=20）→ 精简
  sourceType 参数段：保留三业务来源的卡名/日期/字段映射实质，spec 增量路径收敛为 `openspec/changes/ 各卡 specs/knowledge-rag/spec-delta.md`
  通用引用（具体路径以 contracts-frozen.md 变更日志与三张 spec-delta 为准）；record 十字段与 SSE 结构零改动。
- **D.（既有事务语义）** `SalesOpportunityServiceImpl` 的 create/update 原无 `@Transactional`（业务现状），本卡未擅自补事务注解；
  钩子的无事务路径异步派发兜底（DB 写已 autocommit 提交），delete 保持 `@Transactional` 走 afterCommit 闸，行为不变。
- **E.** 零 Flyway 迁移、零新权限码、零真跑外呼（DASHSCOPE_API_KEY 未设，BenchmarkIT 走 assumeTrue 跳过）、未 push。

## 6. 未决 / 建议

- 分支 `feature/add-sales-opportunity-semantic-retrieval` 在册未合入（严禁 push）；owner 复核后可 `git merge --no-ff` 合入 master。
- `POST /sales/reingestAll` 挂 `SALES_CREATE_SALE_OPPORTUNITY`（对齐卡要求，业务侧写权限）；若后续希望收紧为知识库管理员
  900 段权限，可仿 `KnowledgeAdminController.reingestCustomers` 调整——本卡按卡内 annotation 落地，未自行变更。
- 向量 payload 权限锚定 owner_id（跟进销售）为业务主归属（页面列表 SELF 档 userFields=creator_id+owner_id 的 OR 语义下，
  仅创建人非负责人的记录在检索侧比页面略窄——保守方向零泄漏，符合「页面看得到的检索得到」主锚；如需完全对齐可后续追加 creatorUserId
  键并在档位复核处扩判，本卡按 proposal 的 payload 键集未动）。
