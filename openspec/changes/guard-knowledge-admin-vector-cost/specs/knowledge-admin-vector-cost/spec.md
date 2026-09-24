# 规范增量：knowledge-admin-vector-cost

## ADDED Requirements

### Requirement: 管理端真向量检索默认关闭且明确拒绝
系统 SHALL 将 `rag.retrieval.admin-vector.enabled` 登记到既有 `rag.retrieval` 配置命名空间，默认值 MUST 为 false。`POST /knowledge/retrieval/test` 仅当 `useVector=true` 时受该开关保护；开关未启用、配置缺失或配置读取不可用时 MUST 以现有机器可识别业务错误拒绝，MUST NOT 返回表示已执行向量检索的空成功，也 MUST NOT 进入查询改写、embedding 或向量搜索。仅超管可通过既有动态配置管理入口更新该键；操作开启仍需 owner 授权真实外发。

#### Scenario: 缺省配置与显式 true
- **GIVEN** 管理端向量开关未配置或为 false
- **WHEN** 900 权限用户提交 `useVector=true` 的合法查询
- **THEN** 得到明确禁用错误，任何模型或向量检索依赖均不被调用

#### Scenario: 默认稀疏路径保持不变
- **GIVEN** 向量开关为 false
- **WHEN** 用户提交 `useVector=false` 或未传该字段
- **THEN** 仍按既有 KB 授权执行稀疏检索，不因向量开关而被拒绝，不调用 embedding

### Requirement: 真向量请求限定单库和候选上限
管理端 `useVector=true` 请求 MUST 显式指定一个当前用户已授权的 `kbId`，`topK` MUST 在 1–10 范围内（省略时默认为 5）。参数缺失或越界 MUST 在检索/模型调用前以现有参数错误码拒绝；KB 无授权时 MUST NOT 调用检索或模型。该限制 MUST NOT 改变普通助手检索的 topK、知识库授权算法或已冻结的检索契约。

#### Scenario: 未指定 KB 或请求过大
- **GIVEN** 管理端向量开关已启用
- **WHEN** 请求未指定 `kbId`，或 `topK` 大于 10 / 非正数
- **THEN** 请求被显式拒绝且不产生模型调用或配额占用

#### Scenario: 合法单库调用
- **GIVEN** 开关已启用、当前用户可见指定 KB、`topK` 在范围内且配额未满
- **WHEN** 用户提交 `useVector=true` 的查询
- **THEN** 检索输入继续携带当前用户 ID、仅该授权 KB 的 scope 与请求 topK，结果按现有候选映射返回

### Requirement: 每实例用户请求配额在付费调用前生效
系统 SHALL 为管理端真向量检索使用独立于通用 USER 维度的用户固定窗口，缺省每用户每 JVM 实例每分钟 3 次，复用现有 `RequestQuotaService`。仅开关开启且通过参数与 KB 授权的请求消耗一次额度；配额耗尽时 MUST 使用现有 `RATE_LIMIT_EXCEEDED` 错误并 MUST NOT 调用生产检索。该机制不得被描述成跨实例全局限额或货币费用上限。

#### Scenario: 第四次请求被拒
- **GIVEN** 同一用户在同一实例窗口内已有 3 次有效管理端真向量请求
- **WHEN** 第 4 次请求到达
- **THEN** 返回频率超限错误，查询改写/embedding/向量检索均不运行

#### Scenario: 不同用户独立计数
- **GIVEN** 用户 A 已达到本实例窗口上限，用户 B 尚未达到
- **WHEN** 用户 B 提交合法真向量请求
- **THEN** B 不因 A 的计数被拒绝；普通稀疏请求也不消耗该额度

### Requirement: 管理界面说明真向量操作边界
管理界面 SHALL 在选择真向量模式时提示需指定 KB、受服务端开关控制且会产生模型调用，并在提交前阻止明显缺 KB 或越界的 topK；后端继续为最终裁决者。界面 MUST NOT 将默认稀疏模式描述为付费检索，也 MUST NOT 误把服务端拒绝显示为“无候选结果”。

#### Scenario: 用户未选 KB 切换真向量
- **GIVEN** 用户勾选 `useVector` 但尚未选定知识库
- **WHEN** 用户点击检索测试
- **THEN** 界面提示选定 KB，不发送请求

#### Scenario: 服务端仍处于默认关闭
- **GIVEN** 用户选择真向量且输入在界面允许范围内
- **WHEN** 后端开关尚未被授权开启
- **THEN** 界面呈现既有错误提示，不把它渲染为一次成功的空结果
