# ADDED Requirements — static-analysis（tighten-pmd-violations）

## Requirement: PMD 存量违规分片收紧

PMD 声明规则集（25 条）下的存量违规条数必须按规则分片收紧，台账与 pom 阈值只许下调；首个分片（CommentSize）同时校准 `maxLineLength` 荒谬默认值。

#### Scenario: 分片 A 完成
- **WHEN** CommentSize 的 `maxLineLength` 校准为 120 且 4 处超 20 行注释块精简后
- **THEN** `mvn -B -ntp pmd:check` 实测条数较 1329 下降约 282（±残留超宽行）
- **AND** 台账与 pom `<maxAllowedViolations>` 等于该实测值
- **AND** merge-gate 全绿，surefire 计数不变

#### Scenario: 行为风险分片的前置拍板
- **WHEN** 分片（如 AvoidCatchingGenericException）存在行为变更风险
- **THEN** 必须先产出逐例处置表并经 owner 拍板处置方式，才允许改代码

#### Scenario: 台账 ratchet 不回退
- **WHEN** 任一分片收尾
- **THEN** 新登记值 < 上一登记值（只下调），且每分片独立提交
