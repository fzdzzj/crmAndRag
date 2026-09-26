# 提案：项目文件列表鉴权热路径测量与单因素优化

> 2026-09-26 拟案；编写时权威树 `master@31ba8c4` 仅为快照。实施前重查 HEAD、工作树和现行权限矩阵。**尚无该接口的请求级性能基线**，本案先测量，再有条件优化，不预设 N+1 必然是主瓶颈。

## Why

[ProjectFileServiceImpl](../../../src/main/java/com/slz/crm/server/service/impl/ProjectFileServiceImpl.java) 的 `queryPage` 先 `selectPage`，再对页内每行调用 `filterReadable → canReadProjectFile`，转换可见 VO 时又对该行调用一次相同入口，决定是否签发下载链接。全部行可读时，每 N 条记录有最多 2N 次判定的**代码形状**。[AttachmentAccessServiceImpl](../../../src/main/java/com/slz/crm/server/service/impl/AttachmentAccessServiceImpl.java) 每次判定先查用户；非超管继续走 [ProjectFileAttachmentReader](../../../src/main/java/com/slz/crm/server/service/impl/ProjectFileAttachmentReader.java) 的活动、商机、合同/订单、独立上传等记录级判定，部分分支还调用权限服务。`getUserName` 有现成 Caffeine 缓存，不能把 VO 上传人查询一律算作每行 DB 请求。当前尚未量到页内 SQL 次数、时延、冷暖缓存影响或真实用户可见比例。

**冻结的行为边界**：`queryPage` 的 total 为数据库条件总数，records 仅为该页内的可读子集，因此允许“页不足额/空页但 total 非零”；同一文件可挂多个归属维度，任一合法维度可读即可放行；超管与在职检查不得改变。下载链接签发时须判断授权，且 [PublicAttachmentController](../../../src/main/java/com/slz/crm/server/controller/PublicAttachmentController.java) 在真正下载时还会校验 JWT/令牌用户并重新执行当前记录级授权。把权限条件草率下推到 SQL 或跨请求缓存可能改变可见记录、total、签发时序或撤权效果。

**待验证假设**：若代表性页面负载显示用户/权限或关联业务记录的重复读取是稳定主因，且其优化不改变上述安全与分页契约，则只收敛这一类读取可能改善列表端到端延迟和吞吐。若主因不是这类调用、输入样本不可比或改动不能安全等价，交付测量与否定结论，不合入生产优化。

## What Changes

1. **先建立独立、安全基线**：在一次性本机 MySQL（现有 Flyway 建表、确定性本地用户/业务归属/项目文件种子）用生产 `ProjectFileServiceImpl` + 真实 Mapper、`AttachmentAccessServiceImpl` 和 `ProjectFileAttachmentReader`，避免用授权桩捏造 N+1。用本地测试密钥或签发桩仅在测试进程生成/校验令牌，绝不接真实附件、真实用户或生产库。非默认发现的显式 opt-in 入口，启动容器前检查开关、Docker、本地镜像，不自动 pull；缺失条件报告“未测”。
2. **固定矩阵与分段**：至少 10/50/100 页大小 × 全可见/部分可见/全不可见/超管；覆盖在职/离职、活动参与、商机归属、合同/订单、独立上传及一文件多维度。固定 `pageNum`、筛选字段、排序、页内行和权限快照，记录 total、records 顺序/ID、token 有无和下载再校验结果。按每请求分段统计分页 SQL（含 count）、用户状态、权限列表、关联业务查询、上传人名称、令牌签发、VO 组装与端到端 P50/P95、吞吐/错误和资源；冷缓存与暖缓存分别测，不把缓存命中导致的 SQL=0 冒充生产恒态。每条件至少两次独立执行；按实际占比点名一类最高因素，波动/重叠不能解释时标未知。
3. **仅有证据时实现一类窄优化**：如果重复用户/权限/关联记录读取单独领先且能够证明输出和安全等价，选择其中**占比最高的一类**在 `queryPage` 读取链内收敛调用（如一次请求范围的数据复用或有界批量读取），不同时改其他类别。禁止跨请求缓存活动用户、权限或业务归属；同一请求复用第二次授权判定必须先证明在撤权与链接签发时间窗仍满足原有语义，否则保留再次校验，只优化其下方安全可复用的数据库数据。不得改公开下载端点的实时复核。任何 SQL 下推若会改变 total/records/分页语义，需另案获 owner 裁定，本案不做。
4. **同负载复测与停机条件**：以同一矩阵和时间窗、相同缓存预热、种子/配置/并发比较新旧至少两独立执行；报告绝对 SQL 次数与细分、端到端 P50/P95、吞吐、错误/资源及权限负例。SQL 次数降低但端到端改善不稳定、P95/吞吐退化，或撤权/多维归属/令牌/total 口径变化时，停止生产实现的提交与合并，只留下真测量报告，不能以“少查了 N 次”追认加速。

## Impact

- **规范增量**：`specs/project-file-list/spec-delta.md` 新增代表性基线、权限等价和端到端验收要求；不改 [权限矩阵审计](../../../docs/permission-matrix-audit.md) 的端点矩阵及既有 API 契约。
- **潜在文件**：新 `src/test` 的隔离基准/守卫与权限等价测试、新 `docs/project-file-list-auth-hotpath-evaluation.md`；只有测量与安全门禁支持时才改 `server/service/impl/ProjectFileServiceImpl.java` 及当前授权链必要的窄协作组件。具体 Mapper 改动须在前测点名后再决定，不预设改所有读取端点。
- **禁止路径**：不动 `PublicAttachmentController`、`AttachmentDownloadTokenUtil`、权限常量/注解/矩阵、订单/合同/商机/活动写入逻辑、迁移/索引、依赖、动态配置、线程池/JVM/连接池、AI/RAG 检索摄取、前端或 API 响应结构。`listBy*` 四条路径仍按现状；如共享协作类修改会影响它们，须加回归或缩回范围。
- **成本与工作树**：本案只测本地 DB 与本地签名，不运行真实模型/embedding/reingest，不下载镜像、不推生产开关。本轮只写三件套，权威树未跟踪 `docs/backend-optimization-candidates.md` 不纳入本案；原 `D:\code\crmAndRag` 工作树不碰。此轮不提交、不合并、不 push、不归档；后续给子 agent 的任务卡可在一轮覆盖测量、条件实施、回归、门禁及验收成立时提交合并，不把每次 Git 操作拆成一轮。

## 验收与否决条件

- **主验收是列表请求端到端**，不是静态 2N 或 SQL 调用计数。先证实有稳定主因，后按同一负载证明 P50/P95、吞吐改善且零权限回归；噪声大、因子无独占领先或安全等价无法证明，均报告“未获支持/未知”，不交付生产优化代码。
- `total`、records 在原页内筛选后的 ID 与顺序、token 有无/绑定用户、不同用户可见结果及下载撤权再校验必须不变；不可把“不足额页面”修成新的分页口径。本案不授权数据范围口径变更。
- 默认 surefire/merge-gate 不发现 opt-in 容器测量；本机没有 Docker/本地镜像时不拉取也不伪造结果。所有实测分位、调用次数及环境差异写新报告；既有热路径数字不修改、不外推生产收益。
