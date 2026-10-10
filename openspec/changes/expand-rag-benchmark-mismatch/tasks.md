# 任务：expand-rag-benchmark-mismatch（P-ai）

> 权威树：D:\code\crmAndRag-merge-add-knowledge-admin-api，master@c155226，分支 `feature/expand-rag-benchmark-mismatch`。
> 三件套主树草案（d:\code\crmAndRag\openspec\changes\expand-rag-benchmark-mismatch\）由执行者复制入权威树随首笔提交。
> 硬约束：任务组 1-3、5 全程 ¥0；任务组 4 授权节点（未授权标待补跑，不阻塞合入）。禁改 12 个既有 fixture 正文与 GOLD、禁改 54 例既有 case、禁覆盖任何既有真跑锚点 JSON、三开关默认 false 不动、检索主代码零改动、零 DDL、零新依赖、禁 frontend/。中文 Javadoc/注释标注「expand-rag-benchmark-mismatch 任务 x.x」。

## 任务组 1 · 失配语料扩容（¥0）

- [ ] 1.1 设计并新增 3 个失配 fixture（trade-jargon-glossary.md / equipment-codebook.txt / expense-colloquial-faq.md，或同构命名），三类失配模式各一，人名/数字/事件全新造，GOLD 占位登记入 `RagBenchmarkDataPreparer` FIXTURES 清单
- [ ] 1.2 新 fixture 加载红测先行：DataPreparer 加载测试先在基线跑红（fixture 未登记），登记后转绿，红绿输出粘贴 §0

## 任务组 2 · MISMATCH 用例组（¥0）

- [ ] 2.1 新增 M-01..M-N（8~10 条）MISMATCH 用例：question 用失配词面、expectedChunkIds 指向新 GOLD、expectedChunkIds 答案点宽写（E 组先例）
- [ ] 2.2 机械失配判据验证：每例 question vs gold chunk 2-gram 交集空/近零，验证输出粘贴 §0
- [ ] 2.3 SUITE_VERSION "2.0"→"3.0"；caseCount 断言红测先行（54≠新值）→ 用例登记转绿，红绿输出粘贴 §0

## 任务组 3 · ¥0 验证链（¥0）

- [ ] 3.1 `RagQualityRegressionTest` regen 更新 `baseline-v1.json` fixtureRegression 段（唯一写入口 :64-71），全组断言绿（failureRate=0、recall≥基线×0.95）
- [ ] 3.2 `DASHSCOPE_API_KEY=""` 后 `mvn -B -ntp test` 全绿，surefire 1088→1088+N 只增不减；`check-test-baseline.sh --update` 从同一真实跑写入（如有新单测）
- [ ] 3.3 四静态 0 违规（checkstyle/spotbugs 双射/spotless/pmd 台账）；三守卫 CLEAN；`bash scripts/merge-gate.sh` 全 PASS
- [ ] 3.4 红线自检：git diff 确认未触碰既有 fixture 正文、既有 case 定义、任何既有真跑锚点 JSON、检索主代码、frontend/

## 任务组 4 · 真跑授权节点（成本闸门，同 P-ah-D3 模式）

- [ ] 4.1 owner 授权 `RAG_BENCHMARK_REAL=1` 后：Docker 实测在线 → 白名单单轮默认矩阵真跑（命令见 design.md §4，`-Drag.benchmark.out=docs/rag-quality/baseline-v3.json`）→ 跑毕清环境变量
- [ ] 4.2 验收：SUITE_VERSION=3.0、failureRate=0、旧 54 例对照 `baseline-after-quality-loop.json` 无回退、MISMATCH 组 recall<1.0 用例 ≥6（缺口落证）；产物 baseline-v3.json + baseline-v3-diff.md 差异说明
- [ ] 4.3 未获授权则 §0 标「真跑待授权」合入收口，不阻塞

## 任务组 5 · 收口（¥0）

- [ ] 5.1 `openspec/project.md` 处置表 07/15/06 行台账注记「激活度量前置已闭合（expand-rag-benchmark-mismatch）」；HANDOFF.md 更新（v3 锚点状态、激活卡接口）
- [ ] 5.2 分笔中文提交（组1 / 组2 / 组3 / 组5 对齐），`git merge --no-ff` 合入 master 保留分支；push 需 owner 显式授权
- [ ] 5.3 停步回报：粘贴 git log --oneline --graph -8 / git status --short / 各笔 show --stat / 门禁 raw 关键行 / 红绿测留痕；任何未实际执行的命令不得出现在回报中

## §0 执行记录

- 决策点拍板（2026-10-10 owner「按建议」）：D1 新 category `MISMATCH`；D2 真跑节点留本卡尾段（先 ¥0 合入后授权跑，expand 卡 f2bcfbd→4c3431e 先例同构）；D3 失配缺口以授权真跑 per-case recall<1.0 落证，内存基准只验功能。
- （执行留痕区）
