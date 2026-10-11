# design — harden-gold-marker-tearing

## 撕裂形态学（取证实证）

滑窗 320 / overlap 40；标记 `【GOLD:占位id】` 长度小于 overlap，故切点穿过标记时完整标记必然因 overlap 复制在至少一块完整命中（设计假设仍成立，归点不受影响）。撕裂产生两种残段 + 一种错位：

| 形态 | 位置 | 现行处置 | 缺陷 |
|---|---|---|---|
| 左残段（`【GOLD:…` 前缀残段，无闭合） | 左块尾部 | FRAGMENT 剥掉（索引文本干净） | 剥离正确但**无检出信号** |
| 右残段（`占位id】`，无前缀） | 右块开头 | FRAGMENT **不识别** | 泄漏进索引文本（T-15 实锤：sop-3 以 `customer-onboard-3】` 开头） |
| 归点-主体分离 | 跨块 | 归点=标记完整命中块（sop-2），语义主体在 sop-3 | **无任何显式信号**，citP 口径恒错 |

T-15 实测三件套（citation-forensics-v4.json trace + 复核字面核验）：
- `goldenToChunkId("customer-onboard-3") = customer-sop-2`（完整标记 overlap 复制命中、最小 chunkIndex 规则）；
- customer-sop-2 索引文本止于段首句，不含报备句；customer-sop-3 含报备句且以右残段开头。

## 检出算法（两遍收集，只读旁路）

对每个 fixture，在现有 chunk 循环之外追加（**主循环行为逐字不动**）：

1. **第一遍（goldIds 收集）**：先对该 fixture 全部 chunks 跑一遍 `GOLD_MARKER` 完整扫描，收集合法占位 id 集合 `fixtureGoldIds`（只读；右残段的 goldId 必属于该集合——归点已证明完整命中存在）；
2. **第二遍（检出）**：逐 chunk 在**剥离前原文** `chunk.text()` 上检出：
   - **右残段**：对每个 `fixtureGoldIds` 中的 id，匹配 `(?<!【GOLD:)` + id + `】`（Java 定长负向后视）→ 登记 `RIGHT` 事件。负向后视排除完整标记本体；
   - **左残段**：`GOLD_MARKER_FRAGMENT` 命中片段中，凡未被完整 `GOLD_MARKER` 命中区间覆盖的（即前缀残段）→ 登记 `LEFT` 事件（goldId 尽力解析：残段含 `:` 且带可读前缀则记部分 id，否则记空串）；
3. 事件按 fixture 顺序 + chunkIndex 确定性排序，经 `Preparation.tornMarkers()` 产出（无撕裂语料为空表，零开销）。

## 事件模型

```java
/** harden-gold-marker-tearing 任务 1.2：GOLD 标记撕裂检出事件（只读旁路，零行为变更）。 */
record TornMarkerEvent(
    String goldId,          // 涉事占位 id（左残段可能为部分/空）
    Direction direction,    // LEFT（前缀残段）/ RIGHT（右残段）
    String tornChunkId,     // 残段所在 chunkId
    String alignedChunkId,  // 该 goldId 的归点 chunkId（RIGHT 事件对照用；无归点时空）
    String matchedFragment  // 残段原文（留证）
) { enum Direction { LEFT, RIGHT } }
```

`Preparation` record 尾部追加 `List<TornMarkerEvent> tornMarkers`；构造点仅 `prepare` 一处（无其他调用方直接构造）。

检出非空时 prepare 内打一行聚合 WARN（`java.util.logging`，理由：Preparer 属 surefire 域单测可达，真跑 IT 走同一 prepare 自然产出该行，**避免改动 failsafe 域的 RagRealRetrievalBenchmarkIT 文件**）。

## 零行为变更证明面

| 面 | 证明 |
|---|---|
| indexedText / embedText | 剥离逻辑逐字不动；单测锁 sop-3 索引文本仍以 `customer-onboard-3】` 开头 |
| goldenToChunkId | 归点逻辑逐字不动；单测锁 customer-onboard-3 → customer-sop-2 |
| chunkIds / 向量输入 / 幂等 | 主循环不动；`rerunProducesIdenticalChunkIdSetAndMapping` 等现有 7 测零改动全绿 |
| failsafe 域 | RagRealRetrievalBenchmarkIT 零触碰，failsafe 报告计数不变 |
| report / trace JSON 产物 | runner 产物结构零改动；撕裂信息仅走 WARN 日志行 |
| 锚点文件 | 全部禁改，mvn test 跑前跑后 SHA256 对账 |

## 测试矩阵（红绿）

| 测试 | 断言 | 红绿形态 |
|---|---|---|
| 新增 1：T-15 撕裂现场检出 | tornMarkers 含 customer-onboard-3 的 RIGHT 事件（tornChunkId=customer-sop-3，alignedChunkId=customer-sop-2，matchedFragment 以 `customer-onboard-3】` 结尾）；customer-sop 语料同时存在 LEFT 事件；左残段精确形态以实测校准写入测试注释 | 骨架（空表字段）先落 → 实跑红（期望非空实际空）→ 实现检出转绿 |
| 新增 2：零行为变更锁 + 零误报 | sop-3 索引文本仍以右残段开头（剥离行为未漂）；归点仍 customer-sop-2；tornMarkers 中所有事件均属 customer-sop 语料（其余 14 份语料零登记） | 同上 |
| 现有 7 测 | 零改动，全绿 | 不红 |

surefire：1089 → 1090+（新增 1-2 个测试方法），`bash scripts/check-test-baseline.sh --update` 从本次真实运行写入。

## 风险与回退

- **误报**：右残段依赖负向后视 + 合法 goldId 集合——占位 id 为脚手架命名（`customer-onboard-3` 等），正文自然出现 `goldId】` 形态概率为零；即便误报，事件表只影响日志可见性，不 fail、不阻断、不改判分；
- **PMD 复杂度**：Preparer 现有体量不小，若新增检出触发 PMD 规则红，允许把检出逻辑拆为包内协作者类 `TornMarkerScanner`（仍标注本卡任务号），其余禁拆禁挪；
- **回退**：纯增量字段 + 只读旁路 + 日志行，revert 单笔即回原状。
