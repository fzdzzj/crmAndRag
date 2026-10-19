# A 档独立复核报告 · fix-engineering-debt-loop-write-batch

- 复核对象：权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/fix-engineering-debt-loop-write-batch`（**未 merge、未 push**）
- 复核基点：`master@8d61f89`；分支三笔 `d9f3ae4`（fix）→ `5ab0446`（test baseline）→ `efda011`（docs）
- 复核方式：只读核验 + 定向复跑；**未做任何写操作**（未 merge / 未 push / 未改源文件），复核后 `git status --porcelain` 仍为空
- 复核者：独立复核子 Agent（A 档，只核不改）
- 实测时间：2026-10-19（本机时钟）

---

## 0. 结论：**GO（无阻断项）**

三面（git / 代码 / 复跑）全部核过，与 handoff 声明一致；定向 43 例全绿（EXIT=0）、回归基线校验模式 EXIT=0 且四元组吻合、附加 `pmd:check` EXIT=0。**MAJOR = 0**。共出 5 条 MINOR（全部为文档精度 / 论证表述 / 可维护性，不阻断合并）与 6 条待 Owner 知悉项（含两项行为口径变化与一项同类残留债）。

---

## 1. 复核范围与实测命令（可复现）

| # | 面 | 命令 | 实测 |
| --- | --- | --- | --- |
| 1 | git | `git -C <树> rev-parse --abbrev-ref HEAD / HEAD / master`、`git log --oneline --graph -6`、`git status --porcelain`、`git diff --name-status master..<分支>`、`git diff --stat d9f3ae4/5ab0446/efda011` | 见 §2 |
| 2 | 代码 | `git diff` 逐文件 + `read`/`grep` 实读（行号一律用 ripgrep/`read` 口径，不用 PowerShell `Get-Content`，见 §6 坑 2） | 见 §3 |
| 3 | 复跑 | `mvn -B -ntp "-Dtest=<5 类逗号清单>" test` | **EXIT=0，Tests run: 43, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**（`work/review-targeted-mvn.log`） |
| 4 | 复跑 | `D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh`（**校验模式，未加 --update**） | **EXIT=0**，surefire 283/1777/0 + failsafe 31/118/7（`work/review-baseline-check.log`） |
| 5 | 附赠 | `mvn -B -ntp pmd:check` | **EXIT=0 / BUILD SUCCESS**（`work/review-pmd.log`）→ PMD 返工后 0 违规（含 `OnlyOneReturn`） |

> 复跑顺序说明：先在**未跑过定向测试前**执行第 4 步。`check-test-baseline.sh` 的计量口径是 `target/surefire-reports` 现存报告集合（`reports>=283`），先跑定向测试会把集合缩到 5 份、必然误判为不达标。实测复核前 `target/surefire-reports` 恰有 **283** 份、`failsafe-reports` **31** 份，且从 283 份 XML 的 `<testsuite tests="...">` 求和 = **1777**，与基线文件逐位一致（见 §4.4）。

---

## 2. 面一 · git 面（逐项实测）

### 2.1 拓扑与终态

| 项 | 预期 | 实测 | 判 |
| --- | --- | --- | --- |
| 当前分支 | `feature/fix-engineering-debt-loop-write-batch` | `rev-parse --abbrev-ref HEAD` = 同名 | ✅ |
| HEAD | `efda011` | `efda011430255f18fc6dc50f91c1014fd16b769a` | ✅ |
| master 未动 | `8d61f89` | `8d61f895bdd5d3b075261ef3448709fd6f119cca`（= `origin/master`） | ✅ |
| 笔序 | fix → test(baseline) → docs | `d9f3ae4` → `5ab0446` → `efda011`（三笔正是分支相对 master 的全部） | ✅ |
| 工作树终态 | `status --porcelain` 空 | 复核开始与两次 mvn 之后**均为空** | ✅ |
| merge / push | 未做 | 分支无 merge 提交、master 仍在基点 | ✅ |

### 2.2 master..feature 全量 diff 清单（= d9f3ae4..efda011 全量，18 文件）

| # | 文件 | 笔 | 行数 |
| --- | --- | --- | --- |
| 1 | `src/main/java/com/slz/crm/common/untils/ForeignKeyDeleteUtil.java` | d9f3ae4 | +53 / −0 |
| 2 | `src/main/java/com/slz/crm/server/service/impl/CustomerContactServiceImpl.java` | d9f3ae4 | +2 / −5 |
| 3 | `src/main/java/com/slz/crm/server/service/impl/BusinessActivityRelationSupport.java` | d9f3ae4 | +137 / −96 |
| 4 | `src/main/java/com/slz/crm/server/service/impl/UserServiceImpl.java` | d9f3ae4 | +9 / −7 |
| 5 | `src/main/java/com/slz/crm/server/mapper/BusinessActivityContactMapper.java` | d9f3ae4 | +30 |
| 6 | `src/main/java/com/slz/crm/server/mapper/BusinessActivityUserMapper.java` | d9f3ae4 | +30 |
| 7 | `src/main/resources/mapper/BusinessActivityContactMapper.xml` | d9f3ae4 | +24 |
| 8 | `src/main/resources/mapper/BusinessActivityUserMapper.xml` | d9f3ae4 | +24 |
| 9 | `src/test/java/com/slz/crm/common/untils/ForeignKeyDeleteUtilBatchTest.java`（新增） | d9f3ae4 | +178 |
| 10 | `src/test/java/com/slz/crm/server/service/impl/BusinessActivityRelationSupportBatchTest.java`（新增） | d9f3ae4 | +559 |
| 11 | `src/test/java/com/slz/crm/server/service/impl/BusinessActivityRelationSupportTest.java`（改写） | d9f3ae4 | +10 / −4 |
| 12 | `src/test/java/com/slz/crm/server/service/impl/CustomerContactBatchLogicalDeleteTest.java`（新增） | d9f3ae4 | +79 |
| 13 | `src/test/java/com/slz/crm/server/service/impl/UserDeleteByIdsBatchTest.java`（新增） | d9f3ae4 | +123 |
| 14 | `scripts/test-baseline.txt` | 5ab0446 | +4 / −4 |
| 15 | `openspec/changes/fix-engineering-debt-loop-write-batch/proposal.md`（新增） | efda011 | +18 |
| 16 | `openspec/changes/fix-engineering-debt-loop-write-batch/tasks.md`（新增） | efda011 | +41 |
| 17 | `work/handoff-fix-engineering-debt-loop-write-batch.md`（新增） | efda011 | +170 |
| 18 | `work/task-card-fix-engineering-debt-loop-write-batch.md`（新增） | efda011 | +16 |

合计 `git diff --stat master..<分支>`：**18 files changed, 1507 insertions(+), 116 deletions(-)**；分笔实测 `d9f3ae4` = 13 文件 / +1258 / −112、`5ab0446` = 1 文件 / +4 / −4、`efda011` = 4 文件 / +245 —— 与 handoff §6 声明**逐位一致**。

写集面核对：6 源文件（4 改动类 + 2 mapper 接口）✅、2 mapper XML ✅、5 测试文件（含既有 `BusinessActivityRelationSupportTest` 改写）✅、`scripts/test-baseline.txt` ✅、openspec 卡物料 2 份 + handoff + task-card ✅。**未见任何预期外文件**。

> 计数口径差异（MINOR-4）：复核提示词写「共 17 文件」，实测 18。差额即 `work/task-card-…md`——提示词 17 = 6+2+5+1+(proposal,tasks)+handoff，未计 task-card；而提示词「输入」段又把 `work/task-card-*.md` 列为卡物料。属提示词算术口径，非分支偏差。

### 2.3 红线：以下路径必须零 diff —— 实测全零

| 路径 | 实测 |
| --- | --- |
| `src/main/resources/db/migration/` | 0 diff（零迁移）✅ |
| `pom.xml` | 0 diff（零新依赖）✅ |
| `frontend/`（含 `typed-router.d.ts`、`error-code-map.ts`） | 0 diff ✅ |
| `spec/` | 0 diff（冻结契约未动）✅ |
| `common/enumeration/ErrorCode.java`（附核） | 0 diff（零新增错误码常量）✅ |

### 2.4 基线笔如实性

`git diff master..<分支> -- scripts/test-baseline.txt` 实测：`source-revision` `c00e0e0`→`d9f3ae4`、`measured-at` `2026-10-19T09:08:21Z`→`2026-10-19T10:56:06Z`、`surefire.reports` 279→283、`surefire.tests` 1738→1777、`surefire.skipped` 0 不变、`failsafe.*` 31/118/7 不变 —— **只增不减**，且仅这 4 行变动（无手改痕迹）。✅

---

## 3. 面二 · 代码面（逐项 + 行号）

### 3.1 A-6 级联批量化下沉

| 检查点 | 实测证据 | 判 |
| --- | --- | --- |
| `deleteCascade` 零改动 | `ForeignKeyDeleteUtil.java:43-78`；diff 对全文件只有两处**纯新增** hunk（+import、+批量方法），该区间无一行出现在 diff 删除侧 | ✅ |
| 单条入口保留走 `updateById` | `:87-96` `logicalDeleteByCascade` → `:95 return logicalDeleteMainRecord(entityClass, id)`，`:139 mapper.updateById((T) updateEntity)` | ✅ |
| 批量入口级联单趟 | `:110-120`：`:112` 守卫 → `:114 deleteCascade(entityClass, ids, null)`（一趟全量 ids） | ✅ |
| 主表单条 IN + entity+wrapper SET 语义 | `:154-177`：`:164` 反射 `setIsDeleted(Boolean.TRUE)`、`:166 wrapper.in("id", ids)`、`:167 mapper.update((T) updateEntity, wrapper)`；MP 默认 `FieldStrategy.NOT_NULL` → `SET` 仅出 `is_deleted=?`，`WHERE id IN (...)`，语义正确（未被误扩成整行覆盖） | ✅ |
| service 循环零残留 | `CustomerContactServiceImpl.java:429-438`：无 `for`，`:436` 注释 + `:437 return foreignKeyDeleteUtil.logicalDeleteByCascadeBatch(CustomerContactEntity.class, idList)`；`grep` 确认该类无 `count += logicalDeleteById` 残留 | ✅ |
| `@Transactional` / `PARAM_EMPTY` 保留 | `:430 @Transactional(rollbackFor = Exception.class)`、`:432-434` null/空 → `PARAM_EMPTY(50001)`；单条入口 `:424-427` 原样 | ✅ |
| 计数口径变化单列 | handoff §1.2(c) 已单列：`application.yml:35` JDBC URL **未设 `useAffectedRows`**（实测该行全文无此参数）→ Connector/J 默认「匹配行数」口径：重复 id 旧=2、新=1。调用侧 `CustomerContactController.java:202` 仅 `Result.success(...)` 回显，无分支依赖 | ✅ |
| 空/ null ids | 批量入口 `result=0` 早退改单出口（PMD 返工），service 侧先抛 `PARAM_EMPTY` 故对外不可达（handoff §8.5 如实） | ✅ |

### 3.2 A-8 活动关联维护集合化（8 路径逐条对照 git diff）

旧实现锚点（`git grep -n "for (" master -- …`）：`42 / 59 / 87 / 105 / 128 / 159 / 187 / 203`（8 处 ✅；handoff 表把第 8 处记作 `:202`，见 MINOR-3）。
新实现：`BusinessActivityRelationSupport.java` —— `applyActivityUserRelations :42-82`（add `:58-72` / delete `:73-81`）、`applyActivityContactRelations :85-127`（add `:103-117` / delete `:118-126`）、`addContactRelations :130-166`、`addUserRelations :169-205`、`deleteActivityContactAssociations :208-222`、`deleteActivityUserAssociations :225-239`。

逐路径四类语义核对（对照 `git show master:…RelationSupport.java` 旧体）：

| 路径 | 旧行为 | 新行为 | 判 |
| --- | --- | --- | --- |
| add 库内已存在 | 逐条 `exists==1` → `PARAM_FORMAT_ERROR(50002)` + `MessageConstant.BUSINESS_ACTIVITY_USER_DUPLICATE` / `..._CONTACT_ALREADY_EXIST` | `intersects(existing, requested)` → 同码同文案（`:60-63`、`:105-108`、`:148-151`、`:187-190`） | ✅ |
| add 请求内重复 id | 第 2 轮 exists 命中第 1 轮刚插入行 → 50002 同文案 | `hasDuplicatedId(userIds/userIds…)` → 50002 同文案（`:60`、`:105`、`:148`、`:187`；`:261-263` 集合去重比长度） | ✅ 语义保留 |
| delete 缺失 | `exists==0` → `BUSINESS_ACTIVITY_USER_NOT_EXISTS(92015)` / `..._CONTACT_NOT_EXISTS(92014)` 同码同文案 | `!containsAllIds(existing, requested)` → 同码同文案（`:75-79`、`:120-124`、`:216-220`、`:233-237`） | ✅ |
| delete 请求内重复 id | 第 2 轮 exists 已删行 → NOT_EXISTS 同文案 | 同码同文案（`:216`/`:233` 的 `||` 右侧 `hasDuplicatedId`；码值实测未变：`PARAM_EMPTY=50001`、`PARAM_FORMAT_ERROR=50002`、`DATA_DELETE_FAILED=91003`、`BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS=92014`、`BUSINESS_ACTIVITY_USER_NOT_EXISTS=92015`，见 `ErrorCode.java:150/153/207/254-255/258`） | ✅ |
| delete 空/null 列表 | 有 `if (非空)` 包壳 → 零 SQL | `:210-212`、`:227-229` 早退 → 零 SQL（用例 `verifyNoInteractions` 锁定） | ✅ |
| `add*Relations` null 项跳过 | `continue` 跳过 null | `:136-140`、`:175-179` `filter(Objects::nonNull)` 后再比对/写入；全 null → `:141-143`、`:180-182` 零交互 | ✅ |
| `applyActivity*Relations` null 项 | 不跳过（`exists(null)`→0→`insert(null)`→DB NOT NULL 拒绝） | 不跳过（仍交给 DB）—— 但多 null 时**先被判请求内重复**抛 50002，属码/文案变化，见 MINOR-1 | ⚠️ MINOR |

- list-vs-set 比对保留请求内重复语义：`hasDuplicatedId` 与「集合是否含全部请求 id」两条判定并联使用，请求内重复不会被集合运算吞掉（`:60`、`:75`、`:105`、`:120`、`:148`、`:187`、`:216`、`:233`）。✅
- `intersects` 用 `Collections.disjoint` 单出口（`:266-268`），`existing==null` 视为空 —— 与旧「逐条 exists，任一命中即抛」等价；`containsAllIds`（`:271-273`）`requested` 为空返回 `true`，但 4 个 delete 调用点均已在 `:210/:227` 早退或上游保证非空，不可达（MINOR-5 记档）。✅
- 新增 mapper 6 个方法 + 2 份 XML 实测点位：接口 `BusinessActivityContactMapper.java:34/44/53`、`BusinessActivityUserMapper.java:50/60/69`；XML `BusinessActivityContactMapper.xml:17/25/33`、`BusinessActivityUserMapper.xml:25/33/41`。
  - 存在性查询：`select contact_id/user_id … where activity_id = #{activityId} and contact_id/user_id in <foreach #{}>` ✅
  - IN delete：`delete … where activity_id = #{activityId} and … in <foreach #{}>` ✅
  - 多值 insert：列 `(activity_id, user_id|contact_id, user_role|contact_role, creator_id)` 与 `values (#{entity.activityId}, #{entity.userId|contactId}, #{entity.userRole|contactRole}, #{entity.creatorId})` **逐列对齐**（4 列 4 值）✅；`create_time` 未列出 → 走库默认（DDL `V1__baseline.sql:339`「`datetime default CURRENT_TIMESTAMP not null`」、`:371` 同），与旧单条 `insert` 的「null 列省略」等价 ✅；`id` 由 auto_increment 生成未列出 ✅
  - 注入面：全部 `#{}` 占位 + `<foreach>` 拼接值占位，**无 `${}`**；`insert` 语句为固定列名 ✅
  - 实体字段名与 `#{}` 属性一致：`BusinessActivityContactEntity`(activityId/contactId/contactRole/creatorId/createTime)、`BusinessActivityUserEntity`(activityId/userId/userRole/creatorId/createTime) 实测 ✅（并有 43 例运行期 + 全量 1777 例间接验证）
