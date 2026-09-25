# 规范差异：request-hotpath 并发波动归因

## ADDED Requirements

### Requirement: 诊断模式必须隔离默认路径和费用边界
WHEN 运行 c8 波动诊断,
系统 SHALL 在既有非默认发现入口之外要求独立诊断选项，且复用本机一次性 MySQL/Qdrant、镜像预检和确定性模型桩。系统 MUST NOT 因环境里有真实密钥或旧真外呼开关而发起真实模型请求。

#### Scenario: 默认构建
GIVEN 未显式选择诊断入口及其独立选项
WHEN 执行 `mvn test`、`mvn verify` 或默认 merge-gate
THEN 不运行本案诊断或启动本案容器

#### Scenario: 不满足隔离条件
GIVEN 诊断选项已传入，但本案 opt-in 未开启、镜像缺失或 Docker 不可用
WHEN 初始化度量
THEN 在任何模型请求和业务数据库连接前停止
AND 不下载镜像、不报告诊断成功

### Requirement: 对照 c8 预热与顺序时一次只变一个因素
WHEN 比较 c8 并发档跨轮差异,
系统 SHALL 保持 4 个授权 KB、查询、topK、数据规模、样本数和存储实现相同，记录原序列与受控变体各自的起始状态。

#### Scenario: 顺序预热与并发预热
GIVEN 两实验都使用同一工作量和相同预热次数
WHEN 比较原单线程预热与 8-worker 同步预热
THEN 仅预热并发性变化
AND 对照报告区分冷态、暖态和计量窗口

#### Scenario: 阶段顺序反转
GIVEN 已完成预热方式的独立对照
WHEN 另测 c8→c1 与 c1→c8 顺序
THEN 预热方式不随顺序一起变化，起始种子/容器状态可比
AND 两种因素的效果不得合并成一个因果结论

#### Scenario: 不可比状态
GIVEN 对照两侧命中、SQL/向量调用次数或起始数据规模发生漂移
WHEN 汇总数据
THEN 标注不可比并给出差异证据
AND 不据此宣布 c8 波动已归因

### Requirement: 请求级分段与尾延迟必须关联
WHEN 8-worker 并发档运行,
系统 SHALL 将每个请求的阶段、worker、端到端时间、失败与连接等待绑定同一标识并报告分位/慢尾样本，而不是仅用窗口平均分段推断尾延迟。系统 SHALL 同窗记录可得的进程资源与诊断开销，缺失项标未知。

#### Scenario: 慢请求属于连接等待
GIVEN 某个请求 p95 以上且连接获取显著变长
WHEN 分析其阶段轨迹
THEN 可直接看到该请求的连接等待、对应 SQL/Qdrant 分段及端到端时间
AND 不以跨请求全局平均替代它的耗时

#### Scenario: 线程内标识清理
GIVEN 度量线程复用 worker
WHEN 一个请求正常返回或抛异常
THEN 下一请求不继承前一请求的计时标识或失败状态
AND 已完成样本计数与实际成功/失败数一致

#### Scenario: 无法解释并发波动
GIVEN 两次独立执行的 c8 分位仍不稳定或关键等待段缺测
WHEN 报告下一步
THEN 保留“无法归因”，列出待补证据
AND 不因本机结果改变生产连接池、JVM、GC 或检索链路
