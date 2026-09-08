# 多 Agent 执行拆分与并行计划

> 目的：把 `tasks.json`（17 任务 / 6 阶段）重排为**可分派给多个子 agent**的执行计划，明确波次、并行 lane、契约冻结、文件归属、冲突规避与集成时序。
> 结论先行：**这份提案作为"规范"是完备的，但原 6 阶段是"单人线性推进"假设**；要多 agent 并行，必须补上本文件的四样东西：依赖波次、契约冻结、文件归属、集成时序。

---

## 0. 重新评估结论（诚实版）

**优点（可直接用于拆分）**
- 能力域边界清晰（6 个 spec-delta），天然对应 lane。
- 决策已锁定（D1–D10），agent 无需再做架构选择。
- `migration-subtasks.md` 已把最重的 D2/D4 拆到方法级。

**不足以支撑多 agent 并行的缺口（本文件补齐）**
1. **无依赖图**：17 任务是线性编号，未标明谁阻塞谁。
2. **无契约冻结**：多处"缝合线"（身份、Result、ModelProvider、VectorStore、DataScope、SSE、Token 计量）被多个 lane 共用，若不先冻结接口，并行必然互相返工。
3. **无文件归属**：`pom.xml`、`application.yml`、`Flyway 脚本`、SSE/流式、`DataScopeResolver` 是**多 lane 争用的冲突热点**，未指定 owner。
4. **无集成时序与 integrator**：并行 lane 收敛时谁合并、按什么顺序合并未定义。
5. **规模不均**：任务 7（含 B3 流式，XL）远大于任务 15（S），单个 agent 会话装不下 XL，需要再切。

**已修复的真实缺陷**
- `tasks.json` 任务 1 原写"MyBatis-Plus + JPA 共存、LangChain4j"，与 D2/D4 矛盾 → 已改为"唯一 MyBatis-Plus + Spring AI(dashscope)"。

**核心判断**
- **空仓 + 基座是硬串行瓶颈**：任务 1–4 与契约冻结必须由**单一基座 agent 顺序完成**，不能并行。
- **关键路径 = RAG 迁移 lane（任务 7/8/9 + A/B 子任务）**，其中 B0 思考模式 spike 是全局 go/no-go 闸。
- **建议最大并行度 3–4 个 lane**；超过会因 `pom/yml/SSE/配置` 争用陷入 merge hell。

---

## 1. 依赖波次（Wave）

```
Wave 0  [串行·1 个基座 agent]   任务 1,2,3,4 + 冻结契约（§3）+ Flyway V1 基线
   │      产出：可编译可跑的空平台骨架 + CRM 全量并入 + 稳定接口契约
   ▼
Wave 1  [并行·3–4 个 lane]      ┌ Lane A 组织数据权限   任务 5,6        （低风险，可最先并行）
   │                            ├ Lane B RAG 迁移       任务 7,8,9 + A/B （关键路径，最长）
   │                            ├ Lane C AI 助手        任务 11（先行）,10（依赖 B 检索契约）
   │                            └ Lane D 平台治理骨架   任务 12（先行）,13,14（依赖 B/C 落点）
   ▼
Wave 2  [收敛·integrator]       Lane E 动态配置 任务 15（依赖 B/C/D 的配置读取点 + D 审计）
   │                            任务 16 质量基准（依赖 B 完成）
   ▼
Wave 3  [集成·integrator]       任务 17 契约/性能测试 + CI 门禁 + 服务拆分 + 灰度回退
```

**为什么 Wave 0 不能并行**：`pom.xml`、包结构、`Result`、`UserContext`、配置骨架是所有 lane 的公共依赖；多 agent 同时改基座 = 编译互相打断 + 契约漂移。

---

## 2. Wave 0 必须交付的"稳定契约"（并行前提）

基座 agent 在 Wave 0 末尾必须把下列接口**定义并冻结**（放在 `com.slz.crm.platform.contract` 或各域 api 包），各 lane 只实现/消费、不改签名：