- 旧单条 mapper 方法保留、3 处其他调用方不破：`existsByActivityIdAndUserId` 仍被 `AssistResponsibilityResolver.java:132`、`BusinessActivityServiceImpl.java:214`、`ProjectFileAttachmentReader.java:100` 调用（实测 3 处）；`existsByActivityIdAndContactId` / 两个单条 delete 声明仍在接口内（未被删）✅
- check-then-act 竞态不劣化：6 方法全部调用方都在 `BusinessActivityServiceImpl` 且逐一对齐事务注解 —— `:238/:242` 位于 `:225 @Transactional`、`:361` 于 `:347`、`:383` 于 `:369`、`:406/:409` 于 `:391`（`grep` 实测 4 处注解 + 6 处调用，无遗漏调用方）✅；唯一键兜底未变（`V1__baseline.sql:340-341 uk_activity_contact_unique`、`:372-373 uk_activity_user_unique`）✅
- null-id 退化情形不可达论证：**论证前提不成立**（见 MINOR-1），但无数据损坏、无 happy-path 回归。

### 3.3 A-10 用户批删单条化 + 置空修复

| 检查点 | 实测证据 | 判 |
| --- | --- | --- |
| 单条 update SET 三列 + IN | `UserServiceImpl.java:304-310`：`.set(UserEntity::getStatus, 3).set(UserEntity::getEmail, null).set(UserEntity::getPhone, null).in(UserEntity::getId, id)`，首参 `null` | ✅ |
| 预检闸逐行保留 | `:292-300`：`selectList(in(id, id).in(status, 0, 2))` + `size() != id.size()` → `DATA_DELETE_FAILED(91003)`「删除失败，请注意用户状态」（与旧逐字一致） | ✅ |
| 注解保留 | `:288 @CacheEvict(value="userName", allEntries=true)`、`:289 @Transactional(rollbackFor=Exception.class)` | ✅ |
| 旧循环彻底消失 | diff 删除侧即原 `for (UserEntity userEntity : userEntities){…updateById}`；用例 `never()).updateById(any())` 锁定 | ✅ |
| 置空修复证据链（MP NOT_NULL） | ① `application.yml:204-206`：`mybatis-plus:` 块仅 `mapper-locations`（`:206`）✅；② `grep -r "update-strategy\|field-strategy\|useAffectedRows" src/main/resources/application*.yml` = **No matches** ✅；③ `UserEntity.java` 全文件 `@TableField` 计数 **0**，`phone:43` / `email:47` 无字段级策略覆盖 ✅；④ `uk_email:20` / `uk_phone:24` 为 `IndexTypeEnum.UNIQUE`，DDL `V1__baseline.sql:80-83` 同为 `unique` ✅ → 旧 `updateById` 的 SET 子句整列丢弃 null，**确为空操作**；新显式 `set(..., null)` 不受策略影响 | ✅ 结论成立 |
| 差异表如实 | handoff §3.3 五项（email/phone 变 NULL、uk 释放、查重路径、返回计数、码/文案）与实测一致；`sys_user.update_time` 为 `on update CURRENT_TIMESTAMP`（`V1__baseline.sql:78`）→ 旧逐条 `updateById` 与新单条 update 均照旧刷新，**未见时间戳回归** | ✅ |
| 返回计数 | 新实现忽略 `update` 返回值、恒 `return true`（`:312`），与旧一致 | ✅ |

