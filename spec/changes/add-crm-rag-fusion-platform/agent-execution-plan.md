# 多 Agent 执行拆分与并行计划（重定位版）

> 目的：把 `tasks.json`（**18 任务 / 6 阶段**）重排为**可分派给多个子 agent**的执行计划，明确波次、并行 lane、契约冻结、文件归属、冲突规避与集成时序。
> **★架构重定位（D11）**：以 CRM 为基座的单体，AI 助手吸收 RAG 对话能力，知识库能力移植为 `com.slz.crm.knowledge`。因此 **Lane B 不是"全量迁移一个在跑的 RAG"，而是"新建知识库模块 + 移植检索/文档能力"**；**Lane C 助手范围扩大**（吸收思考/记忆/图文/接管）；RAG 独立对话层（chat_*/RagChatPipeline/匿名）丢弃。
> 行为细节见 `assistant-decision-tree.md`；决策见 `design-decisions.md`（D1–D17）。

---

## 0. 结论（诚实版）

**可直接用于拆分**：能力域边界清晰（6 个 spec-delta）；决策已锁（D1–D17）；`migration-subtasks.md` 把 D2/D4 拆到方法级；`assistant-decision-tree.md` 把助手行为拆到分支级。

**并行必备的四样（本文件提供）**：依赖波次、契约冻结、文件归属、集成时序。

**核心判断**：
- **基座是硬串行瓶颈**：任务 1–4 + 契约冻结必须由单一 agent 顺序完成，不能并行。
- **关键路径 = Lane B 知识库能力（任务 7/8/9）+ Lane C 助手吸收（任务 10–12）**；B 的 Spring AI spike（思考流式/视觉）是全局 go/no-go 闸。
- **建议最大并行度 3–4 lane**；超过会因 `pom/yml/SSE/配置` 争用陷入 merge hell。
- **测试基线要拆**（见 §9）：RAG 原 491 含被丢弃的对话层测试，不能整体"重建 491"。

---

## 1. 依赖波次（Wave）

```
Wave 0  [串行·基座 agent]        任务 1,2,3,4 + 冻结契约（§2）+ Flyway V1 基线 + Actuator 保护
   │      产出：可编译可跑骨架 + CRM 全量并入 + 稳定接口契约
   ▼
Wave 1  [并行·3–4 lane]          ┌ Lane A 数据权限     任务 5,6        （纯 CRM 内，最低风险，可最先）
   │                             ├ Lane B 知识库能力   任务 7,8,9      （关键路径；内部 spike→build→retrieval）
   │                             ├ Lane C 助手吸收     任务 10,11,12   （10/11 部分依赖 B 检索契约）
   │                             └ Lane D 治理骨架     任务 13（先行）,14,15
   ▼
Wave 2  [收敛]                   Lane E 动态配置 任务 16（依赖 B/C/D 读取点 + D 审计）
   │                             任务 17 质量基准（依赖 B 检索完成）
   ▼
Wave 3  [集成·integrator]        任务 18 契约/性能测试 + CI 门禁 + 串行合并 + 灰度回退
```

**为什么 Wave 0 不能并行**：`pom.xml`、包结构、`Result`、`UserContext`、配置骨架、Actuator 保护是所有 lane 公共依赖；多 agent 同改基座 = 编译互相打断 + 契约漂移。

---

## 2. Wave 0 必须冻结的"稳定契约"（并行前提）

基座 agent 在 Wave 0 末尾把下列接口**定义并冻结**（放 `com.slz.crm.platform.contract` 或各域 api 包），各 lane 只实现/消费、不改签名：