| 契约 | 内容 | 消费方 |
|------|------|--------|
| `UserContext` / 身份 | 当前登录 userId(`user:<id>`)、roleId、deptId、数据范围；ThreadLocal + 异步快照传播接口 | A/B/C/D/E |
| `Result` + 错误码 | 统一响应封装、错误码枚举（含 RATE_LIMITED/QUOTA/UNAUTHORIZED/CONTENT_RISK） | 全部 |
| `ModelProvider` | `chatModel()/streamingChatModel()/embeddingModel()/visionModel()`；provider=dashscope(默认)/openai/vllm | B/C/E |
| `VectorStore` 抽象 | `QdrantVectorStore`(默认) + `InMemoryVectorStore`(回退)；统一 search/filter 接口 | B/D |
| `DataScope` 接口 | `getHighestDataScopeLevel/getSubordinateUserIds/…`（含 DEPT/DEPT_AND_CHILD） | A/C |
| SSE 事件契约 | 事件类型 `message/stopped/references/error/thinking` + 字段结构 + 超时/心跳口径 | B/C/D |
| Token 计量接口 | `record(model,user,session,kb,usage)`；B/C 产数，D 聚合预算 | B/C/D |
| 动态配置读取接口 | `DynamicConfigService.get(key, type, default)`（E 实现，B/C/D 消费） | B/C/D/E |
| Flyway 版本段分配 | 见 §5，避免多 lane 撞版本号 | 全部 |

> 契约冻结后如需变更，走"契约变更申请 → 基座/integrator 批准 → 通知所有 lane"，禁止单 lane 擅自改。

---

## 3. 并行 Lane 划分与文件归属（防冲突核心）

| Lane | 负责 tasks | **独占**的包/文件（owner） | 依赖契约 | 入口条件 | 出口条件 | 规模 |
|------|-----------|--------------------------|----------|----------|----------|------|
| **基座** | 1,2,3,4 | 项目骨架、`pom.xml`、`application.yml`、`com.slz.crm.common.*`、`com.slz.crm.platform.contract.*`、Flyway `V1` | — | 空仓 | 骨架可编译启动 + CRM 306 测试重建通过 + 契约冻结 | L |
| **A 组织数据权限** | 5,6 | `common.enumeration.DataScopeLevel`/`PermissionOperates`、`server.service.impl.DataScopeResolverImpl`、`MyDataPermissionHandler`、`pojo.entity.SysDeptEntity`、Flyway `V2x` | UserContext, DataScope | Wave0 完成 | 四级可见性 + 负责人跨子部门 + 越权测试通过 | M |
| **B RAG 迁移**（关键路径） | 7,8,9 + 子任务 A0–A7 / B0–B10 | `com.slz.crm.knowledge.**`（实体/mapper/服务/控制器）、Flyway `V3x`、`docker-compose` | UserContext, Result, ModelProvider, VectorStore, SSE | Wave0 + 契约 | 登录态下 上传→检索→流式问答 冒烟 + RAG 456 测试重建 | XL（建议再拆 B-持久化 / B-AI 两个子 agent，见 §6） |
| **C AI 助手** | 11（先行）,10（依赖 B） | `server.ai.**`、`AiChatServiceImpl`、`AiToolRegistry`、`ai_insight`、Flyway `V4x` | UserContext, ModelProvider, SSE, DataScope, Token | Wave0；任务10 另需 B 的检索契约 | 限流/references/洞察/评测通过；queryKnowledgeBase 越权不可检索 | L |
| **D 平台治理** | 12（骨架先行）,13,14 | `com.slz.crm.platform.**`（trace/async/resilience/quota/token/lifecycle/reconcile/audit）、Flyway `V5x` | UserContext, SSE, Token, VectorStore | Wave0 | 跨线程 traceId、配额拒绝、韧性、对账、审计测试通过 | L |
| **E 动态配置** | 15 | `com.slz.crm.platform.config.**`（dynamic-config）、Flyway `V6x` | DynamicConfig 接口, 审计(D) | Wave0 + D 审计骨架 + B/C 配置读取点 | 越权写拒绝/非法值拒绝/热生效/回滚测试通过 | M |
| **integrator** | 16,17 + 合并 | 集成分支、CI、契约/性能测试 | 全部 | 各 lane 出口达标 | 全量回归 + CI 门禁绿 + 发布检查 | L |

---

## 4. 冲突热点与规避策略