### 3.4 既有 `BusinessActivityRelationSupportTest` 改写（唯一触及既有测试处）

`git diff` 实测该文件仅 5 行实质变动：`+import any`、`+import Set`、4 处 `when(...)` 桩点（`:46/:66/:86/:103` 附近）从 `existsByActivityIdAnd*Id(anyLong(), anyLong()) → 1` 换成 `selectExisting*Ids(anyLong(), any()) → Set.of(TARGET_ID)`，外加类注释 `:30-33` 双记（写明「断言与文案一字未改，仅换事实源」）。4 个 `@DisplayName`、4 处 `assertThat(...).isEqualTo(MessageConstant.…)`、`code == 50002` 断言**全部以 diff 上下文行出现（未被删改）**。✅

### 3.5 PMD `OnlyOneReturn` 返工后形态

- `logicalDeleteByCascadeBatch`（`:110-120`）单出口：`Integer result = 0; if (非空){…} return result;`（无提前 return）✅
- `intersects`（`:266-268`）单出口：`existing != null && !Collections.disjoint(existing, requested)` —— 与旧「逐个 contains 命中即 true」等价（`disjoint` 为「无交集」，取反即「有交集」；null 视为空集）✅
- `containsAllIds`（`:271-273`）单出口：`requested.isEmpty() || (existing != null && existing.containsAll(requested))` —— 与旧「任一缺失即 false」等价 ✅
- 附加实测（超出复核提示词要求）：`mvn -B -ntp pmd:check` **EXIT=0 / BUILD SUCCESS**（PMD 7.9.0，`maxAllowedViolations=0` 口径下即 0 违规），返工形态合规 ✅

