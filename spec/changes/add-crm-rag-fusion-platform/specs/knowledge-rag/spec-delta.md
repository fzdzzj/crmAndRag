# 规范差异：knowledge-rag（知识库与 RAG 能力）

本文件包含对 `spec/specs/knowledge-rag/spec.md` 的规范变更。

## ADDED 需求

### Requirement: 知识库与成员授权
系统 SHALL 提供知识库的所有者、成员、角色与公开范围模型，且成员身份 MUST 绑定 CRM 稳定 userId。

#### Scenario: 创建知识库并授权成员
GIVEN 已登录用户 U1 创建知识库 KB1
WHEN U1 添加成员 U2 并指定角色
THEN 系统记录 KB1 的 owner 为 U1、member 为 U2（均以 CRM userId 表示）
AND U2 可按角色访问 KB1

#### Scenario: 非成员访问被拒
GIVEN 用户 U3 不是 KB1 的成员且 KB1 非公开
WHEN U3 请求 KB1 的文档或问答
THEN 系统拒绝访问并返回统一越权错误

#### Scenario: 公开范围访问
GIVEN KB1 的可见性设置为公开
WHEN 任意已登录用户请求 KB1 的问答
THEN 系统允许在公开范围内访问
AND 公开语义为“所有已登录用户可见”，不提供匿名访问

### Requirement: 仅登录访问（移除匿名态）
系统 SHALL 要求所有知识库、文档与问答接口均在 CRM 登录态下访问，MUST NOT 保留匿名/未登录问答链路。

#### Scenario: 未登录访问被拒
GIVEN 请求未携带有效登录态
WHEN 访问知识库问答或文档接口
THEN 系统拒绝并返回统一未授权错误
AND 不进入任何检索或生成流程

#### Scenario: 匿名链路移除
GIVEN 原 RAG 存在匿名问答服务（AnonymousRagChatService）
WHEN 迁入融合平台
THEN 系统移除匿名问答链路，问答统一走已认证路径
AND 公开知识库仅对已登录用户开放

### Requirement: 文档入库与多格式处理
系统 SHALL 支持文档上传、多格式解析、OCR/视觉抽取、分块与向量化入库，并 SHALL 为单文件上传写入知识库归属。

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
THEN 系统将该文件标记为可恢复状态（PENDING）而非永久失败
AND 支持后续重试恢复

### Requirement: 混合检索与授权过滤
系统 SHALL 提供向量与关键词（BM25）混合检索，并 SHALL 在检索时按调用者授权过滤，过滤 MUST 采用按需查询而非全表扫描。

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
GIVEN KB1 含带图文档（图片经 OCR/视觉理解抽取为文本并嵌入同一向量集合）
WHEN 用户发起图文混合检索（可携带查询图片）
THEN 系统并行执行文本路召回与图片路召回（图片路用图片理解后的查询向量独立召回）
AND 按可配置权重（默认文本 0.7 / 图片 0.3）加权融合、按片段去重后排序
AND 返回结果的来源引用分别标记为 TEXT 与 IMAGE 类型

### Requirement: 流式问答与会话记忆
系统 SHALL 提供基于检索增强的流式问答（SSE），并 SHALL 维护会话记忆；流式接口 MUST 与平台统一的超时、心跳、取消与 traceId 口径一致。

#### Scenario: 流式回答
GIVEN 用户对 KB1 提问
WHEN 系统生成回答
THEN 系统通过 SSE 增量下发回答内容
AND 附带来源引用（source references）

#### Scenario: 同会话新请求接管
GIVEN 用户在同一会话中于生成过程发起新问题
WHEN 系统受理新请求
THEN 系统取消旧的流式生成并按新请求继续
AND 旧连接收到停止事件

#### Scenario: 思考模式与提示词预算
GIVEN 请求携带思考模式标志
WHEN 系统构造模型请求
THEN 系统按开关决定是否透传思考内容
AND 写入记忆与历史的文本恒定剥离思考块
AND 整份提示词不超过配置字符预算，超出时从最旧会话历史淘汰

#### Scenario: 记忆旁路拒绝降级
GIVEN 记忆旁路线程池队列已满
WHEN 提交新的旁路任务被拒绝
THEN 系统跳过本轮旁路任务（降级）
AND 不影响主问答流的正常返回

### Requirement: 对象存储与健康检查
系统 SHALL 使用对象存储（MinIO，S3 兼容）保存原始与派生文件，并 SHALL 提供向量库与对象存储的健康检查与本地回退。

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

#### Scenario: 依赖健康检查
GIVEN 应用运行中
WHEN 健康检查探测 Qdrant 与 MinIO
THEN 系统报告各依赖的健康状态
AND 依赖不可用时就绪判定为不健康

### Requirement: 向量库抽象与本地回退
系统 SHALL 通过统一的向量库抽象接入向量存储，默认使用 Qdrant，并 SHALL 提供内存回退实现用于本地开发与测试。

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

- 本能力域对应决策 D3（认证/授权绑 userId）、D4（Spring AI + dashscope，可插拔 Provider）、D7（Qdrant+MinIO）、D8（移除匿名态）、D9（VectorStore 抽象 + 内存回退）。
- 知识库能力由 `com.mark.knowledge.*` 重打包为 `com.slz.crm.knowledge.*`，JPA→MyBatis-Plus、LangChain4j→Spring AI，授权判定逻辑保留、身份来源替换。
- 检索/嵌入模型经平台 `ModelProvider` 抽象（默认 dashscope），检索参数可经 `dynamic-config` 运行期调整。
- 检索质量基准与性能优化在 `platform-governance` 能力域统一约束。
