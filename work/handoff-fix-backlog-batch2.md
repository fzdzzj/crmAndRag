# 交接 · fix-backlog-batch2（B 档，⑦⑧⑫ 三点收口）

- 权威树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- 分支：`feature/fix-backlog-batch2`（自 `master@9cbf55e` 检出，`git rev-parse --abbrev-ref HEAD` 实测 = `feature/fix-backlog-batch2`）
- 提交：`0dbae59`（fix）→ `1234f86`（test baseline）→ `<docs 笔见 §8>`
- 停步状态：**未 merge、未 push、分支保留**（`git log --oneline master..HEAD` 仅上述三笔）
- 平台：Spring Boot 3.5.5 / Spring 6.2.10 / Hibernate Validator 8.0.3；Docker Engine 实测 `29.6.2`

---

## 1. ⑦ SQLExceptionHandler 四子项

改动文件：`src/main/java/com/slz/crm/server/handler/SQLExceptionHandler.java`、`src/main/resources/application.yml`、
`src/test/java/com/slz/crm/unit/server/handler/SQLExceptionHandlerTest.java`。

### 1.1 越界守卫（子项 ①）

改前 `compositeUniqueIndexMap.containsKey(a = indexName.split("\\.")[1])`：`split` 结果被裸下标 `[1]` 取值，任何不含 `.` 的索引名
（MySQL 部分报文只给裸索引名）即抛 `ArrayIndexOutOfBoundsException` → 500。
改后统一经 `bareIndexName(String)` 归一：

| 入参形态 | `bareIndexName` 结果 |
|---|---|
| `sys_user.uk_phone` | `uk_phone` |
| `uk_phone`（裸名） | `uk_phone` |
| `uk_share_unique`（裸组合名） | `uk_share_unique` |
| `.`（仅点号） | `null`（走兜底） |
| `""`（空串） | `null`（走兜底） |
| `null` | `null`（走兜底） |

实现取 `lastIndexOf('.')` 后 `substring` + `trim`，**不再对 `split` 数组做任何下标访问**；`doesn't have a default value`
路径的 `` split("`") `` 同样改为 `parts.length > 1 ? parts[1] : null`（报文无反引号时不再 AIOOBE）。

### 1.2 死代码删除与分支重构（子项 ②）

- 删除原 `:50` 恒真判断（`split.length > 0`，`String.split` 永不返回空数组）及其 `:53-56` 死分支。
- 分支收敛为 `duplicateKeyResult(...)` 一条三态：
  1. 组合索引命中（`compositeUniqueIndexMap`）→ `组合名 + 冲突值首段 + " 已存在"`；
  2. 未命中 → `getFieldNameByIndex(索引名) + 冲突值 + " 已存在"`；
  3. 索引名不可解析/未登记 → 字段名回落「数据」。
- 分支覆盖表（`SQLExceptionHandlerTest` 13 例逐态锁定）：

| 路径 | 输入样例 | 命中分支 | 断言文案 |
|---|---|---|---|
| 单列已知（带表前缀） | `sys_user.uk_phone` | ② | `手机号 138066666666 已存在` |
| 单列已知（裸名，新增） | `uk_phone` | ②（①的守卫产物） | `手机号 138066666666 已存在` |
| 单列 6 项全路由 | `sys_user.uk_email` 等 | ② | 与改前逐字一致 |
| 组合已知（带表前缀） | `data_share.uk_share_unique` | ① | `共享 客户A 已存在` |
| 组合已知（裸名，新增） | `uk_share_unique` | ① | `共享 客户A 已存在` |
| 未知索引（带前缀 / 裸名，新增） | `sys_user.uk_not_registered`、`uk_not_registered` | ③ | `数据 X 已存在` |
| 空名 / 仅点号（新增） | `""`、`.` | ③ | `数据 X 已存在` |
| 报文不可解析 | `nope` | 兜底 | `数据已存在，无法重复添加` |
| FK 已知列 / 未知列 | `user_id` / `ghost_id` | 列名映射 | `…用户ID…` / `…未知字段…` |
| 缺省值已知列 / 未知列 / 无反引号（新增） | `company_id` / `ghost_column` / 无 | 列名映射 | 见 §1.4 |

