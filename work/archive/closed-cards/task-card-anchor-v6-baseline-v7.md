# 任务卡 · anchor-v6-baseline-v7（v6.0 主套件真跑落锚 baseline-v7）

- 档位：**A 档**（72 例全量真跑 opt-in 落锚；零 src/main / 零迁移 / 零权限 / 零新依赖）
- 树：**权威树** `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/anchor-v6-baseline-v7`（从 master 检出）
- 目标：跑主套件真跑 runner 落锚 `baseline-v7.json`（suiteVersion=6.0，72 例），补齐 v6.0 评测基线缺口

## Owner 拍板（2026-10-22）
72 例全量真跑（真嵌入 + 真查询改写 + 真模型生成 + 真判卷），RAG_BENCHMARK_REAL=1 opt-in

## 关键锚点（已实读，勿重复侦察）
- `RagRealRetrievalBenchmarkIT`（1025 行）：主套件真跑 runner，RAG_BENCHMARK_REAL=1 opt-in，历史锚 V6_ANCHOR_FILE_NAME="baseline-v6.json"
- `RagBenchmarkSuite.SUITE_VERSION="6.0"`（72 例）；`RagBenchmarkV6AnchorValidationTest`（纯 JVM 锁 v6=5.0、SUITE_VERSION=6.0）
- `RagBenchmarkDataPreparer`（fixture 入库 + 黄金对齐 + rewriteSuite）+ `RagQualityEvaluator`

## 任务（写集：src/test quality 模块 + docs/rag-quality/，无其他写入者）
1. 跑 RagRealRetrievalBenchmarkIT（RAG_BENCHMARK_REAL=1）→ 72 例全量真跑 → Report 落盘 baseline-v7.json（suiteVersion=6.0）
2. RagRealRetrievalBenchmarkIT 增 V7_ANCHOR_FILE_NAME="baseline-v7.json"
3. 新增 v7 锚点对账单测（纯 JVM，对齐 RagBenchmarkV6AnchorValidationTest）：suiteVersion=6.0 / caseCount=72 / 与 v6 不可比
4. 无 key 默认零外呼断言保持

## 边界（禁改面）
- 禁改 RagBenchmarkSuite 72 例/SUITE_VERSION/用例黄金片段；禁碰 baseline-v1~v6/image-baseline-v1/pdf-ingest-image-baseline-v1
- 零 src/main、零迁移、零权限、零契约、零新依赖

## 验证
`mvn -B -ntp clean verify` + 基线 `--update` EXIT=0 + `merge-gate.sh` 10/10；真跑后 baseline-v7.json 成功落盘（suiteVersion=6.0 caseCount=72）

## 停步
未 merge、未 push、未 reset/rebase/amend、未删分支。回报 ≤300 字（产出路径/行数/校验结果/未解决项/待决策项 + 72 例真跑关键指标），细节留文件。