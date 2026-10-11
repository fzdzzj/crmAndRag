# 规范增量：rag-quality

## ADDED Requirements

### Requirement: 引用精度按多块承载判卷（scoringVersion=v2 受控解冻）

citationPrecision 的判卷期望 SHALL 为**黄金承载集**：位置区间承载黄金句 span 的全部块。黄金句 span = GOLD 标记起点至段末（首个换行符，与 fixture「段首标记标注整段」的标注语义一致；xlsx 行语料无行内换行即该行全文）；块承载 = 块区间（页组内重叠消解拼接坐标下的字面连续区间，对任意 ChunkingStrategy 策略无关）与 span 的区间交集长度 ≥ `MIN_CARRIAGE_CHARS`（常量 16，远离 1-2 字符偶然相交）。主锚块（最小 chunkIndex 完整标记命中，现行归点规则）MUST 恒在承载集内。

口径分离（冻结条款）：承载集 **MUST 只供 citationPrecision 判卷**；recall / precision / MRR / answerConsistency 一律仍按主锚块集判卷，保证检索质量指标跨口径可比。评分口径版本化：v1 = 主锚块单块（3 参 `RagQualityEvaluator.evaluate` 兼容口径）；v2 = 多块承载（4 参重载，`citationExpectedByCaseId` 入参）；`citationPrecision` 公式零改动，仅期望入参切换。`scoringVersion` 随真跑锚点 `baseline-v4.json` 登记落盘（任务组 4，另授权）。

判卷侧不改切分（辨权声明，design D1/D4）：切分 320/40 overlap 语义为生产契约，判卷侧 MUST NOT 为判分改切分链路——改切分即向量输入变（indexedText/embedText 逐字节变化）即全锚点漂移，属 B2 升版范畴（SUITE_VERSION 4.0 + 全量重锚）。本口径变更下 indexedText / embedText MUST 逐字节不变，旧锚点文件（含 baseline-v3.json）SHA256 MUST 零漂移。

#### Scenario: 重叠复本承载
- **WHEN** 黄金段尾部被滑窗 overlap 复制进邻块头部（取证 v4 T-02 的 B1 形态）
- **THEN** 邻块计入承载集
- **AND** 模型引用该邻块时不再因口径误判非黄金

#### Scenario: 跨块段落与撕裂语义主体承载
- **WHEN** 黄金段被切分到两块、或标记撕裂致归点与语义主体分离（T-15 的 B3 场景）
- **THEN** 语义主体块按同一区间规则自然计入承载集

#### Scenario: 无承载零误报
- **WHEN** 黄金段末与切分边界的区间交集 < MIN_CARRIAGE_CHARS（16）
- **THEN** 邻块不计入承载集，承载集恰为锚点单块

#### Scenario: 口径分离防 recall 通胀
- **WHEN** 同一批确定性检索产出分别按 v1/v2 双口径评分
- **THEN** recall / precision / MRR / ansC 逐例一致
- **AND** 承载集非空例 citP 不降且存在严格上升例

#### Scenario: 判卷侧不改切分（辨权）
- **WHEN** 本口径变更实施
- **THEN** 切分 320/40 语义、CitationAligner、fixtures、RagBenchmarkSuite、SUITE_VERSION=3.0 零改动
- **AND** indexedText / embedText 逐字节不变、旧锚点文件 SHA256 零漂移

#### Scenario: 真跑另授权
- **WHEN** 未获 owner 授权执行任务组 4
- **THEN** 不设置 `RAG_BENCHMARK_REAL=1`，`baseline-v4.json` 不产出
- **AND** 卡停在「代码合入 + 旧锚点校验」态留痕
