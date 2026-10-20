# handoff · fix-semantic-hook-support-unify（B 档 · 五路语义同步 hook 泛型化合一）

- 权威树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- 分支：`feature/fix-semantic-hook-support-unify`（自 `master@c17cc18` 检出，`git rev-parse --abbrev-ref HEAD` 实测）
- 停止位：**未 merge、未 push**，分支保留待主 Agent 审 diff 后提请合并
- 日期：2026-10-20（以下所有「实测」均为本卡执行期真实命令输出）

## 1. 结论

五域语义同步 hook 由「五份逐字同形克隆」收敛为「一个基类 `SemanticHookSupport` + 五个薄门面」。`runAfterCommitAsync` 现在只有**一份**实现；
`reingestById` 泛型化为函数式注入，软删/物理删的语义分叉由各域谓词**显式**表达，未被抹平。全部调用点零改动（diff 未触及任何 ServiceImpl 调用方）。
既有 3 专项 + 1 间接测试断言零改动，新增基类四象限单测与两个缺失域薄门面回归。

## 2. 五域前后对照表

| 域 | 门面签名（前 → 后） | reingest 谓词形态（前 → 后） | deleteById 处置 | 日志文案变化 |
|---|---|---|---|---|
| Contract 合同 | `runAfterCommitAsync(String,Executor,Runnable)` / `reingestById(Long,ContractIngestionService,Function<Long,ContractEntity>)` / `deleteById(Long,ContractIngestionService)` —— 签名不变，方法体改为委托 | 前：`full == null` 转删、否则 ingest（物理删域，无软删谓词）→ 后：谓词 `full -> full == null` 注入基类，**语义逐字等价** | **保留**（实测有调用：`ContractServiceImpl:299`）→ 一行直删 `ingestion.deleteByContractId(id)` | 无变化：`合同语义向量同步失败（{scene}），可由全量回埋端点兜底`（改为基类 `"{}语义向量同步失败（{}）…"` + `DOMAIN_LABEL="合同"`，输出字节一致） |
| Customer 客户 | `runAfterCommitAsync(...)` / `reingestById(Long,CustomerIngestionService,Function<Long,CustomerCompanyEntity>)` —— 签名不变，委托 | 前：`full == null \|\| Boolean.TRUE.equals(full.getIsDeleted())` → 后：**同一条谓词原文**注入基类（软删分叉在位，不抹平） | 前无此方法，后仍无（删除路径由服务层直调 `deleteByCompanyId`）——**无变化** | 无变化：`客户语义向量同步失败（{scene}）…`（`DOMAIN_LABEL="客户"`） |
| ContactTask 跟进任务 | `runAfterCommitAsync(...)` / `reingestById(...)` / `deleteById(...)` + 四个本域便捷闸 `syncAfterCommit` / `syncAllAfterCommit` / `deleteAfterCommit` / `deleteAllAfterCommit`（签名全原样） | 前：`full == null` → 后：谓词 `full -> full == null`（物理删域） | **保留**（便捷闸内部调用 `:131`/`:155` + 专项测试） | 无变化：`跟进任务语义向量同步失败（{scene}）…`（`DOMAIN_LABEL="跟进任务"`） |
| SalesOpportunity 销售商机 | 三个公共方法签名不变，委托 | 前：`full == null \|\| Boolean.TRUE.equals(full.getIsDeleted())` → 后：同一谓词原文注入（软删分叉在位） | **保留**（`SalesOpportunityServiceImpl:170`） | 无变化：`销售商机语义向量同步失败（{scene}）…`（`DOMAIN_LABEL="销售商机"`） |
| BusinessActivity 商业活动 | 三个公共方法签名不变，委托 | 前：`full == null` → 后：谓词 `full -> full == null`（物理删域，无软删列） | **保留**（`BusinessActivityServiceImpl:267`） | 无变化：`商业活动语义向量同步失败（{scene}）…`（`DOMAIN_LABEL="商业活动"`） |

基类形状（新增 85 行，`final class` + 私有构造，同包 package-private）：

- `static void runAfterCommitAsync(String domainLabel, String scene, Executor executor, Runnable action)`
- `static <E> void reingestById(Long id, Function<Long,E> entityLoader, Predicate<E> needsVectorDelete, Consumer<E> ingestAction, LongConsumer deleteAction)`

`Predicate` 承担「是否需要删向量」的**完整**判定（含实体缺失 `null`），基类不二次判空——这样每个域的分叉在门面里一眼可见，
不会被基类的公共空判掩盖。该契约写入基类 Javadoc，并由 5 个域门面的「实体缺失」用例逐一固定。

## 3. afterCommit 行为等价性论证（三轴）

改造前五份 `runAfterCommitAsync` 逐字同形（仅 WARN 里的域名词不同），改造后基类代码与原实现逐行同构：

