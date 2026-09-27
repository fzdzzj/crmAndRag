# 提案：收紧知识库成员可见集的有效库边界

> 拟案日期：2026-09-27；观察起点为权威树 `master@fba022b`，实施时须重新实测。此案修正确认的授权集合不一致；“请求级数据泄露”仍须先经红灯证明，不以静态推断代替复现。

## Why

`add-knowledge-base-scope-auth-baseline` 的本机真 MySQL 反例显示：`KnowledgeBaseAuthorizationService.visibleKnowledgeBaseIds` 的成员分支只过滤成员行的逻辑删除，不检查被引用的 `knowledge_base` 是否存在且未软删，导致软删库和孤儿库 ID 进入非超管可见集。现行 `KnowledgeAdminService.listFiles` 可直接按该集合查询未删的 `uploaded_file`，`KnowledgeRetrievalServiceImpl` 将该集合交给向量及稀疏召回，召回 SQL/向量过滤也未再核查 `knowledge_base.is_deleted`。因此若失效库仍留有文件、切片或向量，存在可达的越界读取路径；实际请求是否返回内容尚未由已有基线证明。本案先补红灯，再做安全修复，不接着做授权性能优化。

## What Changes

1. 在本地一次性真 MySQL（生产迁移链、生产 mapper）造三组对照：有效成员库；库已软删但成员行仍有效且留有未删上传文件/切片；孤儿成员引用（如需测试返回数据，显式造孤儿文件）。先在**修复前**跑授权集合、`KnowledgeAdminService.listFiles(kbId/null)` 与稀疏召回的请求/服务真实路径，留下红灯原文；向量路用本地确定性假 embedding/假 store 或本地 Qdrant，证明失效 KB 是否进入 search filter，零真实模型费用。不能仅重跑旧基线的“ID 在集合”断言冒充端到端泄露证据。
2. 把有效成员 ID 收敛到**真实存在且未软删的库**；owner/PUBLIC 已由实体逻辑删除过滤，超管路径及可读/可写角色语义不放宽。选择最小的查询/批查方案，保留当前 owner→PUBLIC→member 的有效 ID 相对顺序与去重；不得按成员循环逐库查库（N+1），大量成员 ID 时查询参数有界；校验查询出错要失败关闭，绝不回退到原宽集合。若直接传入 `isDeleted=true` 的实体给 `canRead`/`canWrite` 可被放行，同案给该入口补显式拒绝并测试，正常实体的既有角色矩阵不变。
3. 修后跑相同真库存证：失效和孤儿 ID 从可见/授权集合消失；保留的文件/切片/向量不再通过这条授权链被列出或召回；有效 owner/PUBLIC/member、重叠去重、超管、无效/重复/空 scope、有效文件和检索输出仍保持。先前的 `KnowledgeBaseScopeAuthBaselineBenchmark` 锁定了旧漏洞作为历史快照，必须把它的**当前行为断言**改为修后契约并显式说明历史报告不可追认；原报告原始数字和日志不得改写，可追加修复索引。
4. 新增 `docs/knowledge-base-member-visibility-security-evaluation.md`，逐路径记录红/绿证据、剩余风险与未跑项。修复以授权正确性验收，不把新增 SQL 或改写后的延迟冒称优化；真正的并发删除与读取交错（授权后才删库）不是本案已证明解决的语义，须单独标为未验证/后续审计项。

## Impact

- 生产改动预计限于 `KnowledgeBaseAuthorizationService`（必要的有界批查可小幅扩展 mapper）；本案测试、已有基线入口行为断言、报告及三件套同案交付。调用侧代码只有红灯证明中央修复不能收口时才允许最小必要调整，并须先说明原因和新增等价反例。
- 不改冻结契约、端点权限常量、迁移链/索引、动态配置、前端、模型 Provider、重试/线程池/JVM、费用开关。不触碰只读 `D:\code\crmAndRag` 与受保护未跟踪 `docs/backend-optimization-candidates.md`。
- 新增测试按 `openspec/git-workflow.md` §4：真实离线 `clean verify` 后由 `check-test-baseline.sh --update` 重写基线，不能使用旧 `target` 数字或手填。默认 merge-gate 不含 IT，需单独报告本轮定向 IT 与全量 verify 的结果。

## 证据闸门、非目标及反例

- **红灯闸门**：在修前的真库存证中至少证明中央授权集合宽于有效库集合；同时验证一个下游路径在保留数据时能否返回信息或触发失效 KB search。若下游仅返回空或未进入失效库查询，不得声称“数据泄露已实证”，也不得按本案既定安全影响继续合并；先重新审视种子、下游机制及影响面，必要时修订提案后再实施。
- **绿灯闸门**：相同种子修后失效引用被排除，合法用户结果、顺序与错误边界不退化；成员数高于单条 IN 限额时不得出现无界 SQL，失败不放权。仅把 `listBases` 额外 `is_deleted` 过滤留在下游而不修成员可见集，不能算闭环。
- **最强反例**：库在授权集合生成**之后**被并发软删，本案的前置过滤不能保证后续文件/向量读的原子撤权；不得把稳定状态下的红绿扩大成线性一致性声明。此竞态若在本案测试中重现越界，要单列可复现问题并经 owner 定夺更强的读时校验/事务边界，不能隐瞒或盲目加长事务。
- **非目标**：不证明生产数据已泄露、不宣称生产加速、不跑真实模型、不为此案新增迁移或自动修复历史孤儿数据。真实部署是否有该残留数据及访问日志需另行授权审计。