### 3.6 测试真实性（39 新增 + 4 改写 = 43，无空转）

| 类 | 例 | 断言实证（非空转证据） |
| --- | --- | --- |
| `ForeignKeyDeleteUtilBatchTest` | 5 | `:60` 1-id 与 3-id 的 `mockingDetails().getInvocations().size()` 相等（真实夹具由 `ForeignKeyConfig.ALL_RELATIONS` 递归构图）+「必须真的发出 SQL」下限断言；`:74` 捕获 `update(entity, wrapper)` 断 `getIsDeleted()==TRUE`、`getSqlSegment().contains("id IN")`、参数含 11/12、`never(updateById)`；`:98` 重复 id 仍 1 条；`:106` 单条入口 `times(1) updateById` + `never(update)`；`:116` 空入参返回 0 且 `totalInvocations()==0` |
| `BusinessActivityRelationSupportBatchTest` | 28 | 6 段（applyUser 6 / applyContact 6 / addContact 4 / addUser 4 / delContact 4 / delUser 4）；错误例同时断 `ex.getCode()` 与 `ex.getMessage()` 整串（`assertBizError :521-526`），成功/异常例均带「批量写恰一次 + 逐条 exists/insert/delete 零调用」；`:287/:351` 断 IN 查询收到的正是过滤后的 `List.of(7L,8L)`；`:291/:354` 断批量写条目数（null 项不得进入）；`:299/:362/:450/:509` 断全 null / 空列表 `verifyNoInteractions` |
| `BusinessActivityRelationSupportTest`（既有） | 4 | 50002 + `MessageConstant` 原文案逐字 |
| `CustomerContactBatchLogicalDeleteTest` | 3 | `:42` 委托恰一次且 `List.of(11,12,13)` 原样下沉 + `never(logicalDeleteByCascade)`；`:57` 重复 id 仍一次委托、返回值按实际行数；`:71` 空/null 抛 `PARAM_EMPTY` 且 `verifyNoInteractions` |
| `UserDeleteByIdsBatchTest` | 3 | `:61` `getSqlSet()` 含 status=/email=/phone=、`getSqlSegment()` 含 `id IN`、参数含 3/11/12、**null 绑定参数恰 2 个**（`Collections.frequency`）、`never(updateById)`；`:93` size 不符 → 91003 + 同文案 + 零写；`:107` 重复 id 被预检闸拦 |

