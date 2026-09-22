# 提案：PMD 剩余存量第二轮收紧（tighten-pmd-residual-325）

> 变更 ID：`tighten-pmd-residual-325` ｜ 能力域：`static-analysis` ｜ 序列：`tighten-pmd-violations` 的后续提案（该提案台账注释已预告本名：'第二轮 tighten-pmd-residual-325 处置复杂度类/命名口径/删参等剩余存量'）。
> 授权：owner 授权分片 D/E 直接执行（低风险机械）；**分片 F 动控制流，动前必须 owner 拍板**（沿用分片 C 的 Q6 模式）。

## Why

1. **1329 → 325 后，门禁从 325 起步 ratchet，仍有压价空间。** 第一轮三分片处置了三巨头
   （CommentSize 归零 / AvoidCatchingGenericException 134 全会处置 / OnlyOneReturn 591 处合并、141 处残留跳过）。
   剩余 325 条实测分布（`mvn -B -ntp pmd:check` 产 `target/pmd.xml`，2026-09-22 实测，files=69）：

   | 规则 | 条数 | 性质 |
   |---|---|---|
   | OnlyOneReturn | 141 | 第一轮显式跳过项（try/catch 内无法等价合并等） |
   | CyclomaticComplexity | 75 | 复杂度，需拆方法 |
   | FieldNamingConventions | 39 | 命名口径，改名触面广 |
   | NcssCount | 29 | 复杂度，需拆方法/类 |
   | CognitiveComplexity | 22 | 复杂度，需拆方法 |
   | UnusedFormalParameter | 9 | 死参，删参或豁免 |
   | UnusedLocalVariable | 5 | 死变量，删除 |
   | EmptyCatchBlock | 2 | 空 catch，逐例 |
   | InsufficientStringBufferDeclaration | 2 | 初始容量，机械修 |
   | TooManyMethods | 1 | 类过大，拆类或豁免 |

2. **三类剩余的风险完全不同，必须分片、不许一把梭：**
   - 死参/死变量/小异味（19 条）：删除即消，但**覆写方法/接口实现的参数不可删**（签名契约）——能删的删，不能删的 `@SuppressWarnings` + 中文理由。
   - 命名口径（39 条）：机械但**可能撞 API 契约**——`pojo`（entity/dto/vo）字段名即 Jackson 序列化名与前端契约，改名即契约变更；先盘点落点，pojo 侧只豁免不改名。
   - 复杂度类 + OnlyOneReturn 残留（267 条）：动控制流/拆方法，逐例分析，**拍板前置**。

## What Changes

按风险从低到高三分片：

- **分片 D（死参/死变量/小异味 19 条 → 目标 ≈0）**：
  1. UnusedLocalVariable 5 / InsufficientStringBufferDeclaration 2：直接修（删变量、补初始容量）；
  2. UnusedFormalParameter 9：逐例判断——非覆写直接删参；覆写/接口实现/回调签名保留参数并 `@SuppressWarnings("PMD.UnusedFormalParameter")` + 中文注释理由；
  3. EmptyCatchBlock 2：逐例——确属有意吞异常的补 `@SuppressWarnings("PMD.EmptyCatchBlock")` + 中文理由（或最小日志），非有意的补处理；
  4. TooManyMethods 1：默认豁免登记（拆类触面大、收益低），如 owner 不同意再议。
- **分片 E（FieldNamingConventions 39 条）**：先盘点 39 处落点分布（模块 × 违例属性），产出处置表；
  `pojo/**` 与其他序列化/反射依赖命名处只豁免不改名，非契约侧命名按 PMD 口径改名（全仓引用同步，编译器兜底）。
- **分片 F（复杂度类 126 + OnlyOneReturn 残留 141，拍板前置）**：盘点产出逐例处置表（位置/复杂度成因/候选处置：
  拆方法 / 拆 helper 类 / `@SuppressWarnings` 豁免 / 保留不修），**停下向 owner 上会拍板**后才动代码。
  **拍板已完成（见下"拍板记录 Q7"）**，按批执行：F-1 方法级拆方法 → F-2 低风险类级拆 helper →
  F-3 高风险类（SSE/数据权限/附件越权，配套反向用例）→ F-4 AssistRequestServiceImpl 巨类单批 → F-5 收尾归档。

