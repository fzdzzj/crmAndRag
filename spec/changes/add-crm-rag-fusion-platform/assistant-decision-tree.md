# AI 助手行为决策树（ai-assistant 能力域行为规范）

> 本文件把评审锁定的助手行为固化为**可实现的决策树**，作为 `specs/ai-assistant/spec-delta.md` 各 EARS 场景的实现依据。
> 架构定位（已锁）：**一个 CRM 单体**，业务模块原样搬入；**一个 AI 助手**吸收 RAG 的思考/记忆/图文/检索能力；知识库是助手的一项能力（`com.slz.crm.knowledge`），RAG 的独立对话层（chat_conversation/chat_message、RagChatPipeline 独立入口、匿名问答）**丢弃不迁**。
> 触发方式（已锁）：知识库检索 = **前端手动开关 `useKnowledgeBase`**（非 LLM 自主）；业务工具 = LLM 自动调用。
> 图例：【使用】读取 ·【新增/更新】写入 ·【删除/淘汰】清理。

---

## 1. 请求处理树（单轮问答主干）

```text
入口：用户发消息 = 文本 [+图片] [+imageRef] + 开关{useKnowledgeBase(手动), thinking}
│
├─A 前置守卫（顺序短路，命中即返回，不进 LLM）
│   ├─ 未登录 ───────────────────────→ 401 UNAUTHORIZED（融合后无匿名态）
│   ├─ 输入为空 / 超长(>阈值) ────────→ 校验错误（统一提示，不透校验器细节）
│   ├─ 限流命中（每用户滑窗，默认10/min）→ SSE error code=RATE_LIMITED（不消耗 LLM）
│   ├─ 配额 / Token 预算超限 ─────────→ SSE error code=QUOTA_EXCEEDED（机器可读原因+重试间隔）
│   └─ 同会话已有活跃流（重发/接管）──→ createGeneration 顶替 map 条目
│                                        → 旧任务在 shouldAbort 检查点自动退出（见 F）
│
├─B 会话与记忆装载
│   ├─ 载入 ai_session + recentMessages（=ai_message 最近N轮投影，滑窗 max-history-rounds）
│   └─ 记忆加工品 summary/facts/intent 从持久化装载（重启/多实例不丢）
│
├─C 图片处理（★与知识库解耦；取消无差别最近图回退）
│   ├─ 本轮带图 或 显式 imageRef 引用某图 → 进入图片理解；否则【不注入任何图片上下文】
│   ├─ 图片理解 vision：OCR文本 + 图片摘要 + 关键实体 +（问题聚焦摘要）
│   │    ├─ 缓存分层：OCR/摘要/实体 按 hash 复用；问题聚焦+图片向量 按 hash+问题 复用/重算
│   │    └─ 同图换问题 → 复用 OCR，重算「问题聚焦 + 图片向量」
│   ├─ 理解文本块【恒注入】prompt（闲聊/工具也能"看图"）
│   └─ 图片向量【仅 useKnowledgeBase=ON 时懒生成+缓存】（KB OFF 不 embed）
│
├─D 知识库检索（★useKnowledgeBase=ON 才触发，每轮强制）
│   ├─ OFF → 跳过检索，且【绝不触发空匹配兜底】→ 直接正常生成（闲聊 + 业务工具 + 图片理解）  ★修复RAG坑
│   └─ ON  →
│         ├─ 有图片向量 → 图文双路召回：文本路×0.7 + 图片路×0.3，按片段去重加权
│         ├─ 无图片向量 → 文本混合检索：问题向量→Qdrant召回(minScore,候选=maxResults×k)
│         │                →BM25重排(向量+BM25加权归一)→topK
│         ├─ 意图/类目过滤：CRM 域类目（DynamicConfig 可配；无配置则跳过）——新建，非复用死代码 QueryIntentClassifier
│         ├─ 授权过滤：KB 成员 / 公开=所有已登录用户（RagRetrievalAccessFilter 语义）
│         ├─ 命中 0 条(且无图) → 注入「(知识库未检索到相关内容)」标记 + 诚实约束，仍进正常生成
│         │                      （不硬返回 canned；strict-KB 硬兜底可由 DynamicConfig 保留）
│         ├─ 检索依赖失败(Qdrant/embedding) → 韧性执行器 重试/熔断 → 降级(无KB上下文继续或提示)
│         └─ 产出 来源引用(见 §4，含 chunkIndex/pageNo) + 片段注入 prompt（受 prompt-max-chars 预算裁剪）
│
├─E 组装 prompt → LLM 生成（Spring AI / dashscope，可插拔 Provider）
│   ├─ 系统提示词：恒定常量（可被 DynamicConfig 覆盖）；要求「含糊先反问」+「用到来源打内联引用 [n]」
│   ├─ 用户上下文：同一模板条件填充 = 会话记忆块(有则加) + 图片资料块(有则加) + 文档上下文块(恒有,空填"无") + 当前问题 + 指令
│   ├─ thinking ON  → 思考块经 SSE thinking 事件透传（前端折叠）；写记忆/历史前【恒定剥离】think
│   ├─ thinking OFF → 剥离思考块，只出答案（快速模式，默认）
│   └─ 业务工具（★LLM 自动决定，与 KB 手动开关是两套触发）
│        ├─ 只读工具（查客户/商机/合同/订单/回款/统计…）
│        │     → 数据权限过滤：本人 / 本部门 / 本部门及以下(上司看下属) / 全部
│        │     → 命中实体汇总 references 事件（type+id+name，前端可跳转）
│        │     → 参数歧义 → 追问 / 给候选
│        └─ 草稿工具（创建合同/订单/回款…）→ 生成草稿 + 待确认卡片（不直接写库）
│
├─F 流式输出与中止（SSE，复用助手管线 + shouldAbort 检查点）
│   ├─ 事件：start → sources(检索后、答案前) → thinking(可选) → delta(增量) → references → done/complete
│   ├─ 心跳 ping(15s) + 超时 sse-timeout(300s)
│   ├─ shouldAbort 检查点埋在：嵌入前 / 向量检索后 / 重排后 / LLM首包(TTFT) / 每 delta
│   │     判定 = isCancelled() || isCompleted() || 当前会话活跃生成已不是自己(被接管)
│   ├─ 生成中重发 ─────→ 回 A·接管（旧任务下一检查点退出，存部分回答{interrupted:true}，发 stopped）
│   ├─ 用户点取消 ─────→ markCancelled → shouldAbort → cancelled 事件
│   ├─ SSE 超时 ───────→ onTimeout → markCancelled → cancelled(reason=timeout)
│   ├─ 客户端断连(IOException) → markCancelled → 清理，不冒泡
│   └─ LLM 失败 → 连接型零输出自动重试(≤2次,指数退避) → 修复重试(max-fix-rounds) → 降级模型 → 静态兜底
│                 error 事件脱敏（不透 SQL/类名/堆栈）
│
├─G 待确认动作（若 E 产出草稿）—— Human-in-the-Loop
│   ├─ 确认 → 执行写（再校验权限）→ 成功 / 失败(脱敏，仅存{error:"执行失败"})
│   ├─ 取消 → 丢弃草稿
│   ├─ 超时(pending-expire) → 过期作废
│   └─ 重复确认 → 状态机幂等
│
└─H 收尾
    ├─ done/complete → 保存 ai_message（含 references/sources payload）
    ├─ Token 计量回写（usage→平台统一计量；缺失/为负记 0）
    ├─ 记忆更新（见 §2：intent 异步抽取 / facts 从 top1 / summary 溢出压缩；均旁路降级）
    └─ 指标(TTFT/总耗时/工具调用/取消/失败/降级) + 审计(traceId 贯穿)
```

