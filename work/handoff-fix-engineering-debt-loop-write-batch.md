# 交接快照 · fix-engineering-debt-loop-write-batch（循环写收口批，A 档）

- 分支：`feature/fix-engineering-debt-loop-write-batch`（自 `master@8d61f89` 检出，权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`）
- 基点四元组（master@8d61f89）：surefire 279 报告 / 1738 例 / 0 跳，failsafe 31 报告 / 118 例 / 7 跳，merge-gate 10/10
- 交付后四元组（本分支 HEAD）：surefire **283 报告 / 1777 例 / 0 跳**（+4 报告 = 4 个新测试类，+39 例），failsafe **31 / 118 / 7**（未变），merge-gate **10/10 PASS**
- 状态：**未 merge、未 push**，分支保留，待 A 档独立复核
- 红线自查：零数据库迁移（`src/main/resources/db/migration` 0 diff）／零新依赖（`pom.xml` 0 diff）／零新增错误码常量（`ErrorCode` 0 diff）／冻结契约未动（`spec/` 0 diff）／前端 0 diff（`frontend/` 0 diff）／基线只增不减（279→283、1738→1777、failsafe 不变）

---

## 1. A-6 级联批量化下沉

### 1.1 锚点前后对照

| 位置 | 基点实测 | 交付后 | 变更 |
| --- | --- | --- | --- |
| `ForeignKeyDeleteUtil.deleteCascade` | :42-77（每层 `wrapper.in(parentIds)` 已是批量） | :43-78 | **未改**（复用本体，只改调用次数） |
| `ForeignKeyDeleteUtil.logicalDeleteByCascade` | :86-95（单 id 包装） | :87-95 | **保留**（单条入口，仍走 `updateById`） |
| `ForeignKeyDeleteUtil.logicalDeleteByCascadeBatch` | 不存在 | **:110-120（新增）** | 级联一趟 + 主表单条 IN |
| `ForeignKeyDeleteUtil.logicalDeleteMainRecords` | 不存在 | **:154-178（新增 private）** | `update(entity(isDeleted=true), wrapper.in("id", ids))` |
| `CustomerContactServiceImpl.batchLogicalDeleteByIds` | :429-441，循环 :437-439 自调用 `logicalDeleteById` | :429-436 | 循环删除，改一次委托 `logicalDeleteByCascadeBatch`；`@Transactional(rollbackFor=Exception.class)` 与空入参 `PARAM_EMPTY` 保留 |

diff 摘要：`ForeignKeyDeleteUtil +53 / -0`、`CustomerContactServiceImpl +4 / -7`；新增私有方法复用单条入口同一套反射口径（`setIsDeleted(true)` + MP 列映射），不硬编码列名。

### 1.2 等价性论证

**(a) 级联层级与顺序不变。** `deleteCascade` 一行未改（:43-78），层级遍历仍为「先递归孙子辈 `deleteCascade(childEntityClass, childIds)`，再 `deleteChildRecords(childEntityClass, foreignKey, ids)`」；批量入口只是把**同一组** ids 从「逐 id 各跑一趟」合并为「一趟跑全量 ids」，每层仍是一条 `selectObjs(wrapper.in(parentIds))` + 一条 `delete(wrapper.in(parentIds))`。子表先于主表删除的偏序不变。

**(b) SQL 条数（N 个 id、每层 1 个外键）。** 旧：N 轮 `deleteCascade`（每轮每层 1 查 1 删）+ N 条 `updateById`；新：1 轮（每层 1 查 1 删）+ 1 条 `update`。定向用例 `batchCascade_queryCountIndependentOfIdCount` 直接断言「1 个 id 与 3 个 id 的总调用次数完全相等」，并在同一方法注释里点明旧实现会放大 3 倍。

**(c) 返回计数含义。** 返回值仍是「主表逻辑删除的数据库命中行数」：

- 无重复 id：旧 Σ`updateById` = 逐行命中数 = 新单条 `IN` 的命中行数（hit 集合相同），**逐位一致**；
- 有重复 id（如 `[11,11]`）：旧对同一行发两条 update，按 Connector/J 默认口径（**JDBC URL 未设置 `useAffectedRows`**，见 `application.yml:35`，故默认 `false` = 返回「匹配行数」）会计 **2**；新 `IN (11,11)` 由 WHERE 去重只匹配 1 行，计 **1**。此即卡面要求「重复 id 以实际行数计」的口径变化，**已单列于此**；调用侧 `CustomerContactController` 仅把该值作为批量删除成功数回显，无分支逻辑依赖它；
- 不存在的 id：旧该轮 `updateById` 计 0，新 `IN` 自然不命中，两侧一致。

**(d) 事务语义不劣化。** `batchLogicalDeleteByIds` 的外层 `@Transactional(rollbackFor = Exception.class)` 原样保留；util 自身无事务注解（改动前后一致），因此仍在调用方事务内执行。批量入口额外的好处：级联与主表更新之间不再夹 N-1 轮往返，异常点更少；若主表 update 抛错，整个事务回滚的行为与旧实现一致（旧实现同样依赖外层事务回滚前面的级联删除）。`logicalDeleteById`（单条）路径完全未变，`CustomerContactServiceImpl.logicalDeleteById` 仍委托 `logicalDeleteByCascade`。

**残留风险（已评估，不阻断）**：批量入口把 id 全量塞进 `IN`，超大批量（数千 id）会生成超长 SQL。该入口来自前端勾选列表（分页粒度），且 MySQL 8 max_allowed_packet 默认 64MB；如需硬上限应另开卡在 controller 层做分批，不在本卡范围。

---

## 2. A-8 活动关联维护集合化

### 2.1 锚点前后对照（8 处循环）

| # | 方法（基点点位） | 交付后 | 分支 | 批量形态 |
| --- | --- | --- | --- | --- |
| 1 | `applyActivityUserRelations` :42 | :42-83 | add | 1 次 `selectExistingUserIds` + 1 次多值 `batchInsert` |
| 2 | 同上 :59 | 同上 | delete | 1 次 `selectExistingUserIds` + 1 次 `deleteByActivityIdAndUserIds` |
| 3 | `applyActivityContactRelations` :87 | :85-128 | add | 1 次 `selectExistingContactIds` + 1 次 `batchInsert` |
| 4 | 同上 :105 | 同上 | delete | 1 次 `selectExistingContactIds` + 1 次 `deleteByActivityIdAndContactIds` |
| 5 | `addContactRelations` :128 | :130-167 | add | 同上（null 项先过滤再比对） |
| 6 | `addUserRelations` :159 | :169-206 | add | 同上 |
| 7 | `deleteActivityContactAssociations` :187 | :208-223 | delete | 1 次比对 + 1 次 IN delete |
| 8 | `deleteActivityUserAssociations` :202 | :225-240 | delete | 1 次比对 + 1 次 IN delete |

辅助判定：`hasDuplicatedId` :261、`intersects` :266（`Collections.disjoint` 单 return）、`containsAllIds` :271。
新增 mapper：`BusinessActivityUserMapper.selectExistingUserIds / deleteByActivityIdAndUserIds / batchInsert`、`BusinessActivityContactMapper.selectExistingContactIds / deleteByActivityIdAndContactIds / batchInsert`（接口 + XML，`create_time` 交由库默认 `CURRENT_TIMESTAMP`，与旧单条 insert 的「null 列省略」口径等价；两表 DDL 见 `V1__baseline.sql`）。**未删除**任何旧单条 mapper 方法——`existsByActivityIdAndUserId` 仍被 `AssistResponsibilityResolver`、`BusinessActivityServiceImpl:214`、`ProjectFileAttachmentReader` 使用。

单方法 SQL：旧 2N（每次 exists 1 + 每次写 1）→ 新 2（1 查 + 1 写）。

### 2.2 错误语义对照表（8 方法 × 4 类语义）

语义逐路径保持：码、文案、null 跳过、请求内重复 id 全部与旧逐条实现一致（旧实现里「第二轮 check 看到第一轮写入/删除的效果」这一可观测量被显式建模为 `hasDuplicatedId`）。

| 方法 | add 重复（库内已存在） | add 重复（请求内重复 id） | delete 缺失 | delete 请求内重复 id | null 项 |
| --- | --- | --- | --- | --- | --- |
| applyActivityUserRelations | 50002 `BUSINESS_ACTIVITY_USER_DUPLICATE` | 50002 同文案 | 92015 `BUSINESS_ACTIVITY_USER_NOT_EXISTS` + 同文案 | 92015 同文案 | 不跳过（与旧一致，交给 DB 约束） |
| applyActivityContactRelations | 50002 `BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST` | 50002 同文案 | 92014 `BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS` + 同文案 | 92014 同文案 | 不跳过（与旧一致） |
| addContactRelations | 50002 `..._CONTACT_ALREADY_EXIST` | 50002 同文案 | —（无 delete 分支） | — | **跳过保留**（只为非空 contactId 比对与写入） |
| addUserRelations | 50002 `..._USER_DUPLICATE` | 50002 同文案 | — | — | **跳过保留** |
| deleteActivityContactAssociations | — | — | 92014 + 同文案 | 92014 同文案 | null/空列表 → 直接返回，零 SQL（旧一致） |
| deleteActivityUserAssociations | — | — | 92015 + 同文案 | 92015 同文案 | null/空列表 → 直接返回，零 SQL（旧一致） |

（`applyActivity*Relations` 一个方法含 add/delete 两分支，故 8 处循环落在 6 个方法里，上表按「方法 × 分支」展开即 8 条路径。）
每条路径的「零写入」也由定向用例断言：错误在批量写之前抛出，旧实现「先写一部分再抛 + 外层事务回滚」在成功路径上等价、在失败路径上少一次无效写。

### 2.3 check-then-act 竞态不劣化论证

- 6 个方法的全部调用方均为 `@Transactional(rollbackFor = Exception.class)`（`BusinessActivityServiceImpl:225/347/369/391`），读-比对-写在**同一事务**内，窗口从「N 次独立往返」压到「1 读 + 1 写」。
- 竞争依然可能（两事务同时读到「不存在」再各自写），但**旧实现同样可能**且窗口更长（N 次往返期间任何一次都可能被插入）。竞态兜底未变：`business_activity_contact.uk_activity_contact_unique(activity_id, contact_id)` 与 `business_activity_user.uk_activity_user_unique(activity_id, user_id)`（`V1__baseline.sql`）仍在，并发重复写最终仍由数据库唯一键拒绝。
- 与旧实现的差异仅在失败粒度：旧实现逐条 insert，冲突发生在第 k 条时前面 k-1 条已写（靠事务回滚）；新实现单条多值 insert，冲突则整条语句失败、一条不写——对调用方可见的仍是「事务回滚 + 异常」，无劣化。

---

## 3. A-10 用户批删单条化 + 置空修复（已拍板：修复）

### 3.1 锚点前后对照

| 位置 | 基点实测 | 交付后 |
| --- | --- | --- |
| `UserServiceImpl.deleteById` | 注解 :287-289，方法体 :290-311 | :290-311（注解未动） |
| 预检闸 | :292-300（`selectList(in(id)) + in(status,0,2)`，`size != id.size()` → `DATA_DELETE_FAILED`「删除失败，请注意用户状态」） | :292-300 **逐行保留** |
| 写路径 | :303-308 `for (userEntity : userEntities) { setStatus(3); setEmail(null); setPhone(null); updateById(...) }` | :302-309 **单条** `userMapper.update(null, LambdaUpdateWrapper.set(status,3).set(email,null).set(phone,null).in(id, idList))` |
| `@CacheEvict(userName, allEntries=true)` + `@Transactional` | 保留 | 保留 |

### 3.2 置空缺陷证据链（MP 策略 → 旧代码空操作 → 唯一键占用）

1. `application.yml:204-206` 的 `mybatis-plus` 块**只有** `mapper-locations`；全仓 `grep 'update-strategy|field-strategy'` 于 `application*.yml` **零命中** → 使用 MP 全局默认 `FieldStrategy.NOT_NULL`。
2. `UserEntity.email`(:47) / `phone`(:43) **无 `@TableField` 注解**（同文件仅 `@TableIndex` 与 `@TableName`）→ 无字段级 `updateStrategy` 覆盖。
3. 故旧实现 `userEntity.setEmail(null); setPhone(null); userMapper.updateById(userEntity)` 的 `SET` 子句里 **email/phone 两列被 NOT_NULL 策略整列丢弃**，从未真正置空（`status` 非 null 才生效）。
4. `UserEntity` 上 `uk_email`(:20-21) / `uk_phone`(:24-25) 唯一索引仍在 → 已删除用户长期占住这两个唯一键，无法复用/无法被别人登记，构成实际缺陷。
5. 同类先例：`CustomerPoolServiceTest:218` 注释「实体 NOT_NULL 策略下 updateById 置空是空操作，必须走显式 set null」（fix-minor-debt-family 任务 5.1）——本卡与该先例同型。
6. 新实现用 `LambdaUpdateWrapper.set(..., null)`：显式 SET 不受 NOT_NULL 策略影响，两个 null 会作为绑定参数进入 SQL。定向用例 `deleteById_singleBatchUpdateWithExplicitNulls` 断言 `sqlSet` 含 `status=`/`email=`/`phone=` 且绑定参数里**恰有 2 个 null**。

### 3.3 行为差异（拍板项，单列）

| 维度 | 旧（缺陷态） | 新（修复态） | 判断 |
| --- | --- | --- | --- |
| 已删用户 email/phone | 保留原值（策略静默丢弃 null） | **置为 NULL** | 符合代码原意；同时是 PII 清理（正向） |
| `uk_email`/`uk_phone` | 被已删用户永久占用 | 释放可复用 | 符合唯一键业务语义（提案立项理由） |
| 登录/查重路径 | 已删用户（status=3）本就被状态闸拦下，但 email 仍可被查重命中 | email 为 NULL，不再参与唯一键/查重命中 | 无功能回退风险 |
| 返回计数 | 忽略（恒 `true`） | 忽略（恒 `true`） | 未变 |
| 预检闸/重复 id | 重复 id 因 `selectList` 去重导致 `size != id.size()` → 抛 `DATA_DELETE_FAILED` | 同 | 未变（用例 `deleteById_duplicateIds_rejectedByPrecheck` 锁定） |
| 客户端可见错误码/文案 | — | 同码同文案，前端零改动 | 未变 |

唯一需要下游知晓的变化：**已删用户的 email/phone 现在是 NULL**。若将来有报表/审计依赖「已删用户仍保留联系方式」，需另开卡显式落"删除快照"字段（本卡不做迁移，故不动表结构）。

---

## 4. 定向测试清单与结果

命令（PowerShell 直跑，勿经 Git Bash）：

```
mvn -B -ntp "-Dtest=com.slz.crm.common.untils.ForeignKeyDeleteUtilBatchTest,com.slz.crm.server.service.impl.CustomerContactBatchLogicalDeleteTest,com.slz.crm.server.service.impl.BusinessActivityRelationSupportBatchTest,com.slz.crm.server.service.impl.BusinessActivityRelationSupportTest,com.slz.crm.server.service.impl.UserDeleteByIdsBatchTest" test
```

实测：`EXIT=0`，`Tests run: 43, Failures: 0, Errors: 0, Skipped: 0`（日志 `work/logs-loop-write-batch/targeted-final.log`）。

| 测试类 | 例数 | 覆盖点 |
| --- | --- | --- |
| `ForeignKeyDeleteUtilBatchTest`（新增） | 5 | 级联单趟（1 个 id 与 3 个 id 的 SQL 条数相等）；主表**单条** update + `SET isDeleted=true` + `id IN` + 双 id 绑定；重复 id 仍单条；单条入口仍走 `updateById`；空入参零交互 |
| `BusinessActivityRelationSupportBatchTest`（新增） | 28 | 8 条路径 × {add 库内重复 / add 请求内重复 / delete 缺失 / delete 请求内重复 / 正常批量写形态 / null 跳过 / null 全跳过零交互 / null 与空列表 no-op}；每例均断言「一次 IN 查询」+「多值 insert 或单条 IN delete 恰一次」+「逐条 exists/insert/delete 零调用」 |
| `BusinessActivityRelationSupportTest`（既有 4 例） | 4 | 50002 + 原文案（断言与文案**一字未改**，仅桩点改为集合化事实源） |
| `CustomerContactBatchLogicalDeleteTest`（新增） | 3 | 委托**恰一次**且全量 ids 原样下沉；重复 id 也只一次委托；空入参 `PARAM_EMPTY` + 零交互 |
| `UserDeleteByIdsBatchTest`（新增） | 3 | 单条 update 的 SET 三列 + `id IN` + 两个 null 绑定 + `never(updateById)`；预检闸 size 不符抛 `DATA_DELETE_FAILED` 同文案且零写；重复 id 被预检闸拦下 |

## 5. 全量验证

| 项 | 命令 | 结果 |
| --- | --- | --- |
| Docker 在线 | `docker info --format '{{.ServerVersion}}'` | `29.6.2` |
| 全量 | `mvn -B -ntp clean verify` | `BUILD SUCCESS`；surefire **1777 / 0 / 0**（283 报告），failsafe **118 / 0 / 0 / 7 跳**（31 报告）；spotless 0 待改（`work/logs-loop-write-batch/full-verify.log`） |
| 回归基线（单次、树净） | `D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update` | `EXIT=0`，写入 `source-revision=d9f3ae4`（无 `-dirty`）、`measured-at=2026-10-19T10:56:06Z`、**surefire 283/1777/0 + failsafe 31/118/7**（`work/logs-loop-write-batch/baseline-update.log`） |
| 基线四元组 | 基点 → 交付 | reports 279 → **283**，tests 1738 → **1777**，skipped 0 → 0；failsafe 31/118/7 → **31/118/7**（只增不减 ✓） |
| merge-gate | `D:\git\Git\bin\bash.exe scripts/merge-gate.sh` | **10/10 PASS**（unit / checkstyle / spotbugs / spotless / pmd / baseline / frontend-unit / hook / bijection / pmd-baseline；`[it]` 默认不跑，其间实测数字来自上面的 clean verify）→ 「== merge-gate 通过：所有已执行的子门禁绿 ==」；typed-router 生成物噪声已 `git checkout --` 还原（`work/logs-loop-write-batch/merge-gate.log`） |

PMD 一次返工记录：首轮 `mvn clean verify` 因 PMD `OnlyOneReturn`（代码风格规则，`maxAllowedViolations=0`）红 4 条——`logicalDeleteByCascadeBatch` 提前 return、`intersects` 3 return、`containsAllIds` 2 return。已改为单 return 写法（`Collections.disjoint` / `||` 短路），语义等价；返工后 `mvn -B -ntp compile pmd:check` = `violations=0`，重跑 `clean verify` 全绿。

## 6. 提交哈希清单（单行 ASCII，分笔）

| 哈希 | message | 内容 |
| --- | --- | --- |
| `d9f3ae4` | `fix: collapse loop writes in cascade delete, activity relations and user batch delete` | 13 文件 / +1258 / -112（6 源文件 + 2 mapper XML + 5 测试文件，含 4 个新测试类） |
| `5ab0446` | `test(baseline): update clean baseline to d9f3ae4` | `scripts/test-baseline.txt`（283/1777/0 + 31/118/7） |
| `(docs 笔)` | `docs: record loop write batch card materials and handoff` | 本 handoff 所在提交（紧随 `5ab0446`）：`openspec/changes/fix-engineering-debt-loop-write-batch/{proposal,tasks}.md` + `work/task-card-…md` + 本 handoff |

## 7. tasks.md 勾选证据（6 空格缩进行已写入 `openspec/changes/fix-engineering-debt-loop-write-batch/tasks.md`）

1.1–1.3 / 2.1–2.3 / 3.1–3.3 / 4.1–4.2 均在对应勾选行下一行留「补勾依据（2026-10-19 实测命令与结论）」。1.4/2.4 依据 = 本文件 §1.2/§2.3；4.3 依据 = §5 merge-gate 行；4.4 依据 = §6 提交清单。

## 8. 偏差清单

1. **既有测试桩点改写**（唯一触及既有测试的地方）：`BusinessActivityRelationSupportTest` 4 例的 `@DisplayName`、断言、期望文案**逐字未改**；仅把 `when(mapper.existsByActivityIdAndUserId(...))` 换成 `when(mapper.selectExistingUserIds(...))`（生产路径已不再调用逐条 exists，否则桩点失效）。已在类注释里写明该改动，并在 §2.1 说明旧单条 mapper 方法未被删除。
2. **`applyActivity*Relations` 的 null id 极端情形**：旧实现对 `userId=null` 的项会走 `exists(null)` → 0 → `insert(null)` 由 DB NOT NULL 拒绝；新实现若请求里出现**多个 null**，会先被判为「请求内重复」并抛 50002（码/文案不同）。该校验路径的 DTO 经 controller 校验不会产生 null 元素，且两表该列 `not null`，故不构成可达回归；记录以免复核时误判。
3. **卡面行号漂移**：`UserServiceImpl.deleteById` 卡面记 :287-311，实测注解起 :287、方法体 :290-311（一致）；`CustomerContactServiceImpl.batchLogicalDeleteByIds` 实测 :429-441（一致）。其余以实读为准。
4. **PMD 返工**：见 §5 末段，属门禁驱动的等价改写，无行为变化。
5. **`logicalDeleteByCascadeBatch` 对 null/空 ids 返回 0**（防御性早退改单 return 后为 `result=0`）：service 侧仍先抛 `PARAM_EMPTY`，故该分支对外不可达，只为 util 自身的健壮性保留。
