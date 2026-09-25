# 规范差异：vector-store 非空搜索分数

## ADDED Requirements

### Requirement: Qdrant 非空命中必须映射为契约 double 分数
WHEN 生产 `QdrantVectorStore.search` 收到 Qdrant 的非空 `ScoredPoint` 列表,
系统 SHALL 将 gRPC `float` 分数数值转换为 `VectorSearchHit.score` 的 `double`，并保持每个命中的 id、文本、metadata 和顺序。系统 MUST NOT 对反射返回的装箱 `Float` 直接作 `Double` 引用强转。

#### Scenario: 非空命中
GIVEN 真正的 Qdrant 客户端返回至少一个带 payload、score 为 0.75f 的命中
WHEN 生产 store 映射搜索结果
THEN 返回的命中分数为从 0.75f 转换得到的 double 数值
AND chunkId、documentId、text、metadata 与 Qdrant payload 对齐
AND 不因 `Float`/`Double` 装箱差异抛 `ClassCastException`

#### Scenario: 空命中
GIVEN Qdrant 返回零个搜索命中
WHEN 生产 store 映射搜索结果
THEN 返回空列表
AND 不虚构分数或重试

#### Scenario: KB 过滤及顺序
GIVEN 搜索请求带有已授权 KB 的过滤条件和多个不同分数的命中
WHEN 生产 store 发出检索并映射结果
THEN 过滤条件、阈值与 topK 的传递保持不变
AND 返回结果保留客户端命中顺序及分数，不额外改变相关性语义

### Requirement: 度量入口不再以 shim 代替生产搜索
WHEN 本机隔离度量入口运行 Qdrant 搜索,
系统 SHALL 使用修复后的生产 `QdrantVectorStore.search` 完成非空路径验收，并保留其原有 opt-in、假模型和镜像预检边界。系统 SHALL 保留历史 shim 采样数字的原始证据级别，不将其重新标为生产搜索结果。

#### Scenario: 本地 Qdrant 搜索回归
GIVEN 本地已有镜像、一次性 Qdrant、固定检索种子和确定性模型桩
WHEN 显式执行本案回归及度量入口
THEN 不经测试侧 search shim 返回至少一个生产 store 搜索命中
AND 验证 KB scope、分数、文本和 metadata
AND 不运行付费模型或自动下载镜像

#### Scenario: 隔离环境不具备
GIVEN Docker 或本地镜像缺失
WHEN 尝试执行真实 Qdrant 回归
THEN 如实记录端到端验证未完成，不把内存桩或静态类型验证冒充真存储回归
AND 默认构建仍不启动容器或真实模型

#### Scenario: 报告修订
GIVEN 历史报告的采样通过测试侧 shim 完成
WHEN 记录修复后的新验证
THEN 原采样数值和 shim 口径保持不变
AND 新增带运行命令与结果的独立修复记录；未实测项标为未测
