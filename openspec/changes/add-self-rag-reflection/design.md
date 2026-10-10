# 设计决策 — add-self-rag-reflection

> 本文件记录架构决策与取舍；范围/处置/验收的最终拍板见 proposal.md §6 回填。

## D-A 挂点选择：流式收尾 references 组装前

- **实证**：`AiChatServiceImpl` L236-246 检索上下文 SystemMessage 注入 → L268-271 `sources` SSE 事件先发（契约冻结，不可撤回）→ 流式生成 → `references` 事件在 delta 后（payload `{citations:[n], items:[...]}`）。
- **取舍**：反思若要修正引用集合，唯一合法落点是 `references.citations` 过滤——`sources` 已发出无法撤回。因此 L 档自评结论只作用于 references 组装，不回溯 sources；用户看到的来源清单保持「检索所得」，引用编号清单体现「被支持子集」。
- **否决项**：F 档重检索-重生成——流式 delta 已发不可撤回（SSE 契约 `delta*` 无删除语义），重生成只能整轮重来，成本 ×3 且 UX 倒退；如未来需要，须解冻 SSE 契约另立项。

## D-B 分层：规则反思（R）与 LLM 自评（L）分离

- R 档零成本、确定性可测，是 citationPrecision 的下限保障；L 档处理语义级「引用是否被内容支持」，是提升面。
- L 档失败路径（超时/模型不可用/空输出/解析失败）**一律回退 R 档规则链**，与 LLM 压缩/重排的回退语义同构（`rag.context.compressor.mode` / `rag.retrieval.rerank.mode` 先例）。
- mode 键三态合一（off/rule/llm）而非两开关，避免「规则开+LLM 开」的状态组合歧义。

## D-C 成本控制：COST 档 + 审批流 + 默认关

- L 档 = 每答一次 LLM 调用，属费用红线族，待遇对齐 07/15/06 三开关（`rag.query.*` COST 先例）：
  - 默认 off；`ConfigKeyTierPolicy` COST 档超管专写；608 持有者走 `/platform/config/cost-requests` 申请-审批流（add-cost-key-approval-workflow 落地）。
- 计量：ModelProvider + ModelCallOptions + TokenUsageRecorder，type 复用语义最近枚举（同 LLM 压缩口径，TokenUsageType 为冻结契约无新增枚举值）。

## D-D 命名空间：新增 `rag.generation`

- `DynamicConfigKeyRegistry.NAMESPACES` 白名单现十个（ai.prompt/ai.model/rag.retrieval/rag.context/rag.chunking/rag.query/rag.intent/business/platform.resilience/rag.ingest），均为检索侧/平台侧。
- Self-RAG 作用于生成侧，塞进 `rag.query`（查询侧增强）语义错位；新扩 `rag.generation` 命名空间，登记 3 键（proposal §3）。
- tier 定级（owner 拍板 D1 后定）：**三键全 COST 档**——`selfrag.mode` 是费用开关（rule→llm 切换即产生 LLM 调用），与 `rag.retrieval.rerank.mode` / `rag.context.compressor.mode` 两先例同构（两者均 COST）；`llm.timeout-ms` / `llm.max-claims` 同 `rerank.llm.timeout-ms` COST 先例。census 全量从 63 键 → 66 键（COST 22 → 25）。

## D-E 与 D16 的边界护栏

- Self-RAG 触发前提 = `retrieval.hasSources()`（有命中才自评）；零命中路径完全走 D16 现状（MISS_CONTEXT），两机制互不进入对方路径。
- D2 若拍板软降级：剥除引用 + 保留答案文本 + 尾注标注；不删答案内容（与 SSE delta 已发语义兼容）。

## D-F 评测接入

- `RagQualityEvaluator.RetrievalFunction` 可插拔（单测 fake / 生产真 port），反思层以装饰器方式包在 RetrievalFunction 外即可被基准评测复用，评测器本身零改动。
- citationPrecision 判定（引用中黄金比例）与反思目标函数天然对齐：自评剥除非支持引用 → citationPrecision 单调改善的机制通路成立。