| 契约 | 内容 | 消费方 |
|------|------|--------|
| `UserContext` / 身份 | 当前登录 userId(`user:<id>`)、roleId、**deptId**、数据范围；ThreadLocal + 异步快照传播 | A/B/C/D/E |
| `Result` + 错误码 | 统一响应封装、错误码枚举（RATE_LIMITED/QUOTA_EXCEEDED/UNAUTHORIZED/CONTENT_RISK…） | 全部 |
| `ModelProvider` | `chatModel()/streamingChatModel()/embeddingModel()/visionModel()`；provider=dashscope(默认)/openai/vllm；**须返回带 usage 的响应**（修 RAG `chat(String)` 漏计 token） | B/C/D/E |
| `VectorStore` 抽象 | `QdrantVectorStore`(默认) + `InMemoryVectorStore`(回退)；统一 search/filter | B/D |
| `DataScope` 接口 | 数据范围解析（含 DEPT/DEPT_AND_CHILD）；**入参带 userId+deptId**（现 `RoleAO` 无 deptId）；落点 `DataScopeServiceImpl`+`QueryWrapperAspect`，**非 MyDataPermissionHandler（不存在）** | A/C |
| **助手请求契约** | `POST /ai/chat/stream` body：`sessionId/message/useKnowledgeBase/thinking/imageRef/attachments` | C（B 提供检索能力被调） |
| **SSE 事件契约** | 统一事件名：`start/meta/sources/thinking/delta/references/done/cancelled/stopped/error` + `ping`(心跳注释)；字段结构 + 超时/心跳口径（**修正**：CRM 原用 text/done/stopped/title、RAG 原用 start/sources/delta/complete/cancelled/error，融合统一到此集） | B/C/D |
| **来源引用契约** | `SourceReference`：`sourceType/route/filename/documentId/chunkId/chunkIndex/pageNo/rowIndex/excerpt/relevanceScore`；`payload.citations`（档 B 高亮） | B/C |
| Token 计量接口 | `record(model,user,session,kb,type,usage)`；type∈chat/embedding/ocr/vision/summary/intent；B/C 产数，D 聚合预算 | B/C/D |
| 动态配置读取接口 | `DynamicConfigService.get(key,type,default)`（E 实现，B/C/D 消费；含意图类目/strict-KB/图片缓存上限） | B/C/D/E |
| Flyway 版本段 | 见 §5，避免撞版本号 | 全部 |

> 契约冻结后如需变更，走"契约变更申请 → 基座/integrator 批准 → 通知所有 lane"，禁止单 lane 擅自改。

---

## 3. 并行 Lane 划分与文件归属（防冲突核心）

| Lane | tasks | **独占**包/文件（owner） | 依赖契约 | 出口条件 | 规模 |
|------|-------|--------------------------|----------|----------|------|
| **基座(Agent-0)** | 1,2,3,4 | 骨架、`pom.xml`、`application.yml`、`com.slz.crm.common.*`、`com.slz.crm.platform.contract.*`、Flyway `V1`、Actuator 保护/publicPaths | — | 骨架可编译启动 + CRM 306 重建 + 契约冻结 + Actuator 受保护 | L |
| **A 数据权限** | 5,6 | `DataScopeLevel`/`PermissionOperates`、`DataScopeServiceImpl`、`ResourceTypeConstant`、`QueryWrapperAspect`、`RoleAO`(+deptId)、`SysDeptEntity`(+leaderId)、Flyway `V2x`；删死代码 `DataScopeResolver(Impl)` | UserContext, DataScope | 四级可见性 + 负责人跨子部门 + 越权 + 两 switch 生效 | M |
| **B 知识库能力**（关键路径） | 7,8,9 | `com.slz.crm.knowledge.**`（7 表实体/mapper/文档/检索/嵌入/视觉/授权）、Flyway `V3x`、`docker-compose`、VectorStore 实现 | UserContext, Result, ModelProvider, VectorStore, 来源引用契约 | 登录态 上传→按页分块→检索(带高亮锚点)冒烟 + 知识库能力测试子集重建 | XL（拆 spike/build/retrieval，见 §6） |
| **C 助手增强** | 10,11,12 | `server.ai.**`、`AiChatServiceImpl`、`AiToolRegistry`、记忆(吸收 RAG)、`ai_conversation_memory`/`ai_insight`、Flyway `V4x` | UserContext, ModelProvider, SSE, DataScope, Token, 来源引用 | 思考/记忆持久化/图文解耦/接管/KB开关/空匹配修复/高亮/限流/洞察/评测 通过 | XL |
| **D 平台治理** | 13,14,15 | `com.slz.crm.platform.**`（trace/async/resilience/quota/token/lifecycle/reconcile/audit/health/metrics）、Flyway `V5x` | UserContext, SSE, Token, VectorStore | 跨线程 traceId、记忆旁路降级、配额、韧性、对账、审计、Actuator 健康/指标 通过 | L |
| **E 动态配置** | 16 | `com.slz.crm.platform.config.**`、Flyway `V6x` | DynamicConfig 接口, 审计(D) | 越权写拒绝/非法值拒绝/热生效/回滚/意图类目热更新 通过 | M |
| **integrator** | 17,18 + 合并 | 集成分支、CI、契约/性能测试 | 全部 | 全量回归（见 §9 拆分基线）+ CI 门禁绿 + 发布检查 | L |

---

## 4. 冲突热点与规避

