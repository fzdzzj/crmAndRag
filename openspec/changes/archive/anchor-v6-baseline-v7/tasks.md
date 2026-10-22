# 任务清单 · anchor-v6-baseline-v7

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 真跑落锚
- [ ] 1.1 `RagRealRetrievalBenchmarkIT`（`RAG_BENCHMARK_REAL=1` + `DASHSCOPE_API_KEY`）跑 72 例全量真跑（真嵌入 + 真查询改写 + 真模型生成 + 真判卷）→ `Report` 落盘 `docs/rag-quality/baseline-v7.json`（suiteVersion=6.0）
- [ ] 1.2 `RagRealRetrievalBenchmarkIT` 增 `V7_ANCHOR_FILE_NAME="baseline-v7.json"`（对齐 V6 常量形态）

## 2. 锚点验证
- [ ] 2.1 新增 v7 锚点对账单测（纯 JVM 零外发，对齐 `RagBenchmarkV6AnchorValidationTest` 形态）：suiteVersion=6.0 / caseCount=72 / 与 v6（5.0）不可比声明
- [ ] 2.2 无 key 默认零外呼（Assume 跳过）断言保持

## 3. 验证与收口
- [ ] 3.1 `mvn -B -ntp clean verify` BUILD SUCCESS + 基线 `--update` + 校验 `EXIT=0` + `merge-gate.sh` 10/10
- [ ] 3.2 变更规格归档 + handoff + 看板/台账更新；停步未 merge 未 push（A 档，独立复核四判据通过后由 Owner 授权 merge）