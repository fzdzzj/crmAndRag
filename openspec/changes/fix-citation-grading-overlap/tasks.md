# tasks — fix-citation-grading-overlap

> 红线：所有测试/门禁运行前 `DASHSCOPE_API_KEY` 置空字符串；surefire 只增不减；旧锚点 17 文件 SHA256 零漂移；indexedText/embedText 逐字节不变；生产链路（server/ai、knowledge 切分）零改动；真跑（RAG_BENCHMARK_REAL=1）未经 owner 授权不得执行。

## 任务组 1 · 承载判定实现（quality 评测域）

- [ ] 1.1 RagBenchmarkDataPreparer 增 chunk 区间游标（顺序 indexOf 求每块 [start,end)），红测先行：三形态（重叠复本承载 / 跨块段落承载 / 无承载零误报）
- [ ] 1.2 黄金句 span 界定 + 承载集计算（`goldenToCarrierChunkIds` 多值映射，MIN_CARRIAGE_CHARS=16；主锚块恒在集内；tornMarkers 场景自然涵盖）
- [ ] 1.3 runner 接线：citP 的 expected 入参切承载集；recall/ansC 口径不动（主锚块）；RagQualityEvaluator 仅 Javadoc 注明口径
- [ ] 1.4 向量输入红线断言：indexedText/embedText 与改动前逐字节一致；旧锚点 17 文件 SHA256 跑前跑后一致

## 任务组 2 · 测试与口径锁

- [ ] 2.1 既有 RagBenchmarkDataPreparerTest 10 测适配全绿；新增承载判定单测（含零误报形态与 MIN_CARRIAGE 边界）
- [ ] 2.2 mock 全量评测断言：承载集非空例 citP 不降、确定性可复算；recall/ansC 逐例不变（防口径分离失守）
- [ ] 2.3 中文 Javadoc 标注「fix-citation-grading-overlap 任务 x.x」

## 任务组 3 · spec 登记与门禁

- [ ] 3.1 specs/rag-quality/spec-delta.md 登记：旧口径 → 多块承载新口径冻结条款 + 「判卷侧不改切分」辨权声明（D4）
- [ ] 3.2 全量门禁：`mvn -B -ntp test` 0 失败；四静态 0 违规；三守卫 CLEAN；merge-gate 全绿；surefire 基线 `--update` 只增不减（1091 → 1092+）
- [ ] 3.3 停步回报：分支自证、提交笔、门禁 raw 关键行、写集清单、锚点零漂移证明

## 任务组 4 · 真跑锚点切换（另授权，本段未授权不执行）

- [ ] 4.1 【另授权】owner 授权 RAG_BENCHMARK_REAL=1 后：63 例全量单轮真跑（预估 ~290s / tokens ~60k），产出 baseline-v4.json（suiteVersion=3.0 + scoringVersion=v2）
- [ ] 4.2 【另授权】锚点校验增量切 v4 + scoringVersion 登记 + tasks.md §0 真跑留痕（清开关自证 + 锚点前后 SHA256）
- [ ] 4.3 【另授权】修复笔/证据笔提交与 push 请示

## §0 执行留痕

（执行方填写：红测先行记录、基线 bump 命令与哈希、真跑另授权节点状态——未授权则记「未执行，待 owner 授权」）
