# 提案：候选池待取证方向收口复查（close-candidate-pool-residue）

## 为什么

2026-10-08 候选池重盘（intake-openspec-proposal-specs）在 §4 留了三个「待取证方向」。同日主 agent 亲测完成取证：① 前端质量残留——权威树 `pnpm lint:check`（`eslint --ext .js,.vue src`）@master 3c8a8b5 实测零输出（0 警告 0 错误），已清零；② docs/ingest-gap-map.md 遗留项——唯一推荐切口（图像 PDF 检测 + 单页 VLM 转写试点，默认关）已随 add-vision-pdf-ingest-pilot 合入（`PdfVisionTranscriber` 入 src/main，`rag.retrieval.vision-pdf.*` 三键已注册 DynamicConfigKeyRegistry），剩余项属 owner 成本拍板延伸；③ 重放运行配套——仍被 `rag.ingest.replay-enabled` 生产启用授权（费用红线）与运行数据双重前置阻塞。owner 拍板（AskUserQuestion，2026-10-08）：以候选池收尾微卡把三项结论落盘，保持台账诚实。

## 做什么

1. docs/backend-optimization-candidates.md 两处改动：头部注记追加「同日 §4 收口复查」；§4 三方向改写为带日期、带证据（命令 / 节点 hash）的定夺结论（清零关闭 / 已落地注记 / 维持阻塞并注明前置），以主 agent 预置底稿 work/_paa-candidates-pool.md 整文件覆盖落盘（纯 LF，三处 sha256 一致留证）。
2. 本卡三件套随卡入库（承 intake-openspec-proposal-specs 契约 5）。

## 不做什么

- 零 Java 代码、零 frontend、零迁移、零依赖（`src/` 与 `frontend/` diff 必须为空）。
- 不动 §1 方法论原文、§2 闭环归档表、§3 现行挂账（2026-10-08 重盘定夺继续有效）。
- 不补勾 openspec/changes/ 下任何 tasks.md 历史未勾项（复核区未勾属只读复核惯例、授权门控项属 owner 决策，均不在本卡范围）。
- work/ 执行留痕不入库（惯例不变）。
- 不动测试基线台账（surefire 1019 逐字不变）。

## 影响

纯文档治理：候选池 §4 从「无证据待办」转为「日期化证据结论」；后续功能方向（VLM 泛化 / PPTX / OCR / PDF 表格 / 重放监控）明确为待 owner 拍板项。红测试豁免（纯文档零代码，P-z 先例），最强不变量 = 1019 surefire 逐字不变。