---

## 2. 会话记忆 & 短问题生命周期树（使用 / 新增 / 删除）

```text
真相源 = ai_message（持久化）；recentMessages = 其内存投影；summary/facts/intent = 加工品(持久化)
│
├─ 轮次开始：取快照 memory = {summary, facts, intent, recentMessages}
│    └─【使用】全部作为本轮输入：改写锚点 + prompt「会话记忆」块
│
├─ 短问题改写 rewriteQuestion（纯规则·不调模型·检索前）
│    ├─ 判定：追问(继续/展开… 或 ≤10带疑问词) | 指代(这个/该/它… 或 ≤6字) | 普通短问(≤14字 或 与事实词重叠)
│    ├─【使用】锚点优先级：intent > facts[0] > 最近用户问 > summary
│    ├─ 产出："锚点：原问题" 作检索 query；信息足够则不改写
│    └─ 兜底：仍含糊 → 系统提示词「必须先反问」列选项澄清
│
├─ 意图 intent（单值，≤200字）
│    ├─【使用】改写锚点(最高) + prompt「当前意图」
│    ├─【新增/更新】本轮后 异步LLM抽一行 → updateIntent（CAS单飞去重，只服务下一轮）
│    └─【删除】被新意图覆盖；会话TTL/清理
│
├─ 事实 facts（≤8条·去重）
│    ├─【使用】改写锚点(次高) + prompt「已确认事实」
│    ├─【新增/更新】检索后取 top1(score≥阈值) 片段 → 抽原文行(≤3/次) → updateFacts
│    └─【删除】超上限按插入序淘汰；TTL/清理
│
├─ 历史摘要 summary（≤2000字）
│    ├─【使用】改写锚点(兜底) + prompt「历史摘要」
│    ├─【新增/更新】窗口(默认6轮)溢出→最旧2条原文累加→达阈值 异步LLM重压缩(或截断兜底)
│    └─【删除】被重压缩结果覆盖；TTL/清理
│
├─ 最近消息 recentMessages（窗口≤12条）
│    ├─【使用】注入历史(buildHistoryMessages，恒剥离think，按prompt预算裁剪)
│    ├─【新增】每轮 appendRoundMessages(user问 + assistant答)
│    ├─【淘汰】超窗口→溢出进 summary（不丢）
│    └─【融合调整】不再独立双写 → ai_message 内存投影（restoreMemoryIfAbsent 回灌）
│
└─ 结束/清理
     ├─【删除】主动 clear / 清空接口；TTL(默认1800s) 定时清理
     └─【融合】持久记忆挂 ai_session（重启/多实例不丢；多实例须持久化或粘性会话）

旁路治理（intent/summary 共用）：专用 memoryExecutor(pool=2/有界队列=64/AbortPolicy)，
CAS 单飞，饱和即拒绝降级跳过，绝不阻塞主答 → 归 platform-governance。
```

