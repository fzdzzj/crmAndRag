# 提案：邻居上下文缺失 chunkIndex 时安全降级

> 2026-09-27 拟案。编写时权威树 `master@7440f48` 为快照；实施前重新实测 HEAD、工作树及现行代码。这里是静态可证明的失败条件，不是线上故障统计。先补确定性红灯，再做最小修复；本案不以性能优化为目标。

## Why

`NeighborContextSupport.chunkIndex(hit)` 在 metadata 不含 `chunkIndex` 或字段不是 `Number` 时返回 `null`；`fetchNeighborsBatched` 对这种命中不生成邻居查询目标，而 `appendWithNeighbors` 仍计算 `hitChunkIndex - 1` / `+ 1`，会拆箱空值抛 `NullPointerException`。对于负索引，批查同样跳过目标，但若同批其他命中已取回索引 0，当前拼装有机会错误地把这行当作该负索引命中的后置邻居。现有正常数字索引测试未覆盖这些边界。命中 metadata 的实际缺失频率及生产影响未知，不能冒充已观测线上事故。

另有一个与本缺口**无行为关系**的已入库文档矛盾：`docs/project-file-list-interleaved-ab-evaluation.md` 首页写“吞吐不升反降 6/6”，§7.4 的六个 B/A 比值全大于 1 且 §8 判“吞吐不降 6/6”。只修正首页措辞，不改历史测量、裁决或权限代码；文档勘误在同一执行轮以**独立 docs 提交**留痕，不混进修复提交。

## What Changes

1. **先红后绿**：经现有 `ContextBuilder` 生产拼装路径增加缺失字段、非数字字段及负索引回归，独立复现缺失/非数字触发异常；负索引须在同批含有效命中且存在索引 0 行的构造中证明不会误取邻居。记录原始失败信息，不用只搜索源码代替红灯。
2. **窄范围修复**：仅在 `NeighborContextSupport.appendWithNeighbors` 对无效索引作明确守卫，输出该命中的原文本（沿用现有 `strip` / null 文本处理），不追加前后邻居；不得改 `chunkIndex` 解析为字符串容错、不得修改有效索引 0/正数的 SQL 目标和拼装、候选排序、编号、父块优先或动态配置语义。批查/逐条回查的异常降级和有界查询逻辑不变。负索引与查询目标的既有“跳过”规则保持一致。
3. **边界等价**：用混合命中断言无效项不污染有效项的前后邻居、同页取舍/跨文档隔离及 `[n]` 编号；仅无效命中不得触发邻居 SQL；有父块文本时继续父块优先；邻居开关关闭的纯文本路径保持现状。定向测试和默认 merge-gate 全绿。
4. **另记文档勘误**：只把项目文件 A/B 报告首页“吞吐不升反降 6/6”改成与 §7.4 和 §8 一致的“吞吐 6/6 上升”；逐字确认其他数字、顺序和原始证据未改。作为同一轮内第二个独立 docs 提交，不形成另一轮只做 Git 操作。

## Impact

- **生产**：仅 `src/main/java/com/slz/crm/knowledge/retrieval/NeighborContextSupport.java` 的无效索引分支；正常命中与 API、数据库结构、费用开关均不变。
- **测试**：`src/test/java/com/slz/crm/unit/knowledge/retrieval/ContextBuilderTest.java` 和必要时现有等价测试内新增反例，不启动真实模型/外呼。
- **规范**：`specs/context-assembly/spec-delta.md` 新增无效 metadata 的降级要求；不放宽 `update-context-snapshot-batch-read` 的快照查询与输出等价约束。
- **文档**：仅 A/B 报告摘要一处措辞勘误，不能以此扩大或削弱本机有条件 GO。

## Out of Scope

修复历史数据、推断线上发生率、改变检索排序/候选过滤/索引写入协议、批查重构、额外缓存或性能调参、真实模型及业务库外呼、push、归档和对受保护未跟踪文档的任何操作。
