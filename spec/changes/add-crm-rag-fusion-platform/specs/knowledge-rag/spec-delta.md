# 规范差异：knowledge-rag（知识库能力：文档/检索/授权/存储）

本文件包含对 `spec/specs/knowledge-rag/spec.md` 的规范变更。
本能力域 = 从 RAG **移植**的知识库能力（`com.slz.crm.knowledge`），**被 AI 助手调用**；对话/流式/记忆/思考/图文理解在 `ai-assistant`。
**保留 7 张表新建**（`knowledge_base`/`knowledge_base_member`/`uploaded_file`/`document_vector_chunk`/`chunk_upload_session`/`batch_task`/`batch_file_result`）；**丢弃** `chat_conversation`/`chat_message`/`teacher_account` 与 RAG 独立对话层。
对应决策：D3、D4、D7、D8、D9、D11、D15、D17。

## ADDED 需求

### Requirement: 知识库与成员授权
系统 SHALL 提供知识库的所有者、成员、角色与公开范围模型，且成员身份 MUST 绑定 CRM 稳定 userId（`user:<id>`）。

#### Scenario: 创建知识库并授权成员
GIVEN 已登录用户 U1 创建知识库 KB1
WHEN U1 添加成员 U2 并指定角色
THEN 系统记录 KB1 的 owner 为 U1、member 为 U2（均以 CRM userId 表示）
AND U2 可按角色访问 KB1

#### Scenario: 非成员访问被拒
GIVEN 用户 U3 不是 KB1 的成员且 KB1 非公开
WHEN U3 请求 KB1 的文档或检索
THEN 系统拒绝访问并返回统一越权错误

#### Scenario: 公开范围访问
GIVEN KB1 的可见性设置为公开
WHEN 任意已登录用户请求 KB1 的检索
THEN 系统允许在公开范围内访问
AND 公开语义为"所有已登录用户可见"，不提供匿名访问

### Requirement: 仅登录访问与独立对话层移除
系统 SHALL 要求所有知识库、文档与检索接口均在 CRM 登录态下访问；MUST NOT 保留匿名问答链路，且 MUST NOT 保留 RAG 独立对话入口（`chat_*` 表、`RagChatPipeline`/`RagStreamSessionManager` 独立 SSE、独立会话控制器）。

#### Scenario: 未登录访问被拒
GIVEN 请求未携带有效登录态
WHEN 访问知识库文档或检索接口
THEN 系统拒绝并返回统一未授权错误
AND 不进入任何检索流程

#### Scenario: 匿名与独立对话层移除
GIVEN 原 RAG 存在匿名问答服务与独立对话层
WHEN 移植进融合平台
THEN 系统移除匿名链路与独立对话入口
AND 知识库仅作为能力被 AI 助手调用（对话统一在 ai-assistant）

### Requirement: 文档入库与多格式处理
系统 SHALL 支持文档上传、多格式解析、OCR/视觉抽取、分块与向量化入库，并为文件写入知识库归属；批量上传 SHALL 走状态机且失败可恢复。

#### Scenario: 上传并处理文档
GIVEN 用户上传一个 PDF/Excel/图片文件到 KB1
WHEN 系统处理该文件
THEN 系统解析文本（必要时 OCR/视觉抽取）、分块、生成向量并写入向量库
AND 记录该文档归属 KB1

#### Scenario: 缺少知识库归属
GIVEN 单文件上传未指定知识库归属参数
WHEN 系统处理上传
THEN 系统拒绝或要求补齐归属，避免文档不属于任何可授权范围

#### Scenario: 处理失败可恢复
GIVEN 文档向量化过程中依赖（如向量库）暂时不可用
WHEN 处理失败
THEN 系统将该文件/批量任务标记为可恢复状态（PENDING）而非永久失败
AND 支持后续重试恢复

### Requirement: 按页分块与高亮锚点
系统 SHALL 对分页文档（PDF text/OCR 模式）**按页分块**并为每个片段打 `pageNo`（写入 `document_vector_chunk.extra_metadata_json`）；来源引用 MUST 携带定位锚点 `chunkIndex/pageNo/chunkId`（Excel 另带 `rowIndex`），以支持前端跳页高亮。

#### Scenario: PDF 按页分块打页码
GIVEN 一个多页 PDF 经文本或 OCR 解析
WHEN 系统分块入库
THEN 每个片段带其所属 `pageNo`（不再把全页 merge 成单一文本后整体分块）
AND `pageNo` 持久化到分块快照的额外元数据

#### Scenario: 来源携带定位锚点
GIVEN 检索命中若干片段
WHEN 系统构造来源引用
THEN 每条含 `documentId/chunkIndex/pageNo/excerpt/score`（Excel 含 `rowIndex`）
AND 前端可据 `pageNo` 跳页、据 `excerpt`(chunk_text) 段内匹配高亮

