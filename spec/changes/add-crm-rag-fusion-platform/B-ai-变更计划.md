# B-ai 变更计划（任务 9：知识库检索能力）

## 目标

- 将 `KnowledgeRetrievalPort` 从基础向量召回升级为生产实现：授权过滤 → 查询改写 → 文本/图片双路召回 → BM25 rerank → 路由融合 → 来源引用。
- 保持 `KnowledgeRetrievalQuery/Result` 现有形状不变，C 接线时可直接关闭 mock。

## 检索管线

1. **身份与授权**：从 `UserContextHolder.require()` 取当前用户；`KnowledgeBaseAuthorizationService` 先把请求 `kbScope` 收敛到可见知识库，再按 KB 写入 Qdrant metadata filter。
2. **查询改写**：新增 `RetrievalQueryRewriteService`，使用 `ModelProvider.chat(Prompt, ModelCallOptions)`；温度 0.1、maxTokens 128，只输出一个查询。动态开关 `rag.retrieval.query-rewrite.enabled` 默认 true；模型失败或输出为空时回退原查询，不阻断检索。
3. **文本路**：`EmbeddingService.embed()` 生成查询向量；每个授权 KB 分别向 `CrmVectorStore.search()` 发起候选召回，候选数 = `topK * candidateMultiplier`（默认 4 倍）。
4. **BM25 rerank**：新增纯 Java `Bm25Scorer`，移植中文 bigram + 英数 token 评分；候选内归一化后按 `vector=0.6 / bm25=0.4` 得到路内混合分。
5. **图片路**：`imageVector` 非空时对同一批授权 KB 做向量召回，并参与同一 BM25 rerank；不存在图片向量时跳过图片路。
6. **路由融合**：以 `documentId + chunkId` 去重融合，文本路权重 0.7、图片路权重 0.3；同切片两路命中则累加加权分，最终按分数降序取 topK。
7. **来源契约**：继续使用冻结 `SourceReference`；保留 `documentId/chunkId/chunkIndex/pageNo/rowIndex/excerpt/score`，`chunkIndex=0` 不得被误删。

## 配置与降级

- `DynamicConfigService` 仍用 `ObjectProvider` 可选注入；缺省硬编码 topK=5、minScore=0.20、candidateMultiplier=4、text=0.7、image=0.3、vector=0.6、bm25=0.4。
- 查询改写失败只回退原查询；向量或模型底层异常继续抛出，不静默返回伪造上下文。
- 所有模型调用只经 `ModelProvider + ModelCallOptions`，不传 provider 专有 options。

## 测试

- `Bm25ScorerTest`：中英文分词、候选归一化和相关文档加权有效。
- `RetrievalQueryRewriteServiceTest`：模型成功改写；模型失败/空输出回退原查询。
- `KnowledgeRetrievalServiceImplTest`：验证授权收敛、文本/图片双路权重 0.7/0.3、BM25 rerank、`chunkIndex=0`。
- 回归：`mvn -q compile`、定向测试、全量 `mvn -q test`。

## 非目标

- 不修改端口契约和 C 业务实现。
- 不在检索侧懒生成图片向量；图片向量仍由 C 显式传入。
- 不新增依赖；PDFBox 直连改造等待 base 提交合入后单独处理。
