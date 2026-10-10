# 提案：add-self-rag-reflection —— 生成侧 Self-RAG 反思（方案 12）

> 立项依据：owner 2026-10-10 拍板「做 12 Self-RAG」，推翻 `openspec/project.md` 处置表原「不做（复杂度；等基线归因后再议）」定夺（logbook §73.2 记档）。
> 铁律：先有度量再优化——本提案作用面为**生成侧支持度指标**，验收锚点见 §4。

## 1. 背景与归因（2026-10-10 取证）

- 54 例套件基线（`docs/rag-quality/baseline-v2.json`）：meanCitationPrecision = **0.7843**，meanAnswerConsistency = **0.9475**。低分 case 归因两类：
  - **引用支持度缺口（本提案作用面）**：T-01(0.667) / T-03(0.333) / T-15(0.333) / T-14(0.25) 等——检索已命中黄金片段，但答案引用了非黄金片段、或引用未被检索内容支持；
  - **检索零命中（非本提案作用面）**：TB-01 / TB-10 双 0——属检索缺口与 D16 诚实语义范畴，Self-RAG 不救。
- 18 例真检索基准（`docs/rag-quality/ladder-report.md`，after-chunking 锚点）：citationPrecision = **0.8889**（仍有 11% 引用不指向黄金）、answerConsistency = 1.0、hitRate = 1.0——fixtures 量级检索侧已饱和。与「17 CRAG 不做：诚实兜底 D16 已覆盖」的既有定夺互补印证：**缺口不在召回，在生成侧引用忠实度**。
- 结论：Self-RAG 的反思机制作用面 = citationPrecision / answerConsistency，不动检索链路、不做 web 兜底。

## 2. 与 D16 诚实生成的分工边界（实证）

- **D16 现状（事前诚实）**：`AiChatKnowledgeRetrievalService#retrieve` KB ON 零命中 → `RetrievalOutcome.miss()` 注入 MISS_CONTEXT 软提示（诚实回答、不伪造来源）；`rag.retrieval.strictKb` 键注册为「硬兜底」但**无生产消费点**（仅 registry / 契约 Javadoc 提及，src/main 无消费者——本提案不动此现状）。
- **Self-RAG（事后反思）**：检索**有命中**时，对生成答案做支持度自评——每条引用是否真被检索内容支持、答案断言是否有据；不支持 → 过滤引用 / 剥除无据断言 / 标注。
- 分工定夺：D16 管「没有证据别编造」（零命中、事前），Self-RAG 管「有证据但没用对」（生成后、事中）。互补不重叠；硬兜底/拒答语义仍归 D16。

## 3. 方案与落点（范围按 owner 拍板回填 §6）

**挂点（生成链实证）**：`AiChatServiceImpl` L236-246 检索上下文以 SystemMessage 注入；SSE 契约（contracts-frozen §4）`sources` 事件在答案前先发（**不可撤回**）、`references` 事件在 delta 之后（**citations 可修正**）。反思层落点 = 流式收尾的 references 组装前；**不加新 SSE 事件、不改 sources 事件**，即不触碰冻结面。

**分层设计（三档，按拍板取舍）**：

| 档 | 机制 | 成本 | 默认 |
|---|---|---|---|
| R 规则反思 | 确定性校验：引用编号越界 / 指向空块 / 引用与命中源不匹配的剥除与修正 | 零 LLM 成本 | 开 |
| L LLM 支持度自评 | 生成后单次 ModelProvider 调用（+ModelCallOptions+TokenUsageRecorder 计量），逐条引用判定「被支持/不被支持」+ 要点覆盖缺口 → 过滤 references.citations、剥除或标注无据断言 | 每答一次 LLM 调用（费用红线族） | 关，COST 档，走成本键申请-审批流 |
| F 完整反思-重检索环 | 自评不足 → 重检索 → 重生成 | 成本 ×3 | **不推荐**：流式已发内容不可撤回、SSE 契约张力 |

**新动态键**（新命名空间 `rag.generation`，需扩 `DynamicConfigKeyRegistry.NAMESPACES` 白名单；L 档键全量登记 `ConfigKeyTierPolicy` COST 档）：

- `rag.generation.selfrag.mode`：`off` \| `rule` \| `llm`（默认值按拍板；非法值回落默认）
- `rag.generation.selfrag.llm.timeout-ms`：LLM 自评等待超时，超时/失败/空输出一律回退规则链（默认 3000）
- `rag.generation.selfrag.llm.max-claims`：单次自评断言上限，防 prompt 膨胀（默认 20）

## 4. 验收（口径按 owner 拍板回填 §6）

- **单测（无外呼）**：反思层确定性单测（fake ModelProvider）全绿；`DASHSCOPE_API_KEY` 置空串跑测；surefire 基线只增不减（`scripts/check-test-baseline.sh` 裁决）；四静态 0 违规；三守卫 CLEAN。
- **计量**：L 档反思 LLM 调用 token 挂 TokenUsageRecorder（type 复用语义最近枚举，同 LLM 压缩/摘要旁路口径）。
- **真跑（另授权）**：`RAG_BENCHMARK_REAL=1` 基准真跑 citationPrecision 不低于 after-chunking 锚点 0.8889、answerConsistency 不回退（真外呼须 owner 授权，成本闸门）。

## 5. 冻结面（不动清单）

- 不改接口签名：`KnowledgeRetrievalPort` / `SourceReference` / `CrmVectorStore` / `ModelProvider` / `DynamicConfigService` / `TokenUsageRecorder`。
- 不改 SSE 事件契约：事件名/顺序/payload 字段级冻结——反思复用 `references` 事件语义，不加新事件、不动 `sources`。
- 不动 D16 语义与 `strictKb` 键现状；不动检索链路（融合/重排/切分/上下文组装）。
- 零 DDL、零新依赖、不动 `frontend/`。
- 写集内完成 `openspec/project.md` 处置表 12 行改判：「不做（复杂度；等基线归因后再议）」→「做（生成侧反思）｜add-self-rag-reflection」。

## 6. 决策点（owner 2026-10-10 拍板）

- **D1 实现范围**：**R+L 组合**——规则反思默认开（零成本下限保障）+ LLM 支持度自评默认关（COST 档、审批流）；L 失败回退 R；**F 重检索环不做**。
- **D2 无据断言处置**：**软降级**——剥除无据引用 + 保留答案文本 + 尾注标注「未获知识库直接支持」；硬拒答语义仍归 D16。
- **D3 验收口径**：**真跑锚定不回退**——门禁全绿后、合入前停步，向 owner 单独请求 `RAG_BENCHMARK_REAL=1` 真跑授权；citationPrecision ≥ 0.8889（after-chunking 锚点）、answerConsistency 不回退方判验收成立。
