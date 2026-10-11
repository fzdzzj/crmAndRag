# design — fix-citation-grading-overlap

## 0. 事实基线（取证在案，实现前不得假设）

- 判卷：`RagQualityEvaluator#citationPrecision`（src/main/java/com/slz/crm/quality/RagQualityEvaluator.java L163-186）——引用编号 n → `retrieved.get(n-1) ∈ expected` 占比；
- 归块：`RagBenchmarkDataPreparer`（src/test/java/com/slz/crm/quality/，L204-216）——`goldenToChunkId.putIfAbsent` 最小 chunkIndex 首个完整标记命中；`GOLD_MARKER`/`GOLD_MARKER_FRAGMENT` 正则剥标记；`Preparation.tornMarkers()`（上卡 06e69ac 新增）承载撕裂事件；
- 切分：`FixedChunkingStrategy`（320/40，`start = max(end - CHUNK_OVERLAP, start + 1)`）与 `ParagraphChunkingStrategy`（同窗口基座 + 段落边界收缩）——overlap 复本与跨块段落的共同来源；
- 版本：`RagBenchmarkSuite` L26-32 `SUITE_VERSION = "3.0"`，`expectedChunkIds` 为占位 id 与 fixture `【GOLD:占位id】` 一一对应；
- 锚点：`docs/rag-quality/baseline-v3.json`（suiteVersion=3.0）为现行 63 例 V1 口径锚，17 锚点文件受 SHA256 漂移校验保护。

## D1 方向拍板：判卷口径侧（已选）vs 切分链路侧（否决）

| 候选 | 触碰面 | 否决/采纳理由 |
|---|---|---|
| **判卷口径侧（多块承载计分）** | quality 评测域（src/test Preparer + runner 映射 + evaluator 输入） | **采纳**：A 类 0 例证明邻块引用语义真实；写集最小；indexedText/embedText 不变（向量输入红线天然满足）；不碰生产链路 |
| 切分链路侧（重叠区归属标注） | VectorRecord（平台冻结契约）+ metadata 落库（DDL 风险） | 否决：契约面扩权 + DDL 禁区；且标注只服务于评测，判卷侧等效且更便宜 |
| 改切分消除 overlap | 向量输入变 → 全锚点漂移 | 否决：属 B2 升版范畴（SUITE_VERSION 4.0 + 全量重锚），本卡明确不做 |

## D2 承载判定规则：位置区间交集（确定性）

- **chunk 区间记录**：Preparer 对切分产物逐块求归一化文本区间 [start,end)——顺序游标 + `indexOf`（切片是归一化文本的字面顺序切片，对任意 ChunkingStrategy 策略无关成立）；
- **黄金句 span**：标记起点 → 该标记所标注句/段末（与 fixture 标注语义一致，span 边界在实现时以 Preparer 既有文本视角定夺并在单测锁死）；
- **承载判定**：`chunk ∩ span` 长度 ≥ `MIN_CARRIAGE_CHARS`（常量 16，取证 9 例重叠面均为 40 字符级以上，16 远离 1-2 字符偶然相交）→ 该块入承载集；
- **主锚块保留**：标记归点块（现行最小 chunkIndex 规则）恒在承载集内（承载集 ⊇ 主锚块）；tornMarkers 场景（B3）承载集按同一区间规则自然涵盖语义主体块——撕裂错位的判卷低估被本口径顺带缓解，但 T-15 锚修正仍留 B2（不动 case 不动标注）；
- **口径分离（关键）**：承载集**只供 citP**（evaluator expected 入参）；recall 判卷仍用主锚块集——检索质量指标跨口径可比，baseline-v4 锚中 recall/ansC 与 v3 直接对账、citP 单列新口径。

## D3 评分口径版本与锚点策略

- `scoringVersion` 概念落位：suiteVersion（3.0，case/黄金面）与评分口径（v2=多块承载）**解耦单列**——B1 不动 SUITE_VERSION，B2 升版独立不受阻；
- `baseline-v4.json` 为**新增**锚点文件（suiteVersion=3.0 + scoringVersion=v2 + 63 例 V1 全量），由尾段授权真跑产出；旧锚点 17 文件零触碰；
- 锚点校验切换：RagReal 域锚点校验增量支持 v4（文件在则按 v4 校 citP 口径，recall/ansC 沿用同文件值）；v4 不存在时（真跑前）锚点校验不因本卡代码变红——切换与真跑产出同笔落，CI failsafe 跳过态（恒 6）不受影响。

## D4 受控解冻登记路径

- contracts-frozen.md（平台 20 契约）**零改动**——判卷口径不属平台契约包；
- 解冻登记 = 本卡 `specs/rag-quality/spec-delta.md`：旧口径（标记归点单块）→ 新口径（多块承载 + MIN_CARRIAGE + 口径分离）冻结条款化，含「切分 overlap 语义为生产契约、判卷侧不改切分」的辨权声明；
- 程序合规：owner 拍板本卡 = 变更申请批准；卡合入 = 登记生效（contracts-frozen.md L4 程序在案）。

## 实现面与写集预估（3 tracked + 测试）

1. `RagBenchmarkDataPreparer`：chunk 区间游标、黄金 span、承载集计算（`goldenToChunkId` 单值映射旁**新增** `goldenToCarrierChunkIds` 多值映射，旧映射保留供 recall 主锚）；`Preparation` 增承载集只读视图；
2. runner/evaluator 接线：citP 入参用承载集（映射点在 runner 组装 expected 处，evaluator 公式不动）；
3. `RagQualityEvaluator`：零公式改动（仅 Javadoc 口径注明）；
4. 单测：三形态承载判定（重叠复本/跨块段落/零误报）+ 既有 10 测适配 + mock 全量 citP 方向断言 + recall/ansC 逐例不变断言；
5. 尾段（另授权）：真跑 63 例 → `baseline-v4.json` + 锚点校验切换 + tasks.md §0 留痕。

## 风险与红线

- **向量输入零变化**：indexedText/embedText 逐字节不变（单测断言锁死；这是旧锚点 SHA256 零漂移的前提）；
- **承载集不虚增**：MIN_CARRIAGE 阈值防 1-2 字符偶然相交虚增黄金集（单测零误报形态覆盖）；
- **口径分离防 recall 通胀**：recall 若误用承载集，T-14 类例 recall 将虚升至 1.0——单测断言 recall 逐例不变；
- **fail-safe 域零结构改动**：RagRealRetrievalBenchmarkIT 仅在尾段授权节点后按 D3 增量切锚，非授权合入态不改变 CI failsafe 跳过计数（恒 6）。