无 `assertTrue(true)` / 无断言缺失 / 无用例级 `@Disabled`。✅

---

## 4. 面三 · 复跑面（本机实测）

### 4.1 定向单测（PowerShell 直跑，未过 Git Bash）

```
mvn -B -ntp "-Dtest=com.slz.crm.common.untils.ForeignKeyDeleteUtilBatchTest,com.slz.crm.server.service.impl.CustomerContactBatchLogicalDeleteTest,com.slz.crm.server.service.impl.BusinessActivityRelationSupportBatchTest,com.slz.crm.server.service.impl.BusinessActivityRelationSupportTest,com.slz.crm.server.service.impl.UserDeleteByIdsBatchTest" test
```

实测（`work/review-targeted-mvn.log`）：

```
Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0 -- in ...ForeignKeyDeleteUtilBatchTest
Tests run: 28, Failures: 0, Errors: 0, Skipped: 0 -- in ...BusinessActivityRelationSupportBatchTest
Tests run: 4,  Failures: 0, Errors: 0, Skipped: 0 -- in ...BusinessActivityRelationSupportTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- in ...CustomerContactBatchLogicalDeleteTest
Tests run: 3,  Failures: 0, Errors: 0, Skipped: 0 -- in ...UserDeleteByIdsBatchTest
Tests run: 43, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS      [exit code 0]
```

