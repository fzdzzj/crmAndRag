# 提案：单库 scope 知识库授权路径基线

> 拟案日期：2026-09-27；观察起点：权威树 `master@838d5e9`。实施前必须重新检查 HEAD、状态及在途变更。本案只增加可复现实验和报告，不批准生产授权改写。

## Why

`KnowledgeBaseAuthorizationService.authorizedKnowledgeBaseIds` 当前先通过 `visibleKnowledgeBaseIds` 收集全部可见库，再与请求的 scope 求交。非超管路径分别查询 owner、PUBLIC 和成员库；即使只请求一个库，当前调用形状仍会枚举全部可见 ID。既有四库本机检索中授权 SQL 是三次，但没有 128/512 等规模下的行数、计划、尾延迟或请求占比证据。代码形状不等于生产瓶颈，更不等于可以把 scope 提前用于放权。

历史候选清单 `docs/backend-optimization-candidates.md` 是 2026-09-26 的未跟踪只读快照，其中快照批读、摄取批写、项目文件鉴权、无效 chunkIndex 等项之后已分别落案；本案只取其第 5 节未验证的知识库授权问题，不改该受保护文件。

## What Changes

1. 新增默认测试发现规则不会运行的独立 opt-in 真 MySQL 度量入口及常规 surefire 守卫；测试侧使用生产 Flyway 迁移、MyBatis-Plus mapper、`KnowledgeBaseAuthorizationService`，不改 `src/main`。缺开关、Docker 或已存在镜像时须在创建容器/拉取镜像/连接外部库前显式停止并报告未测。
2. 固定少/中/多规模（建议 4/128/512 库；若资源不足，事先调整并记录，不事后筛选）与固定 owner/PUBLIC/member 分布，分别采集非超管和超管、单库/多库/空/无效 scope 的返回 ID、三类查询次数及取回行数、SQL 计划、授权段 P50/P95/P99、吞吐、失败、连接等待与可获得资源；同机至少两条独立命令，各含稳态轮，保留全部原始输出。控制种子、索引、软删状态和样本次序；不同规模必须报告数据行数。
3. 为无效/重复/溢出 scope、软删、孤儿成员引用、owner/PUBLIC/member 重叠、超管和空 scope 增加结果及顺序快照断言。孤儿成员是否可能出现在结果中应**按现行实现如实记录**，不得把实验偷偷变成安全修复。授权段微基准不得宣称请求端到端收益；若能复用代表性检索入口，单独报告同负载请求占比与局限，不能混入授权段数值。
4. 新增 `docs/knowledge-base-scope-auth-baseline.md`，含机器/JDK/镜像 digest、命令、样本量、原始日志位置、SQL/EXPLAIN、噪声和统计口径。结论只可为“值得单独提出受限单库授权改造”“证据不足/未定”或“不优先”，不能在本案实施优化、增索引或声明生产加速。

## Impact

- 预计新增：`src/test` 度量入口与守卫、上述报告、此三件套；若新增常规测试，用**本轮真实 `mvn -o -B -ntp clean verify` 报告**经 `scripts/check-test-baseline.sh --update` 更新基线，不能手改或借用旧 `target` 报告。
- 禁区：`src/main`、已合入 Flyway、依赖、动态配置、CI、权限/可见性/排序语义、JVM/连接池/线程池、真实模型和外部业务库；`D:\code\crmAndRag` 仅只读；未跟踪 `docs/backend-optimization-candidates.md` 不触碰。
- 不改变任何运行时 API 或费用；实验只能在一次性本地 MySQL 与确定性测试数据上进行。真容器、`clean verify` 和默认 merge-gate 是不同覆盖面，分别报告；未跑不能写成通过。

## 决策闸门与最强反例

- 单库 scope 可能仅返回一个 ID，但历史实现允许成员查询直接返回被软删 KB 的引用；“提前查存在性”即使更快也可能改变当前结果。本案只测不修；后续若涉及权限漏洞，应独立安全定夺，绝不把行为差异藏在性能改写里。
- 三次 SQL 在大规模时可能仍很快；反之 SQL 次数固定但取回行数放大。必须同时看行数、计划、授权墙钟、漂移与资源，不以形状或单次最快结果立 GO。
- 单库授权查询即使更快，也不能用授权段收益冒充检索端到端收益；生产数据量和真实 RTT 保持 unknown。环境不具备时 fail closed，不下载镜像，不提交虚构测量报告，不将 tasks 标完成或强行合并。
