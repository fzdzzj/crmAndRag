# 任务卡 · image-slice-ingest-chain-reconciliation（卡 27 · B 档 · 零外呼）

## 0 执行记账（执行子 agent 回填，2026-10-25）

- **分支**：`feature/image-slice-ingest-chain-reconciliation`（自 master `cc3c6d5` 检出；未合并未 push）。
- **verdict：`COUNTER-SUPPRESSED`**。七链 n1/n8 相邻差值全 0（`1,1,1,1,1,1,1` / `8,8,8,8,8,8,8`），链路完好；R2 四数 `{invocationsNoCache=6, invocationsWithCache=5, distinctEmbedTexts=5, childRows=6}`、必然命中 1 次、差值恰等于 childRows−distinctEmbedTexts。卡 26 缺口 5 的机制 = 测试侧缓存抑制，分母应换真实子块行数。
- **写集 7 项全落**：探针 `ImageSliceIngestChainReconciliationTest`（7 用例）、守卫 `ImageSliceIngestChainGuardTest`（6 用例）、锚 json（180 行 LF）/md（15 行）、proposal §7 追加、tasks 20 条勾选、本卡 §0。
- **测试**：全量 `mvn -B -ntp test` = `Tests run: 2150, Failures: 0, Errors: 0, Skipped: 0`（基线 345/2137 → 347/2150 只增不减）。
- **变异自证**：R2 数字 5→4 红（`r2FourNumbersRelationsHold`，work/_isic-mut1-r2number-red.log）/ verdict→CHAIN-BROKEN 红（`verdictThreeWayWithConditionalFields`，work/_isic-mut2-verdict-red.log）/ n8 切片 8→7 红（`r1DeltasRecomputedFromCounts`，work/_isic-mut3-r1count-red.log）；还原 13/0 绿（work/_isic-mut-restore-green.log），锚 `cmp` 逐字节一致；全量绿日志 work/_isic-full-test-clean.log。
- **验收**：`git diff master -- src/main` 空；status 恰写集 7 项；入库面两锚 + 签收锚 SHA256 复算与 master 相等（6671204ec2fae043 / d67fef744528c90c / dbe799471fd1bdb1）；`git diff --check` 干净；`*.bak` 已清。
- **零外呼对账**：桩计数 vision=27 / embedImage=25 / embed=22（未命中口径）+ 命中 1；未构造真实 provider、未读 key。
- **执行新坑（3 条）**：① vision 桩 answer 内必须自增计数器——漏写则 describe 恒 0 而切片照常，链计数与切片数静默分叉；② 缓存桩的 embed 调用口径必须数「未命中」（真实外呼）而非 mock 总调用——把命中也计入会测不出抑制差值（R2 恒红）；③ perl -pi 在本机偶发遗留 `.bak`（两次复现），触发 `HarnessGovernanceGuardTest.noBakOrClassFilesInWorkDirectory`，用后必须 `find . -name "*.bak"` 清查。

## 1 目标（一句话）

用**零外呼的 stub 链**把图像切片从「提取 → describe → 过闸 → 切片 → 子块行 → embed 调用 → image 向量暂存」逐链数一遍，判定卡 26 的「过闸 41 图 / 文本 embed 36 次」缺口属于 **`COUNTER-SUPPRESSED`（计数被缓存抑制）** 还是 **`CHAIN-BROKEN`（链路真丢）**。

## 2 为什么不能靠推理

两种解释的处置**相反**：`COUNTER-SUPPRESSED` ⇒ 消融可继续，只需换分母；`CHAIN-BROKEN` ⇒ 必须先改 `src/main`，消融一律暂停。卡 26 花完 124 次外呼才看到数字不符，且当时无逐链计数，所以区分不出来。本卡的成本是 **0 次外呼**。

## 3 写集（只允许动这 7 项，无其他写入者）