| 热点 | 争用 lane | 规避 |
|------|-----------|------|
| `pom.xml` | 基座/B(A6 移 JPA、B10 移 LangChain4j)/C/D | **基座 agent 独占**；其他 lane 提"依赖变更申请"，由 integrator 合并；禁止 lane 直接改 |
| `application.yml` | 基座/B/C/D/E | 拆分为 `application.yml`(核心) + `application-<lane>.yml`(命名空间) 或用 config 树分文件；**键归属按 lane 划分**，动态项迁到 E |
| Flyway 脚本 | 全部 | **预分配版本段**（§5），各 lane 只在自己号段内加脚本 |
| SSE / 流式 | B(RAG 流) / C(AI SSE) / D(traceId) | 冻结 **SSE 事件契约**；RAG 流 owner=B，AI SSE owner=C，D 只加 traceId 装饰不改事件结构 |
| ModelProvider / VectorStore | B 建 / C·D·E 用 | Wave0 冻结接口；B 提供实现，C/D/E 面向接口编程 |
| Token 计量 | B/C 产 / D 聚合 | Wave0 冻结 `record(...)` 接口；D 只消费不落各自实现 |
| `DataScopeResolver` | A 建 / C 用 | A 拥有；C 的 AI 工具查询面向 `DataScope` 接口 |

---

## 5. Flyway 版本段预分配（防版本号撞车）

| 号段 | Lane | 内容 |
|------|------|------|
| `V1__*` | 基座 | CRM + RAG 现有表基线（RAG 表 DDL 从 Hibernate 固化） |
| `V2x__*` | A | `sys_dept.leader_id` + DEPT/DEPT_AND_SUB 权限种子 |
| `V3x__*` | B | 知识库授权 userId 映射、`uploaded_file.knowledge_base` 回填、chat `username→user_id` |
| `V4x__*` | C | `ai_insight` 及助手相关表 |
| `V5x__*` | D | 配额、Token 计量、生命周期事件、对账账本、审计 |
| `V6x__*` | E | 动态配置项 + 版本历史 |

> 规则：号段内递增（如 `V31__`、`V32__`）；跨 lane 依赖的表由**被依赖方**建，消费方不重复建。

---

## 6. Lane B 再拆（XL 装不进单 agent 会话）

Lane B 是关键路径且 XL，建议拆 2 个子 agent + 1 个前置 spike：

- **B-spike（先，M）**：只做 `migration-subtasks.md` 的 **B0**——验证 Spring AI 能否等价承接思考块流式/自定义参数/Qdrant 过滤/维度统一。**产出 go/no-go 结论**，决定 B4 深度或降级。**这是全 Lane B 的闸，必须最先出结论。**
- **B-persist（A 线 A0–A7）**：owner = `knowledge` 的 entity/mapper/persistence 相关文件 + Flyway V3x 的持久化部分。
- **B-ai（B 线 B1–B10）**：owner = `knowledge` 的 ai/chat/embedding/vector/retrieval/stream 服务 + ChatConfig 重写。
- **协同点**：B-persist 与 B-ai 都会碰 `knowledge` 服务层（服务既调 mapper 又调模型）。规避：**先由 B-persist 完成实体+mapper+服务对持久化的调用改写并合入**，B-ai 再在其上做模型层替换；两者按"持久化先、AI 后"串行合入，避免同文件并发改。

---

## 7. 集成时序（integrator 合并顺序）

按"低风险先行、关键路径居中、横切最后"合并，每次合并后必须**编译 + 冒烟**再合下一个：

1. **Lane A**（数据权限，纯 CRM 内，最低风险）→ 合并、跑四级可见性测试。
2. **Lane B**（RAG，关键路径）→ 合并、跑 RAG 冒烟 + 456 测试。
3. **Lane C**（AI 助手）→ 合并、跑 queryKnowledgeBase 缝合 + 限流/references/洞察。
4. **Lane D**（治理）→ 合并、跑 traceId/配额/韧性/对账/审计。
5. **Lane E**（动态配置）→ 合并、跑热生效/回滚/越权。
6. **任务 16/17**（质量基准 + 门禁）→ 全量回归 + CI。

> 并行开发、**串行合并**。合并冲突集中在 `pom/yml/Flyway/SSE`，由 integrator 统一裁决。

---

## 8. 每个子 agent 简报模板（分派时套用）

```
你是 Lane <X> 的执行 agent，负责 tasks.json 的任务 <n,m>。
入口条件：Wave 0 基座已完成、契约已冻结（见 agent-execution-plan.md §2）。
你独占（可改）：<§3 中该 lane 的包/文件>、Flyway <号段>。
你禁改（需申请）：pom.xml、application.yml 核心、其他 lane 的包、已冻结契约。
必须遵守：面向 §2 契约编程；每步保持可编译；中文 Javadoc 风格；
          不提交 master、不推送远程；每阶段先出可审查变更摘要。
出口条件：<§3 中该 lane 的出口测试全绿>。
产出：变更摘要 + 测试结果 + 对契约/依赖的变更申请（如有）。
```

