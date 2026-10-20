# Handoff · harden-test-backlog-batch1（C 档，测试加固五点）

- 分支：`feature/harden-test-backlog-batch1`（权威树，自 master@590afae 检出；`git rev-parse --abbrev-ref HEAD` 实测）
- 基点四元组：surefire 296 报告 / 1838 tests、failsafe 32 / 119 / 7 skipped、merge-gate 10/10
- 收尾四元组：surefire **297 / 1848 / 0 skipped**、failsafe **33 / 122 / 7 skipped**、merge-gate **10/10 PASS**
- 状态：**未 merge、未 push，分支保留**；`git diff --stat master...HEAD -- src/main` 为空（硬红线达成）

## 0. 红线实证

| 红线 | 实证 | 结论 |
|---|---|---|
| src/main 零 diff | `git diff --stat master...HEAD -- src/main` → 空（exit 0） | ✅ |
| 零迁移 | `git diff --stat master...HEAD -- src/main/resources/db/migration` → 空 | ✅ |
| 零新依赖 | `git diff --stat master...HEAD -- pom.xml` → 空 | ✅ |
| 零新增错误码 | ErrorCode / MessageConstant / PlatformErrorCode 全在 src/main（零 diff）内 | ✅ |
| 不动前端 | `git diff --stat master...HEAD -- frontend` → 空（门禁产生的 typed-router 噪声已 `git checkout --` 还原） | ✅ |
| 基线只增不减 | 296/1838/32/119 → 297/1848/33/122，skipped 0/7 不变 | ✅ |
| 既有断言不弱化 | 仅 3 处形态适配：①的扫描定位改正则（同一判据、样本只增）、③的 join 冒烟换 latch+有界自旋（断言更严）、④的文案断言原样保留并在其后追加码断言 | ✅ |
| 单笔哈希 | `98c407e`(test) → `5c9b8c7`(test(baseline)) → `docs`（本文件所在笔） | ✅ |

完整改动面（`git diff --stat master...HEAD`）：7 文件 +581/−28，全部落在 `src/test/**` 与 `scripts/test-baseline.txt`。

## 1. 五点前后对照

### ① 空格形态防回归（`src/test/java/com/slz/crm/common/exiception/BaseExceptionConvergenceTest.java`）

| 项 | 前 | 后 |
|---|---|---|
| 锚点 | 旧扫描 `flattened.indexOf("new BaseException(", from)`（原 L55） | `CONSTRUCTOR_SITE = Pattern.compile("new\\s+BaseException\\s*\\(")`（L46）+ `constructorArgs(String)`（L146） |
| 断言形态 | 字面量定位：括号前多一个空格 → 整点不计数、不判违规（格式化回滚即可静默绕过） | 正则定位：`new`/类名/左括号之间任意空白都计入扫描点，再走同一套实参判定 |
| 负向自证 | 只有实参形态样本（空参/`.getMessage()`/字符串） | 新增 `spacedConstructorFormIsScannedAndRejected`（L158）：①先证「旧实现字面量对空格形态漏检」`doesNotContain("new BaseException(")`；②空格违规形态被抽出且判违规；③合法形态带空格仍被计数且判绿（对称盲区）；④同一行混合两形态必须数得 2 个点位 |
| 用例数 | 6 | **7**（+1） |
| src/main 现状 | `new BaseException (` 命中 **0**、`new BaseException(` 命中 **513** → 加固为纯前瞻，扫描点下限 `>300` 保持不变 | 同左 |

### ② release 置空真库回环 IT（新增 `src/test/java/com/slz/crm/integration/service/CustomerPoolReleaseRoundTripIT.java`）

| 项 | 前 | 后 |
|---|---|---|
| 锚点 | `CustomerPoolServiceImpl#release`（生产 L105-132，`UpdateWrapper.set(ownerId, null)` L121）；`CustomerPoolServiceTest` 为纯 mock，无法证明「置空真的落库」 | 真 MySQL（Docker/Testcontainers）failsafe IT，3 用例 |
| 断言形态 | mock 层「调用了 set(ownerId,null)」 | 真库逐列断言：`owner_id` 确为 **NULL**、`pool_status=1`、`pool_release_time` 非空、`pool_release_reason` 原样、`is_deleted=0` 仍可见 |
| 用例数 | 0 | **3**（`releasePersistsOwnerNulling` L64 / `releaseWithBlankReasonFallsBackToDefault` L97 / `releasedCompanyVisibleInPublicPool` L109） |