1. **注册时机**：`Runnable guarded` 先构造（纯闭包，无副作用），再 `TransactionSynchronizationManager.isSynchronizationActive()` 判定；
   激活则 `registerSynchronization(new TransactionSynchronization(){ afterCommit(){ executor.execute(guarded); } })`，否直接
   `executor.execute(guarded)`。判定顺序、注册对象、`afterCommit` 回调体与改造前**逐字一致**（匿名类仍内联、仍只覆写 `afterCommit`）。
2. **回滚不派发**：未覆写 `afterCompletion`/`beforeCommit`，回滚仅走框架默认空实现 → `executor.execute` 不会被调用，向量零派发。
   基类单测 `transactionRolledBack_doesNotDispatch` 以 `afterCompletion(STATUS_ROLLED_BACK)` 显式固定该行为。
3. **异常 WARN 兜底**：`try { action.run(); } catch (Exception e) { log.warn(...) }` 原样搬运；`@SuppressWarnings("PMD.AvoidCatchingGenericException")`
   与两行豁免理由注释一并承接（理由文本未改）。日志文案改为参数化 `"{}语义向量同步失败（{}），可由全量回埋端点兜底"`，参数为
   `domainLabel` + `scene`，五个 label 取原文案前缀（合同/客户/跟进任务/销售商机/商业活动）→ **最终日志字符串与改造前逐字节相同**；
   基类单测断言了完整文案（含域标签与 scene）。

异步隔离等价性：`executor.execute(guarded)` 位置、次数、时机未变（提交后仅派发一次，不在 commit 回调里执行 action），
HTTP 主线程零等待的性质不变。`@Slf4j` 从五个门面移除后，门面不再生成 `log` 字段，无行为影响（门面内本无日志）。

## 4. deleteById 调用面实测证据

命令：`grep '(Contract|Customer|ContactTask|SalesOpportunity|BusinessActivity)SemanticHookSupport\.' src/**/*.java`（main 侧命中）：

- `BusinessActivityServiceImpl.java:267` → `BusinessActivitySemanticHookSupport.deleteById(id, businessActivityIngestionService)`
- `ContractServiceImpl.java:299` → `id -> ContractSemanticHookSupport.deleteById(id, contractIngestionService)`
- `SalesOpportunityServiceImpl.java:170` → `SalesOpportunitySemanticHookSupport.deleteById(id, salesOpportunityIngestionService)`
- `ContactTaskSemanticHookSupport.java` 内部 `deleteAfterCommit:124` / `deleteAllAfterCommit:145` 调 `deleteById`，其上层调用点
  `ContactTaskServiceImpl.java:131` / `:155`
- Customer 无 `deleteById` 方法（删除由服务层直调 `deleteByCompanyId`：`CustomerCompanyServiceImpl:233/265/286/297/348`）

**处置：五个 `deleteById` 全部保留门面，本卡零删除。** 无「无调用可删」的候选。

## 5. 定向测试清单与结果

命令（PowerShell 直跑，未过 Git Bash）：

```
mvn -B -ntp "-Dtest=SemanticHookSupportTest,ContractSemanticHookSupportTest,CustomerSemanticHookSupportTest,ContactTaskSemanticHookSupportTest,SalesOpportunitySemanticHookSupportTest,BusinessActivitySemanticHookSupportTest,CustomerContactServiceImplTest" test
```

结果：`Results: Tests run: 41, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`。

| 测试类 | 用例数 | 性质 |
|---|---|---|
| `SemanticHookSupportTest`（新） | 7 | 基类四象限：提交后派发 / 回滚不派发 / 无事务直发 / 异常 WARN 不阻断（含文案断言）/ reingest 三分支（缺失、软删、正常） |
| `ContractSemanticHookSupportTest`（既有） | 7 | 专项，**断言零改动** |
| `CustomerSemanticHookSupportTest`（新） | 5 | 薄门面回归（原无专项）：事务路径委托 + 软删谓词注入 + 缺失/正常分支 |
| `ContactTaskSemanticHookSupportTest`（既有） | 7 | 专项，断言零改动 |
| `SalesOpportunitySemanticHookSupportTest`（既有） | 8 | 专项，断言零改动 |
| `BusinessActivitySemanticHookSupportTest`（新） | 4 | 薄门面回归（原无专项）：委托 + 物理删域不误删 + deleteById |
| `CustomerContactServiceImplTest`（既有，间接） | 3 | 断言零改动；仅 javadoc 失效链接修正（见偏差 D1） |

证据日志：`%TEMP%\semantic-hook-unify\targeted-3.log`（前两轮为编译/格式失败轮，已修复；本轮为最终绿）。

## 6. 全量验证