---

## 3. 图片处理矩阵（带不带图 × 用不用 RAG × 同图不同问）

| 本轮输入 | useKnowledgeBase | 图片理解(OCR/摘要/实体) | 问题聚焦+图片向量 | 注入 prompt | 检索 |
|---|---|---|---|---|---|
| 不带图·无 imageRef | 任意 | 不触发（**不回退旧图**） | 无 | 无图片块 | OFF:无 / ON:纯文本检索 |
| 带新图 | OFF | 新理解 | 只算问题聚焦(供看图说话)，**不算向量** | 图片资料块 | 不检索 |
| 带新图 | ON | 新理解 | 问题聚焦 + **图片向量(懒生成)** | 图片资料块 | 图文双路(图片路0.3) |
| 带同图(hash命中)·同问题 | 任意 | **复用缓存**(零 vision) | 复用缓存 | 图片资料块 | ON:复用向量走双路 |
| 带同图(hash命中)·**换问题** | 任意 | **复用 OCR/摘要/实体** | **重算问题聚焦(+向量若ON)** | 图片资料块 | ON:用新向量双路 |
| imageRef 引用旧图 | 任意 | 复用该图缓存 | 按当前问题重算聚焦 | 图片资料块 | ON:用该图向量 |

**缓存策略**：`cache[会话::hash]`（多图共存）+ `latestImageHashes[会话]`；TTL 滑动(默认1800s) + **每会话上限+LRU(如8张)**（新增，防堆积）。
**隐患处置**：① 取消无差别最近图回退（改 imageRef 显式引用）；② 同图换问题只复用问题无关部分；③ 加缓存条数上限。

---

## 4. 来源引用与高亮契约（档 B：页级高亮）

**SourceReference 字段（在 RAG 现有基础上补齐定位锚点）**：
`sourceType`(TEXT/IMAGE) · `route` · `filename` · `documentId` · `chunkId`(向量点/分块PK) · `chunkIndex` · `pageNo`(可空) · `rowIndex`(Excel,可空) · `excerpt`(=chunk_text) · `relevanceScore`

**入库侧改动（档 B 关键，非一行透传）**：
- PDF **按页分块**：text 模式与 OCR 模式均逐页处理，每块经 `extraMetadata` 打 `pageNo`（当前实现把所有页 merge 成一个 `mergedText` 再整体分块，页边界丢失，需重构）。
- Excel 保留 `rowIndex/headers`；纯文本/markdown 用 `chunkIndex + chunk_text`。
- `pageNo`/`rowIndex` 落 `document_vector_chunk.extra_metadata_json`，检索时随片段带出。
- bbox 像素级高亮**不在本期**（OCR/vision 只返回纯文本、无坐标）。

**下发与前端**：
- 流式：检索后、答案前先发 `sources` SSE 事件（前端先渲染引用列表）。
- 答案内联引用 `[n]`（系统提示词驱动，n 对应 sources 顺序）→ 前端点 `[n]`：定位来源卡 → 有 `pageNo` 跳页 + `chunk_text` 段内字符串匹配高亮；Excel 用 `rowIndex` 高亮行；纯文本 `chunk_text` 匹配。

---

## 5. 本文件锁定的相关决策（供 specs/tasks 引用）

