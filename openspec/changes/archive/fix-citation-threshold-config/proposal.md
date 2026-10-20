# 提案 · fix-citation-threshold-config（引用对齐阈值动态化 + 评测判分同源回灌）

## 为什么（池账 #27 / C-2，B 档）
`CitationAligner:21-27` 三阈值 KEEP_MIN=0.12 / REMAP_MIN=0.22 / TIE_MARGIN=0.08 为编译期常量，不可运维调整；且生产对齐器误杀黄金引用时评测 citP 仍可满分（评测侧 citations 与生产对齐路径不同源，`RagQualityEvaluator.citationPrecision` 只按 chunk id 命中判分）——对齐器回退评测拦不住。2026-10-20 权威树实读：Aligner/CitationSupport 均「零外呼、无 Spring 依赖」静态工具；`DynamicConfigKeyRegistry` 已有 `rag.retrieval.*` 段 9 键成熟范式（topK/minScore/chunkSize 等）。

## 做什么
1. **三阈值动态化（默认=现常量，行为零漂移）**：新增 `CitationThresholds` record（keepMin/remapMin/tieMargin，含范围校验）；`CitationAligner.align` 增三参重载（注入 thresholds），既有两参形态委托默认常量——核心类保持零 Spring；生产调用点（`AiChatStreamFinalizer` 等实读确认）从动态配置解析注入；`DynamicConfigKeyRegistry` 增 `rag.retrieval.citation.keepMin / remapMin / tieMargin` 三键（ConfigKeyDefinition 校验 + tier 定级按 `ConfigKeyTierPolicy` 封闭表 + 默认值=现常量）。
2. **评测同源回灌**：真跑 IT 适配层（`RagRealRetrievalBenchmarkIT`）citations 上报前先经 `CitationAligner.align`（生产同款路径）——对齐器误杀黄金引用时 citP 如实下降，C-2 缺口闭环。
3. **测试**：thresholds 注入单测（KEEP/REMAP/DROP 三态在不同阈值下的决策边界）；默认两参形态零漂移回归（既有 Aligner 测试全绿）；registry 三键校验测试（范围/默认值）；回灌证据单测——fake 构造「黄金引用被对齐器 DROP」场景，断言经对齐后 citP 下降（缺口闭环的确定性证明）。

## 红线
零数据库迁移 / 零新依赖 / 零新增错误码 / 不改冻结契约 / 不动前端 / 基线只增不减 / **默认值=现常量（未改配置时行为逐字节等价）** / Aligner/CitationSupport 核心类保持零 Spring 依赖（动态解析在调用侧）/ 严禁 git push、merge、reset/rebase/amend、删分支。

## 档位
**B 档**（常规业务代码+测试，无 DB/权限/契约变更）：门禁退出码 0 + 主 Agent 审 diff 裁决。

## 验收口径
定向 + 全量 verify + 单次基线 --update + merge-gate 10/10；handoff 含阈值注入路径图（配置键→解析点→Aligner 重载）、三键 registry 登记对照、回灌单测断言清单、默认零漂移论证。停步：未 merge 未 push。