**seed-断言设计**

1. seed：`@Transactional` + `@Sql(/init_data.sql)`；`@BeforeEach` 用 `BaseUnit.setCurrentRole(id=2)` 指定登录人，再用 `JdbcTemplate` 插一行自有客户 `company_id=9001, owner_id=2, pool_status=0, is_deleted=0`（事务回滚，共享容器零残留）。
2. 为什么用 2 而不是 TestRole 的 12：真库 `init_data.sql` 只种 `sys_user` id 1/2/3，`customer_company.creator_id` 有真 FK `fk_company_creator → sys_user(id)`——首轮用 12 **实测撞 FK 报红**（`work/_harden-it.log` 首跑），改 2 后绿。这是设计偏差 D2，非断言弱化。
3. 前置自检 `ownerIdOfCompany() == 2`：先证置空前负责人确实存在，否则「置空为 NULL」无法区分「被清空」与「本来就没写」，直接堵住 `NOT_NULL` 策略下 `updateById` 空操作的假绿。
4. 主断言按「置空 → 字段成组 → 行可见 → 日志成对」四段：`owner_id IS NULL`；`pool_status=1`+`pool_release_time IS NOT NULL`+`pool_release_reason` 精确；`COUNT(*) WHERE is_deleted=0 AND owner_id IS NULL = 1`（软删回环：只清负责人不删行）；`customer_pool_log` 的 `action_type=RELEASE / operator_id=2 / previous_owner_id=2 / new_owner_id IS NULL / reason` 与客户行一致（证明置空是同一次流转，不是后续覆盖）。
5. 可见性单独一例：`queryPool`（`pool_status=1 AND is_deleted=0`）能查到该行且 `VO.ownerId == null`，覆盖「置空后行可见性与字段值」。
6. 空白原因一例：`release(id, "   ")` 回退文案「销售主动放弃」，owner 同样为 NULL。

### ③ TokenBudget 区别力（`src/test/java/com/slz/crm/platform/token/TokenBudgetPropertiesConcurrencyTest.java`）

| 项 | 前 | 后 |
|---|---|---|
| 锚点 | `TokenBudgetProperties` 三字段 volatile（生产 L27/L30/L33） | 同左（生产零改动） |
| 断言形态 | 「volatile 结构断言 + `writer.join()` 后主线程读值」——后者靠 `Thread.join` 的 happens-before，**对 volatile 与非 volatile 无区别力** | 结构断言逐字段（L44，另加 not-final）+ 负向自证（L58）+ latch 排序的有界自旋可见性用例（L78） |
| 用例数 | 3 | **4**（+1） |

**区别力依据（三点，写进类 javadoc）**

1. **确定性判据**：`sharedMutableFieldsAreVolatile` 逐字段读 `Modifier.isVolatile`，移除**任一** volatile → 该字段判 false → 红。反射读修饰符，与机器/时序/仲裁器无关。
2. **负向自证**：`ProbeFixture`（L131）同包放一个非 volatile、一个 volatile 字段，证明探针会判 false/true，排除「探针恒真 → 结构断言永远绿」的假绿。
3. **语义冒烟**：读线程先确认读到灰度默认值（证明确实在读字段），再 `CountDownLatch` 排序后写线程写满三字段，读线程在 **2s 纳秒截止 + `Thread.onSpinWait()` 有界自旋**内必须读到（无 sleep）。JMM 上 volatile 写建立 happens-before，最迟下一轮读取可见；非 volatile 时 JIT 可合法把字段读提升出循环 → 永不命中 → 截止判红。该类用例**不承担唯一判据**（x86 缓存一致性下非 volatile 也可能偶然可见），故与 1) 组合。
4. **实测证据**：把 `enabled` 的 `volatile` 临时删掉 → `sharedMutableFieldsAreVolatile:49 [enabled 必须 volatile…]` **红（Tests run 4, Failures 1）**，随后 `git checkout --` 还原、`git diff --stat -- src/main` 为空（日志 `work/_harden-mutation.log`）。可见性用例在 mutation 下未红，正是上述「偶然可见」的实证，故判据落在结构断言上——这是**有意设计**，不是侥幸。

### ④ MessageConstant 9 处码断言

