# 任务卡 · near-dup-ingest-cooccurrence（A 档 · 真跑 + 外呼，阶梯授权）

卡名：`near-dup-ingest-cooccurrence`　分支：`feature/near-dup-ingest-cooccurrence`（从权威树 `master` 检出，保留不删）
交付判据：`openspec/changes/near-dup-ingest-cooccurrence/{proposal.md,tasks.md}`（**冲突时以 tasks.md 为准**）

## 1. 一句话目标

把带内近重复图**真的入库**，与文本竞争者在同一 top-k 竞争；用**权重消融**（生产 `0.75/0.6/0.5` vs 全 `1.0`）测出「降权是否真的造成跨路位移」，把口径四的**反事实 68.5%** 变成实测。

## 2. 授权（Owner 给，缺一项就地停）

- 已授权外呼：**S0 探针 18 次**（6 图 × 3 调用），通过后**S1 144 次**（48 图 × 3 调用），**累计 ≤162 次**。
- 已授权在权威树从 `master` 检出 `feature/near-dup-ingest-cooccurrence`。
- **未授权**：`git push`、改写历史、删分支、改数据库结构、新增依赖、**改 `src/main` 任何文件**、重跑 S2（144 图全量）、跑其它真外呼 IT。

## 3. 写集（只有你写这些，声明无其他写入者）

```
openspec/changes/near-dup-ingest-cooccurrence/**                     仅勾选 + 6 空格补勾依据
work/task-card-near-dup-ingest-cooccurrence.md                       仅补勾记录
src/test/java/com/slz/crm/quality/NearDupIngestPdfBuilder.java                新建
src/test/java/com/slz/crm/quality/NearDupIngestGateProbeIT.java               新建
src/test/java/com/slz/crm/quality/NearDupIngestCooccurrenceIT.java            新建
src/test/java/com/slz/crm/quality/NearDupIngestGateProbeGuardTest.java        新建
src/test/java/com/slz/crm/quality/NearDupIngestCooccurrenceGuardTest.java     新建
docs/rag-quality/near-dup-ingest-gate-probe-v1.json / .md                     新建（LF）
docs/rag-quality/near-dup-ingest-cooccurrence-v1.json / .md                   新建（LF）
scripts/test-baseline.txt                                                     仅经 --update
```

**写集外一个字节都不碰。** 特别地：`NearDuplicateCorpus`、`PdfImageSliceIngestService`、`NearDupDisplacementEvidenceIT`、六份既有锚、`near-dup-floor-signoff-v1` 全部只读。

## 4. 硬边界（违反任一条 = 整卡作废）

1. **`src/main` 零 diff**。尤其**不许**把 `stageImageVector` 从 `description != null` 分支里挪出来——闸门拒图是要被**测量**的现象（proposal §3.2），要解耦属另立卡。
2. 外呼 **≤162 次**；`externalCalls` 必须是实测值。
3. 不改语料生成逻辑、不改既有 IT、不改任何既有锚。
4. 判据 R0–R7 冻结：不调阈值、不换样本、不放宽容差、不删断言。
5. 结论只准三选一，**禁造第四种**；`INSUFFICIENT-COVERAGE` 是可交付结论，不许为了好看硬判 `DISPLACED`。

## 5. 停手上报条件

- **S0 `gatePassRate < 4/6`** ⇒ 立刻停，附逐图失败原因 + 「解耦图像向量与描述」的另立卡建议。
- R0 合成 PDF 抠图不对（图数/尺寸不符）⇒ 停（换方案需 Owner 决定）。
- 带内 7 对参与者凑不齐 48 图 ⇒ 停（不许换语料）。
- `externalCalls ≠ 3 × 图数` ⇒ 停（说明有隐藏调用或计量漏记）。
- R5 不成立（文本路分两次不等）⇒ 停（消融不纯，结论无效）。

## 6. 真跑命令

```bash
RAG_NEAR_DUP_INGEST_REAL=1 mvn -B -ntp -Dit.test=NearDupIngestGateProbeIT verify          # S0
RAG_NEAR_DUP_INGEST_REAL=1 mvn -B -ntp -Dit.test=NearDupIngestGateProbeIT -Dnear.dup.ingest.gate.write=true verify
RAG_NEAR_DUP_INGEST_REAL=1 mvn -B -ntp -Dit.test=NearDupIngestCooccurrenceIT verify       # S1
RAG_NEAR_DUP_INGEST_REAL=1 mvn -B -ntp -Dit.test=NearDupIngestCooccurrenceIT -Dnear.dup.ingest.cooccurrence.write=true verify
```

