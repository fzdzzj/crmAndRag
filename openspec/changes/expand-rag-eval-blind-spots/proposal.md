# 提案 · expand-rag-eval-blind-spots（评测盲区补齐：多轮/时序/拒答断言）

## 为什么（池账 #26 / C-3+C-4+C-5，B 档）
RAG 基准 63 例与 AiEval 例全单轮（RagBenchmarkCase :20-26 无 history、AiEvalCase :7-12 单 utterance），多轮改写/澄清零覆盖（C-3）；类别封闭集 TEXT/TABLE/IMAGE/LEXICAL/EDGE/MISMATCH 无 TEMPORAL，时间表述改写零评测（C-4）；E-02/E-04 期望空集时检索零召回即满分，生成层「是否真拒答/借近邻编造」无断言（C-5，evaluator :141-150/:197-219 判卷只到检索层）。2026-10-20 权威树实读全部吻合（Suite SUITE_VERSION="5.0"、E-01~04 :460-468、IT 独立 CUSTOMER-SUITE-2.0）。

## 做什么
1. **C-3 多轮用例模型**：RagBenchmarkCase 增 history 组件（List<HistoryTurn>，旧用例空表，提供兼容重载或全量补空参）+ 新增多轮用例组（指代消解/上下文追问/澄清触发，4-6 例，占位 id 走既有 fixtures 黄金标记约定）；AiEvalCase 增 history + 多轮工具选择用例组。**SUITE_VERSION 升 6.0**（63→~68，历史版本不可比语义保留，v6.0 真跑落锚 baseline-v7.json 属另卡授权项，沿 v3.0「暂未授权真跑落锚」先例）。
2. **C-4 TEMPORAL 类别**：Category 增 TEMPORAL + 3-4 例（问句含时间限定词「本月/最近/上季度」，黄金锚 fixtures 静态语料中含时效语义的块；纯静态可 fake 验证，不依赖真库时效数据）。
3. **C-5 拒答断言**：CaseOutcome 增 refused 信号（适配层填报：expected 空的用例答案是否诚实拒答）；evaluator D16 判分收紧——expected 空时 answerConsistency = refused ? 1 : 0（recall/precision/MRR/citP 公式零改动）；E-02/E-04 由新信号覆盖「借近邻编造」幻觉面。
4. **测试**：全部以确定性 fake 验证（evaluator 解耦设计既有先例）——多轮 history 透传、TEMPORAL 判卷、拒答三态（诚实拒答满分/借近邻编造 0 分/正常召回不受影响）；既有 evaluator/suite 单测零弱化。

## 红线
零真实模型外呼（本卡全部 fake 单测；真跑落锚/IT 真嵌入属另卡授权）/ 零数据库迁移 / 零新依赖 / 零新增错误码 / 不改冻结契约 / 不动前端 / 基线只增不减 / **既有 63 例与既有指标公式（recall/precision/MRR/citP/ansC 正常用例）零改动** / SUITE_VERSION 语义不破坏（升版留痕）。

## 档位
**B 档**（quality 模块纯测试/评测资产扩展，无生产业务代码语义变更——evaluator 判分收紧属评测口径，生产链路零触碰）：门禁退出码 0 + 主 Agent 审 diff 裁决。

## 验收口径
fake 单测四象限全绿；定向 + 全量 verify + 单次基线 --update + merge-gate 10/10；handoff 含用例组清单（编号/类别/history 形态/黄金锚）、SUITE_VERSION 6.0 变更说明、C-5 判分口径前后对照表。停步：未 merge 未 push。