| 热点 | 争用 lane | 规避 |
|------|-----------|------|
| `pom.xml` | 基座/B/C/D | **基座独占**；其他 lane 提"依赖变更申请"，integrator 合并 |
| `application.yml` | 基座/B/C/D/E | 拆 `application.yml`(核心) + `application-<lane>.yml`；键按 lane 归属，动态项迁 E |
| Flyway 脚本 | 全部 | **预分配号段**（§5），各 lane 只在自己号段加脚本 |
| SSE / 流式 | C(助手 SSE) / B(检索被调) / D(traceId) | 冻结 **SSE 事件契约**（§2）；助手 SSE owner=C，B 只提供检索能力不 own SSE，D 只加 traceId 装饰不改事件结构 |
| 记忆旁路执行器 | C 用 / D 建 | **D 建共享执行器**（pool/队列/单飞/降级），C 复用不自建 |
| ModelProvider/VectorStore | B 建 / C·D·E 用 | Wave0 冻结接口；B 提供 VectorStore 实现，ModelProvider 基座冻结、B/C 消费 |
| Token 计量 | B/C 产 / D 聚合 | Wave0 冻结 `record(...)`；D 只聚合，B/C 产数（含意图/摘要补盲点） |
| `DataScopeServiceImpl` | A 建 / C 用 | A 拥有；C 的 `queryKnowledgeBase` 面向 `DataScope` 接口 |
| 来源引用契约 | B 产 / C 消费 | Wave0 冻结 `SourceReference`（含 chunkIndex/pageNo）；B 填充，C 下发+高亮 |

---

## 5. Flyway 版本段预分配

| 号段 | Lane | 内容 |
|------|------|------|
| `V1__*` | 基座 | CRM 现有表基线（真实名 sys_user/sys_role/sys_dept…，36 张） |
| `V2x__*` | A | `sys_dept.leader_id` + DEPT/DEPT_AND_SUB 权限种子（默认不授予） |
| `V3x__*` | B | 知识库 **7 表新建**（单数名/create_time/is_deleted/user:<id>）+ 授权 userId + `uploaded_file.knowledge_base` 回填；**无 chat_* 迁移（已丢弃）** |
| `V4x__*` | C | `ai_conversation_memory`（记忆加工品）+ `ai_insight` |
| `V5x__*` | D | 配额、Token 计量、生命周期事件、对账账本、审计 |
| `V6x__*` | E | 动态配置项 + 版本历史 |

> 规则：号段内递增（`V31__`/`V32__`）；跨 lane 依赖表由被依赖方建，消费方不重复建。

---

## 6. Lane B / C 再拆（XL 装不进单 agent 会话）

**Lane B（知识库能力）**：
- **B-spike（先，M）**：`migration-subtasks.md` 的 **B0**——验证 Spring AI 能否等价承接思考块流式(`.returnThinking`/`enable_thinking`)、视觉理解、Qdrant 过滤、向量维度统一。**产出 go/no-go**，是全局闸，最先出结论并广播（影响 C 的 SSE `thinking` 契约）。
- **B-build（任务 7,8）**：7 表新建 + 文档/检索/嵌入/视觉移植到 Spring AI + VectorStore 抽象 + 授权 userId + 移除匿名/对话层。
- **B-retrieval（任务 9）**：按页分块(档 B) + SourceReference 高亮锚点 + 意图类目 CRM 化 + 检索冒烟。
- 协同：B-build 先合入（实体/mapper/服务），B-retrieval 在其上做分块/高亮/意图。

**Lane C（助手增强）**：任务 10（吸收对话能力：思考/记忆持久化/图文解耦/接管）→ 11（KB开关/工具/高亮/空匹配修复/payload）→ 12（限流/references/洞察/评测/Token）。10/11 部分依赖 B 检索契约（未就绪用接口/mock）。

---

## 7. 集成时序（integrator 串行合并）

按"低风险先行、关键路径居中、横切最后"，每次合并后**编译 + 冒烟**再合下一个：
1. **Lane A**（数据权限，纯 CRM 内）→ 四级可见性测试。
2. **Lane B**（知识库能力，关键路径）→ 上传→按页分块→检索(高亮锚点)冒烟 + 知识库测试子集。
3. **Lane C**（助手增强）→ 思考/记忆/图文/接管/KB开关/空匹配/高亮/queryKnowledgeBase 缝合。
4. **Lane D**（治理）→ traceId/记忆旁路/配额/韧性/对账/审计/Actuator 健康。
5. **Lane E**（动态配置）→ 热生效/回滚/越权/意图类目。
6. **任务 17/18**（质量基准 + 门禁）→ 全量回归 + CI。

> 并行开发、**串行合并**。冲突集中在 `pom/yml/Flyway/SSE`，integrator 统一裁决。

---

## 8. 子 agent 简报模板