## 拍板记录

- **Q7（2026-09-22 owner 拍板："全部拆完"）**：分片 F 全量处置，不留"保留不修"。落地口径：
  1. 方法级复杂度（圈 56 / 认知 22 / NCSS 2）行为等价拆方法，OnlyOneReturn 残留的 66 个方法随所在方法的拆分**顺带单出口化**；经拆分后仍无法等价合并的守卫式早返回，允许 `@SuppressWarnings("PMD.OnlyOneReturn")` + 中文理由豁免——**禁为压数做伤害可读性的机械合并**（Q2 遗训同源）。
  2. 类级超标 46 条拆 helper/协作类，不改公共 API 与冻结契约（`platform/contract` 命中处只豁免）。
  3. 高风险面（SSE 生命周期 `AiChatStreamLifecycle`/`AiChatSseEventWriter`、数据权限 `DataScopeServiceImpl`（超集不变量必须保持）、附件越权 `AttachmentAccessServiceImpl`）单独批次并**配套反向用例**——本分片允许 surefire 只增（基线 `--update` 只增不减），这是本提案对"计数锁死"的唯一例外。
  4. `AssistRequestServiceImpl` 巨类单批拆分，拆完**同步移除分片 D 任务 4.5 的 `TooManyMethods` 豁免**（口径自洽，两条规则不许互相打脸）。
  5. 分批推进、每批独立收尾（pmd:check 实测 → --update → pom 照抄 → merge-gate 全绿 → 提交）；批间以新鲜 pmd 实测重排剩余项，不照抄旧数字。

每分片收尾（缺一不可，沿用第一轮口径）：`mvn -B -ntp pmd:check` 实测 → `bash scripts/tests/pmd-baseline-check.sh --update`
下调台账 → pom `<maxAllowedViolations>` 照抄同值 → `bash scripts/merge-gate.sh` 全绿（surefire 计数不变）→ 限路径直落 master 提交。

## Impact

- **修改文件**：`src/main/java/**`（删死代码 / 少量命名 / F 视拍板）、`scripts/tests/pmd-violation-baseline.txt` + `pom.xml`（每分片随 --update 同步下调）、本提案 tasks.md。
- **不改**：测试代码（surefire/failsafe 计数锁死，本提案不增减测试）、冻结契约签名（`platform/contract` 若有命中只豁免）、`pojo` 序列化字段名、迁移链、SpotBugs/checkstyle/spotless 配置、`pmd-rules.xml`（本轮不校准尺子）。
- **已知风险**：分片 E 改名漏改引用 → 编译期兜底 + 全量 surefire；分片 F 改控制流引入行为缺陷 → 拍板前置 + 每批全绿才提交。

## 风险

- 删"看似死"的参数/变量撞反射或框架回调 → 只删编译器确认无引用者，覆写签名一律豁免登记。
- 命名改错碰 Jackson/MyBatis 映射 → pojo 侧一律豁免；非 pojo 改名后跑全上下文冒烟（ApplicationContextSmokeTest 在 surefire 内自动覆盖）。
- 台账连续下调产生多次中间态 → 每分片独立提交、独立 --update。

## Non-Goals

- 不校准 `pmd-rules.xml` 属性、不换规则集选型、不调 failurePriority。
- 不修 `src/test/**`（PMD 默认不扫测试目录）。
- 不为压条数调松规则（Q2 遗训）；豁免必须逐条带中文理由。

## 验收

1. 分片 D 后：19 条小异味归零或逐条豁免登记；台账与 pom 同步新值；surefire 计数不变。
2. 分片 E 后：39 条命名口径逐条有归属（改名/豁免+理由）；pojo 零改名。
3. 分片 F：处置表 267 条逐条有归属建议，owner 拍板记录留痕后才执行；执行部分每批 merge-gate 全绿。
4. 全程台账只下调；最终基线显著低于 325。
5. `git log` 按分片/任务组整齐；每分片提交信息含实测条数（前值→后值）。
