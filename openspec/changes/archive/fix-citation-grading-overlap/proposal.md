# proposal — fix-citation-grading-overlap（B1 判卷口径受控解冻）

## 背景与动机

取证卡 trace-citation-redundancy（citation-forensics-v4.md，已合入 master @ 88ba84d）实测坐实：**B1 切分边界内容重复/跨块段落 9 例**——被引句是黄金块尾部在邻块头部的重叠复本（FixedChunkingStrategy 320/40 滑窗 overlap），或一个段落被切到两块的下半段承载。模型引用这些邻块时 excerpt **字面承载**答案所引内容（取证 §3 逐例粘贴在案），但当前判卷口径只认「标记归点单块」→ 语义正确的引用被判非黄金 → citationPrecision 系统性低估。

当前判卷口径（i05-forensics 在案口径）：`RagBenchmarkDataPreparer` 按最小 chunkIndex 首个完整标记命中归块（`goldenToChunkId` 单值映射），`RagQualityEvaluator.citationPrecision` 按 `引用块 ∈ expected` 计分。该口径以 baseline-v3 锚点 + 基线脚本为事实冻结面，**变更属契约改动，须走受控解冻程序**（contracts-frozen.md L4：变更申请 → 批准 → 登记），本卡即为该解冻申请与落地。

关键旁证：A 类（噪声误引）0 例——全部非黄金引用字面支撑答案，判卷低估纯属性口径缺陷，非模型缺陷。

## 方案（判卷口径侧：多块承载计分）

- **黄金承载集**：黄金集合从「标记归点单块」扩为「位置区间承载黄金句文本的全部块」。承载判定用**位置区间交集**（确定性）：Preparer 逐块记录归一化文本区间 [start,end)，黄金句 span = 标记起点至句/段末，区间交集长度 ≥ MIN_CARRIAGE（阈值 16 字符级，设计定夺）即承载；
- **口径分离**：承载集**仅用于 citP 判卷**；recall / answerConsistency 口径不动（recall 仍按主锚块=标记归点评判，保证检索质量指标跨口径可比，锚点对比面最小化）；
- **评分口径版本化**：新增 scoringVersion 登记（v2=多块承载），锚点处置 = 新增 `baseline-v4.json`（scoringVersion v2 口径，由本卡尾段真跑产出），旧锚点 17 文件（含 baseline-v3.json）SHA256 零漂移；
- **受控解冻登记**：本卡 spec-delta（rag-quality）登记新判卷口径为冻结契约；contracts-frozen.md（平台 20 契约登记册）无需改动——判卷口径不在其列，冻结面在锚点与在案口径，登记落本卡。

## 不做什么（边界）

- **不改切分链路**：320/40 overlap 语义 = 生产契约保持不变（改切分即向量输入变 = 全锚点漂移，属 B2 升版范畴）；「重叠区归属标注」方向（VectorRecord metadata）否决——触碰平台冻结契约且涉 DDL；
- **不动 CitationAligner**（server/ai 生产链路零改动）：对齐器重映射正确性是独立候选卡（取证 §4 建议），与本卡正交；
- 不动 fixtures / RagBenchmarkSuite / SUITE_VERSION（case 与黄金标注未变，3.0 保持；B2 升版 4.0 独立后续）；
- 旧锚点文件（baseline-v1|v2|v3、after-*、i05-*、citation-forensics-v4* 全部 17 个）禁改；
- indexedText / embedText 逐字节不变（向量输入红线，锚点零漂移的前提）；
- 零 DDL、零新依赖、不碰 frontend/、不动 RagRealRetrievalBenchmarkIT 的 failsafe 域结构（锚点切换以增量方式落）。

## 验收

1. 承载判定单测三形态红转绿：重叠复本承载 / 跨块段落承载 / 无承载邻块零误报；既有 `RagBenchmarkDataPreparerTest` 适配后全绿；
2. mock 全量评测（fake embedder）citP 变化方向符合预期（承载集非空例 citP 不降）且确定性可复算；recall/ansC 逐例不变；
3. surefire 基线 1091 → 1092+（只增不减，`check-test-baseline.sh --update` 从真实运行写入）；四静态 0 违规；三守卫 CLEAN；merge-gate 全绿；旧锚点 17 文件 SHA256 跑前跑后一致；
4. **真跑另授权节点（本卡尾段）**：owner 授权 RAG_BENCHMARK_REAL=1 后 63 例全量单轮产出 `baseline-v4.json`（预估 ~290s / tokens ~60k），锚点校验切换 v4 + scoringVersion v2 登记；无授权则本段不执行、卡在「代码合入 + 旧锚点校验」态停步留痕。