**43/43 全绿**，逐类例数与 handoff §4 表格（5/28/4/3/3）**逐位吻合**，`EXIT=0`。日志中 `+ FullyQualifiedErrorId : NativeCommandError` 为 mvn 写 stderr 诱发的 PowerShell 记录噪声，同日志 `BUILD SUCCESS` 为准（见 §6 坑 3）。

### 4.2 回归基线（校验模式，未 --update）

```
D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh        # 树净下执行
```

实测（`work/review-baseline-check.log`，**EXIT=0**）：

```
surefire 测试：报告=283（默认口径），Tests run=1777，Failures=0，Errors=0，Skipped=0｜基线 reports>=283 tests>=1777 skipped<=0
failsafe 测试：报告=31（默认口径），Tests run=118，Failures=0，Errors=0，Skipped=7｜基线 reports>=31 tests>=118 skipped<=7
回归基线门禁通过（基线文件：/d/code/crmAndRag-merge-add-knowledge-admin-api/scripts/test-baseline.txt）
```

四元组 **283/1777/0 + 31/118/7** 与基线文件、与 handoff §5 声明完全一致；`source-revision=d9f3ae4`、`measured-at=2026-10-19T10:56:06Z` 均已入文件（未见 `-dirty`）。✅

### 4.3 附加：PMD

`mvn -B -ntp pmd:check` → `BUILD SUCCESS`，**EXIT=0**（`work/review-pmd.log`）。✅

