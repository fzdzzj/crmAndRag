# 提案 · 向量运维端点统一收口 (unify-vector-ops-endpoints)

> 档位：B 档（零 DB 迁移 / 零权限常量新增 / 零 LLM 新调用路径 / 零冻结契约触碰）
> 来源：`work/_paa-candidates-pool.md` §7 断点 3（L157-158）与优化清单第 1 项（L114-116 / L174）
> 基线：master@1c314b7，Surefire 1472 / Failsafe 118，前端 188 用例

## 1. 背景与目标

5 大实体的向量全量回埋（`reingestAll`）散落在各业务 Controller，无统一运维入口与观测面（§110 断点 3）。本卡收口：

1. **统一入口**：`KnowledgeAdminController` 新增 `POST /knowledge/admin/sync/{entity}` 与
   `POST /knowledge/admin/sync/all`，内部委托既有各实体回埋服务逻辑（不改回埋实现本身）；
   权限复用 KnowledgeAdminController 既有权限常量（Grep 确认，零新增）；
2. **前端运维卡片**：知识管理页新增向量库运维监控卡片——各实体入口按钮 + 最近同步结果
   （成功/失败计数）；展示能力以现成返回结构为准，**不为监控新建表或新端点**；
3. **旧散落端点处置**：各业务 Controller 的 `reingestAll` 端点保留（向后兼容）或标注
   `@Deprecated` 并在 openapi 描述注明统一入口——执行侧按 diff 最小原则选择，handoff 留痕。

## 2. 成本红线（硬约束）

- 端点收口是**入口统一**，不自动触发任何真实重嵌入；单测一律 mock 向量服务；
- **首次真实调用 `/sync/*` 属成本敏感操作（DashScope API），须 Owner 显式授权**，
  本卡交付不包含任何真实重嵌入执行。

## 3. 非目标

零迁移、零权限常量、零冻结契约；不新建失败队列表；不改各实体回埋服务实现。

## 4. 验收

- 收口端点单测（路由/权限锚/委托调用断言）+ 前端卡片渲染用例；基线 1472 只增；
  merge-gate 8/8（Docker 在线 9/9）；openapi 同步；前端三检 + Playwright 检查点。
