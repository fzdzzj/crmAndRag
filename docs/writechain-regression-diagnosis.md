# WriteChainRegressionIT 两个红灯诊断报告

> 诊断日期：2026-09-13　｜　实证方式：本机 Docker + Testcontainers MySQL 8.0.36 真库首跑
> 只诊断不出修复；修复需用户拍板后另起。本报告不涉及任何 `src/main` 或存量测试改动。

---

## 0. 结论速览

| # | 测试 | 表象 | 根因归属 | 是否有数据丢失风险 |
|---|------|------|----------|:---:|
| 1 | `deleteActivityShouldCascadeAssociationsAndAttachments` | setup 第一步 INSERT 即抛 FK 异常（Error） | **测试自身缺陷**（种子数据缺失 + 级联删除断言携带本会被 FK 阻断的冗余数据） | 无 |
| 2 | `deleteTaskShouldCascadeComments` | `DELETE /contactTask/1` 返回 `90004` 而非 `1`（Failure） | **产品级真缺陷**：`approval_attachment` 表与 `ApprovalAttachmentEntity` 发生列漂移，删除链在附件联查时抛 `BadSqlGrammarException` | 无（全链 `@Transactional`，fail-closed；见 §4 修订） |

> **修订注（2026-09-13 复核）**：初版报告曾红标「`deleteByRecords` 未包事务 → 孤儿数据风险」。经复核 [AssistRequestServiceImpl.java:943](file:///d:/code/crmAndRag/src/main/java/com/slz/crm/server/service/impl/AssistRequestServiceImpl.java#L943)，该方法**有** `@Transactional(rollbackFor = Exception.class)`，且 `ContactTaskServiceImpl` 两个删除方法（L193/L214）同样有——**全链事务保护，孤儿风险不成立，红标撤回**。缺陷的真实影响收敛为：删除功能不可用（可用性事故），无数据丢失。P0 修复项（V24 迁移）不受影响。

---

## 1. 目录 / 涉及文件

| 文件 | 角色 |
|---|---|
| `src/test/java/com/slz/crm/integration/security/WriteChainRegressionIT.java` | 被测 IT（2 个 @Test） |
| `src/test/resources/init_data.sql` | 每用例重放种子（BEFORE_TEST_METHOD + @Transactional） |
| `src/main/resources/db/migration/V1__baseline.sql` | 真库 schema（Flyway 生效，含真实外键） |
| `src/main/java/com/slz/crm/server/service/impl/ContactTaskServiceImpl.java` | 任务删除链 `deleteById` |
| `src/main/java/com/slz/crm/server/service/impl/BusinessActivityServiceImpl.java` | 活动删除链 `deleteByIds` |
| `src/main/java/com/slz/crm/server/service/impl/AssistRequestServiceImpl.java` | 协助级联 `deleteByRecords` |
| `src/main/java/com/slz/crm/server/service/impl/ApprovalAttachmentServiceImpl.java` | 附件级联删除 `removeByAndIds`/`removeByIds` |
| `src/main/java/com/slz/crm/pojo/entity/ApprovalAttachmentEntity.java` | 附件实体（带 `uploader_id`） |
| `src/main/java/com/slz/crm/server/handler/GlobalExceptionHandler.java` | 90004 的唯一出口（未捕获异常） |
| `src/main/resources/application.yml` `application-test.yml` | test profile：H2 配置 + `flyway.enabled` 继承 |

**回放命令**（防误外发，`.env` 常备 DashScope key，故显式清空 + 仅过滤本 IT）：

```powershell
$env:DASHSCOPE_API_KEY=""
mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=WriteChainRegressionIT"
```

---

## 2. 红灯 1：`deleteActivityShouldCascadeAssociationsAndAttachments` —— 测试自身缺陷

### 2.1 现象（实测复现）

```
[ERROR] ...deleteActivityShouldCascadeAssociationsAndAttachments -- Time elapsed: 0.192 s <<< ERROR!
org.springframework.dao.DataIntegrityViolationException: StatementCallback; SQL [INSERT INTO business_activity_contact (id, activity_id, contact_id, creator_id) VALUES (20, 20, 1, 1)]; ...
Caused by: java.sql.SQLIntegrityConstraintViolationException: Cannot add or update a child row: a foreign key constraint fails (`crm_it`.`business_activity_contact`, CONSTRAINT `fk_act_contact_contact` FOREIGN KEY (`contact_id`) REFERENCES `customer_contact` (`id`))
```

### 2.2 根因（证据链）

1. **测试 setup 引用了不存在的 `customer_contact` 行**。
   - [init_data.sql](file:///d:/code/crmAndRag/src/test/resources/init_data.sql) 全表 grep `customer_contact`：**零命中**——既未 `CREATE TABLE` 也未 `INSERT`，`sys_user` 只种到 id=3，`customer_contact` 表连行都没有。
   - 测试第 41-42 行却向 `(20,20,1,1)` 插入 `business_activity_contact.contact_id=1`、`business_activity_user.user_id=12`。
2. **真库有真实外键，绝不放过悬空引用**。
   - [V1__baseline.sql](file:///d:/code/crmAndRag/src/main/resources/db/migration/V1__baseline.sql#L345-L347)：`business_activity_contact.contact_id` 存在 `fk_act_contact_contact → customer_contact.id`（注意：`business_activity_user` 无指向 `sys_user` 的外键，只用索引 `fk_act_user_creator` 指向 creator，属历史设计，不是本条主因）。
   - `customer_contact.id=1` 不存在 → 第一条 `business_activity_contact` INSERT 在真库直接 FK 失败。与任务描述「init_data.sql 可能没有 id=1 的联系人」**一致并验证**。
3. **为何此前从没红过**：本 IT 组标注「task18 时代存量」，长期跑在 **H2 + auto-table**（test profile 默认），H2 `MODE=MySQL` 下不会强制该外键，悬空引用能写进去；Testcontainers 真库是**第一次**让这些外键真正生效（V1..V23 已通后首跑）。

### 2.3 修复方案（**改测试**——在 `init_data.sql` 与 `/或` 测试用例层面）

- **方案 A（最小、推荐）**：
  1. 在 `init_data.sql` 补 `customer_contact` 种子，或把关联行改为「对已存在种子」的引用。
  2. `business_activity_contact.contact_id` → 指向已插入的合法联系人；`business_activity_user.user_id` → `customer_contact` 不存在约束，但为语义正确建议指向 `sys_user.id∈{1,2,3}`。
- **方案 B（对齐断言意图）**：由于 V1 真库对 `activity_id` 本就是 `ON DELETE CASCADE`（`fk_act_contact_activity`、`fk_act_user_activity` 均带 `on delete cascade`），测试「级联删除」断言其实**可以用更少数据**验证——只需插入活动主表 + 一个带 FK 的关联，删除主表后查关联即可；`approval_attachment` 的插入对本用例可保留（附件是服务层显式删，非 DB CASCADE）。
- **风险**：改 `init_data.sql` 会影响**其它共享此脚本的 IT**（同一脚本被 `@Sql` 重放），需先确认 `customer_contact` 种子不影响既有用例断言基线。
- **需要判断**：是否删掉 `business_activity_contact / business_activity_user` 的 setup 行，改为「该表在真库走 DB 级 `ON DELETE CASCADE`」即可，取决于测试想验证的是**服务层删关联**还是 **DB 级联**。当前用例断言为「删除后关联计数=0」，两种实现都能满足，但从显式语义看服务层已在 `deleteByIds` 手动 `deleteByActivityId`，DB CASCADE 是冗余兜底。

> **判定**：**测试自身缺陷**，非产品缺陷。级联行为本体（服务层手动删关联 + DB 级 CASCADE 兜底）在真库成立，只是测试的种子/断言与现实 schema 外键冲突。

---

## 3. 红灯 2：`deleteTaskShouldCascadeComments` —— 产品级真缺陷

### 3.1 现象（实测复现）

```
[ERROR] ...deleteTaskShouldCascadeComments -- Time elapsed: 6.368 s <<< FAILURE!
java.lang.AssertionError: JSON path "$.code" expected:<1> but was:<90004>
... at com.slz.crm.integration.security.WriteChainRegressionIT.deleteTaskShouldCascadeComments(WriteChainRegressionIT.java:71)
```
> 注：`status().isOk()` 通过（HTTP 200），但业务体 `{code:90004}`，与任务描述一致。

### 3.2 根因（证据链：堆栈 + 代码行 + schema 对照）

后台日志中由 [GlobalExceptionHandler.java](file:///d:/code/crmAndRag/src/main/java/com/slz/crm/server/handler/GlobalExceptionHandler.java#L113-L117) 捕获的**真实堆栈**：

```
ERROR ... GlobalExceptionHandler : 系统异常：
org.springframework.jdbc.BadSqlGrammarException:
### Error querying database. Cause: java.sql.SQLSyntaxErrorException: Unknown column 'uploader_id' in 'field list'
### SQL: SELECT id,and_id,model_name,file_name,file_path,file_size,file_type,upload_time,uploader_id FROM approval_attachment WHERE (and_id IN (?) AND model_name = ?)
	at ... ApprovalAttachmentServiceImpl.removeByAndIds(ApprovalAttachmentServiceImpl.java:216)
	at ... ContactTaskServiceImpl.deleteById(ContactTaskServiceImpl.java:221)
	at ... ContactTaskController.deleteById(ContactTaskController.java:127)
```

调用链（`DELETE /contactTask/1`）：

```
contactTaskController.deleteById (ContactTaskController.java:124-129)
 └─ contactTaskService.deleteById (ContactTaskServiceImpl.java:214-224, 全程 @Transactional(rollbackFor=Exception))
     ├─ assistRequestService.deleteByRecords(CONTACT_TASK, [1])   // 无本任务协助 → 空，跳过
     ├─ approvalAttachmentService.removeByAndIds([1], CONTACT_TASK)  // ★ 第 221 行 → 抛 BadSqlGrammarException
     ├─ deleteCommentByTaskIds([1])                                // 未执行（在 221 之后）
     └─ return removeById(1)                                       // 未执行
```

**schema 与实体对照（漂移核心）**：

| 位置 | `uploader_id` 是否存在 |
|---|---|
| [ApprovalAttachmentEntity.java](file:///d:/code/crmAndRag/src/main/java/com/slz/crm/pojo/entity/ApprovalAttachmentEntity.java#L83-L86)（`@TableField("uploader_id")`，notNull=false） | **实体有** → MP 生成 `SELECT ... , uploader_id` |
| [V1__baseline.sql](file:///d:/code/crmAndRag/src/main/resources/db/migration/V1__baseline.sql#L23-L39)（`approval_attachment` 建表） | **表没有**（仅 `and_id/model_name/file_name/file_path/file_size/file_type/upload_time`） |
| V1 中出现的 `uploader_id`（[L848](file:///d:/code/crmAndRag/src/main/resources/db/migration/V1__baseline.sql#L848)） | 属 **`project_file`** 表，非 `approval_attachment` |

→ **结论**：`approval_attachment` 真实表缺 `uploader_id` 列，实体带了该字段。任何读取该附件表（`selectList` 全字段）的查询在真库都会抛 `Unknown column 'uploader_id'`。这解释：
- 为什么 `removeByAndIds`（[ApprovalAttachmentServiceImpl.java:216](file:///d:/code/crmAndRag/src/main/java/com/slz/crm/server/service/impl/ApprovalAttachmentServiceImpl.java#L212-L226)）先 `selectList(...和_Id+model_name)` 时撞上；
- 而**单测长期绿**：H2 由 auto-table **按实体**建表，实体有 `uploader_id` → H2 表也有 → MP 查询不报错。真库首次跑才暴露。

### 3.3 溯源判断（为什么出现漂移）

`ApprovalAttachmentEntity` 带 `uploaderId`，且 [ApprovalAttachmentServiceImpl 第 73、205 行](file:///d:/code/crmAndRag/src/main/java/com/slz/crm/server/service/impl/ApprovalAttachmentServiceImpl.java) 在保存时 `setUploaderId(BaseUnit.getCurrentId())`——产品**业务逻辑事实上依赖该列**（上传人追溯、`assertCanDelete` 用 `UploaderId==currentId` 做删除鉴权）。因此节点列是业务需要的，**缺的是迁移脚本**。极可能该列是某版本在实体/服务层加入、却未配套飞前向迁移脚本（V1 是基线，`uploader_id` 建在 `approval_attachment` 上应属于本表），或 V1 建表时漏了该列而实体沿用旧字段。迁移历史 `db/migration/*.sql` 全表 grep `uploader_id` 仅命中 `project_file` 一处，**没有任何显式迁移把该列补到 `approval_attachment`**。

### 3.4 修复方案（**改 src/main**；分两层）

> ★ 这是**授权需确认**的产品变更。按 AGENTS/规则 2：外发、改库结构、影响产品行为均需事前拍板。

- **根修（推荐，改迁移 + 配套）**：新增前向迁移 `V24__approval_attachment_add_uploader.sql`（不改已合入 V1，禁改旧脚本）：
  ```sql
  ALTER TABLE approval_attachment
      ADD COLUMN uploader_id bigint NULL COMMENT '上传人ID';
  ALTER TABLE approval_attachment
      ADD INDEX idx_attachment_uploader (uploader_id);
  ```
  理由：不可逆前向迁移、无 DROP 回滚（符合项目 runbook）；列 `NULL` + 存量行回填 `=creator` 语义需业务确认，新迁移内标注回填策略。
- **注意副作用覆盖**：除 `approval_attachment` 漂移外，还应全量比对 `pojo/entity/**` 与 V1/V3…V23 真实表列，排查是否还有**同类「实体列 → 无迁移列」漂移**（同根因可能不止 `uploader_id` 一处）。本报告不扩大范围执行，仅提示。
- **事务一致性确认**：`deleteById` 全程 `@Transactional(rollbackFor = Exception.class)`——SQL 错误会整体回滚，**当前不会产生部分删除**（见 §4.1 结论），数据不丢但**删除功能不可用**。若要走「删评论不删附件」等分支路径，需重新评估部分调用点（见 4.3）。

### 3.5 修复方案（测试侧，可选最低改法）

若不想在授权「迁移前」让本用例连锁阻塞其他 IT，可将断言从「期望 code=1」折衷为「在 `uploader_id` 缺陷被正式修复前，本用例可先聚焦**任务+评论**删除路径」——但**此为回避而非修复**，不推荐作为终态。更合适的临时处置是：本用例继续留 `@Disabled` 直至 3.4 根修合入，避免在真实库反复暴露 90004。

> **判定**：**产品级真缺陷**。非测试写错——测试只是第一次在真库触发了这个一直存在、被 H2 掩盖的 schema 漂移。

---

## 4. 数据丢失风险评估（90004 背后）

### 4.1 直接路径：整体回滚，不丢数据
`deleteById` 带 `@Transactional(rollbackFor=Exception)`，`approvalAttachmentService.removeByAndIds` 抛异常 → 事务回滚 → `contact_task` 主表与 `task_comment` 均未删。**结果 = 一切都没删**：删除功能对带附件任务直接 90004，**不构成部分丢失**。

### 4.2 但「删除不可用」本身是可用性事故
联络任务是 CRM 高频对象。删除接口对大范围带附件任务 90004、前端只能看到「服务器内部错误」，无法区分是无权限/无数据/系统错，体验与运维定位都差。

### 4.3 ~~孤儿风险（初版红标，已撤回）~~ —— 事务保护复核结论

初版曾判断 `AssistRequestServiceImpl.deleteByRecords`（L944-961）「未标注 `@Transactional`、在外部事务外调用会产生孤儿」。**复核证伪**：该方法 L943 有 `@Transactional(rollbackFor = Exception.class)`，`ContactTaskServiceImpl.deleteById/deleteByIds` 亦然（L193/L214）。SQL 错误发生时整链回滚，且错误位置（`removeByAndIds` 的 `selectList`）先于该链任何删除动作——既无部分提交、也无孤儿。初版红标撤回，风险收敛为 §4.2 的可用性事故。若后续新增调用点，保持「级联删除入口必须 `@Transactional`」即可（可选审计项，非当前缺口）。

### 4.4 建议优先级排序（写入待办）
1. **P0**：`V24__approval_attachment_add_uploader`（根修，解除删除接口 90004）。
2. **P1**：全量列漂移比对（实体↔真实表），排查是否还有同类缺失列（不只 `approval_attachment`）。
3. **P2**：红灯 1 的测试种子/断言修整（纯测试，低风险，可随任何一次提交顺带）。
4. **可选**：新增级联删除调用点时审计「入口必须 `@Transactional`」（§4.3 撤回后仅作约定，非当前缺口）。

---

## 5. 关键文件证据索引

- 测试：`src/test/java/com/slz/crm/integration/security/WriteChainRegressionIT.java`（setup L39-44 / L66；断言 L53-60 / L68-74）
- 种子：`src/test/resources/init_data.sql`（无 `customer_contact`；`sys_user` 仅 1-3）
- 真库外键：`V1__baseline.sql` L331-390（activity 关联）、L591-604（task_comment FK → sys_user + contact_task）
- 漂移对照：`V1__baseline.sql` L23-39（approval_attachment 无 uploader_id） vs `ApprovalAttachmentEntity.java` L83-86（有 uploader_id）
- 删除链：`ContactTaskServiceImpl.deleteById` L214-224；`ApprovalAttachmentServiceImpl.removeByAndIds` L212-226；`AssistRequestServiceImpl.deleteByRecords` L944-961
- 90004 出口：`GlobalExceptionHandler.handleException` L113-117（未捕获异常 → INTERNAL_SERVER_ERROR）
- 测试 profile：`application-test.yml`（H2 驱动行 L5-10；auto-table: create → 按实体建表，掩蔽漂移）