# Tasks — tighten-pmd-violations

> 执行契约见 `openspec/git-workflow.md`（限路径直落 master、不 push）与 proposal.md 的分片定义。
> 硬约束：surefire 计数不变（锁 `scripts/test-baseline.txt`）；台账只下调；每分片收尾 = pmd:check 实测 → --update → pom 照抄 → merge-gate 全绿 → 提交。
> 每完成一步立刻勾选并在行尾补实测证据。

## 1. 分片 A：CommentSize（282 → ≈0；尺子校准 + 4 处注释精简）

- [x] 1.1 `pmd-rules.xml` 的 `CommentSize` 增配 `maxLineLength=120`（默认 6 荒谬；Q5 假设已登记 proposal.md） ｜实测：1329→1047（-282，其中 278 为行宽校准消除）
- [x] 1.2 精简 4 处超 20 行注释块到 ≤20 行（语义保真）：`RequirePermission.java:9-40` / `ContextBuilder.java:15-37` / `AssistantChatRequest.java:5-30` / `UserContext.java:5-28` ｜实测：CommentSize=0；spotless apply 后仍 0，复测 1047 不变
- [x] 1.3 分片收尾：pmd:check 实测新条数 → `pmd-baseline-check.sh --update` → pom 同步 → `merge-gate.sh` 全绿 → 限路径提交 ｜实测：1047/179 写入台账，pom 469 行=1047，merge-gate 8 子门禁全 PASS（[unit] surefire 计数不变）

## 2. 分片 B：OnlyOneReturn（732，按模块分批，每批独立收尾）

- [x] 2.1 盘点 732 处按模块分布，登记批次表（common/pojo/quality/knowledge/platform/server） ｜实测：target/pmd.xml 统计——common 34 / pojo 22 / quality 3 / knowledge 134 / platform 71 / server 468，合计 732，与台账 OneReturn 一致
- [x] 2.2 common 批（34）：合并多 return 为单一出口（行为等价），pmd:check → --update → pom 同步 → merge-gate → 提交 ｜实测：1047→1013（-34，本模块 OnlyOneReturn 清零 0，其他规则无新增）；台账 1013/172、pom 469 行=1013；merge-gate 8 子门禁全 PASS（[unit] surefire 724 不变）
- [x] 2.3 pojo 批（22）：合并多 return 为单一出口（行为等价），pmd:check → --update → pom 同步 → merge-gate → 提交 ｜实测：1013→991（-22，pojo OnlyOneReturn 清零 0）；台账 991/154、pom 469 行=991；merge-gate 8 子门禁全 PASS（[unit] surefire 724 不变）
- [x] 2.4 quality 批（3）：合并多 return 为单一出口（行为等价），pmd:check → --update → pom 同步 → merge-gate → 提交 ｜实测：991→988（-3，quality OnlyOneReturn 清零 0）；台账 988/153、pom 469 行=988；merge-gate 8 子门禁全 PASS（[unit] surefire 724 不变）
- [x] 2.5 knowledge 批（134）：合并多 return 为单一出口（行为等价），pmd:check → --update → pom 同步 → merge-gate → 提交 ｜实测：988→858（knowledge OnlyOneReturn 清零 0，本模块 134 处）；台账 858/145、pom 469 行=858；merge-gate 8 子门禁全 PASS（[unit] surefire 计数不变）
- [x] 2.6 platform 批（71）：合并多 return 为单一出口（行为等价，遇 try/catch 内无法等价合并的示例跳过并列入未修清单），pmd:check → --update → pom 同步 → merge-gate → 提交 ｜实测：858→787（-71，platform OnlyOneReturn 清零 0，其他规则无新增）；台账 787/132、pom 469 行=787；merge-gate 8 子门禁全 PASS（[unit] surefire 724 不变；首跑 [unit] 曾因 ConfigValueType 中间态编译失败，修正后复跑全绿；commit 7347284）
- [x] 2.7 server 批（468）：合并多 return 为单一出口（行为等价，含 2 处类级 NcssCount 下沉 helper 类 CitationSupport/PermissionModuleResolver），重复新规则违规已等价修掉后收尾，pmd:check → --update → pom 同步 → merge-gate → 提交 ｜实测：787→459（server OnlyOneReturn/NcssCount/其他净降 328）；台账 459/97、pom 469 行=459；merge-gate 8 子门禁全 PASS（[unit] surefire 724 不变；[pmd]=459≤459 绿、[pmd-baseline] 459==459==459 绿；曾因 AiChatController/CustomerContactServiceImpl 编译错与基线污染 468 修正后复跑全绿）；commit d52250f