---

## 9. 风险与建议

1. **过度并行 → merge hell**：建议 Wave 1 **最多 3–4 个 lane** 同时开工（A + B + C任务11 + D骨架），其余排队。
2. **B-spike 失败**：若 Spring AI 无法等价思考流式，Lane B 的 B3/B4 需降级（仅快速模式或自定义解析），**会影响 C 的 SSE 契约**——所以 spike 结论要在 Wave 1 开工前广播。
3. **契约漂移**：任何 lane 想改 §2 契约，必须走 integrator 批准，否则并行成果无法合并。
4. **关键路径盯 Lane B**：整体工期由 B 决定；A/C/D/E 即使提前完成也要等 B 才能全链路集成。
5. **测试基线重建**：CRM 306 / RAG 456 测试迁移后必须重建，作为各 lane 出口的硬门禁（沿用源项目 JaCoCo 与 Testcontainers 约定）。

---

## 10. 一句话给分派者

- **先派 1 个最强的 agent 做 Wave 0（基座 + 契约冻结）**，这是所有并行的前提，不要跳过。
- Wave 0 完成后，**并行派 A、B、C(任务11)、D(骨架) 四个 lane**，B 内部先跑 spike。
- **合并交给 integrator 串行做**，顺序 A→B→C→D→E→16/17。
- 每个 agent 只准改自己 §3 的归属文件，`pom/yml/契约` 一律走申请。

---

## 11. Worktree 并行策略（决策：能并行就并行）

**决策**：实施阶段用 git worktree 让多个子 agent **真正并发**（各自独立目录 + 独立分支，共享同一 `.git`）。原因：一个工作目录同时只能 checkout 一个分支，并发 agent 会互相抢工作树与构建产物。

**唯一不能并行的**：Wave 0（基座 + 契约冻结）——它是所有 worktree 的派生根，必须先由单一 agent 串行完成并提交，之后才谈并行。

### 布局
```
d:\code\crmAndRag\                    ← 主树：base 分支（Agent-0 完成后作为派生根）
d:\code\crmAndRag\.worktrees\         ← 必须 .gitignore（连同 target/）
   ├─ lane-a-datascope      → feature/lane-a-datascope      (Agent-A, 任务5,6)
   ├─ lane-b-rag            → feature/lane-b-rag            (Agent-B, 任务7,8,9)
   ├─ lane-c-ai-assistant   → feature/lane-c-ai-assistant   (Agent-C, 任务10,11)
   ├─ lane-d-governance     → feature/lane-d-governance     (Agent-D, 任务12,13,14)
   └─ lane-e-dynamic-config → feature/lane-e-dynamic-config (Agent-E, 任务15)
```

### 创建命令（Agent-0 base 提交后执行）
```
git worktree add .worktrees/lane-a-datascope    -b feature/lane-a-datascope    <base>
git worktree add .worktrees/lane-b-rag          -b feature/lane-b-rag          <base>
git worktree add .worktrees/lane-c-ai-assistant -b feature/lane-c-ai-assistant <base>
git worktree add .worktrees/lane-d-governance   -b feature/lane-d-governance   <base>
# lane-e 在 Wave 2 再建（依赖 D 审计骨架 + B/C 配置读取点）
```

### 最大并发编排
- **Wave 0**：Agent-0 单独跑（串行闸，不可并行）。
- **Wave 1 起同时开 4 个 worktree**：A、B、C（先做任务11）、D（先做任务12骨架）；E 待 D 审计骨架 + B/C 读取点就绪后在 Wave 2 加入。
- **lane 内部仍串行**：B = spike → persist → ai（同目录顺序做）；C 的任务10 等 B 检索契约。
- **合并串行**：integrator 按 A→B→C→D→E→16/17 merge，每次编译+冒烟。

### worktree 不消除的东西（务必记住）
- **不消除合并冲突**：`pom.xml`/`application.yml`/Flyway 版本号/SSE 契约的争用仍在 merge 时爆发 → §3 文件归属、§2 契约冻结、§5 Flyway 号段**照样强制**。
- 一个分支只能被一个 worktree 占用；lane 分支必须互不相同。
- 每个 worktree 有独立 `target/`，`.gitignore` 必须排除 `.worktrees/` 与 `target/`。
- 磁盘/构建成本随并发上升；建议并发 ≤ 5。

**何时建**：不是现在（当前只有提案、无代码）。在 Agent-0 把基座落地并提交后、Wave 1 开工时建。
