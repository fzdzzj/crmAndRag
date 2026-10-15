# §0 留痕 —— add-business-activity-semantic-retrieval（商业活动语义检索与权限过滤）

> 分支 `feature/add-business-activity-semantic-retrieval`（自权威树 master 检出，HEAD=`a0a61b4`）。
> 两件套正版首笔提交 `755db43` 落入 `openspec/changes/add-business-activity-semantic-retrieval/`，
> 后续每任务组一提交。全部 4 个任务组完成，四静态 + merge-gate 全绿，回归基线 1290→1314。

## 0.1 团队成员与分支

- 权威树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（master 检出，无 push）。
- 分支提交（master..HEAD，顺序）：
  - `755db43` 任务组1 · 任务卡两件套正版入库 + SourceReference `BUSINESS_ACTIVITY` 受控解冻登记
  - `9fb4784` 任务组2 · 商业活动语义摄取（BusinessActivityIngestionService + SemanticTextSupport + afterCommit 增量钩子 + reingestAll 端点）
  - `77ca295` 任务组3 · BusinessRetrievalService 双业务实体多路召回（business_activity 权限闸 + SELF/TAGE 档位复核 + 来源判源）
  - `658ef54` 任务组4 · 同步 frontend/openapi.yaml（新增 `POST /business/activity/reingestAll`）
  - `07e011e` 任务组4 · BusinessRetrievalService 抽出档位复核协作类（PMD NcssCount/Cyclomatic/CommentSize 归零）
  - `a0a61b4` 任务组4 · 回归基线 --update（surefire 1290→1314，source-revision=07e011e 树净重打戳）

## 1. 任务组 1 · 契约解冻与 spec-delta

- `openspec/changes/add-business-activity-semantic-retrieval/specs/knowledge-rag/spec-delta.md`：声明
  `SourceReference.sourceType` 支持 `BUSINESS_ACTIVITY`（documentId=活动主键、filename=activity_title、
  chunkId=`BUSINESS_ACTIVITY:{活动主键}`），字段映射表 + 4 个 Scenario（对齐 add-customer-semantic-retrieval D3 先例）。