### 4.4 基线数字与现存报告的自洽性（独立于脚本的旁证）

复核前实测 `target/surefire-reports/TEST-*.xml` = **283** 份，对 283 份 `<testsuite tests="…">` 求和 = **1777**；`target/failsafe-reports` = **31** 份。即基线不是手改出来的数字，背后确有一次真实全量运行的报告集合（且包含 4 个新测试类各自的报告文件，时间戳 2026-10-19 19:03 前后）。✅

---

## 5. 三级清单

### MAJOR（阻断）：**0 条**

### MINOR（不阻断，建议合入后择机收口）

- **M1 · handoff §8.2「null id 退化不可达」论证前提不成立（表述问题，非行为缺陷）。**
  handoff 称「该校验路径的 DTO 经 controller 校验不会产生 null 元素」。实测：`AddActivityUserRequestDTO.java:10 private Long userId;`、`AddActivityContactRequestDTO.java:10 private Long contactId;`、`BusinessActivityUserDTO.java:20`、`BusinessActivityContactDTO.java:17` **均无 `@NotNull`/任何约束注解**（`grep "@NotNull|@Valid"` 于这 4 个 DTO = 0 命中），controller 的 `@Valid`（`BusinessActivityController.java:53/215/233`）对这些字段不生效。
  实际影响：请求内**单个** null → 新旧都失败（旧=DB NOT NULL 500，新=`batchInsert` 触发 DB NOT NULL 500，同类）；请求内**多个** null → 旧=DB 500，新=`hasDuplicatedId` 先命中 → **50002「重复关联」**（码与文案变化）。两者都不写入数据、不影响合法客户端，故判 MINOR。
  建议：订正 handoff 该段表述（或标注「多 null 情形码/文案由 500 变 50002」），根治可下卡在这两个 DTO 加 `@NotNull` 或入口过滤 null。
- **M2 · `logicalDeleteMainRecords` 硬编码主键列名 `"id"`**（`ForeignKeyDeleteUtil.java:166 wrapper.in("id", ids)`），绕过 MP `TableInfo` 映射；单条入口走 `updateById` 由 MP 解析主键列。当前唯一调用方 `CustomerContactEntity`（`@TableId(type=AUTO)` 无 value、DDL 主键 `id`）成立，**无现状缺陷**；但该 util 是通用组件，将来给主键列非 `id` 的实体复用时该语句会因未知列失败（抛 `DATA_DELETE_FAILED`，非静默）。建议改用 `TableInfoHelper.getTableInfo(entityClass).getKeyColumn()` 或补注释约定。
- **M3 · 卡物料 / handoff 的行号有 1–2 行漂移。** 实测校正：`BusinessActivityRelationSupport` 第 8 处旧循环 `master:203`（handoff/tasks 记 `:202`）；`logicalDeleteByCascade` 实为 `:87-96`（记 `:87-95`）；`logicalDeleteMainRecords` 实为 `:154-177`（记 `:154-178`）；`CustomerContactServiceImpl.batchLogicalDeleteByIds` 实为 `:429-438`（记 `:429-436`）；`UserServiceImpl.deleteById` 方法体实为 `:290-313`（记 `:290-311`）。全部为纯文档精度问题，代码锚点与语义描述均正确。
- **M4 · 文件计数口径差 1。** 提示词「共 17 文件」vs 实测 18（差 `work/task-card-…md`）。属提示词算术口径，分支无预期外文件。
- **M5 · handoff §3.2 括注失准。** 称 `UserEntity`「同文件仅 `@TableIndex` 与 `@TableName`」；实测还有 `@AutoTable` / `@Table`（auto-table）/ `@Column`（`:16/:17/:30…`）。**决定性论断（`@TableField` 计数 0、无字段级 update 策略）成立**，仅括注不准确。

### 待 Owner 知悉（不需改代码）

