# Tasks — add-context-compression-and-enrichment

## 1. 邻居上下文增强（方案 04）

- [ ] 1.1 实现 `ContextBuilder`：命中块按 documentId + chunkIndex 取紧邻邻居（同页优先），拼 `[前置][命中][后置]`；`rag.context.neighbors = 0|1`（默认 1）
- [ ] 1.2 邻居不产生 SourceReference：单测断言 sources 数 == 命中块数（邻居不混入引用）
- [ ] 1.3 边界单测：首块无前置、末块无后置、documentId 不同不跨文档取邻居
- [ ] 1.4 开关回退单测：`rag.context.neighbors=0` 时 buildContext 输出与现行为一致

## 2. 上下文压缩（方案 10）

- [ ] 2.1 实现 `Compressor` 抽象 + 规则压缩（按 `rag.context.token-budget` 截断；保留含数字/锚点词/首末句；去连续重复段）
- [ ] 2.2 LLM 压缩可选实现（`rag.context.compressor.mode = rule | llm`，默认 rule）：`ModelProvider` 要点化，失败/空输出/超时回退规则链——三条回退路径单测
- [ ] 2.3 LLM 压缩的 token 消耗挂 `TokenUsageRecorder`（单测断言计量调用发生，补计量盲点）
- [ ] 2.4 预算触发单测：构造超预算上下文，断言压缩后 token 数 ≤ 预算；未超预算不压缩（原文逐字保留）

## 3. 引用编号完整性

- [ ] 3.1 单测：压缩/邻居拼装后，上下文 `[n]` 标号与 sources 下标一一对应（对全部五类基准用例的构造数据）
- [ ] 3.2 现有助手引用链路回归：`AiChatKnowledgeRetrievalService` 相关既有测试不改全绿

## 4. 消费侧参数化

- [ ] 4.1 `AiChatKnowledgeRetrievalService` 硬编码 topK=4 → 读 `rag.retrieval.topK`（默认回退 4）；单测覆盖配置生效与缺省回退

## 5. 基线验收（对照 add-rag-quality-baseline）

- [ ] 5.1 重跑真检索基准：token 用量较 `baseline-v1.json` 下降（或混合检索后最新基线）；答案要点覆盖 / answerConsistency / citationPrecision 不回退
- [ ] 5.2 产出 `baseline-after-context.json` 落盘同目录，差异摘要写入本 change 验证记录
- [ ] 5.3 `mvn -B -ntp test` 绿（surefire ≥ 前序变更后的计数）；`mvn -B -ntp verify` failsafe 不减
