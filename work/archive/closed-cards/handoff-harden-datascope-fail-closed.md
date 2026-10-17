# Handoff · harden-datascope-fail-closed（数据权限切面 Fail-Closed 治理）

> 执行子 Agent 交付快照（2026-10-18）。分支 `feature/harden-datascope-fail-closed`，基点 master@c40b56a；**未 merge、未 push，停步等待 A 档复核**。

## 1. 结论一览

- I-1 数据权限切面 Fail-Open 越权漏洞已按规格闭环：条件注入双重载与重载转发三处全部 fail-closed，失败统一转 `BaseException(12002)` 阻断，系统后台/未登录与非托管表放行语义保持不变。
- 提交三笔（全在 feature 分支，均为内联 ASCII `-m`）：`e0a9c39` feat（切面+单测 2 文件 +268/−13）、`6aaacc6` test(baseline)（台账 4 行）、docs 笔（本 handoff + tasks.md 勾选 + 规格目录 + 任务卡）。
- 门禁：靶向单测 8/8 绿；`pmd:check` + `spotbugs:check` 绿；全量 `clean verify` VERIFY_EXIT=0（12min10s，Docker 29.6.2 实跑）；`merge-gate.sh` GATE_EXIT=0，8/8 子门禁 PASS（unit/spotbugs/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline）。
- 基线：surefire 报告 261→262、用例 1620→1628（+1 套件 +8 用例，超过卡的 1626+ 预期），failsafe 31 报告/118 用例不变（skipped=7）；台账 `source-revision=e0a9c39` 无 `-dirty`。

## 2. 写集 diff stat

```
src/main/java/com/slz/crm/server/aspect/QueryWrapperAspect.java | 修复 5 处（注入 catch ×2、转发 catch、三处无用户日志、抑制注释）
src/test/java/com/slz/crm/unit/server/aspect/QueryWrapperAspectTest.java | 新增 8 用例（+268/−13 与上合并计）
scripts/test-baseline.txt                                          | 4 行（--update 真实写入）
openspec/changes/harden-datascope-fail-closed/{proposal,tasks}.md  | 规格随卡入库，tasks.md 全勾 + 6 空格证据行
work/handoff-harden-datascope-fail-closed.md、work/task-card-harden-datascope-fail-closed.md | 交接与任务卡
```

红线逐项：零 DB 迁移 ✓、零 pom 依赖 ✓、零新增错误码（复用 12002）✓、`frontend/typed-router.d.ts` 已 checkout 恢复 ✓、无 push / 无 merge ✓。

## 3. 关键实现决策（复核关注点）

1. **注入方法整段 try 统一 fail-closed**：规格字面只提 `dataScopeService.addDataScopeCondition` 异常，但既有 catch 本就包裹整个方法体（含 `getCurrentRole`/`extractTableName`），保持原结构、只改 catch 行为——托管表+已登录下任何异常都阻断，语义更严不放宽。
2. **包装异常不挂 cause**：`BaseException` 无 (ErrorCode, String, Throwable) 构造器，按卡面精确构造 `new BaseException(ErrorCode.PERMISSION_DENIED, "数据权限过滤失败，拒绝查询")`；原始异常栈由抛出前的 `LOGGER.error(..., e)` 保留诊断链。
3. **BaseException 原样重抛**：注入路径 `instanceof BaseException` 直接 `throw`；转发路径先解包 `InvocationTargetException.getTargetException()` 再判断（IllegalAccessException 等非 ITE 异常同样走包装分支）。两个方向各有单测以 `assertSame` 锁死。
4. **无用户审计日志**：三处 `LOGGER.warn("当前用户未登录...")` 统一替换为卡面指定的 `LOGGER.debug("系统/后台任务无当前用户，跳过数据权限注入")`。
5. **测试夹具**：用 JDK 动态代理复刻生产 MyBatis mapper（切面表名提取依赖 `$Proxy*` → 接口名链路）；夹具接口与测试类需 `public`，否则跨包反射 `invoke` 抛 `IllegalAccessException`（首轮实败已证，见 tasks.md 3.4）。

## 4. 残留观察（超出本卡规格，请 owner 定夺，未改动）

- `aroundQueryWithoutWrapper` 中"未找到带 Wrapper 重载 → proceed()"（L125-128）与 L132 `dataScopeService` 直调（不在 try 内，异常裸抛而非转 12002）两处仍是原行为；后者已 fail-closed（异常传播即阻断）但不走 12002 文案，前者对无重载的托管表查询仍放行。均属规格未授权范围，A 档复核可一并评估是否开新卡。

## 5. 验证记录（可复跑）

```
mvn -B -ntp test -Dtest=QueryWrapperAspectTest     # Tests run: 8, Failures: 0, Errors: 0, Skipped: 0
mvn -B -ntp pmd:check spotbugs:check               # BUILD SUCCESS
mvn -B -ntp clean verify                           # VERIFY_EXIT=0；日志 work/_harden-datascope-verify.log
"D:/git/Git/bin/bash.exe" scripts/check-test-baseline.sh --update   # source-revision=e0a9c39，无 -dirty
"D:/git/Git/bin/bash.exe" scripts/merge-gate.sh    # GATE_EXIT=0 8/8；日志 work/_harden-datascope-mergegate.log
```

## 6. 下一步（owner 动作）

1. 按复核档位矩阵派发 A 档独立复核 sub agent（复核提示词由主 agent 生成）。
2. 复核回贴 GO 后于权威树执行 `git merge --no-ff`（merge 后需按惯例重跑 clean verify + `--update` 刷新台账至 merge commit）。
3. push 仍需 owner 显式授权。
