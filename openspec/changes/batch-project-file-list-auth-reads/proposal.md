# 提案：batch-project-file-list-auth-reads（卡 P-t）

## 为什么
项目文件五个列表方法（`queryPage` + `listBy*`×4）的记录级鉴权虽经 P-o 把判定调用收敛为每行 1 次，但每次判定内部仍是 O(k) 次 SQL：1 次 `sys_user` 读 + 每维度 1 次权限链联查 + 1 次维度实体 `selectById` + 参与人 `exists`。热路径评估实测（S2 口径）ALL-100 页 auth SQL ≈ 550 次/请求、端到端 P50 ~880ms——同一请求内对同一用户行、同一维度实体反复单读是纯粹的往返放大。owner 已定夺（2026-10-07）：`/query` total 语义维持冻结（前端零消费 `/query`，全 frontend grep 实测零命中，不做分页下推），方向取 B1 保守批量化。

## 改什么
`AttachmentAccessService` 新增批量过滤入口：列表链路一次 `sys_user` 读完成状态/超管整组判定，非超管走 `ProjectFileAttachmentReader` 批量维度判定——维度实体与订单项按 ID 去重后 `selectBatchIds` 预取（≤4 次）、参与人 1 次 IN 查询，随后**逐行**套用与单行入口逐字一致的判定矩阵（activity→opportunity→contract→独立上传→reservedRead）。`ProjectFileServiceImpl.toReadableVOs` 改调批量入口。预期 SQL 形状：`auth/user` N→1、维度实体读 N×维度→≤4、参与人 N→1；`auth/permission` **保持逐行实时不减**（B1 红线：不引入请求级权限快照，S1 教训）。

## 不改什么
- 任何 API 契约：`/query` 的 total=库内条件总数语义（三把锁：单测 total 断言 / 守卫 `emptyPageKeepsDatabaseTotal` / 基准 `PFLHOT frozen` 指纹）、四 list 端点 List 形状、Page 字段、OpenAPI 契约；
- 单行入口 `canReadProjectFile` 行为（`PublicAttachmentController` 下载复核与 `AttachmentModelScopeChecker` 共享路径）；
- 权限判定的逐行实时性（角色改派交错回归必须原样绿）、下载令牌签发与复核边界、VO 转换、排序；
- DDL、依赖、frontend、基准期望类与冻结指纹语义。

## 验收口径（owner 拍板）
形状 + 零回归 + 交错 A/B：SQL 分类计数前后对照、权限探针/交错回归逐字对照零回归、全量单测只增不减；另跑同机交错 A/B（PRE=`a923bfd` 纯复制 vs POST=本实现，每臂 ≥5 run + 安慰剂/阴性对照，方法论沿用 `add-project-file-list-interleaved-ab-attribution`），可据此宣称本机加速。评估报告落 `docs/project-file-list-auth-batch-evaluation.md`。