- `docker info --format '{{.ServerVersion}}'` → `29.6.2`（EXIT=0，Docker 在线，failsafe 真跑）
- `mvn -B -ntp clean verify` → `BUILD SUCCESS`（EXIT=0）；surefire `Tests run: 1818, Failures: 0, Errors: 0, Skipped: 0`（290 份报告，
  基线 287/1802 → +3 报告 / +16 用例）；failsafe `Tests run: 119, Failures: 0, Errors: 0, Skipped: 7`（32 份报告，与基线同）；
  checkstyle / spotbugs / spotless / pmd 全绿。
- 证据日志：`%TEMP%\semantic-hook-unify\verify-1.log`

## 7. 基线四元组（reports/tests × surefire/failsafe）

树净状态下执行 `D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update`（EXIT=0，`source-revision=a423041`）：

```
surefire.reports=290  surefire.tests=1818  surefire.skipped=0
failsafe.reports=32   failsafe.tests=119   failsafe.skipped=7
```

复跑校验模式 → `CHECK_EXIT=0`，输出「回归基线门禁通过」。四元组（前 → 后）：**287/1802/32/119 → 290/1818/32/119**（只增不减）。
证据日志：`%TEMP%\semantic-hook-unify\baseline-update.log`、`baseline-check.log`。

## 8. merge-gate

`D:\git\Git\bin\bash.exe scripts/merge-gate.sh` → `GATE_EXIT=0`，PASS 计数 **10/10**：
`[unit] [checkstyle] [spotbugs] [spotless] [pmd] [baseline] [frontend-unit] [hook] [bijection] [pmd-baseline]`。
gate 运行中 `frontend/typed-router.d.ts` 被生成物改写，已 `git checkout -- frontend/typed-router.d.ts` 还原，`git status` 无该文件。
证据日志：`%TEMP%\semantic-hook-unify\merge-gate-1.log`。

## 9. 提交哈希清单（本分支，单行 ASCII）

| 序 | 哈希 | message | 内容 |
|---|---|---|---|
| 1 | `a423041` | `refactor(server): unify five semantic hook supports via SemanticHookSupport base` | 新增基类 + 五门面收缩 + 3 个新测试类 + 1 行 javadoc 修正（10 files，+534/−209） |
| 2 | `2392260` | `test(baseline): refresh regression baseline after semantic hook unify (surefire 290/1818, failsafe 32/119/7)` | `scripts/test-baseline.txt` |
| 3 | 本文件所在提交 | `docs: record semantic hook unify handoff and task evidence` | `openspec/changes/fix-semantic-hook-support-unify/{proposal,tasks}.md`（含勾选与依据）+ `work/handoff-fix-semantic-hook-support-unify.md` |

`work/task-card-fix-semantic-hook-support-unify.md` 按卡要求**不入库**（保持 untracked）。

## 10. tasks.md 勾选证据

10 项（1.1/1.2/1.3/2.1/2.2/2.3/3.1/3.2/3.3/3.4）全部勾选，每项下一行 6 空格 + 「补勾依据（2026-10-20 实测命令与结论）」。
勾选对应实测：定向 41 绿、clean verify 1818/119 绿、基线 --update/校验 EXIT=0、merge-gate 10/10、diff 仅 6 main + 4 test 文件。

## 11. 偏差清单（如实记录）

- **D1（唯一代码外改动）**：既有 `CustomerContactServiceImplTest` 的 javadoc 里 `{@link CustomerSemanticHookSupport#runAfterCommit}`
  指向早已不存在的方法名（改造前即失效），本卡顺手改为 `#runAfterCommitAsync`，1 行、非断言、不影响行为。
- **D2（设计选择）**：`reingestById` 的谓词承担「含 null 判定」的完整语义（而非基类先判空、谓词只管软删）。取舍理由：分叉在门面
  可读、可审；代价是谓词必须 null 安全——已由基类 Javadoc 明示，并由五域门面的「实体缺失」用例固定（任何域漏写都会红）。
- **D3（范围克制）**：ContactTask 的四个服务层便捷闸属本域独有 API，未上提基类（基类只承接五域共形部分），`deleteById` 亦未上提
  （各域摄取服务方法名不同，上提只会把差异搬进基类）。
- **D4**：`deleteById` 无「无调用可删」候选，故本卡无删除动作（任务卡允许的两种处置之一未触发）。
- **D5（无 `--with-verify` 的 `[it]`）**：merge-gate 默认不跑 `[it]`，failsafe 结果来自本卡 §6 的真实 `clean verify`（Docker 在线）。

## 12. 风险与观察点

- 新增测试 +16 用例、+3 报告均为**净增**，基线已按一次真实运行更新；后续卡若删改这些测试需同步 `--update`。
- 门面测试刻意做「薄」（只验证委托不倒 + 谓词注入），域级业务语义仍由各 ServiceImpl 既有测试与 IT 覆盖，不在本卡扩张。
- 基类日志使用参数化占位符，若未来新增域务必传 `domainLabel`，否则 WARN 定位能力退化（基类 Javadoc 已声明取值域）。
