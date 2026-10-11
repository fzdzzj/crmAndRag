# proposal — harden-gold-marker-tearing

## 背景与动机

取证卡 trace-citation-redundancy（citation-forensics-v4.md §5-M3 / §6，已合入 master @ 88ba84d）实测坐实：T-15 的 GOLD 标记 `【GOLD:customer-onboard-3】`（customer-sop.md L9 段首）被 320/40 滑窗切分边界撕裂——完整标记因 overlap 复制命中 customer-sop-2（归点按设计正确），但**语义主体（战略客户报备句）落在 customer-sop-3**（非黄金块），且 customer-sop-3 索引文本以右残段 `customer-onboard-3】` 开头。该错位解释了 T-15 citP 三轮波动（v2 0.333 → v3 0 → v4 0）。

三个机械缺陷（取证在案）：

1. **右残段剥离缺口**：`GOLD_MARKER_FRAGMENT`（`【GOLD(?:[^】]*)?】?`）只识别含 `【GOLD` 前缀的左残段；切点落在标记中间时，右残段（`占位id】`，无前缀）泄漏进索引文本——污染向量输入与引用展示（excerpt 实锤）；
2. **断言盲区**：`RagBenchmarkDataPreparerTest#indexedTextIsCleanAndMetadataMatchesRetrievalFilter` 仅断言 `!text.contains("【GOLD")`——右残段恰好绕过，撕裂静默通过全部门禁；
3. **错位无信号**：归点块（sop-2）与语义主体块（sop-3）分离没有任何显式产出，根因排查只能靠人工取证逐层定位。

## 方案（纯检出登记，零行为变更）

- `RagBenchmarkDataPreparer` 新增撕裂检出：**左残段事件 + 右残段事件 + 归点对照**（alignedChunkId ≠ tornChunkId 即黄金锚与内容主体分离的机械证据），经 `Preparation.tornMarkers()` 结构化产出；检出非空时 prepare 内打一行 WARN 日志（JUL，避免触碰 failsafe 域的 IT 文件）；
- 单测锁 T-15 现场（右残段 / 左残段 / 归点三断言）+ **锁现行行为**（sop-3 索引文本仍以残片开头——防止实现手滑改剥离导致锚点漂移）。

## 不做什么（边界）

- **不剥右残段**：剥离改变 indexedText / embedText → 向量输入变 → baseline-v3 锚点漂移。右残段清剿与 T-15 黄金锚修正属 **B2 升版受控校准**范畴（SUITE_VERSION 4.0 + 全量重锚 + 真跑授权），本卡不碰；
- 不改归点规则（最小 chunkIndex 首个完整命中者——机制本身正确，T-15 是数据面撕裂非规则缺陷）；
- 不改 fixtures / RagBenchmarkSuite / SUITE_VERSION / 任何锚点文件（baseline-v1|v2|v3、after-*、i05-*、citation-forensics-v4* 全部禁改）；
- 不动 RagRealRetrievalBenchmarkIT（failsafe 域零改动）与 report/trace JSON 产物结构；
- 零生产代码（全部改动在 src/test 测试域）、零 DDL、零新依赖、不碰 frontend/。

## 验收

1. 新增单测红转绿（T-15 现场三断言 + 零行为变更锁 + 无撕裂语料零误报）；
2. 现有 7 个 `RagBenchmarkDataPreparerTest` 测试零改动全绿；surefire 基线 1089 → 1090+（只增不减，`check-test-baseline.sh --update` 从真实运行写入）；
3. `mvn -B -ntp test` 全量 0 失败；merge-gate 全绿（八子门禁）；锚点文件 SHA256 跑前跑后一致；
4. 无真外呼（全程 fake embedder，无 RAG_BENCHMARK_REAL 节点，¥0）。
