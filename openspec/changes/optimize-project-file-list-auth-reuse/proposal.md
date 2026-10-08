# 变更提案：项目文件列表行级鉴权同请求复用消解重复判定（optimize-project-file-list-auth-reuse）

## 1. 背景与问题定义
在 `ProjectFileServiceImpl`（master@7d64b69 实测）的 5 个列表读取路径中存在同一行被重复鉴权的调用形状：
1. `queryPage`（L235-252）、`listByActivityId`（L255-262）、`listByOrderId`（L265-272）、`listByContractId`（L275-282）、`listByOpportunityId`（L285-292）均先 `selectPage`/`selectList` 取行，交 `filterReadable`（L318-323）对每行调用 `attachmentAccessService.canReadProjectFile` 过滤；
2. 随后对保留的每一行经 `entityToVO`（L326-347）再次调用同一判定（L336）决定是否签发下载令牌；
3. 单请求扫描 N 行、保留 k 行时产生 N+k 次判定调用；全可见页恰为 2N 次；
4. 每次判定成本（`AttachmentAccessServiceImpl` L241-256 + `ProjectFileAttachmentReader` L58-126）：恒定 1 次 `userMapper.selectById`；非超管再走维度判定——每维度 1 次 `permissionService.hasPermission` 联查加 1-2 次维度实体查询（活动/商机/合同/订单项）；
5. `docs/backend-optimization-candidates.md` §4 已将该形状登记为候选，并明示「先评估同一请求内安全复用已通过的授权判定，随后才讨论把过滤和分页下推」。

## 2. 改进方案与架构设计
1. **同请求判定复用**：5 个列表方法收敛到统一的私有转换链——每行可读性判定恰 1 次（`filterReadable` 语义不变），保留行的下载令牌签发直接依据该判定结果，不再二次调用授权服务；
2. **安全等价论证**：下载令牌只是便利性输出，真实下载在 `PublicAttachmentController`（L139）对每次下载独立复核；原第二次判定仅覆盖「同请求内权限被改派」的微窗口竞态，其防御价值由下载端复核兜底。复用严格限于单请求内，不引入任何跨请求的角色/权限缓存；
3. **口径保持**：`total` 保留数据库条件总数、records 仅含可读行的现有分页口径不变；全无权时 records 允许为空而 total 仍非零（该口径已被 `ProjectFileListAuthHotpathGuardTest` 锁定）；
4. **收益形状（代码可见，非生产耗时承诺）**：全可见页判定调用 2N → N（消解 50%），每次节省的判定同时省去其携带的至少 1 次用户查询与非超管维度联查/实体查询；部分可见页 N+k → N。

## 3. 约束边界与非目标
- **零 DDL**：不碰 `src/main/resources/db/migration/` 下任何迁移脚本；
- **零实体/POJO/DTO/VO 改动**、**零 OpenAPI 契约改动**（`frontend/openapi.yaml` 禁碰）、**零新增依赖**；
- **判定语义冻结**：不修改 `AttachmentAccessService`/`AttachmentAccessServiceImpl`/`ProjectFileAttachmentReader`/`PublicAttachmentController`，判定矩阵由既有 `AttachmentAccessServiceTest`（L334-425）锁定；
- **非目标**：分页/过滤下推 SQL（可能改变 API 可见结果，须先单独定夺契约）、批量判定 API 扩展、跨请求缓存、`total` 口径调整、上传人姓名逐行转换（`dataConvertService.getUserName`）的批量化（另行评估）；
- 受保护未跟踪文件 `docs/backend-optimization-candidates.md` 只读不碰。

## 4. 验证与验收标准
1. 新增 `ProjectFileServiceImplTest` 专属单测，采用红绿协议：未改主代码上先实测红（`canReadProjectFile` 恰 N 次的断言在旧形状 N+k 上必红），实现后转绿；
2. 覆盖：混合可读页仅返回可读行且签发令牌、全无权页 records 空而 `total` 保留库内总数、4 个 `listByXxx` 路径判定次数、上传人姓名转换仍生效、空列表零调用；
3. 全量单测 0 失败 0 跳过（920 只增不减）、四静态门禁 0 违规、基线台账经 `check-test-baseline.sh --update` 真实增量写回、换行与写集守卫通过；
4. 可选：opt-in 请求级基准（`PROJECT_FILE_LIST_HOTPATH_MEASURE=1`，本地 Docker `mysql:8.0`，零外呼零真实模型）前后对照，未跑须在未跑项中明列。