| # | 生产站点（实读） | MessageConstant（实读行） | 错误码（ErrorCode 实读行） | 测试断言 |
|---|---|---|---|---|
| 1 | `BusinessActivityRelationSupport:61` | `BUSINESS_ACTIVITY_USER_DUPLICATE`（Text L67-68） | `PARAM_FORMAT_ERROR`=**50002**（L153） | `BusinessActivityRelationSupportTest` 4 例字面码 50002 |
| 2 | `BusinessActivityRelationSupport:76` | `BUSINESS_ACTIVITY_USER_NOT_EXIST`（L71-72） | `BUSINESS_ACTIVITY_USER_NOT_EXISTS`=**92015**（L258） | `...BatchTest`（枚举 getCode 逐例） |
| 3 | `BusinessActivityRelationSupport:106` | `BUSINESS_ACTIVITY_CONTACT_ALREADY_EXIST`（L69-70） | `PARAM_FORMAT_ERROR`=**50002**（L153） | `BusinessActivityRelationSupportTest` 字面 50002 |
| 4 | `BusinessActivityRelationSupport:121` | `BUSINESS_ACTIVITY_CONTACT_NOT_EXIST`（L73-74） | `BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS`=**92014**（L254-255） | `...BatchTest` |
| 5 | `PermissionServiceImpl:128` | `ROLE_PERMISSION_IS_NULL`（L125-126） | `PARAM_EMPTY`=**50001**（L150） | **新增** `PermissionServiceImplMessageCodeTest#nullRoleIdMapsToParamEmpty`（L53） |
| 6 | `PermissionServiceImpl:133` | `ROLE_IS_NOT_EXIST`（L112） | `ROLE_NOT_EXISTS`=**13001**（L68） | **新增** `#missingRoleMapsToRoleNotExists`（L69） |
| 7 | `PermissionServiceImpl:139` | `PERMISSION_IS_NOT_EXIST`（L124） | `ID_NOT_EXISTS`=**92008**（L236） | **新增** `#missingPermissionsMapsToIdNotExists`（L87） |
| 8 | `UserServiceImpl:88` | `MASTER_IS_ONE`（L111） | `ADMIN_EXISTS_ONLY_ONE`=**13002**（L71） | `UserServiceImplTest` 追加 `assertEquals(13002, getCode())`（L73） |
| 9 | `UserServiceImpl:243` | `MASTER_IS_ONE`（L111） | `ADMIN_EXISTS_ONLY_ONE`=**13002**（L71） | 同上（L92） |

- 每个新断言都写「字面码 + `ErrorCode.X.getCode()` 双重锁」：字面值防枚举漂移后测试跟着漂，枚举引用防字面值抄错。
- 邻接加固：`UserServiceImplTest` 的 `ROLE_NOT_EXISTS` 例补 13001（L105），不在 9 处之内。
- 生产批次路径（`BusinessActivityRelationSupport:194/233/262/279`）由 `...BatchTest` 的 `assertBizError(...)` 以 `expectedCode.getCode()` 逐例覆盖，本次未改。

### ⑥ A-7 null id 三类（`src/test/java/com/slz/crm/unit/service/UserHandoverServiceImplTest.java`）

| 项 | 前 | 后 |
|---|---|---|
| 锚点 | `UserHandoverServiceImpl#batchGetUserNames`（生产 L228-248）/`collectUserId`（L251-255） | 同左（生产零改动） |
| 断言形态 | 只有「全非空 id」「用户被删（批量结果缺失）」「空页」三例，null id 跳过分支无显式用例 | 逐类 null + 组合 |
| 用例数 | 3 | **8**（+5：`skipsNullFromId` L196 / `skipsNullToId` L226 / `skipsNullOperatorId` L255 / `allIdsNullSkipsBatchQuery` L283 / `mixedNullAndPresentIds` L306） |

断言口径：每例都「断言 null 项姓名保持 null + 非 null 项正常回填 + `ArgumentCaptor` 锁定进批量查询的 id 集合大小与内容 + `selectById` 零调用」；全 null 例额外断言 `selectBatchIds` **零调用**（空集不发 SQL）；混合页例断言全空行被跳过、正常行照常回填、批量查询恰一次。

## 2. 定向验证清单与结果

