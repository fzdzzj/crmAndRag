# B-persist 变更计划（任务 7/8）

## 范围

- 新建知识库 7 表 Flyway 脚本 `V3__knowledge.sql`，使用统一单数表名、`create_time/update_time`、`is_deleted` 与 String(100) 跨域用户引用。
- 新增 `com.slz.crm.knowledge` 域的实体、授权、存储、解析、分块、嵌入、入库与检索实现；Mapper 放入现有 `com.slz.crm.server.mapper` 扫描包。
- 新增 `QdrantVectorStore` 与 `InMemoryVectorStore`，实现冻结的 `CrmVectorStore` 和 `CrmVectorStoreHealth`；Qdrant 只使用 gRPC 6334。
- 新增 `MinioFileStorageService` 与 `InMemoryFileStorageService`；MinIO 是默认原始文件存储，内存实现仅用于本地/测试。
- 新增 `server.ai.port.KnowledgeRetrievalPort` 的真实实现，检索前完成知识库授权收敛，再通过向量 metadata 过滤。

## 非目标

- 不实现独立 RAG 对话层、匿名账号、Spring Security 或 `chat_*`/`teacher_account` 表。
- 不修改 `pom.xml`、冻结契约、`server.ai` 业务实现、平台治理配置。
- 不把模型调用绕过 `ModelProvider`；嵌入只调用 `ModelProvider.embed`。

## 关键设计

1. **表与实体**：7 张表为 `knowledge_base`、`knowledge_base_member`、`uploaded_file`、`document_vector_chunk`、`chunk_upload_session`、`batch_task`、`batch_file_result`。实体使用 MyBatis-Plus 注解，`isDeleted` 显式 `@TableLogic`。
2. **授权**：`KnowledgeBaseAuthorizationService` 只依据 `owner_user_id`、`knowledge_base_member` 与 `PUBLIC/PRIVATE` 判定；身份来自 `UserContext.userIdRef()`。
3. **文档链路**：TXT/Markdown 直接按页文本处理；PDF 通过 PDFBox 反射适配器按页解析；Excel 使用 EasyExcel 汇总行文本。分块保留 `pageNo/chunkIndex`，Excel 保留 `rowIndex`。
4. **入库一致性**：先存文件，再解析分块；逐块嵌入、落库 `document_vector_chunk`，用数据库 chunkId 作为 Qdrant point id 与 `SourceReference.chunkId`；最后更新 `uploaded_file` 状态与计数。
5. **检索授权**：先按 owner/public/member 计算用户可见 KB 集合，再与请求 `kbScope` 取交集；Qdrant filter 使用 String KB/类目与 Long 0/1 布尔，禁止 Boolean/UUID metadata。
6. **动态配置**：`KnowledgeRetrievalProperties` 提供硬编码默认值，`ObjectProvider<DynamicConfigService>` 存在时覆盖 topK/minScore/strictKb，E 实现落地后无需改 B。

## 依赖与风险

- `org.apache.pdfbox:pdfbox` 不在当前 POM 中。为遵守“不改 pom”的约束，本次代码通过反射适配 PDFBox API；PDFBox 不在 classpath 时上传 PDF 会返回明确的处理失败，后续如需生产解析 PDF，应单独走依赖变更申请。
- EasyExcel、Qdrant、MinIO、Spring AI 已在 POM 中，本次不新增依赖。
- Qdrant 不可达时 `QdrantVectorStore` 可抛异常；启动初始化失败不阻断应用，健康检查继续暴露不可用状态。

## 测试与验收

- 单测覆盖：TXT 按页分块、InMemory 向量写入/过滤/删除、公开与私有知识库授权。
- 编译验证：`mvn -q compile`。
- 定向测试：`mvn -q test -Dtest=DocumentServiceTest,InMemoryVectorStoreTest,KnowledgeBaseAuthorizationServiceTest`。
