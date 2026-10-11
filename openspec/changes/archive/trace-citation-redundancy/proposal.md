# 提案 — trace-citation-redundancy（引用冗余取证，¥0 代码 + 单轮子集真跑）

## 为什么

baseline-v3（63 例现行锚）citationPrecision 均值 0.8272，实测 **20 例 citP<1**：TEXT 10 例（T-01/02/03/04/05/10/11/13/14/15，其中 T-15=0、T-14 为 recall 0.5+citP 0.2+ansC 0.667 三低）、TABLE 5 例（TB-01/08/09/10/11）、IMAGE 1 例（I-03）、MISMATCH 4 例（M-01..04）。满分例 43，20 例均值约 0.456。

引用溯源是生产价值（用户点引用看到的块必须真支撑答案），是质量线收尾前最后一块真实短板。但**修复方向未知**：可能是生成侧引用纪律缺陷（模型误引检索噪声块）、可能是黄金标注口径特性（模型引用了合法支撑但未标黄金的块）、可能是 recall 连带（T-14 型黄金未全召回）。**先取证后定向，避免教考式修复**（改生成侧迎合判卷口径）。

## 做什么

复用 `trace-i05-citation-forensics` 先例机制（trace 旁路 JSON + `-Drag.benchmark.only` 白名单，`BenchmarkOnlyFilterTest` 断言在案）：

1. 20 例子集单轮真跑（`RAG_BENCHMARK_REAL=1`，约 92s / 20k tokens 量级，owner 授权后执行），dump 每例引用块明细。
2. 逐例判读四元组：引用块集 / 其中黄金数 / 非黄金块 excerpt 支撑度 / 答案要点覆盖。
3. 三分类统计 + 定向建议，产出 `docs/rag-quality/citation-forensics-v4.md`：
   - **分支 A（噪声误引）**：非黄金块不支撑答案 → 后续卡做生成侧引用精化（Self-RAG 反思链扩展方向的候选面）
   - **分支 B（口径特性）**：非黄金块合法支撑 → 黄金标注口径问题，记录不改或另行受控校准
   - **分支 C（recall 连带）**：T-14 型，黄金未全召回导致引用次优 → 归并 T-14 拆路残留问题

## 不做什么

- 零代码改动（trace 与 only 机制既有）；不动 SUITE_VERSION（不改任何 case）；不动既有锚点与取证件（baseline-v1/2/3、after-\*、i05-\* 逐字保留）；不改对齐器/评分公式。
- 取证产物独立命名 `citation-forensics-v4.*`，不覆盖任何既有文件。

## 验收

- 20 例逐例明细表落盘（含关键 excerpt 原文粘贴），三分类各例归因明确，定向建议有数据支撑。
- 门禁全绿（零代码改动，surefire 1089 不变）；不合并不 push（owner 授权后另行执行）。
