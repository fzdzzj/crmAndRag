# handoff · production-gray-real-run（真共享空间生产灰度真跑）

> 档位：A 档（真实 PDF 全链路真跑 opt-in 落锚；零 src/main / 零迁移 / 零权限 / 零新依赖）
> 分支：feature/production-gray-real-run（永久保留）｜M1 merge --no-ff `51416d7` → 基线收口 `98a4a91`

## §1 目标与背景
多模态真共享空间系列收官卡。前四张（multimodal-shared-space / image-cross-modal-eval / pdf-ingest-image-eval /
anchor-v6-baseline-v7）的语料全是程序化确定性图/PDF，本卡第一次用**真实公有领域政府出版物 PDF**（NIST/DOE）跑完
「摄取 → 视觉/文本双集合 → query-by-image」，度量真实成本基线，回答「打开 `image-vector-ingest.enabled` 后每张图花多少」。

## §2 实现面（10 文件，零 src/main）
- 3 份真实 PDF 入库 `src/test/resources/rag-quality/real-pdf/`（NIST SP 800-101r1 8 图 / NIST SP 800-183 5 图 / DOE 107 图，均 ≤3MB）+ `sources.json`（URL + SHA-256 + 17 U.S.C. §105 授权 + licenseCaveat）。
- `RealPdfAssets`(233) + `RealPdfAssetsTest`(292，逐份断言 ≥2 可提取位图/最短边≥64/PNG 解码/SHA 一致) + `ProductionGrayRealRunIT`(963)。
- `docs/rag-quality/production-gray-cost-v1.{json,md}`（PRODUCTION-GRAY-COST-1.0 独立套件）。

## §3 实测结论（qwen3-vl-plus，真实 PDF）
提取 9 图 → 入库 7（DOE 2 图被描述闸门剔除）；vision 9 次 9 600 token（**实测回流，纠正上一卡「恒 0」口径**）；视觉 embedding
14 / 文本 embedding 32；65.8s，vision 均值 5.9s / 峰值 20.8s；**≈1 371 token、9.8s/图**。检索 7/7 双路命中，同图余弦 1.0，
异图最大 0.9887（仅断言顺序、不套 0.90 硬阈值）。

## §4 零迁移 / 零权限 / 零契约论证
- `git diff --stat -- src/main/` 零命中。
- 零新 controller/端点/权限常量；IT 授权仅 Mockito 桩。
- 生产开关 `image-vector-ingest.enabled` 默认 false 原样；v1~v7/image/pdf 锚零改动；零新依赖（pom 零 diff）。

## §5 真跑论证（A 档判据）
真跑痕迹可验（复核方独立抽验）：vision 9 次 usage 全回流（8001+1599=9600）；imageEmbedding 14 次 token 恒 0（端点不返 usage，
mock 造不出这种不对称）；textEmbed 32 ≠ 结构值 28（+4 来自约束分路），证非公式推导；DOE 3 次 vision 只落 1 片（计数在描述闸门之前）。
`CountingProvider` 装在 embed/embedImage/vision 真调用点。

## §6 偏差处置与治理留痕
- **D1（DOE 逐图版权，归属 Owner 拍板）**：`sources.json` 记 licenseCaveat（usa.gov 提示联邦网站内容可能含受保护 IP）；
  **Owner 2026-10-22 拍板「保留 DOE + 如实标注」**（测试夹具、非商业再发布）。
- **D2（vision token 口径纠正）**：上一卡 `pdf-ingest-image-eval` 记「视觉端点不回流 usage」在本机不成立——本次 vision 9 次全回流
  9 600 token；相反两类 embedding 端点 token 恒 0（有样本无计量）。已在本成本基线 §4.2 纠正。
- **D3（异图硬阈值不适用真实语料）**：真实语料异图间隔可低至 +0.011（版式图），仅断言「异图 < 同图」顺序；硬阈值定标属后续卡。
- **D4（图帽 3 是成本帽）**：生产默认 max-images-per-document=5（硬上限 20），本卡为控真跑成本取 3；灰度决策按生产默认帽重算。

## §7 停步声明
未 push 前停步；push 由主 Agent 在 Owner 授权 merge 后执行（`1ae1de1..98a4a91`）。未 reset/rebase/amend/删分支。