### 1.3 键空间隔离（子项 ③）

- yml 新增键段 `slz.unique-index-field-map`（`application.yml` :248-255），迁入 7 键：
  `uk_phone=手机号`、`uk_email=邮箱`、`uk_company_name=企业名字`、`idx_mobile=联系电话`、`uk_contract_no=合同编号`、
  `uk_payment_no=支付编号`、`uk_invoice_no=发票编号`（值与迁移前逐项一致）。
- `slz.database-field-map` 只留 20 个**列名**键（`user_id`/`company_id`/…/`task_id`），7 个索引名键已删除。
- handler 新增 `@Value("#{${slz.unique-index-field-map}}") private Map<String,String> uniqueIndexFieldMap;`；
  `getFieldNameByIndex` 改查新 map（裸名归一后查），`getNameByIndex` 仍查列名 map。
- **全仓消费面 grep 留证**（`work/logs/grep-field-map-consumers.txt`，2026-10-20 实测）：

  | 消费点 | 位置 | 用途 |
  |---|---|---|
  | `@Value("#{${slz.database-field-map}}")` | `SQLExceptionHandler:43` | 列名路径（FK / 缺省值） |
  | `@Value("#{${slz.unique-index-field-map}}")` | `SQLExceptionHandler:47` | duplicate-key 路径（新） |
  | `@Value("#{${slz.composite-unique-index-map}}")` | `SQLExceptionHandler:51` | 组合索引路径（未动） |
  | `databaseFieldMap.get` | `SQLExceptionHandler:134`（`getNameByIndex`） | 列名直查 |
  | `uniqueIndexFieldMap.get` | `SQLExceptionHandler:128`（`getFieldNameByIndex`） | 索引名直查 |
  | `compositeUniqueIndexMap.get` | `SQLExceptionHandler:89` | 组合索引直查 |
  | 测试注入 | `SQLExceptionHandlerTest:65-67` | 三个 map 均由 yml 读回注入 |

  结论：`databaseFieldMap` 的消费面**只有** `SQLExceptionHandler.getNameByIndex` 一处（列名路径），迁出索引名键不破列名路径；
  其余命中 `uk_phone` 的文件（`UserEntity` 注释、`UserServiceImpl`、`UserDeleteByIdsBatchTest`、历史 openspec 归档）均与这两个 map 无关。

### 1.4 文案「数据」→ 实际字段名（子项 ④，**拍板行为改善**）

`doesn't have a default value` 路径传入的是**裸列名**（如 `company_id`），改前误走 `getFieldNameByIndex`（索引名映射）→ 必然回落「数据」；
改后改走 `getNameByIndex`（列名映射）。

| 输入报文 | 改前 | 改后 |
|---|---|---|
| ``Field `company_id` doesn't have a default value`` | `缺少必填字段：数据` | `缺少必填字段：客户ID`（拍板） |
| ``Field `ghost_column` doesn't have a default value`` | `缺少必填字段：数据` | `缺少必填字段：未知字段`（列名路径统一回落口径） |
| 含该短语但无反引号 | 500（AIOOBE） | `缺少必填字段：未知字段`（加固） |

除上表与 ① 的「越界→文案」外，duplicate-key / FK / 缺省值三条路径的**文案与错误码逐路径未变**（上表 §1.2 全部旧断言原样保留并通过）。

---

## 2. ⑧ 关联维护 5 DTO null-id 校验（**拍板行为变化**）

改动文件：`BusinessActivityUserDTO`、`BusinessActivityContactDTO`、`AddActivityUserRequestDTO`、`AddActivityContactRequestDTO`、
`BusinessActivityDTO`、新增 `pojo/dto/ValidationGroups.java`（controller **零 diff**，见 §6 D3）。

### 2.1 改前 vs 改后（HTTP 边界）

