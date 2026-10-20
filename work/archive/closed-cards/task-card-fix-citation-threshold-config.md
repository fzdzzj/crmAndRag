# 任务卡 · fix-citation-threshold-config（B 档）

- 基点：master@2c8a148（surefire 292 报告/1827、failsafe 32/119/7、门禁 10/10）
- 分支：`feature/fix-citation-threshold-config`（权威树检出）
- 来源：池账 #27（C-2）

## 目标
1. 三阈值动态化：CitationThresholds record + align 重载（默认=现常量零漂移）+ 生产调用点配置注入 + registry 三键（rag.retrieval.citation.*）。
2. 评测同源回灌：真跑 IT citations 先经 CitationAligner；回灌证据单测（黄金被误杀 → citP 降）。

## 红线
零迁移/零依赖/零新错误码/不动前端/基线只增不减/默认值行为逐字节等价/核心类零 Spring/未授权不真跑。

## 验收
定向+全量 verify + 单次基线 --update + merge-gate 10/10 + handoff 四件套（路径图/键对照/断言/零漂移论证）。**B 档**：门禁 0 + 主 Agent 审 diff。**停步**：未 merge 未 push。
