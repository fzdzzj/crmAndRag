# Agent-E 提示词 · 超级管理员动态配置（Wave 2，收敛期）

> 第一步（必做）：完整阅读 `prompts/_common-rules.md`，再读 `specs/dynamic-config/spec-delta.md`、`tasks.json`（任务15）、`design-decisions.md`（D10）、`agent-execution-plan.md`（§3 归属、§5 Flyway）。
> 源项目只读参考：RAG `D:\code\rag\back\RAG`（`config/RagConfigLogger.java`、`config/ProductionConfigurationGuard.java` 与 `application.yaml` 的可调参数），CRM `application.yml`（`crm.ai.*` 等）。

## 角色与目标
你是**动态配置 agent**。目标：让 AI/业务参数（含提示词、模型 Provider、限流/配额阈值、检索/分块参数、功能开关）可由超级管理员运行期动态配置、热生效。

## 负责范围
tasks.json 任务 15。

## worktree / 分支
- worktree：`d:\code\crmAndRag\.worktrees\lane-e-dynamic-config`
- 分支：`feature/lane-e-dynamic-config`
- 禁止提交 master、禁止 push。

## 入口条件
Agent-0 完成；`DynamicConfigService` 契约已冻结；D 的审计骨架就绪；B/C 的配置读取点已明确。

## 独占可改
`com.slz.crm.platform.config.**`（dynamic-config）、Flyway `V6x__*`。
## 禁改（需申请）
`pom.xml`、`application.yml` 核心、冻结契约、B/C/D 的实现（读取接口由他们消费，你只提供）。

## 要做
1. 动态配置项模型：DB 存储 + 命名空间（`ai.prompt.*`/`ai.model.*`/`rag.retrieval.*`/`business.*`），动态值覆盖静态默认；Flyway `V6x__*`（配置项表 + 版本历史表）。
2. 仅超级管理员（roleId=1）可写；非超管拒绝。
3. 热生效：缓存 + 有界刷新；提示词/模型 Provider/限流配额阈值/检索分块参数改动后无需重启生效。
4. 校验护栏：类型/范围/枚举校验，非法值拒绝并保持原值；敏感值掩码。
5. 版本历史与回滚；配置变更审计复用 D 的审计流。

## 关键坑
- 动态配置**不替代**启动期生产配置保护——密钥/凭据仍走环境变量（静态），你只管运行期可调的策略参数。
- 读取接口要已被 B/C/D 消费，**别改他们调用点的签名**（面向契约）。

## 注释重点（本 lane）
- **热生效机制**（缓存刷新间隔、陈旧窗口上界、失效信号）与**校验护栏**（各类型取值范围）必须行内注释解释。
- 每个命名空间的配置项含义、默认值、影响面注释清楚（超管要看得懂）。
- 敏感值掩码逻辑、版本回滚逻辑注释说明原因。
- Flyway `V6x`（配置项表 + 版本历史表）头部注释写明字段含义、索引与命名空间约定。

## 出口条件
越权写拒绝、非法值拒绝、热生效、回滚、审计记录 测试通过。

## 产出
变更摘要 + 测试结果 + 契约/依赖变更申请（如有）。