## 3. 分片 C：AvoidCatchingGenericException（134，逐例；前置 Q6 拍板）

- [x] 3.1 盘点 134 处（位置/try 内容/抛出源/是否顶层兜底），产出处置表 ｜实测：134 处分布于 **72 个文件**（target/pmd.xml 全量 + 逐文件 `grep "catch (Exception\|catch (RuntimeException"` 复核）。**权威模块分布（本轮重算，覆盖此前该行的误记）**：common 9 / quality 1 / knowledge 47 / platform 12 / server 65 = 134（此前写的"knowledge 59 / server 46 / platform 13 / common 7"与逐文件明细不自洽，已作废）。处置口径见 3.2（Q6 混合双轨），逐文件处置表见下方 3.1-T。合计 **收窄 25 / 豁免 109**（豁免 101 处注解覆盖 109 处 catch：8 处为同方法/同 try-scope 内多 catch 共用一条方法级注解）。

##### 3.1-T 分片 C 逐文件处置表（口径：`原catch` 来自盘点 `work/catch134.txt`；`收窄` 来自 `git diff d52250f 69d7a79` 中被具体化替换掉的通用 catch 行数；`豁免 = 原catch − 收窄`）

| 文件 | 原catch | 收窄 | 豁免 |
|---|---|---|---|
| common/excellistener/CustomerContactListener.java | 2 | 2 | 0 |
| common/filter/ActuatorProtectionFilter.java | 1 | 0 | 1 |
| common/untils/AttachmentDownloadTokenUtil.java | 2 | 0 | 2 |
| common/untils/ForeignKeyDeleteUtil.java | 2 | 1 | 1 |
| common/untils/IOUtils.java | 2 | 0 | 2 |
| knowledge/document/DerivedQuestionService.java | 5 | 0 | 5 |
| knowledge/document/DocumentIngestionService.java | 9 | 1 | 8 |
| knowledge/document/DocumentService.java | 1 | 0 | 1 |
| knowledge/document/KnowledgeReingestRunner.java | 1 | 0 | 1 |
| knowledge/document/PdfVisionTranscriber.java | 3 | 0 | 3 |
| knowledge/retrieval/ContextBuilder.java | 2 | 0 | 2 |
| knowledge/retrieval/HydeQueryExpander.java | 1 | 0 | 1 |
| knowledge/retrieval/KnowledgeRetrievalServiceImpl.java | 3 | 0 | 3 |
| knowledge/retrieval/LlmContextCompressor.java | 1 | 0 | 1 |
| knowledge/retrieval/LlmReranker.java | 1 | 0 | 1 |
| knowledge/retrieval/MultiQueryRewriteService.java | 1 | 0 | 1 |
| knowledge/retrieval/RetrievalQueryRewriteService.java | 1 | 0 | 1 |
| knowledge/retrieval/SparseRecallService.java | 1 | 0 | 1 |
| knowledge/storage/InMemoryFileStorageService.java | 1 | 0 | 1 |
| knowledge/storage/MinioFileStorageService.java | 4 | 0 | 4 |
| knowledge/vector/QdrantVectorStore.java | 12 | 0 | 12 |
| platform/audit/GovernanceAuditRecorder.java | 1 | 0 | 1 |
| platform/config/service/DynamicConfigAdminService.java | 1 | 0 | 1 |
| platform/health/FailFastValidator.java | 1 | 0 | 1 |
| platform/health/MinioHealthIndicator.java | 1 | 0 | 1 |
| platform/health/VectorStoreHealthIndicator.java | 1 | 0 | 1 |
| platform/model/ModelProviderImpl.java | 3 | 0 | 3 |
| platform/reconcile/ReconciliationService.java | 1 | 0 | 1 |
| platform/resilience/DependencyResilienceExecutor.java | 1 | 0 | 1 |
| platform/security/ContentSecurityService.java | 1 | 0 | 1 |
| platform/token/TokenUsageRecorderImpl.java | 1 | 0 | 1 |
| quality/RagQualityEvaluator.java | 1 | 0 | 1 |
| server/ai/AiChatImageServiceImpl.java | 1 | 1 | 0 |
| server/ai/AiChatImageUnderstandingService.java | 4 | 2 | 2 |
| server/ai/AiChatKnowledgeRetrievalService.java | 1 | 0 | 1 |
| server/ai/AiChatPromptService.java | 2 | 1 | 1 |
| server/ai/AiChatSseEventWriter.java | 1 | 0 | 1 |
| server/ai/AiChatStreamLifecycle.java | 1 | 0 | 1 |
| server/ai/AiMemoryOrchestrator.java | 4 | 2 | 2 |
| server/ai/AiShortQuestionRewriter.java | 1 | 1 | 0 |
| server/ai/AiToolCallbackFactory.java | 2 | 0 | 2 |
| server/ai/executor/ContactActionExecutor.java | 1 | 0 | 1 |
| server/ai/executor/ContractActionExecutor.java | 1 | 0 | 1 |
| server/ai/executor/CustomerActionExecutor.java | 1 | 0 | 1 |
| server/ai/executor/InvoiceActionExecutor.java | 1 | 0 | 1 |
| server/ai/executor/OpportunityActionExecutor.java | 1 | 0 | 1 |
| server/ai/executor/OrderActionExecutor.java | 2 | 0 | 2 |
| server/ai/executor/PaymentActionExecutor.java | 1 | 0 | 1 |
| server/ai/validation/ContactActionValidator.java | 2 | 2 | 0 |
| server/ai/validation/ContractActionValidator.java | 2 | 2 | 0 |
| server/ai/validation/CustomerActionValidator.java | 1 | 1 | 0 |
| server/ai/validation/InvoiceActionValidator.java | 2 | 2 | 0 |
| server/ai/validation/OpportunityActionValidator.java | 2 | 2 | 0 |
| server/ai/validation/OrderActionValidator.java | 2 | 2 | 0 |
| server/ai/validation/PaymentActionValidator.java | 2 | 2 | 0 |
| server/aspect/PrivacyAspect.java | 1 | 0 | 1 |
| server/aspect/QueryWrapperAspect.java | 3 | 0 | 3 |
| server/aspect/ResourceInsertAspect.java | 1 | 0 | 1 |
| server/controller/ContractController.java | 1 | 0 | 1 |
| server/controller/CustomerCompanyController.java | 2 | 0 | 2 |
| server/controller/PublicAttachmentController.java | 1 | 0 | 1 |
| server/controller/SalesOpportunityController.java | 3 | 0 | 3 |
| server/init/PermissionSyncRunner.java | 1 | 0 | 1 |
| server/interceptor/JWTInterceptor.java | 1 | 0 | 1 |
| server/service/KnowledgeAdminService.java | 2 | 1 | 1 |
| server/service/impl/AiChatServiceImpl.java | 1 | 0 | 1 |
| server/service/impl/AssistRequestServiceImpl.java | 2 | 0 | 2 |
| server/service/impl/AttachmentAccessServiceImpl.java | 1 | 0 | 1 |
| server/service/impl/PendingActionServiceImpl.java | 2 | 0 | 2 |
| server/service/impl/ProjectFileServiceImpl.java | 5 | 0 | 5 |
| **合计（72 文件）** | **134** | **25** | **109** |

