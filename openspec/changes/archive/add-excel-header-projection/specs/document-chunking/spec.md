# 规范增量：document-chunking

## ADDED Requirements

### Requirement: Excel 多列表头投影到数据行
系统 SHALL 在解析 Excel/xls 时，若首行像表头，则把列名投影进每一条数据行文本，且 MUST NOT 再把表头行当作独立切片入库。投影 MUST NOT 改变 `rowIndex` 的原始行号语义，MUST NOT 影响 PDF / Markdown / 纯文本解析。

启发式 MUST 同时满足：首行非空单元格数 ≥ 2，且每个非空单元格长度 ≤ 32。不满足时 MUST 保持升级前的「单元格值 tab 拼接、首行也入库」行为。

#### Scenario: 多列表头投影
- **GIVEN** 工作表第 1 行为短列名（含「计划工时」），第 2 行起为数据
- **WHEN** 系统解析该 Excel
- **THEN** 数据行文本包含对应列名与单元格值（如含「计划工时」与该行工时数字）
- **AND** 不存在仅由表头单元格组成的独立切片
- **AND** 数据行 `rowIndex` 等于工作表原始行号

#### Scenario: 单列或长句首行不投影
- **GIVEN** 工作表只有一列，或首行某单元格长度 > 32
- **WHEN** 系统解析该 Excel
- **THEN** 行文本仍为单元格原值拼接
- **AND** 首行仍作为切片入库
- **AND** `rowIndex` 仍从 1 起按行对应

#### Scenario: 空值与空列名
- **GIVEN** 某数据行部分单元格为空，或表头某列名为空
- **WHEN** 投影启用
- **THEN** 空值列被省略
- **AND** 空列名回退为 `列{序号}`
- **AND** 重复列名以 `_2` 等后缀区分

#### Scenario: 黄金标记仍对齐
- **GIVEN** 评测语料 Excel 数据行含 `【GOLD:…】` 标记
- **WHEN** 投影后进入 `RagBenchmarkDataPreparer`
- **THEN** 占位 id 仍能对齐到该数据行切片
- **AND** 索引文本不含 GOLD 标记
- **AND** 维保/销售黄金行索引文本分别含「计划工时」「销售额」

### Requirement: 评测复测不覆盖历史锚点
Excel 表头投影的真检索复测 MUST 写入新路径，MUST NOT 覆盖 `baseline-v1.json` / `baseline-v2.json` / `baseline-after-citation.json`。`SUITE_VERSION` MUST 保持 2.0。未授权 MUST NOT 设置 `RAG_BENCHMARK_REAL=1`。

#### Scenario: 未授权不外呼
- **WHEN** 用户未授权任务组 4
- **THEN** 无真基准外呼
- **AND** 合入可以不含 after-excel-header JSON，HANDOFF 标明待补跑

#### Scenario: 授权后对照 v2
- **WHEN** 以纯默认矩阵跑完 54 条并落 `docs/rag-quality/baseline-after-excel-header.json`
- **THEN** 报告 suiteVersion=2.0
- **AND** TB-01 与 TB-10 的 recall@5 相对 v2 提升（目标 > 0）
- **AND** 其余 TABLE 用例 recall 不回退
- **AND** 若 TB-01/TB-10 仍为 0 或其余 TABLE 回退，实现方 MUST 停下汇报，不得放宽启发式或改黄金块
