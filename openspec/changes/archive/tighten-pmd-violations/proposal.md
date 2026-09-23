# 提案：PMD 存量违规分片收紧（tighten-pmd-violations）

> 变更 ID：`tighten-pmd-violations` ｜ 能力域：`static-analysis` ｜ 序列：`wire-pmd-ruleset` 的后续提案（该提案 Q2 已预告"收紧留给后续按规则分片的独立提案"）。
> 授权：owner 已授权开始（2026-09-21）。**这是首个会动 `src/main/java` 的门禁工作**。

## Why

1. **1329 条存量让门禁只拦"新增第 4 种异味"。** 86.4% 的条数压在 3 条规则上（实测 2026-09-21，报告 `target/pmd.xml`，25 条规则口径）：
   - `OnlyOneReturn` **732**
   - `CommentSize` **282**（细分实测：**278 条 "Line too long"** + **4 条 "Too many lines"**）
   - `AvoidCatchingGenericException` **134**（全部为 catch 通用异常）
   其余 12 条规则合计 181 条。不收紧存量，`maxAllowedViolations` 的 ratchet 永远从 1329 起步。
2. **CommentSize 的 278 条"行超长"是尺子默认值荒谬，不是代码问题。** PMD 7.9.0 `CommentSize` 的 `maxLineLength` 默认值 = **6**（`pmd-rules.xml` 只配了 `maxLines=20`，行宽从未配置）——任何中文注释行几乎必然超 6 字符。仓里其余尺子均无此口径：checkstyle `LineLength=800` 管 Java 源码行不管注释语义宽度；spotless/googleJavaFormat 管代码格式不管注释行宽。**校准该属性是修尺子，不是放水**（拍板 Q2 禁的是"为压条数调松规则"，本项是把荒谬默认值对齐工程常值）。
3. **3 条规则的修复风险完全不同，必须分片、不许一把梭：**
   - CommentSize：动注释不动代码，零行为风险（4 处块超长需精简保真）。
   - OnlyOneReturn：合并多 return 改控制流，模式化强但触面广（732 处），按模块分批，每批测试全绿。
   - AvoidCatchingGenericException：**收窄 catch 面会改变行为**（原本兜住的异常会穿出），必须逐例分析抛出源；SSE 流/异步任务/顶层兜底的 `catch (Exception)` 多为有意设计，**禁止机械替换**。

## What Changes

按风险从低到高、独立可合入的三分片：

- **分片 A（CommentSize 282 → 目标 ≈0）**：
  1. `pmd-rules.xml` 的 `CommentSize` 增配 `<property name="maxLineLength" value="120"/>`（默认 6 → 120；理由见 Why-2，120 为中文注释工程常值）。**属规则属性校准，越出"只改代码"范围，在提案里显式登记。**
  2. 修 4 处超 20 行注释块（实测定位）：`common/annotation/RequirePermission.java:9-40`（32 行）/ `knowledge` 侧 `ContextBuilder.java:15-37`（23 行）/ `pojo` 侧 `AssistantChatRequest.java:5-30`（26 行）/ `platform/contract/UserContext.java:5-28`（24 行）——精简保真到 ≤20 行；`platform/contract` 只动注释不动签名，不构成契约变更。
- **分片 B（OnlyOneReturn 732，按模块分批）**：`common` → `pojo` → `quality` → `knowledge` → `platform` → `server` 逐批合并多 return 为单一出口；不改行为、不改签名；每批 `mvn -B -ntp test` 全绿 + merge-gate 后独立提交。
- **分片 C（AvoidCatchingGenericException 134，逐例）**：先盘点 134 处的 try 块内容与抛出源，分三类处置：①能安全收窄的收窄到具体异常；②确属有意兜底的（SSE 生命周期/异步任务/定时任务/全局兜底），**停下向 owner 拍板处置方式**（候选：`@SuppressWarnings("PMD.AvoidCatchingGenericException")` 内联豁免 + 中文注释理由 / 规则集排除该规则改由 SpotBugs 侧把关 / 保留不修）；③拿不准的归入 ② 一并上会。**本分片动前必须有 owner 对处置方式的拍板**。

每分片收尾（缺一不可）：`mvn -B -ntp pmd:check` 实测 → `bash scripts/tests/pmd-baseline-check.sh --update` 下调台账 → pom `<maxAllowedViolations>` 照抄同值 → `bash scripts/merge-gate.sh` 全绿（surefire 计数必须不变，本提案不增减测试）→ 限路径直落 master 提交。

## Impact

- **修改文件**：`src/main/resources/pmd-rules.xml`（仅分片 A 校准 1 属性）、`src/main/java/**`（分片 A 4 文件注释 / 分片 B 多文件控制流 / 分片 C 视拍板）、`scripts/tests/pmd-violation-baseline.txt` + `pom.xml`（每分片随 --update 同步下调）。
- **不改**：测试代码（surefire/failsafe 计数锁死）、冻结契约签名（`platform/contract` 只动注释）、迁移链、SpotBugs/checkstyle/spotless 配置。
- **已知风险**：分片 B 触面广（732 处），单批控制在可 review 规模；分片 C 有行为风险，前置拍板卡死。

## 拍板记录

- **Q5（执行期假设，owner 已授权开始，如不同意可回退）**：`maxLineLength` 校准值取 **120**（分片 A 直接执行；它同时决定 278 条的消除量，若 owner 想要更严的行宽，只改 1 个属性值重跑即可，成本一次 --update）。
- **Q6（待拍板，分片 C 前置）**：134 处 catch 通用异常的处置方式（见 What Changes 分片 C 三候选）。

## 风险

- 分片 B 合并 return 引入控制流缺陷 → 每批全量 surefire + merge-gate，行为等价重构不改语义。
- 台账连续下调产生多次中间态 → 每分片独立提交、独立 --update，不做跨分片的数值跳跃。
- 分片 C 拿"有意兜底"当挡箭牌放水 → 处置表逐条登记（位置/抛出源/理由），先盘后改。

## Non-Goals

- 不动其余 12 条规则的 181 条存量（CyclomaticComplexity 76 / FieldNamingConventions 39 等，另案）。
- 不改 failurePriority、不换规则集选型、不引入新工具。
- 不修 `src/test/**`（PMD 默认不扫测试目录）。

## 验收

1. 分片 A 后：`pmd:check` 实测条数 = 1329 − 278（行宽校准消除）− 4（块精简）± 校准后仍超 120 列的残留行数；台账与 pom 同步该值；4 文件注释语义保真（关键机制说明逐条保留）。
2. 分片 B 每批：surefire 724/0/0/0 不变；merge-gate 全绿。
3. 分片 C：处置表 134 条逐条有归属（收窄/豁免/待拍板），豁免带中文理由注释。
4. 全程台账只下调；最终基线显著低于 1329（目标：分片 A+B 后 < 220，即只剩分片 C 与其余规则）。
5. `git log` 按分片/任务组整齐；每分片提交信息含实测条数（前值→后值）。