| 请求 | 改前 | 改后 |
|---|---|---|
| `POST /business/activity`，`userIdList[].userId=null` | 深探到 `applyActivityUserRelations`：单个 null → 写入 null 列撞库约束（500/未知错误）；**两个 null** → `hasDuplicatedId` 判「重复关联」→ 50002 + `商业活动用户已存在`（误导文案） | Bean Validation 前置拦截 → 全局 handler 统一返回 `code=50002`、`msg=参数校验失败`，service 零调用 |
| `POST /business/activity`，`contactIdList[].contactId=null` | 同上（联系人版） | 同上 |
| `POST /business/activity/{id}/users`，项 `userId=null` | `addUserRelations` 里 `filter(Objects::nonNull)` **静默跳过**，接口返回成功（调用方误以为已添加） | 50002 + 参数校验失败 |
| `POST /business/activity/{id}/contacts`，项 `contactId=null` | 静默跳过 | 50002 + 参数校验失败 |
| `PUT /business/activity`，列表项内 `userId`/`contactId=null` | 同 create 路径（深层拦截/撞库） | 50002 + 参数校验失败 |

- **错误码不变**：改前改后都是 `PARAM_FORMAT_ERROR(50002)`，HTTP 状态仍是 200（`Result` 包装），前端提示形态不变；
  变的只是 `msg` 由具体业务文案变为通用的「参数校验失败」——这正是拍板认可的「前置 400 语义」。
- **创建路径零回归**：`POST /business/activity` 本就不带活动 id（前端 `CreateActivityModal.vue` 的 `createDefaultForm()` 无 `id`），
  故 `BusinessActivityDTO.id` 的必填放在 `ValidationGroups.Update` 组，**Default 组不校验**：创建照旧放行（有专门用例锁定）。

### 2.2 既有测试调整：逐处说明（结论：无既有用例需要改）

- 卡面假设「既有关联维护测试中『多 null id → 50002』用例随前置拦截调整语义」。按此假设逐文件核查（2026-10-20 实测）：
  - `grep -rn "50002" src/test` 命中里与商业活动相关的只有 `BusinessActivityRelationSupportTest`（4 例，全部用非空 id `TARGET_ID=7`）
    与 `BusinessActivityRelationSupportBatchTest`（重复 id 用例用 `7L`，null 用例断言的是「null 项跳过、零数据库交互」）；
  - 这两个类是**服务层直调**（不经 MVC），`@NotNull` 不改变其入参行为，故 null 项跳过语义保持原样；
  - 全仓检索 `userRequest(null)` / `contactRequest(null)`：仅 `addContactRelations_allNull_noDatabaseAccess`、
    `addUserRelations_allNull_noDatabaseAccess`、`*_nullSkipped_batchShape` 三类，断言的是「跳过」而非 50002。
- 结论：**没有**「多 null id → 50002」的既有用例，因此**未删改任何既有断言**（断言强度也未降低）；卡面这段行为变化改由
  新增用例正向锁定（13 例，见 §2.3）。这一偏差记入 §6 D3。

### 2.3 新增测试

- `src/test/java/com/slz/crm/unit/controller/BusinessActivityNullIdValidationTest.java`（MockMvc standalone + 模拟 service，6 例）：
  create 两条（userId/contactId 各一）→ 50002 + `参数校验失败` + `verifyNoInteractions`；`/users`、`/contacts` 各一条；
  update 列表项内 null userId 一条；创建零回归一条（无 id + 身份字段齐全 → code=1 且 `create` 被调用）。
- `src/test/java/com/slz/crm/unit/pojo/dto/BusinessActivityDtoNullIdConstraintTest.java`（打包校验器直测，7 例）：
  4 个关联 DTO 的 Default 组各产生 1 条违例（含 message 与 propertyPath）；`BusinessActivityDTO.id` 在 Default 组零违例、
  在 Update 组 1 条违例、补 id 后零违例；`@Valid` 级联断言 `userIdList[0].userId` 与 `contactIdList[0].contactId`。

