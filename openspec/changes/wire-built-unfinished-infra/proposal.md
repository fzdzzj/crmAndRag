# 提案 · 已建未接线基建接线合卡 (wire-built-unfinished-infra)

> 档位：B 档（多处小接线 + 死键清理，写集分散但每处小）
> 来源：`work/_paa-candidates-pool-v2.md` 三条暗线之①「已建未接线」家族（Top 发现 J-9 / H-5 等）
> 基线：master@17008e2 起算，实际派发以届时 master 实测为准（队列位：D4 → D6 → J-1 → I-2 → **本卡**）

## 1. 背景与目标

暗线①实测（v2 池全锚点）：治理面多处「登记即生效」幻觉——TokenBudget 预算闸已建未接线、
ContentSecurityService 注入规则/评估/落库全实现但**全仓零调用**（prompt-injection 防线做了没插电，
两处接线即上线）、ai.model.* 死配置键。本卡一张合卡消掉家族大半：

1. **TokenBudget 预算闸接线**：按 v2 池锚点接入既有 AI 链路消费点（口径：先查后扣/超限行为复用配额
   既有错误语义，handoff 留痕接线点与语义）；
2. **ContentSecurityService 两处接线**：知识摄取入口 + AI 对话入口（v2 池锚点定位；命中规则的处置
   行为按服务已实现语义启用，单测覆盖命中/未命中两向）；
3. **ai.model.* 死键清理**：application.yml 及 .env.example 同步清理（零风险）。

## 2. 非目标（家族边界裁剪）

零迁移 / 零权限常量 / 零真实外呼（ContentSecurity 为纯本地规则评估）；**不并入**
platform_lifecycle_event / batch_task / batch_file_result 死基建三表处置（涉表语义取舍，留 Owner
后续专项）；不动 TokenBudget/ContentSecurity 内部实现（只接线）；预期零前端。

## 3. 验收

- 预算闸超限路径 + 内容安全命中/放行路径单测全绿（mock，零外呼）；死键清理后 yml 无悬空引用；
- 基线只增（实测为准）；merge-gate 8/8（Docker 9/9）；typed-router 零 diff。
