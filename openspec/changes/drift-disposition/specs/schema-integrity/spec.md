# 规范增量：schema-integrity

## ADDED Requirements

### Requirement: 已定夺漂移登记（KNOWN/NEW 二分）
schema 漂移审计 MUST 维护 `KnownDriftRegistry`（已定夺豁免登记：表.列 + 差异 + 理由 + 定夺日期）。审计时 WARN/INFO 级漂移先查登记：命中计入 KNOWN（已定夺，仅计数不报警）；未命中计入 NEW（新出现，显式打印待定夺）。CRITICAL 门禁语义不变（非空即 fail）。登记项 MUST 与真实实体/迁移链对齐（防呆：登记的表.列不存在时单测报错）。

首轮登记 7 项（2026-09-14 拍板豁免）：W1 `ai_chat_image.key_entities`（String↔json）、W2 `data_share.resource_type`（String↔tinyint）、W3 `stage_resource_binding.resource_type`（String↔tinyint）、W4/W5 `sales_stage_approval.current_stage/target_stage`（Integer↔varchar）、I1 `customer_company.dept_unique_key`（生成列，实体不映射为正确设计）、I2 `platform_token_budget`（治理域预留表，零业务引用）。

#### Scenario: 已登记漂移落 KNOWN
- **WHEN** 审计发现 `ai_chat_image.key_entities` String↔json
- **THEN** 计入 KNOWN（计数），不落入 NEW 待定夺清单

#### Scenario: 新漂移落 NEW 提醒定夺
- **WHEN** 出现登记之外的 WARN 级漂移（如新实体字段类型与库列不亲和）
- **THEN** 计入 NEW 并显式打印明细（不失败），提示需定夺后登记或处置

#### Scenario: 登记防呆
- **WHEN** KnownDriftRegistry 中登记的表.列在实体或迁移链中不存在
- **THEN** 单测失败（防止登记过期或笔误静默失效）

### Requirement: 定夺结论文档化
`docs/schema-drift-audit.md` 的 WARN/INFO 表 MUST 标注定夺状态与豁免理由，指向登记设施；待授权清单 MUST 与登记状态同步（已定夺项移出）。新漂移 MUST 走"NEW 出现 → 定夺 → 登记或处置"流程。

#### Scenario: 审计报告与登记一致
- **WHEN** SchemaDriftAuditIT 运行
- **THEN** 报告中 7 项已定夺（KNOWN=7 / NEW=0 / CRITICAL=0），与 docs/schema-drift-audit.md 定夺表一致