- 锚写完**必须**用默认模式复跑一次并逐字节一致。
- ⚠️ `-Dit.test=` 会覆盖 pom 的 failsafe `<includes>`。
- ⚠️ 定向 IT 跑过之后 `target/` 已污染，`--update` 重锚前必须**重跑全量** `mvn -B -ntp clean verify`。

## 7. 已知陷阱（本仓已踩过）

1. **行尾即判据**：`.gitattributes` 对 `*.json` 强制 LF；Jackson 默认缩进器用 `System.lineSeparator()`（Windows 写 CRLF）⇒ 新锚必须锁 LF，写完用字节检查确认无 `0d 0a`（换行一改 SHA 全变）。
2. **`target/` 污染**：`--update` 前必须全量 `clean verify`。
3. **BOM**：PowerShell `Set-Content -Encoding utf8` 写 BOM；提交消息一律 `git commit -m`。
4. **shell 里别写中文**：Git bash 命令含中文可能 exit 127，输出与注释走文件。
5. **embedding token 恒 0 是端点限制**，不是零成本：写 `null` + 声明，**禁按字符数估算**。
6. **闸门会把退化变体整张丢掉**（`:248`），这是预期现象；不许为了让图入库去放宽 `MIN_COMPACT_CHARS` 或 `UNCLEAR_HAN_RATIO_LIMIT`。

## 8. 修复上限

**1 次。** 首轮红 → 修一次；仍红就如实上报停手，禁止「验证到通过」的循环。

## 9. 回报（≤300 字，硬上限）

六项：① 新增文件路径与行数 ② 五项门禁结果（含 failsafe 统计行）③ `gatePassRate` / `externalCalls`（S0 与 S1 各一组）/ `verdict` / 带内覆盖数 / R5 是否成立 ④ 停步项与原因 ⑤ 未解决项 ⑥ 待 Owner 决策项。
细节一律留文件，**不进回报**。

## 10. 执行状态（2026-10-24 执行子 agent 补记）

- **S0 完成**：`gatePassRate=6/6`、`externalCalls=18`（6+6+6）、`gateVerdict=PROCEED-S1`；锚 `near-dup-ingest-gate-probe-v1.json`（LF）+ `.md`（48 行）落盘；写锚 `_nds0-write7.log`、复跑逐字节一致零外呼 `_nds0-replay2.log`、守卫 3/3 绿 + 自证改坏→红→还原→绿（`_nds0-guard-red.log`/`_nds0-guard-green.log`）。
- **S1 代码与零外呼验证完成**：`NearDupIngestCooccurrenceIT` + 守卫就绪；消融 B = 同一生产链 + `image-similarity-threshold=0.982`（NEAR_DUP 档空 ⇒ 乘数全 1.0，准入面逐位不变）；检索段数学（R3/R5 闭式、tier/band/weight 重建、重复描述缓存路径）已由临时合成数据测试在生产链上证实（绿后已删，日志 `work/_scratch4.log`）。
- **S1 真跑中止**：首轮 298s/124 次外呼（vision 47 + embedImage 41 + embed 36）后红于我方对账断言——同 base 变体 VLM 描述逐字相同 ⇒ 缓存命中零边际 embed，断言口径应改「去重描述文本数」（已修 + 守卫同步）。锚未落，数据在 JVM 内丢失。
- **实测新发现**：① 带内对 `doe-p1-b2@scale-070` 短边 <64px，真实提取期即丢（S1 三态 `EXTRACTION-DROPPED`，预注册 INSUFFICIENT-COVERAGE 语义）；② S1 期望三态分布 ≈ 47 提取 / 41 过闸 / 6 闸拒。
- **外呼台账（全部 API 请求口径）**：S0 终版 18 + S0 废跑 ~11（write4 视觉模型 model_not_found ≤6、write5 三图 9 次成功后于第 4 图提取断言中止）+ S1 中止 124 ≈ **153 ≤ 162**；**S1 重跑需 ~120 次，必然超 162 硬帽** ⇒ 按 §5 停手上报，待 Owner 决策（追加授权 or 以 S0 交付收卡）。
- 未授权动作零发生：无 push、无 src/main diff、无既有锚/IT/语料改动。