| 段 | 命令（PowerShell 直跑） | 结果 | 日志 |
|---|---|---|---|
| 单元定向 | `mvn -B -ntp "-Dtest=BaseExceptionConvergenceTest,TokenBudgetPropertiesConcurrencyTest,UserHandoverServiceImplTest,UserServiceImplTest,PermissionServiceImplMessageCodeTest,BusinessActivityRelationSupportTest,BusinessActivityRelationSupportBatchTest" test` | `Tests run: 58, Failures: 0, Errors: 0, Skipped: 0`（7 类），BUILD SUCCESS，exit 0 | `work/_harden-targeted.log` |
| 新 IT 定向 | `mvn -B -ntp "-Dit.test=CustomerPoolReleaseRoundTripIT" test-compile failsafe:integration-test failsafe:verify` | `Tests run: 3, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS，exit 0 | `work/_harden-it.log` |
| 全量 | `mvn -B -ntp clean verify`（`docker info` 实测 Server 29.6.2 在线） | surefire **1848**/0/0/0（297 报告）、failsafe **122**/0/0/**7**（33 报告）、checkstyle 0 violations、spotless/spotbugs/PMD 全过，BUILD SUCCESS，15:21 min | `work/_harden-verify.log` |
| 基线 | `D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update` → 复跑校验模式 | update EXIT=0（source-revision=98c407e、measured-at 2026-10-20T12:31:08Z）；check EXIT=0 | `work/_harden-baseline-update.log` / `_harden-baseline-check.log` |
| 门禁 | `D:\git\Git\bin\bash.exe scripts/merge-gate.sh` | **10/10 PASS**：`[unit] [checkstyle] [spotbugs] [spotless] [pmd] [baseline] [frontend-unit] [hook] [bijection] [pmd-baseline]`（`[it]` 默认序列按设计未执行），GATE_EXIT=0 | `work/_harden-merge-gate.log` |
| 区别力 mutation | 临时删 `enabled` 的 volatile → 定向 → `git checkout --` 还原 | 红 `Tests run: 4, Failures: 1`（`sharedMutableFieldsAreVolatile:49`），还原后 `git diff --stat -- src/main` 空 | `work/_harden-mutation.log` |

基线四元组：`surefire.reports=297 / surefire.tests=1848 / failsafe.reports=33 / failsafe.tests=122 / skipped 0 & 7`（现行 `scripts/test-baseline.txt`）。

## 3. 提交清单

| 笔 | 哈希 | 内容 |
|---|---|---|
| 1 | `98c407e` | 6 个测试文件（①③④⑥ 改动 + ② 新 IT + ④ 新权限码测试） |
| 2 | `5c9b8c7` | `scripts/test-baseline.txt`（单次 --update 产物，source-revision=98c407e） |
| 3 | 见 `git log` | 文档笔：本 handoff + `openspec/changes/harden-test-backlog-batch1/{proposal,tasks}.md` |

## 4. 偏差清单

- **D1（④ 口径纠偏）**：卡面「9 处均仅断言文案未锁码」部分过期——`BusinessActivityRelationSupportTest` 的 4 处已在 fix-engineering-debt-loop-write-batch（A-8）带 `50002` 字面码断言。本卡实际增量 = 5 处（3 处 `PermissionServiceImpl` 零测试覆盖 + 2 处 `UserServiceImpl` 无码断言）+ 1 处邻接（13001）；未改写既有 4 处断言。
- **D2（② seed 用户）**：首跑用 TestRole.SALES(12) 撞真 FK `fk_company_creator → sys_user(id)`（真库只种 1/2/3），改用 2 后绿；断言强度不变（仍走 `isOwner` 分支）。
- **D3（③ 可见性用例非唯一判据）**：mutation 实测证明「非 volatile + x86 仍可能偶然可见」，故「移除任一 volatile 即红」的确定性由结构断言（反射 isVolatile + 负向自证）承担，可见性用例定位为语义冒烟；已在类 javadoc 写明依据，未用 sleep 碰运气。
- **D4（① 纯前瞻）**：src/main 实测 `new BaseException (` 命中 0，扫描点 513 不变；`>300` 下限与零违规断言未动。
- **D5（任务卡不入库）**：`work/task-card-harden-test-backlog-batch1.md` 按卡要求留在 `work/` 根未跟踪；本 handoff 与 openspec 物料入库。
- **D6（typed-router 噪声）**：merge-gate 的 `[frontend-unit]` 改写 `frontend/typed-router.d.ts`，已 `git checkout --` 还原，`git status` 仅剩 `openspec/` 与任务卡未跟踪项。

## 5. 未做 / 后续

- 未 merge、未 push（按卡停步）；分支 `feature/harden-test-backlog-batch1` 保留。
- 待办池剩余 7 条（⑤ 及 ⑦-⑫）不在本卡范围。