---

## 3. ⑫ Reflector 阈值改注入

改动文件：`src/main/java/com/slz/crm/server/ai/RuleSelfRagReflector.java`、`src/test/java/com/slz/crm/server/ai/RuleSelfRagReflectorTest.java`。

### 3.1 注入路径图

```
Spring 容器
  └─ RuleSelfRagReflector  (@Service，@Autowired 构造)
       └─ ObjectProvider<DynamicConfigService>            // 惰性，未装配时为 null
            └─ CitationThresholdsResolver.resolve(config)   // 同包，静态，只依赖冻结契约
                 ├─ config.get("rag.retrieval.citation.keepMin",  Double.class, 0.12)
                 ├─ config.get("rag.retrieval.citation.remapMin", Double.class, 0.22)
                 └─ config.get("rag.retrieval.citation.tieMargin",Double.class, 0.08)
                      └─ read(): null / 非有限数 / 越出 [0,1] → 逐键回落该键默认
            └─ Supplier<CitationThresholds>                 // 字段仅此一个，无 Spring 类型
                 └─ reflect() 每次实时解析一次（同一答案内一致）
                      └─ handleWithSources → filterCitations → isValid → isClauseSupported
                           └─ score >= thresholds.keepMin()      // 原：>= CitationAligner.KEEP_MIN
```

构造矩阵（三个构造器，Spring 只认 `@Autowired` 那个，其余给测试/旁路）：

| 构造 | 阈值来源 | 用途 |
|---|---|---|
| `RuleSelfRagReflector()` | `CitationThresholds::defaults` | 测试/旁路，**核心逻辑零 Spring** |
| `RuleSelfRagReflector(Supplier<CitationThresholds>)`（包内） | 传入；`null` 等同默认 | 单测显式注入 |
| `@Autowired RuleSelfRagReflector(ObjectProvider<DynamicConfigService>)` | 解析器 | 生产装配 |

与上卡范式一致：`AiChatStreamFinalizer` 用的是 `Supplier<CitationThresholds>` + 调用侧
`CitationThresholdsResolver.resolve(provider.getIfAvailable())`，本卡把同一范式下沉到 Reflector 自身（Reflector 是 Spring Bean，
无需再改 `DefaultSelfRagService ` / `LlmSelfRagReflector` 的接口，`SelfRagReflector` 冻结契约零改动）。

### 3.2 零漂移论证

1. 无 `DynamicConfigService`（`getIfAvailable()==null`）→ `resolve(null)` → `CitationThresholds.defaults()` → `keepMin=CitationAligner.KEEP_MIN=0.12`，
   与改前**同一常量**。
2. `DynamicConfigService` 存在但三键未配置 → 契约返回调用方默认值 → 仍是 `defaults()`。
3. 配置越界/NaN/Infinity → `CitationThresholdsResolver.read()` 逐键回落各自默认（`CitationThresholdsResolverTest` 已锁，本卡未改）。
4. `Supplier` 返回 `null` → `resolveThresholds()` 回落 `defaults()`（新增用例 `nullSupplierFallsBackToDefaults`）。
5. 既有 5 个反射用例（越界/空块/不匹配/全剥除/全支撑）全部保留并通过，`new RuleSelfRagReflector()` 行为与改前逐字一致。

### 3.3 新增测试（`RuleSelfRagReflectorTest` 5 例 → 11 例）

- `injectedHigherKeepMin_stripsWeakCitation`：同一部分支撑输入，默认保留、`keepMin=0.99` 剥除（注入生效，上升向）。
- `injectedLowerKeepMin_keepsWeakCitation`：默认剥除、`keepMin=0.0` 保留（注入生效，下降向）。
- `nullSupplierFallsBackToDefaults` / `resolverWithEmptyConfig_matchesDefaults`：与无参构造**逐字等价**（默认回归）。
- `resolverWithConfiguredKeepMin_changesBehaviour`（生产构造 + fake 配置命中）/ `resolverWithOutOfRangeKeepMin_fallsBackToDefault`
  （`keepMin=1.5` 逐键回落）：锁死「Spring 装配构造经解析器」这条真实路径。

