# 规范差异：request-hotpath 本机隔离度量

本规范在 `add-request-hotpath-baseline` 合成基线之上增加**单独的、非默认执行**度量能力，不修改旧基线数字与调用断言。

## ADDED Requirements

### Requirement: 显式授权执行且无付费模型路径
WHEN 运行本机隔离度量入口,
系统 SHALL 要求独立的显式 opt-in，在任何容器或流水线装配之前校验运行边界，并将所有模型操作绑定本地确定性假实现。系统 MUST NOT 使用真实 Provider、读取真实 API key、自动拉取容器镜像或在默认单测/集成测试/merge-gate 中启动本案。

#### Scenario: 默认构建
GIVEN 没有本案度量 opt-in
WHEN 执行 `mvn test`、`mvn verify` 或默认 merge-gate
THEN 度量入口不被测试发现
AND 不启动本案容器、不调用模型服务

#### Scenario: 镜像或隔离条件不满足
GIVEN 显式指定度量入口，但 Docker/本地镜像缺失或装配指向非一次性环境
WHEN 开始执行
THEN 在发出模型请求或连接目标数据库前失败
AND 报告“未测”，不得下载镜像或假装成功

#### Scenario: 环境中留有真实模型开关或密钥
GIVEN 环境/仓库 `.env` 存在真实模型开关或密钥
WHEN 运行本案度量
THEN 入口不能因此切换真实 Provider
AND 不读取、记录或调用该密钥对应的服务

### Requirement: 同负载核对且分隔读写样本
WHEN 准备检索与摄取样本,
系统 SHALL 固定授权、查询、topK、语料、分块策略及有效配置，并分别记录预热和测量阶段的数据规模及调用形状；摄取写入不得改变后续被比较的检索种子或样本初始状态。

#### Scenario: 检索负载可比性
GIVEN 4 个已授权 KB、topK=5 与旧案相同的单路查询
WHEN 用本机真实 MySQL/Qdrant 运行生产检索实现
THEN 报告返回命中、SQL/嵌入/向量调用次数及实际 KB 过滤条件
AND 与旧案输入或调用形状不一致时标“不具可比性”，不能覆盖旧基线

#### Scenario: 摄取重复测量
GIVEN 每次输入同一份 fixed 320/40、6600 字符文档
WHEN 重复摄取测量
THEN 计时样本的起始数据规模一致，清理或复位位于计时窗口之外
AND 检索种子库不受写入影响

### Requirement: 同窗分段、吞吐与资源测量
WHEN 度量同一负载,
系统 SHALL 对每种并发档分别记录端到端墙钟分位、成功吞吐、错误/超时、实际 SQL 与向量调用分段、模型桩调用、以及进程资源/GC 信息，并说明计时口径、采样窗口与观测开销。未采集的项 SHALL 标为未知，而非零。

#### Scenario: 授权和改写不再成为隐形桶
GIVEN 查询会调用授权和查询改写
WHEN 导出请求度量
THEN 授权 SQL 与查询改写桩耗时单独可见
AND 不将假改写的零 RTT 推断成真实 LLM RTT

#### Scenario: 并发与尾延迟
GIVEN 固定的单并发和另一固定并发检索档
WHEN 完成预热和稳态窗口
THEN 分档报告 p50/p95/p99、样本数、成功请求/秒与失败数
AND 报告 CPU、堆、GC、线程的采样口径以及可取得时的锁等待和容器资源

#### Scenario: 分段重叠或采样缺失
GIVEN 并发下有重叠分段或缺少 GC/锁采样
WHEN 比较耗时占比
THEN 只在同窗、同口径、可解释重叠的类别之间比较
AND 不将差额命名为 CPU/业务规则耗时，不把未观测记为零

### Requirement: 本机证据不越界为生产优化授权
WHEN 汇总测量报告,
系统 SHALL 分开标注合成假端口、本机真实存储加模型桩和未来真实 Provider 三种证据级别。系统 MUST NOT 将本机结果当作生产外呼 RTT、生产瓶颈定论或费用授权。

#### Scenario: 本机存储耗时领先
GIVEN 本机 MySQL 或 Qdrant 分段占比最高
WHEN 选择下一张生产改动提案
THEN 仅把该分段列为待验证候选
AND 下一类生产优化因素仍待同负载代表性/真实环境确认

#### Scenario: 需要真实模型 RTT
GIVEN 模型端口仍为确定性本地桩
WHEN 解释远程 Provider 时间与费用
THEN 标为未观测
AND 真实调用、最大成本与停止条件另经 owner 授权，不由本案自动启动
