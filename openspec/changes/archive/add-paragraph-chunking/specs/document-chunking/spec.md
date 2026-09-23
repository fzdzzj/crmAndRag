# 规范增量：document-chunking

## ADDED Requirements

### Requirement: 段落感知切分策略
系统 SHALL 支持 `rag.chunking.strategy=paragraph`：在保持 320 窗口与 40 重叠的前提下，优先在段落界结束切片，避免把可放入窗口的整段图注/条款拦腰切开。无换行文本 MUST 与 `fixed` 策略逐字相同。策略默认 MUST 仍为 `fixed`，本变更 MUST NOT 把生产 yml 默认改为 paragraph。

#### Scenario: 短段落不被横切
- **GIVEN** 两段均短于 320、合计超过 320、中间有空行
- **WHEN** 策略为 paragraph
- **THEN** 第二段完整落在同一切片内

#### Scenario: 无换行退回滑窗
- **GIVEN** 无换行长文
- **WHEN** 策略为 paragraph
- **THEN** 切片序列与 fixed 逐字一致

## MODIFIED Requirements

### Requirement: 切分策略可配且保锚点
（保留原语义，并补充）`fixed` 策略 MUST 继续与升级前 320/40 滑窗逐字等价；`paragraph` 为额外策略，不得替换 `fixed` 的回退承诺。任何策略下 pageNo/rowIndex MUST 仍按页/行附加。

#### Scenario: 固定策略回退
- **WHEN** 切分策略为 fixed
- **THEN** 切分结果与升级前实现一致