- KB 触发 = 前端手动 `useKnowledgeBase`（非 LLM 自主）；业务工具 = LLM 自动。
- 图片理解与 KB 检索**解耦**；图片向量按 KB ON 懒生成；取消无差别最近图回退，改 `imageRef` 显式引用；同图换问题只复用问题无关部分；缓存加条数上限+LRU。
- 记忆：`ai_message` 为唯一真相源，`recentMessages` 为其内存投影；`summary/facts/intent` **持久化**（挂 `ai_session` 或新 `ai_conversation_memory` 表），归属改 `userId`；旁路执行器降级不阻塞主答。
- 意图/类目：RAG 的 `QueryIntentClassifier`（技术栈类目）是**死代码**，丢弃；**新建** CRM 域意图/类目机制，接进检索 metadata 过滤，类目+关键词由 `DynamicConfig` 可配。
- 来源高亮：采**档 B（页级）**，需按页分块 + `SourceReference` 补 `chunkIndex/pageNo/chunkId` + 内联引用 `[n]`。
- 思考模式：SSE `thinking` 事件透传 + 写记忆/历史前恒定剥离 think + prompt 预算闸门。
- ★空匹配兜底修复：RAG 现状 `if (matches.isEmpty() && !hasImageContext)` 未判 `useKnowledgeBase`，导致 KB OFF+无图恒吐「未检索到」。修复=兜底仅在「KB ON 且零命中且无图」时考虑，且改为「注入未命中标记 + 诚实约束、仍进正常生成」；KB OFF 绝不触发。strict-KB 硬兜底由 DynamicConfig 可选保留。
- ai_message 落库：content = 剥离 think 正文；payload 按 msgType 承载结构化附加（见 §6）；tokenCount 缺失/为负记 0。

---

## 6. ai_message.payload 契约（按 msgType 举例）

> `content` 恒为剥离 think 的正文；`payload` 为结构化 JSON，形状由 `msgType` 决定。以下为助手答案各类 payload 示例。

**① 纯闲聊（msgType=text，KB OFF，无工具）**
```json
{ "references": [], "sources": [], "interrupted": false }
```

**② KB 增强答案 + 来源高亮（msgType=text，KB ON）**
```json
{
  "sources": [
    { "sourceType": "TEXT", "route": "TEXT_ROUTE", "filename": "差旅费报销制度.pdf",
      "documentId": "doc_10086", "chunkId": "chk_555", "chunkIndex": 12, "pageNo": 3,
      "rowIndex": null, "excerpt": "差旅费报销需在出差结束后30日内提交……", "relevanceScore": 0.87 },
    { "sourceType": "IMAGE", "route": "IMAGE_ROUTE", "filename": "报销流程图.png",
      "documentId": "doc_10090", "chunkId": "chk_777", "chunkIndex": 0, "pageNo": null,
      "excerpt": "报销流程：提交→审批→打款", "relevanceScore": 0.72 }
  ],
  "citations": [1],
  "references": [],
  "interrupted": false
}
```

**③ 业务工具查询（msgType=text，调用只读工具）**
```json
{
  "references": [
    { "type": "CUSTOMER", "id": 2001, "name": "华兴科技" },
    { "type": "CONTRACT", "id": 3050, "name": "HT-2026-018" }
  ],
  "toolCalls": [
    { "tool": "queryCustomer", "args": { "name": "华兴" }, "status": "SUCCESS", "resultCount": 1 }
  ],
  "sources": [], "interrupted": false
}
```

**④ 草稿/待确认动作卡（msgType=actionCard）**
```json
{
  "actionId": 9001, "actionType": "CREATE_CONTRACT",
  "draft": { "customerId": 2001, "amount": 150000, "startDate": "2026-09-01" },
  "status": "PENDING_CONFIRM", "expireAt": "2026-09-08T12:30:00"
}
```

**⑤ 草稿进度（msgType=draftProgress）**
```json
{ "actionId": 9001, "stage": "VALIDATING", "progress": 60, "message": "正在校验合同字段…" }
```

**⑥ 图表答案（msgType=chart）**
```json
{
  "chartType": "bar", "title": "本季度回款TOP5客户",
  "data": [ { "label": "华兴科技", "value": 520000 } ],
  "references": [ { "type": "CUSTOMER", "id": 2001, "name": "华兴科技" } ]
}
```

**⑦ 被接管的部分回答（msgType=text，interrupted）**
```json
{ "interrupted": true, "partialReason": "SUPERSEDED_BY_NEW_REQUEST", "sources": [], "references": [] }
```
（content = 已生成的部分正文，think-stripped）

**⑧ KB ON 但零命中（修复后，msgType=text）**
```json
{ "kbHit": false, "sources": [], "references": [], "interrupted": false }
```
（content = 模型诚实说明「知识库未找到相关内容」，可继续闲聊/工具，不再硬返回 canned）

**⑨ 系统/降级提示（msgType=system）**
```json
{ "code": "MODEL_DEGRADED", "userMessage": "已切换备用模型", "from": "qwen-max", "to": "qwen-turbo" }
```

**可选：思考回看**（任意 text 类）：`"thinking": "…"` 仅存 payload 供回看，**绝不进 content**。