---

## 4. 定向测试（PowerShell 直跑，逗号列表未经 Git Bash）

| 命令（`mvn -B -ntp "-Dtest=…" test`） | 结果 | 日志 |
|---|---|---|
| `SQLExceptionHandlerTest,BusinessActivityNullIdValidationTest` | ⑦ 13/13 绿（该轮 ⑧ 尚未定稿，仅作 ⑦ 证据） | `work/logs/directed-2.log` |
| `BusinessActivityNullIdValidationTest,BusinessActivityDtoNullIdConstraintTest` | 6 + 7 = 13/13 绿 | `work/logs/probe-6.log` |
| `RuleSelfRagReflectorTest,LlmSelfRagReflectorTest,DefaultSelfRagServiceTest,CitationThresholdsResolverTest` | 11 + 4 + 3 + 3 = 21/21 绿 | `work/logs/directed-3.log` |

> 注：PowerShell 管道 `Tee-Object` 会让 `$LASTEXITCODE` 记录为 1（假信号），上表结论一律以日志中的 `BUILD SUCCESS` + `Tests run: … Failures: 0` 判绿。

## 5. 全量验证、基线四元组、merge-gate

- Docker 实测：`docker info --format "{{.ServerVersion}}"` = `29.6.2`。首轮 `clean verify` 时 daemon 未运行（Testcontainers 报
  `Could not find a valid Docker environment`，failsafe 仅 16 例 / 跳过 10），已用 `docker desktop start` 拉起引擎后重跑（记入 §6 D2）。
- 全量：`mvn -B -ntp clean verify` → **BUILD SUCCESS**，surefire **1871/0/0/0**、failsafe **122/0/0/7**、
  报告文件 surefire **299** / failsafe **33**（日志 `work/logs/verify-full-4.log`）。
- 基线（树净单次 `--update`，脚本 Git Bash 显式路径）四元组：

  ```
  # source-revision=0dbae59
  # measured-at=2026-10-20T16:11:04Z
  surefire.reports=299  surefire.tests=1871  surefire.skipped=0
  failsafe.reports=33   failsafe.tests=122   failsafe.skipped=7
  ```

  复跑校验模式：`回归基线门禁通过…`，**EXIT=0**。相对卡面基点（surefire 297/1848、failsafe 33/122/7）只增不减。
- merge-gate：**10/10 PASS**（`unit`、`checkstyle`、`spotbugs`、`spotless`、`pmd`、`baseline`、`frontend-unit`（21 文件/197 用例）、
  `hook`、`bijection`、`pmd-baseline`；`[it]` 按脚本默认不跑，需 `--with-verify`），`GATE_EXIT=0`，日志 `work/logs/merge-gate.log`。
  `frontend/typed-router.d.ts` 生成物噪声已 `git checkout --` 还原（当前 `git status` 干净，仅剩未入库任务卡）。

## 6. 提交哈希清单

| 笔 | 哈希 | message | 内容 |
|---|---|---|---|
| 1 | `0dbae59` | `fix(backlog-batch2): guard sql duplicate-key parsing, isolate index key space, dto null-id validation, reflector threshold injection` | 7 个 main 文件 + yml + 3 个测试文件 + `frontend/openapi.yaml` + openspec 卡（proposal/tasks） |
| 2 | `1234f86` | `test(baseline): raise regression baseline to 299 reports/1871 tests after backlog-batch2` | `scripts/test-baseline.txt` |
| 3 | `<docs>` | `docs(handoff): fix-backlog-batch2 handoff and task checklist evidence` | 本 handoff + `openspec/changes/fix-backlog-batch2/tasks.md` 勾选 |

## 7. tasks.md 勾选证据

见 `openspec/changes/fix-backlog-batch2/tasks.md`：10 项全部勾选，每项勾选行下一行 6 空格 + 「补勾依据（2026-10-20 实测命令与结论）」。

