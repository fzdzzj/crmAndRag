# 规范差异：knowledge-base-authorization 单库 scope 基线

本案新增测试侧证据要求，不修改现有授权契约、生产 SQL 或 API。

## ADDED Requirements

### Requirement: 单库授权规模实验默认关闭且只在隔离真库运行
WHEN 执行单库 scope 授权基线,
测试系统 SHALL 使用生产迁移与 mapper、生产授权服务和本地一次性 MySQL；SHALL 在启动容器之前检查独立显式 opt-in、Docker 可用性和本地镜像存在性；MUST NOT 自动拉取镜像、读取业务数据库或触发真实模型外呼。默认单测、默认合并门禁 SHALL 不发现真库度量入口。

#### Scenario: 前提齐全
GIVEN 独立 opt-in 已开启、本地 Docker 和钉扎镜像均存在
WHEN 显式启动度量入口
THEN 按预登记矩阵运行且记录宿主、JDK、镜像身份与种子规模
AND 所有数据库连接仅指向测试自身创建的一次性容器

#### Scenario: 缺开关或镜像
GIVEN 未开启独立 opt-in 或钉扎镜像不在本地
WHEN 尝试启动度量入口
THEN 在容器创建和联网拉取前显式失败并报告未测
AND 不以跳过或旧日志冒充本次完成

### Requirement: 授权测量保留结果和权限边界
WHEN 测量 `authorizedKnowledgeBaseIds` 的单库、多库、空和无效 scope,
测试系统 SHALL 记录授权结果及顺序、owner/PUBLIC/member SQL 次数和取回行数；SHALL 以现行实现作等价快照，对超管、重复/溢出/非数字 scope、软删与成员孤儿引用作显式反例；MUST NOT 通过提前信任请求 scope 绕过授权或在本案修正返回语义。

#### Scenario: 单库请求仍枚举可见集合
GIVEN 非超管用户拥有多种可见来源且只请求一个有效库
WHEN 调用生产授权服务
THEN 返回值与当前授权求交结果和顺序一致
AND 真实查询次数、取回 ID 行数与耗时分别记录，不能仅由源码推断实际开销

#### Scenario: 无效或动态权限数据
GIVEN 请求含无效/重复 scope、成员软删或指向软删库的成员引用
WHEN 调用生产授权服务
THEN 结果按本轮生产实现实测并作为快照保存，不得静默“纠正”行为
AND 发现潜在越权或结果不一致时停止性能 GO，单列安全问题待独立定夺

### Requirement: 结论不超过测量证据
WHEN 对基线给出下一案优先级,
报告 SHALL 至少使用两次独立同条件执行，展示原始数据、样本数、SQL 计划、P50/P95/P99、吞吐、失败、漂移和可获取的资源/连接等待；SHALL 区分授权段微基准和请求端到端；未知资源不得填零。门禁不完整或环境不可用时 SHALL 记未测，不能宣称优化成功。

#### Scenario: 规模增长且授权段占比可证
GIVEN 真库授权数据随规模上升且独立复测完整
WHEN 查询行数和授权段耗时呈稳定上升并可与环境漂移区分
THEN MAY 提议下一张仅改单库授权查询的独立优化提案
AND 本案本身不改生产代码、不声明端到端或生产加速

#### Scenario: 指标持平或数据缺失
GIVEN 授权段未显著增长、反例失败或关键样本/计划/资源缺失
WHEN 作结论
THEN 标记不优先、未定或未测并列明缺口
AND 不转向调 JVM、池参数或批量远程调用掩盖证据不足
