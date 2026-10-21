# 任务清单 · image-cross-modal-eval

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」；未完成不勾。

## 1. 独立图文评测套件
- [ ] 1.1 `ImageCrossModalSuite`（`IMAGE-SUITE-1.0`，独立编号 IM-01~IM-N）：每条 = 图片查询 + 同图变体（应召回）+ 异图/纯白图（不应召回）对照；不动 `RagBenchmarkSuite`/`SUITE_VERSION`
- [ ] 1.2 评测 fixture：真实图文混排 KB 语料（含内嵌图片 PDF → 视觉向量入库）+ 黄金图片对；复用 `RagBenchmarkDataPreparer` 或独立 preparer

## 2. 评测 runner
- [ ] 2.1 `ImageCrossModalBenchmarkIT`：fixture 幂等入库 → 真视觉 embedding（opt-in `RAG_VISION_EMBED_REAL=1`）→ query-by-image 召回 → 余弦判别判分 → `Report` 落盘 `docs/rag-quality/image-baseline-v1.json`；无 key 整类 Assume 跳过（默认零外呼）
- [ ] 2.2 对照 `text-baseline`：同一图片经 VLM 描述文本 → 文本 collection 召回，与真视觉召回并列呈现（证明增量价值）

## 3. 判分与落锚
- [ ] 3.1 同图命中（余弦>阈值）/ 异图正确拒答 判分口径；汇总「同图召回率 / 异图误召率」对照指标
- [ ] 3.2 独立 `image-baseline-v1.json` 落锚（`Report` suiteVersion=IMAGE-SUITE-1.0）；不碰 baseline-v1~v6

## 4. 测试与验证
- [ ] 4.1 单测：套件构造 / 判分口径 / 余弦阈值边界 / 无 key 跳过（fake 向量回函覆盖，零真外呼）
- [ ] 4.2 `mvn -B -ntp clean verify` BUILD SUCCESS（含 opt-in 真跑 IT 默认 skip 口径不变）+ 基线 `--update` + 校验 `EXIT=0` + `merge-gate.sh` 10/10

## 5. 收口
- [ ] 5.1 变更规格归档 + handoff + 看板/台账更新；停步未 merge 未 push（A 档，独立复核四判据通过后由 Owner 授权 merge）