# 任务清单 · fix-backlog-batch2

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. ⑦ SQLExceptionHandler 收口
- [x] 1.1 :48 越界守卫 + 死代码清理 + 分支重构（组合/单列/回落三路清晰）
      补勾依据（2026-10-20 实读 SQLExceptionHandler.java 改动 + `mvn -B -ntp "-Dtest=SQLExceptionHandlerTest,BusinessActivityNullIdValidationTest" test`）：`bareIndexName()` 取代 `split("\\.")[1]` 裸下标，裸名/`.`/空串/null 四形态均不越界；恒真 `split.length > 0` 与 :53-56 死分支删除，分支收敛为 `duplicateKeyResult()` 三态；同一轮 ⑦ 13/13 绿（日志 work/logs/directed-2.log）。
- [x] 1.2 键空间隔离：索引名 7 键迁独立 map（yml+@Value+全仓消费面 grep 留证）
      补勾依据（2026-10-20 全仓 grep `databaseFieldMap|database-field-map|uniqueIndexFieldMap|unique-index-field-map`，留证 work/logs/grep-field-map-consumers.txt）：7 索引名键已迁至 application.yml:248-255 新键段 `slz.unique-index-field-map`，`slz.database-field-map`（:224-244）只留 20 个列名键；main 侧消费点仅 SQLExceptionHandler 三处 `@Value`（:43/:47/:51）与三处查表（:89/:128/:134），列名路径 `getNameByIndex` 改查未动；测试侧仅 SQLExceptionHandlerTest 注入三 map。
- [x] 1.3 SQL 裸列名路径走 getNameByIndex（文案改善拍板项，对照表入 handoff）
      补勾依据（2026-10-20 定向 test `missingDefaultValue_usesColumnName` / `missingDefaultValue_unknownColumnAndNoBacktick_staySafe`）：已知列 `company_id` → 「缺少必填字段：客户ID」（改前「数据」），未知列/无反引号 → 「缺少必填字段：未知字段」且不越界；前后对照表见 work/handoff-fix-backlog-batch2.md §1.4。
- [x] 1.4 测试：裸索引名不越界、组合/单列/未知索引三态、裸列名文案、既有路径回归
      补勾依据（2026-10-20 `SQLExceptionHandlerTest` 13/13 绿）：裸名/裸组合名/未知裸名/空名/仅点号 5 例 + 单列 7 键路由 + 组合 1 例 + 不可解析兜底 1 例 + FK 已知/未知列 2 例 + 缺省值 3 例；改前旧断言（`手机号 … 已存在`、`数据 X-1 已存在`、`共享 客户A 已存在`、FK 文案）原样保留。

## 2. ⑧ DTO null-id 校验
- [x] 2.1 5 DTO id 挂 @NotNull（实读确认 import/分组语义）
      补勾依据（2026-10-20 实读五 DTO + `BusinessActivityDtoNullIdConstraintTest` 7/7 绿）：`BusinessActivityUserDTO.userId`、`BusinessActivityContactDTO.contactId`、`AddActivityUserRequestDTO.userId`、`AddActivityContactRequestDTO.contactId` 挂 `jakarta.validation.constraints.NotNull`（Default 组，文案与 ResetUserPasswordDTO 风格一致）；`BusinessActivityDTO.id` 挂 `@NotNull(groups = ValidationGroups.Update.class)`——创建请求不带活动 id，挂 Default 组会把所有创建打成 50002「参数校验失败」（已有用例锁定创建零回归）。
- [x] 2.2 测试：@Valid 拒绝 null id（400 路径）+ 既有关联维护测试回归（50002 用例语义随前置拦截调整，逐处列明）
      补勾依据（2026-10-20 `mvn -B -ntp "-Dtest=BusinessActivityNullIdValidationTest,BusinessActivityDtoNullIdConstraintTest" test` 6+7=13 绿，日志 work/logs/probe-6.log）：MockMvc 断言 create/update/addUsers/addContacts 四条路径均 code=50002 + msg=参数校验失败 + service 零调用，创建零回归一例；既有「多 null id → 50002」用例 grep 实测全仓不存在（关联维护既有测试要么用非空 id、要么断言「null 项跳过」），故未删改任何既有断言，逐处核查结论见 handoff §2.2 与偏差 D3。

## 3. ⑫ Reflector 阈值注入
- [x] 3.1 经 CitationThresholdsResolver 注入（默认零漂移）；测试：注入生效 + 默认回归
      补勾依据（2026-10-20 `mvn -B -ntp "-Dtest=RuleSelfRagReflectorTest,LlmSelfRagReflectorTest,DefaultSelfRagServiceTest,CitationThresholdsResolverTest" test` 11+4+3+3=21 绿，日志 work/logs/directed-3.log）：Reflector 改持 `Supplier<CitationThresholds>`，Spring 构造经 `ObjectProvider<DynamicConfigService>` → `CitationThresholdsResolver.resolve()`；注入上限/下限两向生效、Supplier 返回 null、空配置、越界配置回落默认各一例；无参构造仍走 `defaults()`（= 原 0.12/0.22/0.08），既有 5 例原样通过。

## 4. 验证与留证
- [x] 4.1 定向全绿 + 全量 mvn -B -ntp clean verify（Docker 实测）
      补勾依据（2026-10-20 `docker info --format "{{.ServerVersion}}"` 实测 29.6.2；`mvn -B -ntp clean verify` BUILD SUCCESS，日志 work/logs/verify-full-4.log）：surefire 1871/0/0/0（299 报告）、failsafe 122/0/0/7（33 报告），均不低于卡面基点 297/1848 与 33/122/7。
- [x] 4.2 树净单次 check-test-baseline.sh --update + 校验 EXIT=0 + merge-gate 10/10
      补勾依据（2026-10-20 `D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update` 于树净状态写入 source-revision=0dbae59、measured-at=2026-10-20T16:11:04Z、surefire 299/1871/0、failsafe 33/122/7；复跑校验模式「回归基线门禁通过」EXIT=0；`D:\git\Git\bin\bash.exe scripts/merge-gate.sh` GATE_EXIT=0，unit/checkstyle/spotbugs/spotless/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline 共 10 项 PASS，日志 work/logs/merge-gate.log；typed-router.d.ts 生成物噪声已 checkout 还原）。
- [x] 4.3 handoff（四子项对照/消费面/行为变化论证/零漂移）+ tasks 勾选 + docs 笔；停步未 merge 未 push
      补勾依据（2026-10-20 提交三笔 0dbae59 fix → 1234f86 test(baseline) → 本 docs 笔；handoff 落 work/handoff-fix-backlog-batch2.md；分支 feature/fix-backlog-batch2 保留，未 merge、未 push）。