```
你是 Lane <X> 的执行 agent，负责 tasks.json 任务 <n,m>。
第一步必读：prompts/_common-rules.md（注释规范+纪律）+ 你的 prompts/agent-*.md。
入口：Wave 0 基座完成、契约冻结（见 §2）。
独占（可改）：<§3 该 lane 包/文件> + Flyway <号段>。
禁改（需申请）：pom.xml、application.yml 核心、其他 lane 包、已冻结契约。
必须：面向 §2 契约编程；每步可编译；中文 Javadoc；不提交 master、不 push；每阶段先出变更摘要。
出口：<§3 该 lane 出口测试全绿>。产出：变更摘要 + 测试结果 + 契约/依赖变更申请（如有）。
```

---

## 9. 风险与建议

1. **过度并行 → merge hell**：Wave 1 最多 3–4 lane（A + B + C任务10 + D骨架），其余排队。
2. **B-spike 失败**：若 Spring AI 无法等价思考流式/视觉，B/C 需降级（仅快速模式或自定义解析），**影响 C 的 SSE `thinking` 契约**——spike 结论要在 Wave 1 开工前广播。
3. **按页分块重构（档 B）**：改 PDF merge→按页分块影响入库语义；新平台新建无存量包袱，但要补分块/高亮回归。
4. **契约漂移**：任何 lane 改 §2 契约必须走 integrator 批准。
5. **★测试基线要拆**（不能整体"重建 RAG 491"）：RAG 原 491 = **知识库能力测试**（文档/检索/嵌入/视觉/授权 → 在 Lane B 重建）+ **对话层测试**（RagChatPipeline/StreamSession/conversation/anonymous → 随代码丢弃，其能力在 Lane C 以助手测试重建）。integrator 全量回归 = CRM 306 + 知识库子集 + 助手对话测试，**不是** 491 原样重建。
6. **记忆持久化并发**：`ai_conversation_memory` 每轮高频写 → 乐观锁 version + 助手每会话 `AiStreamRegistry` 锁串行化。

---

## 10. 一句话给分派者

- **先派 1 个最强 agent 做 Wave 0（基座 + 契约冻结 + Actuator 保护）**，不可跳过。
- Wave 0 后**并行派 A、B(先 spike)、C(任务10)、D(骨架)**。
- **合并交 integrator 串行做**，顺序 A→B→C→D→E→17/18。
- 每个 agent 只改自己 §3 归属文件，`pom/yml/契约` 走申请。

---

## 11. Worktree 并行策略（能并行就并行）

**决策**：实施阶段用 git worktree 让多 agent 真正并发（独立目录 + 独立分支，共享 `.git`）。**唯一不能并行**：Wave 0（所有 worktree 的派生根）。

### 布局
```
d:\code\crmAndRag\                    ← 主树：base 分支（Agent-0 完成后作为派生根）
d:\code\crmAndRag\.worktrees\         ← 必须 .gitignore（连同 target/）
   ├─ lane-a-datascope      → feature/lane-a-datascope      (Agent-A, 任务5,6)
   ├─ lane-b-knowledge      → feature/lane-b-knowledge      (Agent-B, 任务7,8,9)
   ├─ lane-c-ai-assistant   → feature/lane-c-ai-assistant   (Agent-C, 任务10,11,12)
   ├─ lane-d-governance     → feature/lane-d-governance     (Agent-D, 任务13,14,15)
   └─ lane-e-dynamic-config → feature/lane-e-dynamic-config (Agent-E, 任务16)
```

### 创建命令（Agent-0 base 提交后）
```
git worktree add .worktrees/lane-a-datascope    -b feature/lane-a-datascope    <base>
git worktree add .worktrees/lane-b-knowledge    -b feature/lane-b-knowledge    <base>
git worktree add .worktrees/lane-c-ai-assistant -b feature/lane-c-ai-assistant <base>
git worktree add .worktrees/lane-d-governance   -b feature/lane-d-governance   <base>
# lane-e 在 Wave 2 再建（依赖 D 审计骨架 + B/C 读取点）
```

### 最大并发编排
- **Wave 0**：Agent-0 单独跑（串行闸）。
- **Wave 1 起同开 4 worktree**：A、B（先 spike）、C（先任务10）、D（先任务13骨架）；E 待 D 审计 + B/C 读取点就绪后 Wave 2 加入。
- **lane 内串行**：B = spike→build(7,8)→retrieval(9)；C = 10→11→12（10/11 部分等 B 检索契约）。
- **合并串行**：integrator 按 A→B→C→D→E→17/18 merge，每次编译+冒烟。

### worktree 不消除的东西
- **不消除合并冲突**：`pom/yml/Flyway/SSE` 争用仍在 merge 爆发 → §3 归属、§2 契约、§5 号段照样强制。
- 一分支只能被一 worktree 占用；lane 分支互不相同；`.gitignore` 排除 `.worktrees/` 与 `target/`；建议并发 ≤ 5。

**何时建**：不是现在（当前只有提案、无代码）。Agent-0 把基座落地并提交后、Wave 1 开工时建。