新增：
1. `src/test/java/com/slz/crm/quality/ImageSliceIngestChainReconciliationTest.java`（探针：R1/R2/R3/R4）
2. `src/test/java/com/slz/crm/quality/ImageSliceIngestChainGuardTest.java`（守卫：锚对账 + verdict 三选一）
3. `docs/rag-quality/image-slice-ingest-chain-v1.json`（结论锚，LF）
4. `docs/rag-quality/image-slice-ingest-chain-v1.md`（≤40 行）

可改：
5. `openspec/changes/image-slice-ingest-chain-reconciliation/proposal.md`（只许追加「实测补充」，判据 R0–R6 正文禁改）
6. `openspec/changes/image-slice-ingest-chain-reconciliation/tasks.md`（勾选 + 补勾依据）
7. `work/task-card-image-slice-ingest-chain-reconciliation.md`（§0 记账）

## 4 硬边界

- **`src/main` 零 diff**（一行都不许）。需要生产语义就**调用**生产类，别复制阈值/常量。
- **零真实外呼**：只许 stub `ModelProvider`；**禁**读 `DASHSCOPE_API_KEY`，**禁**构造真实 provider，**禁**跑任何 opt-in 真跑 IT。
- 既有六锚 + 签收锚 + 入库面两锚（`near-dup-ingest-gate-probe-v1` / `near-dup-ingest-s1-not-run-v1`）**零字节变动**。
- 既有测试与守卫**不得修改**（只能新增）；**禁新增 `@Disabled`**、禁删既有断言。
- 不加依赖、不改 pom、不做迁移。

## 5 停步条件（命中即停手上报，不许自循环）

1. 需要任何真实外呼；
2. 需要改 `src/main` 才能测（⇒ 说明本卡判据设计有误，回报我改卡）；
3. 测试必须起 Spring 上下文或 Docker 才能跑（本卡要求纯 JVM surefire）；
4. 探针无法区分两种解释 ⇒ **直接出 `INCONCLUSIVE` 并写清缺哪条证据**，**不许**为了有结论去构造有利数据。

## 6 六个已知陷阱（都是实测踩过的）

1. **`resolveMaxImages()` 默认 5**（`PdfImageSliceIngestService:66`，上限 20）。不显式配 `MAX_IMAGES_KEY` 时每文档只抠 5 张，大样本会静默截断在 5——R1 七链第一链就会错，且看起来像「链路丢图」。
2. **桩描述必须过生产闸门**：`VisionIngestSupport.passesQualityGate` 要求 ≥40 字且全角「（截图不清）」占比 ≤0.4。桩文本太短会让链断在「过闸」这一步，被误判成 `CHAIN-BROKEN`。
3. **`embedText` 不是原文**：`ChunkHeaderText.wrap(文件名, 类目, pageNo, rowIndex, 原文)`。图片切片 `rowIndex` 恒 `null`、`pageNo` = 图所在页 ⇒ **同名文件 + 同页 + 同描述 = 相同 embedText**。这是 R2 要逼出的那一次命中。
4. **`ImageSliceIngestSupport` 有一条文档级 `catch (Exception)`**：`buildSlices` 抛异常时**整批图片切片全丢**且只打 WARN；而 `storage.store` 失败只跳过**单张**。两条语义不同，R3 要求分别钉住。
5. **`NearDupIngestGateProbeIT` 是 failsafe 类**：可以引用它的 `static` 工具（既有守卫就这么干），但**不要**让 surefire 测试依赖 Spring 上下文或它的运行期状态。
6. **锚要逐字节可复算**：JSON 用 LF（Jackson 默认缩进器走 `System.lineSeparator()`，Windows 会出 CRLF）；写在测试里由断言钉死，不许手写死值。

## 7 交付与回报

- 全部勾选带 **6 空格缩进补勾依据**（命令 + 实测值 + 行号 + 关键输出行）。
- 回贴 **≤300 字**：提交 SHA / 改动文件数 / `mvn -B -ntp test` 统计行 / **七链七个数** / **R2 四个数** / `verdict` / 未解决项。细节写进任务卡 §0，不进回报。
- **任何「一致 / 全部 / 没有丢」措辞，必须同回合附可复算的数字清单**，否则不许写。