- **I1 · A-6 返回计数口径变化（已如实单列）**：重复 id 输入下旧=N 条 `updateById` 的命中数之和（如 `[11,11]`→2），新=`IN` 命中行数（→1）；不存在 id 两侧一致计 0。调用侧仅回显成功数（`CustomerContactController.java:202`），无分支依赖。
- **I2 · A-10 行为变化（拍板项落地）**：已删用户 `email`/`phone` 由「保留原值」变为 **NULL**（PII 清理 + 释放 `uk_email`/`uk_phone`）。若将来报表/审计依赖「已删用户仍保留联系方式」，需另开卡落删除快照（本卡不做迁移）。
- **I3 · 旧单条 mapper delete 方法已成事实死代码**：`deleteByActivityIdAndUserId/ContactId` 在 `src/main/java` 已无调用方（仅接口声明保留）；`existsByActivityIdAndUserId` 仍有 3 个调用方（见 §3.2）。保留是刻意的 API 面决策，建议下卡清理或加 `@Deprecated`。
- **I4 · 同类残留债（本卡写集面外）**：`CustomerCompanyServiceImpl.java:303-309 batchLogicalDeleteByIds` 仍是「`for (id) totalDeleted += logicalDeleteById(id)`」同型循环写，且无 `PARAM_EMPTY` 闸。该路径每条 id 还挂 `runAfterCommit(deleteByCompanyId)`（`:295-297`），不能直接套用 `logicalDeleteByCascadeBatch`，故不属本卡漏项，建议另立卡。
- **I5 · 基线 `source-revision` ≠ HEAD**：基线记 `d9f3ae4`，HEAD 为 `efda011`（仅 docs 笔，测试集合无变化）。`check-test-baseline.sh` 不校验 revision，本轮复核判为可接受；若项目要求「基线与 HEAD 同笔」，需在 docs 笔后重跑一次 `--update`（会再产生一次基线提交）。
- **I6 · 本轮未复跑项（复核范围声明）**：`mvn clean verify`（含 failsafe / Docker）与 `scripts/merge-gate.sh` 10/10 未复跑——复核提示词「复跑面」只指定定向 mvn + baseline 校验模式；handoff §5 的 clean verify / merge-gate 结论本轮仅做「声明与现存报告自洽性」旁证（§4.4），未重跑。若合并前要求 A 档全量门禁复证，需补跑 `merge-gate.sh`。

---

## 6. 环境坑实录（本轮踩到并已绕过）

1. **`git branch --show-current` 在本机 git 版本不支持**（stderr：`error: unknown option 'show-current'`）→ 用 `git rev-parse --abbrev-ref HEAD` 取分支名。
2. **PowerShell `Get-Content`/`Select-String` 读 UTF-8 中文文件会误码并把行号算错**（表现为中文乱码 + 偶发两行被并成一行，行号整体前移数行）。本轮最初用 pwsh 读 `application.yml` 得到 URL 在 `:27`、而 handoff 写 `:35`；改用 ripgrep（`grep` 工具）复核后确认 **handoff 的 `:35` 正确**，`:204-206` 亦正确。→ 结论：**本报告所有行号均取自 `read` 工具或 ripgrep/`git grep`**，PowerShell 仅用于能自证的计数/状态类输出。
3. **`mvn` 经 PowerShell 管道会混入 `NativeCommandError` 噪声**：`2>&1 | Out-File` 后出现 `+ FullyQualifiedErrorId : NativeCommandError`，但同日志 `BUILD SUCCESS` + `[exit code 0]` 为准。未使用 `Tee-Object`（其 exit 1 为假信号）。
4. **`-Dtest` 逗号清单必须 PowerShell 直跑**：经 Git Bash 会被 shell 拆分/改写。
5. **基线脚本执行顺序敏感**：`check-test-baseline.sh` 读 `target/*-reports` 现存集合，**必须先于**任何定向 `-Dtest` 运行执行，否则报告集合缩小必然判红（本轮已按此顺序执行）。

---

## 7. 复核结论重述

| 面 | 结论 |
| --- | --- |
| git 面 | 写集面 18 文件与声明一致（17 的口径差见 M4）；迁移 / pom / frontend / spec / ErrorCode **全零 diff**；HEAD=`efda011`、master=`8d61f89` 未动、`status --porcelain` 终态空 |
| 代码面 | A-6 单趟级联 + 主表单条 IN（SET 语义正确）、A-8 八路径四类语义逐条等价（含请求内重复 / null 跳过 / 空列表零 SQL）、A-10 单条 update + 置空修复证据链成立；既有测试改写仅桩点；PMD 单出口合规且 `pmd:check` 0 违规 |
| 复跑面 | 定向 **43/43 全绿 EXIT=0**；基线校验模式 **EXIT=0**，四元组 **283/1777/0 + 31/118/7**，`source-revision=d9f3ae4` |

**GO**（0 MAJOR；5 MINOR 均为文档精度/可维护性；6 项待 Owner 知悉）。建议 Owner 在授权合并前决定是否要求补跑 `merge-gate.sh`（I6）与是否订正 handoff §8.2（M1）。