## 8. 偏差清单（需 owner 裁决的 4 项 + 1 项环境注记）

- **D1（需裁决）`frontend/openapi.yaml` 有 8 行生成物 diff**：5 个 DTO 挂 `@NotNull` 后，springdoc 导出把
  `BusinessActivityUserDto.userId` / `BusinessActivityContactDto.contactId` / `AddActivityUserRequestDto.userId` /
  `AddActivityContactRequestDto.contactId` 标为 `required`，`OpenApiContractExportTest` 因此红。按仓内既定做法
  （前例 `47ad58b chore(contract): sync openapi export for reset password dto validation`）执行
  `bash scripts/check-openapi-consistency.sh --update` 同步，**没有手写任何前端代码**，`frontend/` 其余文件零 diff。
  与卡面红线「frontend/ 零 diff」字面冲突，故在此显式上报：若裁决要求严格零 diff，回退只需
  `git checkout 9cbf55e -- frontend/openapi.yaml`（但 `mvn verify` 的 `OpenApiContractExportTest` 会重新变红）。
- **D2（环境）Docker daemon 未运行**：卡面要求「先 `docker info` 实测在线」，实测首次为**未在线**（Testcontainers 报
  `Could not find a valid Docker environment`）。已用 `docker desktop start` 拉起（Engine 29.6.2，Docker Desktop 29 的
  npipe 为 `dockerDesktopLinuxEngine`，旧 `docker_engine` 管道不存在），随后全量 verify 的 failsafe 恢复到 122/7。
  后续跑本树 IT 前需确认 `docker desktop start` 后引擎在线。
- **D3（卡面假设与实测不符）「多 null id → 50002」既有用例不存在**：按 §2.2 逐文件核查，既有商业活动关联测试要么用非空 id，
  要么断言「null 项跳过」，没有任何「多 null id → 50002」用例，故**未调整任何既有断言**（也没有弱化断言强度）。
  行为变化由新增 13 例正向锁定；若 owner 期望保留旧文案（「…已存在」）作为深层兜底，可另开卡讨论。
- **D4（设计取舍）`BusinessActivityDTO.id` 用 `ValidationGroups.Update` 分组**：若挂 Default 组，`POST /business/activity`
  请求体本就不带 id → 所有创建请求会变 50002「参数校验失败」。实测 Spring 对 `List<DTO>` 请求体做元素校验时不携带分组提示，
  因此更新路径的「活动 id 为空」在 HTTP 边界仍由 service 兜底（**与改前行为一致，无回归**），约束语义由
  `BusinessActivityDtoNullIdConstraintTest` 在打包校验器层锁定。若要让边界强制，需把更新端点改成单对象/包装体或显式分组校验，
  那会牵动对外契约，本卡未做。
- **D5（未执行）`frontend/` 的 `pnpm gen:api` 未跑**：契约 gate 只比对 `openapi.yaml`，前例 `47ad58b` 亦未重生成 SDK；
  若后续需要用 TS 类型层面的 `required`，请在 `frontend/` 跑 `pnpm gen:api` 并单独提交（会改 `sdk.gen.ts`/`types.gen.ts`）。

## 9. 风险与遗留

- ⑦ `firstValueSegment` 沿用 `duplicateValue.split("-")[0]`：`split` 至少返回 1 元素，不会越界；组合值本身含 `-` 时的取段语义与改前一致。
- ⑧ `@NotNull` 只覆盖「身份字段为空」；「id 指向不存在记录」仍由 FK 路径（`未知字段存在关联数据` / `…ID不存在`）承载，未改。
- ⑫ 阈值每答案解析一次（同一答案内一致、跨答案热生效）；`CitationAligner.KEEP_MIN` 常量保留为默认值来源，未删除（避免动冻结契约语义）。
- 本卡未动任何迁移脚本、`pom.xml`、错误码常量、冻结契约、前端源码（除 D1 的生成物契约导出）。