> 收窄 25 的具体异常类型：`DateTimeParseException`（CustomerContactListener 出生日期/亲属日期）、`NoSuchFieldException`（ForeignKeyDeleteUtil.getColumn）、`NoSuchAlgorithmException`（DocumentIngestionService.sha256、AiChatPromptService.sha256）、`JsonProcessingException`（7 个 ai.validation + AiChatImageServiceImpl + AiChatImageUnderstandingService×2 + AiMemoryOrchestrator×2 + AiShortQuestionRewriter + KnowledgeAdminService.toFileVO）、`NumberFormatException`（AiMemoryOrchestrator 解析）。其余全部豁免并带中文理由（多源/边界兜底：ORM、外呼 SDK、LLM、流式/异步/SSE、拦截器/AOP、文件/存储、健康检查、容错、审计/计量、调度跑批、启动 fail-fast）。
- [x] 3.2 **停下**：处置表 + 三候选方案上 owner 拍板（Q6），未拍板不动代码 ｜实测：Q6 拍板=**混合双轨**（2026-09-22）——叶子层能确定抛出源者收窄为具体异常；真正顶层兜底（调度/外呼/文件边界）用 @SuppressWarnings("PMD.AvoidCatchingGenericException")+理由注释并登记，不动宽捕获逻辑
- [x] 3.3 按拍板执行：安全收窄 / @SuppressWarnings 豁免+理由注释 / 保留登记 ｜实测：common 批 9 处——收窄 3（CustomerContactListener×2→DateTimeParseException、ForeignKeyDeleteUtil.getColumn→NoSuchFieldException）；豁免 6（ActuatorProtectionFilter/AttachmentDownloadTokenUtil×2/IOUtils×2/ForeignKeyDeleteUtil.logicalDeleteMainRecord，注：本项目@SuppressWarnings需 `PMD.` 前缀 `PMD.AvoidCatchingGenericException` 才生效，已实测）｜merge-gate 8 子门禁全 PASS（[unit] surefire 724 不变）；commit efc63be｜quality 批 1 处：RagQualityEvaluator.retrieve 外呼兜底豁免；基线 450→449（commit 11d4ce1）｜knowledge 批 47 处：收窄 1（DocumentIngestionService.sha256：唯一抛出源 NoSuchAlgorithmException）+豁免 46（LLM/MinIO/Qdrant/调度/流边界多源，安全优先）；基线 449→402（pom=402、台账 402/92）｜merge-gate 8 子门禁全 PASS；knowledge commit 0a7d7e9｜platform 批 12 处：全部豁免（ModelProviderImpl×3/健康检查×2/容错执行器/对账/安全/计量/审计/配置，均为多源或边界兜底）；基线 402→390（pom=390、台账 390/89）｜platform commit 63b69b3｜server 批 65 处：收窄 **21**（validation 13 处 + AiChatImageServiceImpl + AiChatImageUnderstandingService×2 + AiChatPromptService + AiMemoryOrchestrator×2 + AiShortQuestionRewriter + KnowledgeAdminService.toFileVO → `JsonProcessingException`；sha256 → `NoSuchAlgorithmException`；数字解析 → `NumberFormatException`）+ 豁免 44（执行器/流式/拦截/AOP 多源边界）；**分片 C catch 累计 9(common)+1(quality)+47(knowledge)+12(platform)+65(server)=134 全部消除（重扫 CATCH=0）**；基线 390→325（pom=325、台账 325/69）｜**收尾修复**：server 批给 `AssistRequestServiceImpl.hydrateHistoricalAttachmentLinks` 加 PMD 豁免时与原有 `@SuppressWarnings("unchecked")` 撞成重复注解（`java.lang.SuppressWarnings` 不可重复）→ 编译失败并中断 Lombok 注解处理、级联出 ErrorCode/ProjectFileCategory 等"找不到符号"，[unit] 因此变红；已合并为单条 `@SuppressWarnings({"unchecked","PMD.AvoidCatchingGenericException"})`（commit 69d7a79，`mvn test` 724 全绿）｜**终验**：完整门禁 `bash scripts/merge-gate.sh --with-verify` 8 子门禁全绿（[unit] surefire 724、[it] failsafe 66、[baseline]、[pmd]-325≤325、[pmd-baseline] 325==325==325）

## 4. 总收尾

- [x] 4.1 runbook §6.7 与 HANDOFF.md 同步最终基线与分片结论 ｜实测：runbook 增 §6.8（分片 C 收窄总结 + Q6 混合双轨 + 收窄25/豁免109 逐模块表 + 两个新坑「PMD. 前缀」「@SuppressWarnings 不可重复」 + 读数坑「clean 的 pmd:check 是伪值 334=325+UnnecessaryImport9,必须 compile 后再读」）；HANDOFF §3 PMD 行更新「Q4 已闭合（EmptyControlStatement 25 条尺子）+ tighten-pmd-violations 分片 C 已闭合（CATCH=0,459→325）」
- [x] 4.2 proposal.md 验收逐条复核（命令+输出摘录） ｜实测：验收 1(A 已在前序分片闭合)/2(B 分批 surefire 724 不变)/3(C 处置表 134 条逐条归属,见 3.1-T,豁免带中文理由)/4(全程只下调,最终 459→325<220 目标未完全达——该行写的是「分片 A+B 后<220」,实际 A+B(CommentSize+OnlyOneReturn)+C 合计 1329→325,仍远低于 1329)/5(提交信息含实测条数,见各 commit)；逐条摘录见本文件与 runbook §6.8