### Requirement: 混合检索与授权过滤
系统 SHALL 提供向量与关键词（BM25）混合检索，并在检索时按调用者授权过滤；过滤 MUST 采用按需查询而非全表扫描。图文双路召回作为检索能力保留（图片向量由 ai-assistant 在 KB 开启时提供）。

#### Scenario: 授权范围内检索
GIVEN 用户 U2 是 KB1 成员
WHEN U2 对 KB1 发起检索
THEN 系统返回 KB1 内命中的片段
AND 不返回 U2 无权访问的其他知识库片段

#### Scenario: 按需授权过滤
GIVEN 检索候选来自向量库
WHEN 系统执行授权过滤
THEN 系统按 documentId 集合或知识库范围查询授权
AND 不执行 `findAllByDeletedFalseOrderByUploadTimeDesc()` 之类的全表加载后内存过滤

#### Scenario: 图文双路召回融合
GIVEN 助手在 KB 开启且提供了图片向量
WHEN 系统执行图文混合检索
THEN 系统并行执行文本路召回与图片路召回
AND 按可配置权重（默认文本 0.7 / 图片 0.3）加权融合、按片段去重后排序
AND 来源引用分别标记为 TEXT 与 IMAGE 类型

### Requirement: 意图/类目过滤（CRM 域，可配）
系统 SHALL 提供 CRM 域意图/类目过滤（新建机制，**丢弃 RAG 死代码 `QueryIntentClassifier`**），接入检索 metadata 过滤；类目与关键词 MUST 由 `DynamicConfig` 运行期可配，无配置时跳过过滤。

#### Scenario: 按 CRM 类目过滤检索
GIVEN 已配置 CRM 域类目（如 产品资料/价格政策/合同模板/流程制度/客户FAQ）
WHEN 用户检索
THEN 系统按问题归类的类目过滤候选片段（复用文档 `category/direction` 元数据位）

#### Scenario: 类目可配热更新
GIVEN 超级管理员经动态配置修改类目或关键词
WHEN 后续检索到达
THEN 系统使用更新后的类目过滤，无需重启

#### Scenario: 无配置跳过过滤
GIVEN 未配置 CRM 类目或 `intent-filter-enabled=false`
WHEN 用户检索
THEN 系统跳过类目过滤，仅按向量/BM25 与授权过滤

### Requirement: 对象存储与健康检查
系统 SHALL 使用对象存储（MinIO，S3 兼容）保存原始与派生文件，并提供向量库与对象存储的健康检查与本地回退。

#### Scenario: 存储文件
GIVEN 用户上传文档
WHEN 系统保存原始文件
THEN 系统将文件写入 MinIO 指定桶与对象前缀
AND 记录存储键用于后续下载/删除

#### Scenario: 本地开发回退
GIVEN 未启用 MinIO（本地开发/测试）
WHEN 系统需要存储文件
THEN 系统使用内存/文件系统回退实现
AND 生产环境 MUST 保持 MinIO 启用

### Requirement: 向量库抽象与本地回退
系统 SHALL 通过统一 `VectorStore` 抽象接入向量存储，默认使用 Qdrant，并提供内存回退实现用于本地开发与测试。

#### Scenario: 默认使用 Qdrant
GIVEN 生产/默认配置
WHEN 系统写入或检索向量
THEN 系统通过 Qdrant 向量库执行
AND 启动时自动检查/创建集合并校验向量维度

#### Scenario: 本地内存回退
GIVEN 配置切换为内存向量库（dev/test）
WHEN 无可用 Qdrant 时系统写入或检索向量
THEN 系统使用内存向量库（余弦相似度，可选文件持久化）跑通链路
AND 内存实现不用于生产，生产 MUST 使用 Qdrant

---

## 备注

- 对应决策 D3（授权绑 userId）、D4（Spring AI + dashscope，可插拔 Provider）、D7（Qdrant+MinIO）、D8（移除匿名）、D9（VectorStore 抽象 + 内存回退）、D11（丢弃独立对话层）、D15（按页分块 + 高亮锚点）、D17（意图/类目 CRM 化）。
- 由 `com.mark.knowledge.*` 重打包为 `com.slz.crm.knowledge.*`：JPA→MyBatis-Plus、LangChain4j→Spring AI，授权判定逻辑保留、身份来源替换为 CRM userId。
- **保留 7 表新建**（借鉴 RAG schema，非机械迁 JPA），**丢弃** `chat_conversation`/`chat_message`（→ 助手 `ai_session`/`ai_message`）、`teacher_account`（→ `sys_user`）；表约定见 `db-table-coordination.md`。
- **流式问答/会话记忆/思考模式/图片理解与缓存**已移至 `ai-assistant`（助手吸收）；本域只提供被调用的检索/嵌入/文档能力。图片路检索所需的图片向量由 ai-assistant 在 KB 开启时提供。
- 检索/嵌入模型经平台 `ModelProvider` 抽象；检索参数、意图类目经 `dynamic-config` 运行期调整；检索质量基准与性能优化在 `platform-governance` 统一约束。
