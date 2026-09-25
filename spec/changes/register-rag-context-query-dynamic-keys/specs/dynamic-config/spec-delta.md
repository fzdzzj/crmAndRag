# 规范差异：dynamic-config

本文件约束动态配置命名空间白名单与 rag.context / rag.chunking / rag.query 键的登记。不改变各消费点在键缺失时的代码默认值。

## ADDED Requirements

### Requirement: 三个检索周边命名空间必须可登记
WHEN 动态配置注册表启动,
系统 SHALL 在 `DynamicConfigKeyRegistry.NAMESPACES` 中包含 `rag.context`、`rag.chunking` 与 `rag.query`。
系统 MUST NOT 删除既有五个命名空间。

#### Scenario: 八个命名空间齐全
GIVEN 注册表已加载
WHEN 读取 `NAMESPACES`
THEN 集合包含 `ai.prompt`、`ai.model`、`rag.retrieval`、`rag.intent`、`business`、`rag.context`、`rag.chunking`、`rag.query`
AND 不包含 `rag.reingest`

#### Scenario: 新命名空间下的键可以通过前缀自检
GIVEN 一个键名为 `rag.context.token-budget` 且 namespace 为 `rag.context`
WHEN 调用 `ConfigKeyDefinition.selfCheck`
THEN 不因非法命名空间失败

### Requirement: 文档已有的 context/chunking/query 键必须可被注册表识别
系统 SHALL 将下列 13 个键登记到对应命名空间，MUST NOT 改变各消费点在键缺失时的代码默认值：

- `rag.context.neighbors`（Integer，默认 1，范围 0~1）
- `rag.context.token-budget`（Integer，默认 4096，范围 1~100000）
- `rag.context.compressor.mode`（String，默认 `rule`，允许 `rule`/`llm`）
- `rag.context.compressor.llm.timeout-ms`（Long，默认 3000，范围 1~60000）
- `rag.context.parent-expand`（String，默认 `on`，允许 `on`/`off`）
- `rag.chunking.strategy`（String，默认 `fixed`，允许 `fixed`/`semantic`/`paragraph`）
- `rag.chunking.max-chunk-size`（Integer，默认 480，范围 1~10000）
- `rag.query.multi-query.enabled`（Boolean，默认 false）
- `rag.query.multi-query.variants`（Integer，默认 3，范围 1~5）
- `rag.query.hyde.enabled`（Boolean，默认 false）
- `rag.query.hyde.timeout-ms`（Long，默认 3000，范围 1~60000）
- `rag.query.derived-questions.enabled`（Boolean，默认 false）
- `rag.query.derived-questions.max-per-chunk`（Integer，默认 2，范围 1~5）

仅超管可通过既有动态配置管理入口写入已登记键。本案 MUST NOT 把费用相关键的默认值改为开启。

#### Scenario: 13 键可校验
GIVEN 注册表已加载
WHEN 对上表每一键调用 `definitionOf` 与合法值 `validate`
THEN 定义存在且校验通过
AND 登记默认值与上表一致

#### Scenario: 非法值仍拒绝
GIVEN 注册表已加载
WHEN `compressor.mode` 写入非 `rule`/`llm`，或 `hyde.enabled` 写入 `yes`，或 `multi-query.variants` 写入 0 或 6，或 `neighbors` 写入 2
THEN `validate` 失败，不得当作合法动态值

#### Scenario: 未列出的运维键仍未知
GIVEN 注册表已加载
WHEN 校验 `rag.reingest.trigger` 或 `platform.async.derived-questions.queue-capacity`
THEN 仍为未知配置键

#### Scenario: 缺省行为不变
GIVEN 数据库没有覆盖这 13 键
WHEN 检索与摄取代码按现有 `get(key, type, default)` 读取
THEN HyDE / 多查询 / 衍生问题仍默认关、压缩器仍默认 `rule`、切分仍默认 `fixed`、父块展开仍默认 `on`、邻居仍默认 1，与登记前一致

### Requirement: 平台配置页按新命名空间分组
WHEN 动态配置管理页展示配置项,
系统 SHALL 按 `rag.retrieval` 之后、`rag.intent` 之前的稳定顺序展示 `rag.context`、`rag.chunking`、`rag.query`，并使用中文标签。

#### Scenario: 有新命名空间数据时顺序稳定
GIVEN 接口返回 `rag.context`、`rag.chunking`、`rag.query` 与既有命名空间的配置项
WHEN 前端按 `NAMESPACE_ORDER` 分组
THEN 三组出现在 `rag.retrieval` 之后、`rag.intent` 之前
AND 标签不是原始键名

#### Scenario: 无新数据时旧分组不被破坏
GIVEN 接口只返回 `ai.prompt`、`ai.model`、`rag.retrieval` 三项
WHEN 前端分组
THEN 仍按既有顺序只展示这三组
