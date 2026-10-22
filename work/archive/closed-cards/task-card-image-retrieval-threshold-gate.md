# 任务卡 · image-retrieval-threshold-gate（异图阈值进检索硬闸门 + 近重复图分档）

- 档位：**A 档**（src/main 检索行为变更 + 新动态键 + 三档分档；零迁移 / 零新权限）
- 树：**权威树** `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/image-retrieval-threshold-gate`（从 master 检出）
- 目标：把定标锚 0.9944 落到检索侧真实闸门，用「三档分档」解决近重复图单阈值误杀

## Owner 拍板（2026-10-22）
1. 独立图像阈值键 rag.retrieval.image-similarity-threshold（默认 0.9944），recallImage 不再复用文本 minScore 0.20
2. 三档分档：高置信同图(≥阈值)正常召回 / 近似同图(阈值↔下限)召回但降权标记 / 异图(<下限)过滤

## 关键锚点（已实读，勿重复侦察）
- `RetrievalDefaults.MIN_SCORE = 0.20`（文本阈值）；`retrieveFromKnowledgeBases` L253 解析 minScore，L267-268 把同一 minScore 传给 recallImage
- `recallImage` L403-419 走 `recallIn` → `space.search(VectorSearchRequest(..., minScore, ...))`，图像路错配复用文本阈值
- `RetrievalConfigResolver.resolveMinScore` 只有 rag.retrieval.minScore（无 image 专属键）
- 定标锚：`docs/rag-quality/image-threshold-calibration-v1.json`（同图 1.0 / 异图 0.9887 / 阈值 0.9944）

## 任务（写集：src/main knowledge/retrieval + platform/config + src/test，无其他写入者）
1. RetrievalConfigResolver 增 resolveImageSimilarityThreshold + DynamicConfigKeyRegistry 登记键（默认 0.9944）
2. retrieveFromKnowledgeBases 改传独立阈值给 recallImage；recallImage 三档分档（召回/降权标记/过滤）
3. 单测：三档边界 / 近重复图降权 / 文本路 minScore 零回归

## 边界（禁改面）
- 禁改文本 minScore（rag.retrieval.minScore 仍 0.20，文本路零变化）
- 禁改 ImageCrossModalSuite 0.90 / 定标锚本身（只做消费方引用）
- 零迁移、零新权限；新键登记走受控（非冻结契约）

## 验证
`mvn -B -ntp clean verify` + 基线 `--update` EXIT=0 + `merge-gate.sh` 10/10

## 停步
未 merge、未 push、未 reset/rebase/amend、未删分支。回报 ≤300 字（三档边界实现 + 降权语义 + 文本路零回归 + 校验结果），细节留文件。