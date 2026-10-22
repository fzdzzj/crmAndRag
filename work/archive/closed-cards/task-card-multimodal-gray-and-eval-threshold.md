# 任务卡 · multimodal-gray-and-eval-threshold（多模态灰度授权 + 异图阈值定标 + embedding 计量口径）

- 档位：**A 档**（异图定标真跑 + embedding 计量可能触碰冻结契约受控解冻；生产开关登记为纯文档）
- 树：**权威树** `D:\code\crmAndRag-merge-add-knowledge-admin-api`，分支 `feature/multimodal-gray-and-eval-threshold`（从 master 检出）
- 目标：一次做清多模态系列三个收尾项——生产开关灰度路径登记 / 异图硬阈值定标 / embedding 计量诚实化

## Owner 拍板（2026-10-22）
1. 生产开关 = 只登记运行期灰度路径（不改代码，默认 false 保持）
2. 三卡硬合一张 A 档卡

## 关键锚点（已实读，勿重复侦察）
- 生产开关：`DynamicConfigKeyRegistry` `image-vector-ingest.enabled` 默认 false（COST 档超管专写）+ `kb-allowlist` + `max-images-per-document`（默认 5）
- 异图阈值：`ImageCrossModalSuite` 0.90（程序化语料）；production-gray-real-run 实测真实语料异图最大 0.9887（间隔 +0.011）
- embedding 计量：`ModelProviderImpl.embed` L171-176 取 usage 但 DashScope 不返 → null；`ImageEmbeddingSupport.parseResponse` L129-132 `positiveOrNull` 取 input_tokens/total_tokens 但端点不返 → 0；调用次数已由 `VisionIngestSupport.recordEmbeddingUsage`（EMBEDDING 类型）计入 TokenUsageRecorder
- `ImageCrossModalEvaluator`（余弦判据框架，可复用）

## 任务（写集 + 零 src/main 或极小 src/main）
1. 生产开关运行期灰度路径登记（纯文档，看板/docs，零代码）
2. 异图硬阈值定标：多图同类/跨类语料 + 真跑 RAG_VISION_EMBED_REAL=1 + 落锚 image-threshold-calibration-v1.json
3. embedding 计量诚实化：调用次数已记 + token 不可得显式声明（若碰 TokenUsageRecord/ModelCallResult 走受控解冻）

## 边界（禁改面）
- 禁改生产开关默认值、禁改摄取/检索链路行为、禁碰既有 baseline（v1~v7/image/pdf/production-gray 锚）
- 零迁移、零新权限；embedding 计量若碰冻结契约必须受控解冻（不走捷径）

## 验证
`mvn -B -ntp clean verify` + 基线 `--update` EXIT=0 + `merge-gate.sh` 10/10；定标真跑后阈值锚点成功落盘

## 停步
未 merge、未 push、未 reset/rebase/amend、未删分支。回报 ≤300 字（三任务组产出 + 定标阈值结论 + embedding 计量口径 + 校验结果），细节留文件。