- `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 变更日志追记 2026-10-14 受控解冻条目（对齐 B1/D3 先例）。
- `SourceReference.java` javadoc 补记 `BUSINESS_ACTIVITY` 取值（record 十字段零改动，SSE payload 零改动）。

## 2. 任务组 2 · 商业活动语义摄取服务

- **`BusinessActivitySemanticTextSupport`**（knowledge/ingestion 包，纯静态）：拼文本口径「活动标题、活动类型、关联客户、关联商机、
  沟通内容、备注、参与人、联系人」；participant/contact 段共 `SEGMENT_MAX=5`、按 id 升序、`（角色）` 后缀；`documentId=chunkId=BUSINESS_ACTIVITY:{id}`；
  `pointId` UUID v3 确定性派生。标签「参与人: 」「联系人: 」由 buildSemanticText 字段层统一加，段内容仅值。
- **`BusinessActivityIngestionService`**（`ENTITY_TYPE="BUSINESS_ACTIVITY"`，public 常量供检索侧同源引用）：`ingest` 拼关联客户/商机名
  （mapper 缺失跳过）、参与人/联系人段，`EmbeddingService.embed` → `CrmEntityVectorStore.upsert`。payload 键集：entityType / resourceId /
  ownerUserId(=creator_id) / ownerDeptId(creator 反查 sys_user.dept_id) / companyId / opportunityId / title，null 不入键。
  `deleteByActivityId`（documentId 口径删向量）；`reingestAll` 分页游标重嵌（单条失败计数不中断，返回 total/success/failure）。
- **`BusinessActivitySemanticHookSupport`**（server/service/impl 包，对齐 CustomerSemanticHookSupport）：`runAfterCommit` 事务语义闸
  （无事务立即同步、回滚不写向量，异常 WARN 不阻断业务）；`reingestById`（实体缺失转删向量）；`deleteById`（物理删除直删向量，
  business_activity 无软删列）。
- **`BusinessActivityServiceImpl`**：构造注入 `BusinessActivityIngestionService`；`create` 提交后 upsert、`updateList` 批量重嵌
  （回查完整实体防 DTO 丢键）、`deleteByIds` 物理删除后删向量。
- **`BusinessActivityController`**：新增 `POST /business/activity/reingestAll`，挂 `@RequirePermission(SALES_CREATE_BUSINESS_ACTIVITY)`。

## 3. 任务组 3 · 业务数据检索服务多实体扩展

- `BusinessRetrievalService` 重构为**双业务实体**多路召回：`RetrievalEntity` 记录（payloadEntityType / resourceType / documentPrefix /
  sourceType / filenameKey）+ `ENTITIES` 两枚（CUSTOMER_COMPANY→CUSTOMER、BUSINESS_ACTIVITY→BUSINESS_ACTIVITY）。
- 每实体独立查 `getHighestDataScopeLevel(user, 资源类型)`（客户面 `customer_company`、商业活动面 `business_activity`，owner 锚定
  creator_id）：NONE 该实体零外发；两方皆 NONE 空返回零外发（hasAnyScope 前置）。ALL 仅 entityType filter；SELF/TAGE ownerUserId；
  DEPT ownerDeptId；DEPT_AND_CHILD 子树 union；TAGE 补充召回（TAGE/DEPT/DEPT_AND_CHILD 恒开）+ 共享补充召回（全部非 ALL 恒开）。
- **档位复核（纵深防御）**：SELF/TAGE 档召回结果合并前逐命中按落库 payload 复判归属（ownerUserId=本人，或 ownerDeptId=本部门且该实体
  存在补充召回，或 resourceId 命中 TAGE/共享授权清单）——filter 语义漂移时不致越权放大。
- 来源判源 `toSourceReference` 按命中 payload `entityType` 分派：`CUSTOMER`（filename=company_name）/ `BUSINESS_ACTIVITY`（filename=title）。
  上下文 `【业务数据检索】` 片段统一编号 `[n]`（numberStart 起接续知识库），去重取高分后按相似度降序截断 topK。
- **PMD 治理（07e011e）**：档位解析/复核/授权清单抽至 `BusinessRetrievalScopeSupport` 协作类，class 层 CommentSize(>20 行)/NcssCount(>150)/
  CyclomaticComplexity(>75) 归零（`BusinessRetrievalService` 类 javadoc 同步精简、移除死方法 toRoleAO 等）。

## 4. 任务组 4 · 单元测试与门禁

### 4.1 单测矩阵（全部纯 JVM mock，零真跑外呼）

- `BusinessRetrievalServiceTest` 18 用例（↑ 自 15）：归属人 SELF 可检（双实体 filter 断言 + source 逐项）、SELF 跨部门同事绝不可查（正向反向，
  mock 整轮返回同事活动但 owner 复核丢弃）、SELF 活动归属人可检（BUSINESS_ACTIVITY source 判源）、DEPT 上司可检下属、管理员 ALL 全可检、
  **四角色矩阵**（归属人/本部门上级/跨部门同事/管理员逐角色断言）、双实体皆 NONE 零外发、单实体 NONE 另一实体照常召回、TAGE/共享 union 不漏、
  DEPT_AND_CHILD 子树 union + TAGE 补充、编号接续、去重截断、null/空查询/embed 失败降级。
  - 向量库 mock 按调用序喂结果（`stubSearchSequence` 链式 `thenReturn(首个)+thenReturn(rest)`）；Mockito 同参数桩「最后注册生效」坑
    在 TAGE 桩处显式注释留痕。
- `BusinessActivityIngestionServiceTest` 12 用例：payload 键集 + UUID point、creator 空/查无此人键缺省、关联客户/商机查无记录语义降级、
  参与人/联系人段拼装、人员 mapper 缺失跳段、全空抛异常、deleteByActivityId、reingestAll 成功/失败计数/空库零外呼。
- `BusinessActivitySemanticTextSupportTest` 9 用例：全字段固定顺序、空白跳过、全空空串、participant 段排序/上限/过滤、contact 段空白/上限、
  空段、namedEntry 三态、documentId/pointId 确定性、null 实体抛异常。

### 4.2 门禁实测（work/tmp/add-business-activity-semantic-retrieval/mvn-verify.log）

- `mvn -B -ntp clean verify`（本机 Docker 在线）退出码 0：surefire **1314 / 0 / 0 / 0**（↑1290 净增 24，只增不减）、
  failsafe **116 / 0 / 0 / 7**（↑113，FlywayMigrationIT 未新增；本卡零迁移，数量不变）。
- 四静态（verify 内顺序实测）：checkstyle **0 violations**；spotless **995 files clean / 0 needs changes**；
  spotbugs **BugInstance size is 0**；pmd:check **0 violations**（pom `<maxAllowedViolations>=0`，台账 0）。
- 三守卫：**PMD_BASELINE_OK**（实测 0 == 登记 0 == pom 阈值 0）/ **BIJECTION_OK**（12 豁免 == 12 High）/ check-test-baseline 通过
  （surefire 205/1314 ≥ 205/1314、failsafe 31/116 ≥ 31/116、skipped 7 ≤ 7）。
- 基线 `--update`（树净单次）：surefire 1290→**1314**、reports 203→205、failsafe 116/7 不变；source-revision=**07e011e**（树净，无 -dirty）。
- `bash scripts/merge-gate.sh` 退出码 **0**，8/8 子门禁全 PASS：`[unit]/[spotbugs]/[pmd]/[baseline]/[frontend-unit](vitest)/[hook]/[bijection]/[pmd-baseline]`
  （`[it]` 默认跳过，failsafe 报告由 clean verify 提供）；无 typed-router.d.ts 玷污。

## 5. 偏差披露

- **A.（契约同步）** `OpenApiContractExportTest` 因新增 `POST /business/activity/reingestAll` 红：本卡虽「零改前端」，但
  `frontend/openapi.yaml` 是前后端契约门禁的钉死物（springdoc 导出比对），新增端点必然漂移 → 用 `target/openapi/openapi.yaml`
  后端导出回写 `frontend/openapi.yaml`（+11 行，仅新增该端点 + `ReingestResult` schema；`ResultReingestResult` 组件 master 已有先例）。
  其余 `frontend/` 零改动；未跑 `pnpm gen:api`（生成的 SDK 目录不在 git 跟踪面，仓库以手写 api 层为准，提交里无需 SDK 变更物）。
- **B.（Windows 环境）** 系统 `bash` 解析到 WSL（Ubuntu）且 VHDX 挂载失败，`check-test-baseline.sh` / `merge-gate.sh` 等一律改用
  Git Bash 绝对路径 `D:\git\Git\bin\bash.exe` 执行（脚本本身零改动）。merge-gate 内 `[unit]` 等 Maven 子命令与 Git Bash 无环境耦合，语义不变。
- **C.（PMD 重构）** BusinessRetrievalService 类级超 three PMD 阈值（CommentSize>20 行 / NcssCount>150 / CyclomaticComplexity>75）→
  抽 `BusinessRetrievalScopeSupport` 协作类归零；非为压条数，纯「大方法/大 javadoc 拆分」等价重构，行为由单测矩阵锁定。
- **D.** 零 Flyway 迁移、零新权限码、零真跑外呼（DASHSCOPE_API_KEY 未设，BenchmarkIT 走 assumeTrue 跳过）、未 push。

## 6. 未决 / 建议

- 分支 `feature/add-business-activity-semantic-retrieval` 在册未合入（严禁 push）；owner 复核后可 `git merge --no-ff` 合入 master。
- `POST /business/activity/reingestAll` 挂 `SALES_CREATE_BUSINESS_ACTIVITY`（对齐卡要求，业务侧写权限）；若后续希望收紧为知识库管理员
  900 段权限，可仿 `KnowledgeAdminController.reingestCustomers` 调整——本卡按卡内 annotation 落地，未自行变更。
- CustomerRetrievalBenchmarkIT 构造校验：`BusinessRetrievalService` 构造签名未变（5 参），`allScopeStub` 两面档位恒 ALL，双实体检索路径
  等价；该 IT 为 opt-in（RAG_BENCHMARK_REAL=1）默认跳过，未在本地真跑，仅静态确认无编译破坏。