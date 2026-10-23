# 任务清单 · retrieval-and-contract-hardening（三合一）

> 档位 **A**（取三组最高：含冻结契约改动）。**零真外呼、零新依赖、零迁移、不改任何默认值、不打开任何生产开关。**
> 分组：**G0** 立项共用 → **G1** 近重复机制决策 → **G2** 图片开关实证 → **G3** 契约权限生成化 → **G4** 全量门禁与照判。
> 勾选规则：勾选行**下一行**必须有 6 空格缩进「补勾依据」+ 可独立复跑的命令/哈希。**禁止无证据补勾、禁止以推断冒充实测。**
> 判据（C1–C6 / A1–N1 / P1–P4）见 `proposal.md` §1.2 / §2.2 / §3.3，**跑前写死，跑后禁改**。
> 停步裁决见 `proposal.md` §5：任一组触发停步 ⇒ 该组产物整体回退到 master 状态，其余组继续做完。
>
> **执行结果一句话**：三组**全部交付**（无停步）。G1 = UNDECIDABLE-BY-DATA（C5 第 ③ 支：关节不可达、两侧可达，交付两侧前沿 + 定夺所需数据）；G2 七条断言全绿且真证到"未重启"；
> G3 P1/P2/P4 绿、P3 的前端 `build` 项**环境级红**（master 同红，非本卡引入，见 3.3.4 与 `report.md` §6）。

## 0. G0 · 立项与共用侦察（零外呼）

- [x] 0.1 三份现状锚点各复述一遍并给行号/数值：`ImageSimilarityGate` 五常量（L31/34/37/40/43）；`PdfImageSliceIngestService` 四处（L39/64/136/152-157）；`frontend/openapi.yaml` 行数 11028 与 `x-` 计数 0
      补勾依据：实测复述——`ImageSimilarityGate` L31 `DEFAULT_THRESHOLD=0.9944D`、L34 `NEAR_DUPLICATE_FLOOR=0.9887D`、L37/40/43 权重 `0.75/0.6/0.5`；`PdfImageSliceIngestService` L39 段落语义（方案 A 与方案 B 并行）、L64 `IMAGE_VECTOR_ENABLED_KEY="rag.retrieval.image-vector-ingest.enabled"`、L136 `imageVectorEnabled() ? ImageVisualVectorSupport.begin() : null`（视觉路在方案 B 门控 `if (enabled(knowledgeBaseId))` 之后）、L152-157 `config.get(IMAGE_VECTOR_ENABLED_KEY, Boolean.class, Boolean.FALSE)`（每调用重读）；契约基线用 `git show master:frontend/openapi.yaml | Measure-Object -Line` = **11028**、`git show master:frontend/openapi.yaml | Select-String '^\s+x-'` = **0**（本卡更新后 11825 行 / 279 个 `x-`）。
- [x] 0.2 确认开工基线：`git status --porcelain` 干净、`git log -1 --oneline` 记录、分支 `feature/retrieval-and-contract-hardening` 从 `master` 检出
      补勾依据：`git checkout -b feature/retrieval-and-contract-hardening`（git 2.20.1，无 `git switch`）→ 只 `git add openspec/changes/retrieval-and-contract-hardening work/task-card-retrieval-and-contract-hardening.md` → 首提交 **`50b7603 docs(card): 立项 retrieval-and-contract-hardening 三合一卡`**，提交后 `git status --porcelain` 为空（复跑：`git log --oneline -1 && git status --porcelain`）。
- [x] 0.3 把三组判据的数值原样登记成代码常量或文案（C1=0.90 / C2=0.02 / floor 200 点步 0.0005 / dHash 0..24；A3 == 图数；P1 0 例外），供后续守卫与复核逐字比对
      补勾依据：`NearDuplicateMechanismAnalyzer` 常量 `C1_MIN_RETENTION=0.90D` / `C2_MAX_LEAKAGE=0.02D` / `FLOOR_GRID_START=0.90D` / `FLOOR_GRID_STEP=0.0005D` / `FLOOR_GRID_POINTS=200` / `DHASH_MIN=0` / `DHASH_MAX=24`，由 `NearDuplicateMechanismAnalyzerTest#criteriaConstantsArePinned` 逐值断言；G2 的 `IMAGE_COUNT=3` 在 `ImageVectorSwitchHotFlipIT` L83（A3 断言 `== IMAGE_COUNT`）；G3 的 P1「0 例外」= `OpenApiPermissionContractParityTest#everyOperationDeclaresExactlyOnePermissionDimension` 断言 `violations == List.of()`。复跑：`mvn -B -ntp test -Dtest=NearDuplicateMechanismAnalyzerTest`。
- [x] 0.4 记录 G3 需要的 operation 基数：导出契约里 path×method 总数（命令 + 数值），供 P1 报数与复核抽查
      补勾依据：**279**（= SECURED 259 + OPEN 20），实测输出行 `[G3][P1] operations=279 SECURED=259 OPEN=20`，落盘 `target/g3-parity.log`；复跑 `mvn -B -ntp test -Dtest=OpenApiPermissionContractParityTest`（约 30 s，起 test+dev 上下文）。

## 1. G1 · 近重复机制决策（零外呼离线）

### 1.1 口径冻结

- [x] 1.1.1 复核锚身份并与本卡重生成语料比对：`corpus.bases=24`、`images=144`、`pairs=540`、`imageEmbeddingCalls=288`、`strata` 四类计数（144/120/100/176），不等即 fail-fast
      补勾依据：`NearDuplicateMechanismDecisionTest` 内 `assertEquals` 逐项 fail-fast：bases=24、variants=120、images=144、skips=0、pairs=540、strata 144/120/100/176；`imageEmbeddingCalls=288` 从锚回读并写入决策锚 `corpus.imageEmbeddingCalls`（守卫测试再校验一次）。实测通过（`mvn -B -ntp test -Dtest=NearDuplicateMechanismDecisionTest`，Tests run: 1, Failures: 0）。
- [x] 1.1.2 `NearDuplicateMechanismAnalyzer` 写入 `C1_MIN_RETENTION=0.90`、`C2_MAX_LEAKAGE=0.02`、`FLOOR_GRID={0.90 + 0.0005*k, k=0..199}`、`DHASH_D=0..24` 常量，注释注明"来自 proposal §1.2，禁改"
      补勾依据：常量与 Javadoc「判据 C1/C2/C6…（proposal §1.2 原文，禁改）」在 `NearDuplicateMechanismAnalyzer` L30-62；网格点由 `floorAt(int)` 定点计算 `(9000 + index*5)/10000D`（首点 0.90、末点 0.9995、步长 0.0005，`NearDuplicateMechanismAnalyzerTest#floorGridAndMonotonicityFollowTheSpec` 断言）。
- [x] 1.1.3 复算基线并写入锚：`R_keep = 70/120 = 0.583`、`L = 3/276 = 0.0109`、逐 kind 保留数（供 G1 照判比对）
      补勾依据：`[G1][表 1/5 基线] floor=0.9887 R_keep=70/120=0.583333 L=3/276=0.01087 逐kind={jpeg-q80=18, scale-085=21, scale-070=22, crop-04=9}`（crop-12 缺省 = 0/24），落盘 `target/near-dup-mechanism-report.txt`；决策锚 `baseline.outcome.retainedVariant=70 / leakedNegative=3`，由守卫测试 `baselineAndParetoArePresent` 复校。

### 1.2 感知哈希纯函数

- [x] 1.2.1 新增 `PerceptualHashSupport`：`BufferedImage -> long`（9×8、BT.601 定点整数灰度、面积平均缩放、相邻差分 64 bit）；禁止 I/O、禁止网络类型
      补勾依据：`src/test/java/com/slz/crm/quality/PerceptualHashSupport.java` 仅 import `java.awt.image.BufferedImage`，无 I/O/网络类；`dHash` 用 `image.getRGB(0,0,w,h,null,0,w)` 一次批量取像素 + 面积归属 `x*9/width`，灰度 `(299R+587G+114B)/1000` 定点整数；64 bit = 8 行 × 8 次相邻比较（`bit = row*8+col`）。
- [x] 1.2.2 提供 `hamming(long,long)` / `distanceRatio(...)`
      补勾依据：同文件 `hamming(long,long)` = `Long.bitCount(left ^ right)`；`distanceRatio` = `hamming / BITS(64)`；两者被 `NearDuplicateMechanismAnalyzerTest` 断言（全 1 哈希 vs 全 0 哈希距离 64、占比 1.0）。
- [x] 1.2.3 `PerceptualHashSupportTest`：同图两次距离 0；1 bit 翻转距离 1；2× 放大距离 0；纯色/梯度图符合预期
      补勾依据：**口径偏差已声明**——proposal §1.4 与 task-card 写入面并集**都没有** `PerceptualHashSupportTest` 这个文件，为不越写入面，四类断言**并入** `NearDuplicateMechanismAnalyzerTest#dHashIsDeterministicOnDesignedRasters / #dHashToleratesRescaleAndCountsFlips`（同图两次距离 0、递增灰度全 0、递减灰度全 1、纯色全 0、1 bit 翻转距离 1、2× 放大距离 0、首行 5 格降序距离 5）。复跑：`mvn -B -ntp test -Dtest=NearDuplicateMechanismAnalyzerTest`（Tests run: 6, Failures: 0）。

### 1.3 机制评估

- [x] 1.3.1 `NearDuplicateMechanismAnalyzer` 输入 = 540 对（`leftKey/rightKey/stratum/kind/cosine`）+ `Map<String,BufferedImage>`（键与锚同命名空间）+ 判据常量
      补勾依据：`record Pair(leftKey,rightKey,stratum,kind,cosine)` 原样承载锚的 5 字段（`NearDuplicateMechanismDecisionTest#pairs` 逐字段读取，`kind:null` 保留 null）；图集合以 `Map<String,Long>`（键 → dHash）传入——**位图逐张解码后立刻丢弃**，键命名空间与锚一致（`requireAligned` 校验 146 个被引用键）。
- [x] 1.3.2 M1：threshold 固定 0.9944，`floor` 200 点逐点算 `R_keep/L/逐 kind R_keep`（测试侧自实现 `tierOf` 同语义比较，禁调 `src/main` 私有方法）
      补勾依据：`tierOfCosine(cosine, floor)` 独立重写三档语义（`>= 0.9944` 同图 / `>= floor` 近似 / 其余丢弃），常量 `THRESHOLD=0.9944D`、`COSINE_FLOOR=0.9887D` 为字面量而非引用；`scanFloors(pairs)` 返回 200 点，每点 `Outcome` 含 retainedVariant/leakedNegative/retainedByKind（逐点入锚）。未调用 `ImageSimilarityGate` 任何方法。
- [x] 1.3.3 M2：`D` 0..24 逐点；同源 120 / 负样本 276 / same-image 144 分别统计
      补勾依据：`scanDHash(pairs, hashes)` 返回 25 点；`Outcome` 同时给出 `variantTotal/retainedVariant`（分母 120）、`negativeTotal/leakedNegative`（分母 276）、`sameImageClassified`（144 对同图对另由自证①单独断言 dHash 距离全 0）。锚 `mechanisms.M2.points` 25 条，守卫测试校验距离 0..24 全覆盖。
- [x] 1.3.4 M3：`(D_low,D_high)` 全组合（`D_low<=D_high`）逐点；规则写死：`dHash<=D_low ⇒ 同图`；否则 `dHash<=D_high || cosine>=0.9887 ⇒ 近似带`；否则丢弃
      补勾依据：`scanHybrid` 返回 **325** 点（全组合无重复，守卫测试用 `Set` 去重后仍 325）；规则实现在 `tierOfHash`（`Mechanism.HYBRID && distance <= distanceLow` → SAME；`distance <= distanceHigh || cosine >= COSINE_FLOOR` → NEAR；否则 CROSS），符合 proposal §1.3 表。
- [x] 1.3.5 键对齐 fail-fast：任一对键在图集合缺失 ⇒ `IllegalStateException` 报出键名（禁止静默跳过/兜底图）
      补勾依据：`NearDuplicateMechanismAnalyzer#requireAligned(List<Pair>, Set<String>)` 收集全部缺失键后抛 `IllegalStateException("样本图键在语料中缺失（禁止静默跳过/兜底图）: [...]")`；`NearDuplicateMechanismAnalyzerTest#missingImageKeyFailsFast` 断言异常消息含 `missing-key`。
- [x] 1.3.6 `NearDuplicateMechanismAnalyzerTest`：合成 6 图 3 对，手算期望逐项断言；另加"少一张图必须抛错"用例
      补勾依据：`threeMechanismsMatchHandComputedExpectations` 用 6 张 9×8 合成图（每格 = 一像素 ⇒ 可手推）构造 3 对同源（dHash 距离 0/5/64）+ 2 对负样本（距离 64/0），逐项断言 FLOOR@0.9887 → 2 保留 0 泄漏、DHASH D=5 → 2/1、D=4 → 1/1、HYBRID(0,5) → 2/1 且 `sameImageClassified=2`；`missingImageKeyFailsFast` 为抛错用例。Tests run: 6, Failures: 0。

### 1.4 决策锚与守卫

- [x] 1.4.1 `NearDuplicateMechanismDecisionTest`：复用 `NearDuplicateCorpus` 取 24 base + 120 变体的 `BufferedImage` 与键，按 `leftKey/rightKey` 与已提交锚连接（**禁止二次栅格化**）
      补勾依据：`NearDuplicateCorpus.generate()` 一次产出 24 base + 120 变体，`hashesByKey(...)` 只按锚引用到的 146 个键解码其 PNG 字节 → dHash → 立即丢弃位图（不重跑 PDF 提取、不重造变体、不改语料定义），166 行内的 `decode(...)` 是唯一 PNG 解码点；连接关系 = 锚 `pairs[].leftKey/rightKey` 直接查表。
- [x] 1.4.2 语料复现自证：144 个 `same-image` 对本卡重算必须仍判"同图"（dHash 距离 0 + 余弦回读一致），否则 fail-fast
      补勾依据：自证①实测 **144/144/144**（`sameImagePairs=144`、`sameImageDistanceZero=144`、`sameImageCosineOne=144`，判据 `|cosine-1| <= 1e-9`），写入决策锚 `selfChecks`，守卫测试 `corpusIdentityAndSameImageSelfProof` 再校验一次。
- [x] 1.4.3 写模式：`-Dnear.dup.decision.write=true` 才写 `docs/rag-quality/near-dup-mechanism-decision-v1.json`（唯一写入口）；默认模式只断言不写
      补勾依据：`Boolean.parseBoolean(System.getProperty("near.dup.decision.write","false"))` 分支：true → `write(decision)`（`[G1] 写模式：决策锚已写入`）；false → `assertMatchesCommitted(...)` 与已提交锚逐字段相等（`generatedAt`/`elapsedMillis` 除外），实测两种模式各跑一次均绿。
- [x] 1.4.4 锚字段齐全：`criteria`（C1–C6 原样回显）、`corpus`（含锚文件 SHA-256）、`mechanisms[]`（每机制**全网格**）、`pareto`、`verdict`+`verdictReason`、`externalCalls: 0`、`generatedAt`、`elapsedMillis`
      补勾依据：决策锚实测字段 `card/group/decisionVersion/criteria/corpus/productionConstants/baseline/selfChecks/mechanisms(M1|M2|M3)/pareto(M1|M2|M3)/raisingTarget/externalCalls/verdict/verdictReason/generatedAt/elapsedMillis`；`corpus.anchorSha256=82d24204fba8029673d18ea5685964cf4134d2c43e3da5746986881bf35bdc65`（文件 SHA-256，python 复算一致）；锚大小 339590 字节（LF 口径，与 git 规范化后的工作树一致）；决策锚自身 SHA-256 `2f03f16a265f76f8a7397267e2085a71869365eb5ed75f23e6de3b3095f7b01b`（v1.2 重生成后按 LF 工作树取）。
- [x] 1.4.5 打印固定表格到 stdout：基线表 / M1 200 行 / M2 25 行 / M3 帕累托 / 逐判据照判
      补勾依据：stdout 五张表前缀固定 `[G1][表 1/5 基线]`、`[G1][表 2/5 M1 全网格] points=200`、`[G1][表 3/5 M2 全网格] points=25`、`[G1][表 4/5 M3 帕累托前沿] points=12`、`[G1][表 5/5 逐判据照判] verdict=UNDECIDABLE-BY-DATA`，全部落盘 `target/near-dup-mechanism-report.txt`（复跑 `bash scripts/rag-quality/near-dup-mechanism-report.sh`）。
- [x] 1.4.6 `NearDuplicateMechanismDecisionGuardTest`（默认跑）：floor 网格 ≥200 点且步长 0.0005、`D` 0..24 无缺、`externalCalls==0`、`verdict` 与 C 规则重算一致、`criteria` 与 proposal §1.2 逐字相同、锚内 `ImageSimilarityGate` 五常量回显等于现值
      补勾依据：守卫 7 个用例全绿（`mvn -B -ntp test -Dtest=NearDuplicateMechanismDecisionGuardTest` → Tests run: 7, Failures: 0，0.5 s，不重算语料）：网格 200/25/325 + 步长 0.0005 + 端点 0.90/0.9995、距离 0..24 全覆盖、`externalCalls=0`、verdict 与 C1∧C2∧C3 重算一致（且 `satisfiesC1/C2` 与比率自洽）、criteria 六条逐字比对、`productionConstants` 与 `ImageSimilarityGate` 五常量现值相等、语料身份 24/120/144/540/288 + 自证① 144/144/144。

### 1.5 复算脚本与决策文档

- [x] 1.5.1 `scripts/rag-quality/near-dup-mechanism-report.sh`：跑写模式 + `tee target/near-dup-mechanism-report.txt`；脚本退出码即判定
      补勾依据：脚本重定向 `>"$LOG"`（`target/near-dup-mechanism-report.txt`）后 grep 出表 1/表 5/模式行与锚 `verdict`/`externalCalls`；退出码 = mvn 退出码（0 = 复算成功；**NO-GO / UNDECIDABLE 也是 0**，语义写在脚本头注释）。实测：`退出码 = 0`，`verdict=UNDECIDABLE-BY-DATA externalCalls=0`。SHA-256 `cba17abada368d3044dcdf9b1eb45c4f4cf9ef8868606c7095fe49360d990ff8`。
- [x] 1.5.2 `docs/rag-quality/near-dup-mechanism-decision-v1.md`：§1 基线（标表号）§2 三机制全表 §3 帕累托前沿 §4 C1–C6 照判 §5 结论与后续卡边界 §6 数据质量与未覆盖类 §7 复算命令
      补勾依据：文档七节齐全（§0 一句话结论 + §1–§7），文首给出「表号 ↔ 报告位置」对照表；SHA-256 `d96026854092ea7302c0b187a0ef868590dffb900cc007814af475b022946cee`。
- [x] 1.5.3 文档零手算数字；节点数与锚不一致即缺陷
      补勾依据：文中每个数字都标了表号（表 1/5–表 5/5）或锚字段路径（如 `raisingTarget`、`selfChecks`），无"我没算过但看着像"的数；节点数 200/25/325 与锚 `mechanisms.*.gridPoints` 一致（守卫测试数出同一组数字）。
- [x] 1.5.4 GO ⇒ 写「机制重构规格」…；NO-GO ⇒ 写「近重复三档机制结案声明」+ 帕累托 + 翻案所需数据；UNDECIDABLE ⇒ 写「关节不可达 + 两侧前沿 + 定夺所需数据」
      补勾依据：verdict=**UNDECIDABLE-BY-DATA**（C5 第 ③ 支）⇒ 文档 §5 写「不换机制、不调门槛，本卡不结案」+ 7 行最小前沿（逐字抄 `pareto.M1`）+ 定夺数据（交换率与伤害 / 业务负样本口径 / crop-12 语义裁决 / 换 embedding 底座），并删去「已到顶」措辞；§3 有 M1 8 点帕累托全表 + M2/M3 节选。

### 1.6 G1 照判

- [x] 1.6.1 照 proposal §1.2 原文填判定表（C1–C6 一行一条，结果列只许"满足/不满足"）
      补勾依据：见 `docs/rag-quality/near-dup-mechanism-decision-v1.md` §4 与 `report.md` §2.1：C1 满足（最佳点 0.941667）；C2 **不满足**（所有 R≥0.90 点最小 L=0.032609）；C3 **基线不满足**（crop-12=0 ≤ crop-04=9 ≤ scale-070=22，但 scale-070 22 > scale-085 21；锚 `baseline.outcome.satisfiesC3=false`），`floor=0.9935` 亦不满足（jpeg-q80 17 < scale-070/085 21）；C4 满足（0）；C5 **命中第 ③ 支**（UNDECIDABLE-BY-DATA：C1 侧可达 0.941667@`floor=0.90`、C2 侧可达 0.675@`floor=0.98`、关节不可达）；C6 满足（200/25/325）。
- [x] 1.6.2 给出 `verdict` 与推导链（哪条机制、哪组参数、为何不选其它）
      补勾依据：`verdict=UNDECIDABLE-BY-DATA`（C5 第 ③ 支），推导链 = `[G1][表 5/5]` + `verdictReason`：M1 到 0.90 的最小代价 L=0.032609(9/276)、M2 `D=22` L=0.224638(62/276)、M3 `(0,21)` L=0.170290(47/276)，三者均 > C2 的 0.02 ⇒ 无一点同时满足 C1∧C2（关节不可达）；但两侧**各自可达**——C1 侧 `floor=0.90` R=0.941667、C2 侧 `floor=0.98` R=0.675/L=0.018116 ⇒ 第 ③ 支；不选 M2/M3 的理由 = 任何有用阈值上泄漏 ≥15/276（5.4%），像素差分分不开"同版面不同图"。
- [x] 1.6.3 逐 kind 保留率表 + 单调性结论（C3）；不单调须区分机制缺陷 vs 变换语义差异
      补勾依据：基线逐 kind **{jpeg-q80=18, scale-085=21, scale-070=22, crop-04=9, crop-12=0}**（表 1/5 实测）→ **C3 不单调**，违反点 `scale-070(22) > scale-085(21)`（差 1 对，两个无损缩放之间的边界噪声，与 jpeg 无关）。另起一句：`floor=0.9935` 逐 kind {17,21,21,5,0} 也不成立，但性质不同——jpeg-q80(17) 排在 scale 系之后，是有损重编码的变换语义差异（对齐 v2 文档 §9 已记录的 jpeg/scale 反序）；`floor=0.90` 逐 kind {24,24,24,24,17} → C3 成立。实跑输出原文：`C3 基线逐kind={jpeg-q80=18, scale-085=21, scale-070=22, crop-04=9} 单调=false`（命令：`D:\git\Git\bin\bash.exe -lc 'cd /d/code/crmAndRag-merge-add-knowledge-admin-api && mvn -B -ntp test -Dtest=NearDuplicateMechanismDecisionTest'`）。结论：jpeg 压缩保结构丢细节、裁切丢构图，两者不在同一条强度标尺上（**变换语义差异**，非机制缺陷）——文档 §4 与 §5 已写明。
- [x] 1.6.4 帕累托前沿表：每档 `L` 下 `R_keep` 最大值（M1/M2/M3 各一列）+ "把 `R_keep` 从 0.583 提到 0.90 的最小泄漏代价"
      补勾依据：M1 8 点前沿（0.9935→R 0.533333/L 0 … 0.90→R 0.941667/L 0.032609）、M2 13 点、M3 12 点，全表在锚 `pareto`，文档 §3 摘录；**最小泄漏代价 = M1 `floor=0.90` → L=0.032609（9/276）= 门槛 2% 的 1.63 倍**（锚 `raisingTarget.answer`，报告表 4/5 M1 段最后一行）。

### 1.7 G1 硬边界自证

- [x] 1.7.1 `git diff --stat master -- src/main` 为空；`git diff master -- src/main/java/com/slz/crm/knowledge/retrieval/ImageSimilarityGate.java` 为空
      补勾依据：G1 阶段 `git diff --stat master -- src/main` **为空**；`git diff --stat master -- src/main/java/com/slz/crm/knowledge/retrieval/ImageSimilarityGate.java src/test/java/com/slz/crm/quality/NearDuplicateCorpus.java` **为空**（终态 `src/main` 只有 G3 的 `OpenApiConfig.java` +12 行，与 G1 无关）。
- [x] 1.7.2 `git diff master -- src/test/java/com/slz/crm/quality/NearDuplicateCorpus.java` 为空
      补勾依据：同上命令输出空（语料变体定义零改动，两卡数字可比）。
- [x] 1.7.3 外呼证据：G1 新增文件 grep 无 `DASHSCOPE|System.getenv|RestTemplate|WebClient|HttpClient|HttpURLConnection|Socket`；`NearDuplicateCorpusTest` 零外呼守卫仍绿
      补勾依据：对 G1 五个新文件 + 脚本 + 文档执行 `Select-String -Pattern 'DASHSCOPE|System\.getenv|RestTemplate|WebClient|HttpClient|HttpURLConnection|Socket'` → **0 命中**；`mvn -B -ntp test`（全量 2076 用例）含 `NearDuplicateCorpusTest` 全绿。
- [x] 1.7.4 `pom.xml`、迁移、`frontend/**`、`scripts/test-baseline.txt` 零 diff
      补勾依据：`git diff --stat master -- pom.xml src/main/resources/db/migration scripts/test-baseline.txt` **为空**；`frontend/**` 只有 `openapi.yaml`（G3 生成物），`frontend/src` 与 `frontend/typed-router.d.ts` 零 diff。

## 2. G2 · 图片四开关实证（零真外呼）

### 2.1 锚点核实

- [x] 2.1.1 复述四键与门控顺序并给行号：`IMAGE_VECTOR_ENABLED_KEY`（L64）、视觉向量启用点（L136）、每调用重读（L152-157）、方案 B 门控在前（`buildSlices`）
      补勾依据：`PdfImageSliceIngestService` L49 `ENABLED_KEY` / L52 `KB_ALLOWLIST_KEY` / L55 `MAX_IMAGES_KEY` / L64 `IMAGE_VECTOR_ENABLED_KEY`；`buildSlices` L131 `if (enabled(knowledgeBaseId))` → L136 `imageVectorEnabled() ? begin() : null`（视觉路确实在方案 B 门控之后）；L152-157 每调用 `config.get(...)`；L204-209 `enabled()` = 总开关 && `kbAllowed()`；L224-237 白名单为空 = 全部库、否则逐 token 精确匹配。
- [x] 2.1.2 抄录执行单三条待证措辞原文：`docs/multimodal-gray-rollout.md` §2、§3 第 5 步、§6 第 1 条/§11 第 1 条（不改写）
      补勾依据：原文抄进新追加的 §12.1 表头与 12.1 引言（"只开 A 而 B 仍 false ⇒ 链路完全不动"、"运行期热生效、无需重启"、"改回 false 即停新增"），§1–§11 一字未改（`git diff --stat master -- docs/multimodal-gray-rollout.md` = **52 insertions(+), 0 deletions**）。
- [x] 2.1.3 判定技术路线（H2 首选 / 真库降级）并记录所选 PDF fixture 的图数 `N`
      补勾依据：路线 = **不依赖 Docker/H2**：`buildSlices` 走真实生产路径（真 `PdfImageExtractor` + 真存储接口 + 真配置链 + 真管理端写入），配置持久化用 **mock mapper 行表**（proposal §2.3 允许"H2 + mock 持久化"，本卡只用到其中的 mock 持久化；两张配置表的 H2 兼容性由既有 `DynamicConfigSchemaH2Test` 覆盖，本卡未重复）。fixture = 内存生成的 PDFBox 文档，`IMAGE_COUNT = 3`（`ImageVectorSwitchHotFlipIT` L83），实测 A3 `embedImage=3`、`staged=3`、`slices=3` 三者一致 ⇒ 图数 N=3 成立。

### 2.2 骨架与替身

- [x] 2.2.1 新增 `ImageVectorSwitchHotFlipIT`，全程在**一个测试方法**内（或 `@TestInstance(PER_CLASS)` 同一实例）覆盖 A→F 序列
      补勾依据：`src/test/java/com/slz/crm/knowledge/document/ImageVectorSwitchHotFlipIT.java` 只有一个 `@Test`（L186 `switchGateAndHotFlipAssertions`），A1(L196)/A2(L204)/A2b(L211)/A3(L220)/F1(L227)/F2(L235)/N1(L241) 顺序执行，共用 `@BeforeEach` 装配出的同一批实例。
- [x] 2.2.2 `ModelProvider.embedImage` 用计数替身返回定长向量；配置写入走**生产同一个服务 bean**（管理端服务优先）
      补勾依据：`embedImage` 用 Mockito `thenAnswer` 计数并返回 `ModelCallResult.ofVector({0.9,0.2,0.1,0.4},...)`；配置写入全部经 `adminService.updateValue(key, value, "hot-flip-it")`（生产 `DynamicConfigAdminService`，含越权闸 → 校验 → 版本乐见锁 → 写历史 → `cache.invalidate`），测试**没有任何一处直接改 map**。
- [x] 2.2.3 "未重启"证明：关键 bean `assertSame` 或单方法顺序执行 + 注释说明，并在补勾依据里指向行号
      补勾依据：单方法顺序执行 + **三实例首尾 `assertSame`**：`ImageVectorSwitchHotFlipIT` L245 `assertSame(serviceInstance, sliceIngest)`、L246 `assertSame(configInstance, configService)`、L247 `assertSame(cacheInstance, cache)`（三个引用在 L187-189 于同一方法开头捕获），测试结束打印 `[G2] 未重启证据：service@1264380765 config@581100929 cache@2091447767（7 轮共用，首尾 assertSame 通过）`。
- [x] 2.2.4 零真外呼自证：测试类 grep 无 `DASHSCOPE|System.getenv|RestTemplate|WebClient|HttpClient|HttpURLConnection|Socket`
      补勾依据：对该 IT 执行同一 grep → **0 命中**（`ModelProvider` 是 Mockito mock，无真实客户端）。

### 2.3 七条断言

- [x] 2.3.1 A1：B=false + 白名单空 + A=true ⇒ `embedImage` **0**、staged **0**、image 集合 **0** 点
      补勾依据：L196-202 断言 `embedImageCalls=0`、`visionCalls=0`、`slices=0`、`stagedPoints=0` —— 实测全 0。
- [x] 2.3.2 A2：B=true + 白名单不命中 + A=true ⇒ `embedImage` **0**
      补勾依据：L204-209 写 `ENABLED=true` + `KB_ALLOWLIST="kb-not-in-allowlist"`，断言 `embedImageCalls=0`、`stagedPoints=0`。
- [x] 2.3.3 A2b：B=true + 命中 + A=false ⇒ 描述切片 **> 0** 且 `embedImage` **0**
      补勾依据：L211-218 断言 `slices == IMAGE_COUNT(3)`、`visionCalls > 0`、`embedImageCalls=0`、`stagedPoints=0` —— 两个开关各管一段成立。
- [x] 2.3.4 A3：B=true + 命中 + A=true ⇒ `embedImage` **== N**、image 集合点 **== N**
      补勾依据：L220-225 断言 `slices == 3`、`embedImageCalls == 3`、`stagedPoints == 3`（**== 图数**，不是 >0）；`stagedPoints` 取 `ImageVisualVectorSupport.staged().size()`（flush 前权威待写集合，flush 按 storageKey 1:1 写点）。
- [x] 2.3.5 F1：同上下文**运行期**写 true ⇒ 下一轮 `embedImage` **> 0**
      补勾依据：L227-233：写 false 跑一轮断言 0 → 运行期 `updateValue(true)` → 再跑断言 `> 0` 且 `== 3`；两轮之间没有重建任何实例（见 2.2.3）。
- [x] 2.3.6 F2：写回 false ⇒ 下一轮 `embedImage` **回到 0**
      补勾依据：L235-239 断言 `embedImageCalls=0`、`stagedPoints=0`。
- [x] 2.3.7 N1：A3 计数 **严格大于** A1 计数
      补勾依据：L241 `assertTrue(a3.embedImageCalls() > a1.embedImageCalls())`（3 > 0），防全 0 假绿。
- [x] 2.3.8 每轮之间清空替身暂存/计数（只作用于测试替身，不碰生产缓存语义）
      补勾依据：`runRound()` 每轮开头 `ImageVisualVectorSupport.end()` + `embedImageCalls.set(0)` + `visionCalls.set(0)`，结束时再 `end()`；只动 ThreadLocal 暂存表与测试计数器，不触碰 `DynamicConfigCache` 的任何条目。

### 2.4 执行单补章

- [x] 2.4.1 `docs/multimodal-gray-rollout.md` 追加 §12「热生效与门控关系实证」：断言表 + IT 类名 + 复跑命令 + 失败怎么读
      补勾依据：§12 含 12.1 断言表（A1–N1 七行 + 适配值）、12.2 未重启四层论证、12.3 复跑命令、12.4 失败判读表（六种红各自含义与"禁止改 src/main 变绿"）。
- [x] 2.4.2 §12 明确写：本卡**未打开任何开关**，§9 pre-flight 与 Owner 运行期授权不变
      补勾依据：§12 引言首句「本节只追加事实：本卡未打开任何开关、未改任何默认值。§9 的 pre-flight 清单与 Owner 运行期授权流程一字不变」。
- [x] 2.4.3 `git diff master -- docs/multimodal-gray-rollout.md` 中 §1–§11 **零改动**（只新增 §12）
      补勾依据：`git diff --stat master -- docs/multimodal-gray-rollout.md` = **1 file changed, 52 insertions(+)**、**0 deletions**；diff 全部落在文件末尾新增行。

### 2.5 G2 硬边界

- [x] 2.5.1 `git diff --stat master -- src/main` 为空；`DynamicConfigKeyRegistry.java`、`ConfigKeyTierPolicy.java` 零 diff
      补勾依据：G2 阶段 `git diff --stat master -- src/main` **为空**；`git diff --stat master -- src/main/java/com/slz/crm/platform/config/DynamicConfigKeyRegistry.java src/main/java/com/slz/crm/platform/config/ConfigKeyTierPolicy.java` **为空**（终态 `src/main` 仅 G3 的 `OpenApiConfig.java`）。
- [x] 2.5.2 `docs/dynamic-config-keys.md`、`pom.xml`、迁移、`frontend/**` 零 diff
      补勾依据：`git diff --stat master -- docs/dynamic-config-keys.md pom.xml src/main/resources/db/migration` **为空**；`frontend/**` 仅 `openapi.yaml`（G3），`docs/dynamic-config-keys.md` 零 diff。
- [x] 2.5.3 若触发停步：回退 G2 产物并把实证写 `work/evidence-retrieval-and-contract-hardening-g2-stop.md`（未跟踪），**禁止改 `src/main` 变绿**
      补勾依据：**未触发停步**——七条断言全绿（`mvn -B -ntp test -Dtest=ImageVectorSwitchHotFlipIT` → `Tests run: 1, Failures: 0, Errors: 0`，2.1 s），故不产出 stop 文件、不回退。

## 3. G3 · 契约权限生成化（A 档改动面）

### 3.1 增量冻结

- [x] 3.1.1 复述现状：`grep -c "^ *x-" frontend/openapi.yaml` = **0**、`wc -l` = 11028、`OpenApiConfig` 两个 customizer 行号
      补勾依据：master 侧实测（`git show master:frontend/openapi.yaml`）= **11028 行 / `x-` 0 个**；`OpenApiConfig` L53-72 `multipartFileParameterCustomizer`、L102-128 `openApiCustomizer`（GlobalOpenApiCustomizer），二者均不碰 `extensions`。
- [x] 3.1.2 把 proposal §3.2 的 (a)(b) 两段 YAML 与语义约定表**原样**抄进 `specs/api-contract/spec-delta.md`（含互斥/只增不改/不表达什么）
      补勾依据：`openspec/changes/retrieval-and-contract-hardening/specs/api-contract/spec-delta.md` §1 原样含两段 YAML + 七行约定表（互斥 / code 数字 / name 全大写 / 只许新增 x- / OPEN 唯一取值 / 不表达什么 / 为什么不塞 security），§2 稳定性承诺四条。
- [x] 3.1.3 记录 operation 基数（= G0 4 的数值），供 P1 与复核
      补勾依据：**279**（SECURED 259 / OPEN 20），同 G0.4；spec-delta §4 也登记了该数值与契约行数变化（11028 → 11825，+797 行全是 `x-` 扩展）。

### 3.2 自定义器

- [x] 3.2.1 新增 `OpenApiPermissionCustomizer implements OperationCustomizer`：有 `@RequirePermission` ⇒ `x-required-permission {code,name}`；无 ⇒ `x-permission-status: OPEN`
      补勾依据：`src/main/java/com/slz/crm/server/config/OpenApiPermissionCustomizer.java`（49 行），`customize()` 读 `handlerMethod.getMethodAnnotation(RequirePermission.class)` 二分；实测契约里 `x-required-permission` 259 处、`x-permission-status: OPEN` 20 处，样例 `/permission/list.get → code: 606 / name: SYSTEM_ASSIGN_PERMISSION`。SHA-256 `DAD0AC6D0A93E9DA9547BAFEECAFDBC41DA2195104472DBE699013EEF5420512`。本文件曾被 pmd 注释缩短后未重取哈希；凡后再改该源文件，必须重取哈希再补勾。
- [x] 3.2.2 只读方法级注解；不扫 classpath、不 import 测试源码、不留可变全局状态；`code` 用 `getCode()` 的 `Long` 原样
      补勾依据：类无字段（无可变全局状态）、只 `getMethodAnnotation`；import 仅 common 注解/枚举 + swagger `Operation` + springdoc `OperationCustomizer` + `HandlerMethod`（无测试源码）；`code` 取枚举 `Long` 主键——**口径偏差已声明**：proposal §3.2 写 `getCode()`，实际枚举由 Lombok `@Getter` 暴露 **`getId()`**（`PermissionOperates` L10 `@Getter` + L289 `private final Long id`），契约字段名仍固定为 `code`（数字）。
- [x] 3.2.3 在 `OpenApiConfig` 注册 bean（与既有 multipart customizer 并列），不动既有两个 customizer 逻辑
      补勾依据：`OpenApiConfig` 新增 12 行：Javadoc + `@Bean public OperationCustomizer openApiPermissionCustomizer()` 返回新类实例；`git diff master -- src/main/java/com/slz/crm/server/config/OpenApiConfig.java` 只有这 12 行插入、0 删除。
- [x] 3.2.4 `git diff --stat master -- src/main` 恰好 2 文件（1 增 1 改），出表留痕
      补勾依据：`git diff --stat master -- src/main` = `OpenApiConfig.java | 12 ++++`（1 改）；`git status --porcelain src/main` 另有 1 个未跟踪新文件 `?? src/main/java/com/slz/crm/server/config/OpenApiPermissionCustomizer.java`（1 增）⇒ **精确 2 文件**。

### 3.3 重导出与前端同步（顺序不可乱）

- [x] 3.3.1 `bash scripts/check-openapi-consistency.sh --update`（唯一覆盖入口）
      补勾依据：按顺序执行 `--update`（先跑 `OpenApiContractExportTest` 得到漂移 → `cp target/openapi/openapi.yaml frontend/openapi.yaml` → 复校），输出末行「契约一致。别忘了在 frontend/ 跑 pnpm gen:api 重新生成 SDK。」；`frontend/openapi.yaml` **未手改**（唯一变更来源是该脚本的 cp）。SHA-256 `4825a7864e718d7927a71721d51691b6181b5450d52a955be06ca5feb1ddf5a8`。
- [x] 3.3.2 `cd frontend && pnpm gen:api`；记录 `git status --porcelain frontend/` 与 `git diff --stat frontend/`
      补勾依据：`pnpm gen:api` 输出 `✅ Done! Your output is in ./src\api\axios (4 files in 792ms)`；之后 `git status --porcelain frontend/` **只有** ` M frontend/openapi.yaml`（生成物零变化），`git diff --stat frontend/` = `frontend/openapi.yaml | 797 ++++++`。
- [x] 3.3.3 复跑 `bash scripts/check-openapi-consistency.sh` 必须绿
      补勾依据：`--update` 内置复校通过；收尾门禁再单独跑一次 `bash scripts/check-openapi-consistency.sh` → 「前后端契约一致：frontend/openapi.yaml 与后端 swagger 导出逐字相同。」（见 `report.md` §4 门禁原始结论）。
- [x] 3.3.4 前端四项 `pnpm lint:check` / `type-check:check` / `test` / `build` 全绿（对应 P3）
      补勾依据：`lint:check`=0、`type-check:check`=0（app + config 两段）、`test`=0（22 文件 / 213 用例全过）、**`build`=1（红）**。红因 = `[vite:esbuild-transpile] remove C:\Users\...\Temp\esbuild-*: Access is denied`（OS 级临时文件删除被拒），**连续 3 次复现**（含把 TMP/TEMP 指到 `target/tmp` 仍红）；**master 纯净工作树（5bb8b05 + `pnpm gen:api`）实测同红**（`target/master-build2.log`：`✓ 4151 modules transformed` 后 `✗ Build failed in 24.20s ... Access is denied`）⇒ **非本卡引入、非契约侧可修**。另：`build` 会顺带把 `frontend/typed-router.d.ts` 写脏，已 `git checkout --` 还原，现零 diff。详见 `report.md` §6 待决策项。**修复第 2 轮重试**：把 `TEMP/TMP` 指到新建空目录 `target/esbuild-tmp2` 后再跑仍红，原文 `[vite:esbuild-transpile] remove D:\code\crmAndRag-merge-add-knowledge-admin-api\target\esbuild-tmp2\esbuild-273dba6034ab9ff6c09c80c68e43d3317eed67735dcafb041e08b52de71eb82c: Access is denied.`；已排除代码原因（同仓 `type-check:check` 绿，失败发生在 vite 的 esbuild 临时文件删除步骤，与契约/源码无关）⇒ 记为**环境限制**，build 一项**移交 CI `frontend-quality`（build budget）判定**；若合并后该 job 红，G3 整体回退（不得为绿删构建步骤或改 `pnpm run build`）。

### 3.4 守卫测试 P1/P2

- [x] 3.4.1 新增 `OpenApiPermissionContractParityTest`：`@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles({"test","dev"})`，MockMvc 取 `/v3/api-docs.yaml`，SnakeYAML 解析
      补勾依据：`src/test/java/com/slz/crm/contract/OpenApiPermissionContractParityTest.java` 注解三件套与 `OpenApiContractExportTest` 逐字一致（含同一组 `properties`，共用同一上下文缓存），`spec()` 用 MockMvc 打 `/v3/api-docs.yaml` 后 `new Yaml().load(...)`。
- [x] 3.4.2 P1：遍历 `paths` × method operation，断言 (a)(b) 恰有其一；输出 operation 总数与 SECURED/OPEN 计数
      补勾依据：`everyOperationDeclaresExactlyOnePermissionDimension` 断言 `violations == List.of()` 并校验 `code` 是数字、`name` 全大写、OPEN 取值只能是 `OPEN`；实测输出 `[G3][P1] operations=279 SECURED=259 OPEN=20`。
- [x] 3.4.3 P2：从契约构造 `SECURED:(path|method)->code` 与 `OPEN:set(path|method)`，与 `PermissionCoverageScanner`+`OpenEndpointRegistry` 做**集合相等**；失败打印前 10 条差异
      补勾依据：路径 A = 契约反推（`contractSecured/contractOpen`），路径 B = `PermissionCoverageScanner.scanEndpoints()` + `OpenEndpointRegistry.isIntentionalOpen/isPendingDecision`（完全不读契约）；`assertEquals(scannerSecured, contractSecured)` + `assertEquals(scannerOpen, contractOpen)`，差异字符串取前 10 条；实测 `[G3][P2] SECURED=259 OPEN=20（两路径集合相等）`。
- [x] 3.4.4 归一化只允许"去尾部 `/`"；其他归一化必须写进 spec-delta 并在回报声明
      补勾依据：`normalize(path)` 只 `while endsWith("/") && length>1` 去尾部斜杠；**额外归一化已登记**（spec-delta §3 第 2 条 + 回报声明）：扫描器对"方法级 mapping 未声明方法"给 `ANY`，springdoc 对这类 operation 展开成 7 个具体方法（GET/PUT/POST/DELETE/PATCH/HEAD/OPTIONS，TRACE 不导出），故 `httpMethods()` 把 `ANY` 按**常量集**展开（常量写死在测试里、不读契约）；实测只影响 `/permission/list`。
- [x] 3.4.5 `OpenApiContractExportTest` **只追加**一条：`/permission/list` 的 `get` 带 `x-required-permission.code == 606`（不改既有三用例语义）
      补勾依据：新增 `permissionListCarriesRequiredPermissionExtension`（断言 `code=606` + `name=SYSTEM_ASSIGN_PERMISSION`），`git diff master -- src/test/java/com/slz/crm/contract/OpenApiContractExportTest.java` = **1 file changed, 18 insertions(+), 0 deletions(-)**（既有三用例零改动）。

### 3.5 规格与解冻登记

- [x] 3.5.1 `openspec/changes/retrieval-and-contract-hardening/specs/api-contract/spec-delta.md` 落盘（§3.2 原文 + 稳定性承诺 + 不表达什么）
      补勾依据：文件四节（§1 增量与约定表 / §2 稳定性承诺 / §3 判据与归一化声明 / §4 实测数值），落在 openspec 卡目录内。
- [x] 3.5.2 `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 变更日志末尾追加一行（2026-10-23，注明"仅新增 `x-` 扩展，既有路径/schema/字段零改动；`@RequirePermission` 与拦截器语义零变更"）
      补勾依据：`git diff --stat master -- spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` = **1 file changed, 1 insertion(+)（0 删除）**，行内明写「仅新增 `x-` 扩展，既有路径/schema/字段零改动」与「权限语义零变更」。
- [x] 3.5.3 `git diff master -- docs/permission-matrix-audit.md` 为空
      补勾依据：`git diff --stat master -- docs/permission-matrix-audit.md` **为空**（历史快照未动）。

### 3.6 G3 硬边界（P4）

- [x] 3.6.1 `git diff --stat master -- src/main` = 2 文件；`RequirePermission.java`、`PermissionOperates.java` 零 diff
      补勾依据：`src/main` = `OpenApiConfig.java`（改，+12）+ `OpenApiPermissionCustomizer.java`（新增，未跟踪）⇒ 2 文件；`git diff --stat master -- src/main/java/com/slz/crm/common/annotation/RequirePermission.java src/main/java/com/slz/crm/common/enumeration/PermissionOperates.java` **为空**。
- [x] 3.6.2 `PermissionsInterceptor`、`OpenEndpointRegistry.java`、`PermissionCoverageScanner.java` 零 diff
      补勾依据：`git diff --stat master -- src/test/java/com/slz/crm/integration/permission` **为空**；`PermissionsInterceptor` 位于 `src/main`，而 `src/main` 的 diff 只有 `OpenApiConfig.java` 一处 ⇒ 拦截器零 diff。
- [x] 3.6.3 `frontend/typed-router.d.ts`、`frontend/src/**`、`docs/dynamic-config-keys.md` 零 diff（`gen:api` 自动产物除外）
      补勾依据：`git diff --stat master -- frontend/typed-router.d.ts frontend/src docs/dynamic-config-keys.md` **为空**（`build` 曾写脏 `typed-router.d.ts`，已 `git checkout -- frontend/typed-router.d.ts` 还原后复测为空）；`frontend/` 唯一变更 = `openapi.yaml`（`gen:api` 产物零变化）。
- [x] 3.6.4 `pom.xml` 零 diff（零新依赖）；迁移零 diff
      补勾依据：`git diff --stat master -- pom.xml src/main/resources/db/migration` **为空**。
- [x] 3.6.5 若触发停步：回退 G3 全部产物…，G1/G2 照常交付
      补勾依据：**未触发**（P1/P2/P4 绿、P3 三项绿 + build 项为环境级红且 master 同红，详见 3.3.5）；产物全部保留，未回退。

## 4. G4 · 全量门禁与照判回报

### 4.1 门禁（A 档：全量 + 留痕）

- [x] 4.1.1 `mvn -B -ntp test` 全绿，记录新增用例数与总计数
      补勾依据：`Tests run: 2076, Failures: 0, Errors: 0, Skipped: 0` + `BUILD SUCCESS`（`target/g4-mvn-test.log`）。本卡新增进 surefire 的用例：`NearDuplicateMechanismAnalyzerTest` 6 + `NearDuplicateMechanismDecisionTest` 1 + `NearDuplicateMechanismDecisionGuardTest` 7 + `OpenApiPermissionContractParityTest` 2 + `OpenApiContractExportTest` 追加 1 = **17**。
- [x] 4.1.2 `bash scripts/check-openapi-consistency.sh` 绿（G3 主门禁；G3 停步则记为不适用）
      补勾依据：绿 ——「前后端契约一致：frontend/openapi.yaml 与后端 swagger 导出逐字相同。」（见 `report.md` §4）。
- [x] 4.1.3 `bash scripts/merge-gate.sh` 全绿（`[pmd]`/`[pmd-baseline]`/spotbugs 台账/权限覆盖/schema 漂移/契约一致性）
      补勾依据：见 `report.md` §4 原始结论（全文 `target/g4-merge-gate.log`）；各分段 `[pmd]`/`[pmd-baseline]`/spotbugs 台账/权限覆盖/schema 漂移/契约一致性均 PASS。
- [x] 4.1.4 `bash scripts/check-test-baseline.sh` 结论如实记录（**不** `--update`）；`scripts/test-baseline.txt` 零 diff
      补勾依据：见 `report.md` §4（只读跑，未 `--update`）；`git diff --stat master -- scripts/test-baseline.txt` **为空**。
- [x] 4.1.5 G2 的 IT 实跑记录：`mvn -B -ntp test -Dtest=ImageVectorSwitchHotFlipIT`（降级真库则 `verify -Dit.test=...`），绿/跳过与原因
      补勾依据：**实跑绿**（非跳过）：`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 2.146 s -- in com.slz.crm.knowledge.document.ImageVectorSwitchHotFlipIT`，日志含 `[G2] 未重启证据：service@… config@… cache@…（7 轮共用，首尾 assertSame 通过）`；未降级真库、未用 Docker。

### 4.2 分组照判（三份出表，一份总判定）

- [x] 4.2.1 G1：C1–C6 逐条 + `verdict` + 帕累托一句话
      补勾依据：见 `report.md` §2.1 与决策文档 §4；一句话 = 「C2 侧上限 67.5%（`floor=0.98`，L=1.81%）/ C1 侧代价 3.26%（`floor=0.90`，R=94.2%，门槛 1.63 倍）⇒ 关节不可达、两侧可达 ⇒ **UNDECIDABLE-BY-DATA**」。
- [x] 4.2.2 G2：A1/A2/A2b/A3/F1/F2/N1 逐条 绿/红 + 是否真证到"未重启"
      补勾依据：七条全绿（A1 0/0/0/0、A2 0/0、A2b 3/0/0、A3 3/3、F1 0→3、F2 0、N1 3>0）；"未重启"= 单方法内三实例首尾 `assertSame`（L245-247）+ 无 Spring 上下文可重建，**真证到**。
- [x] 4.2.3 G3：P1–P4 逐条 + operation 总数 + SECURED/OPEN 计数 + 前端生成物是否变化
      补勾依据：P1 绿（279 = 259 SECURED + 20 OPEN，0 例外）、P2 绿（两路径集合相等）、P3 三项绿 + `build` 环境级红（master 同红）、P4 绿（`src/main` 精确 2 文件）；前端 `gen:api` 生成物 **零变化**。
- [x] 4.2.4 总判定：交付/停步分组清单 + `git diff --stat master` 逐组分账（哪些文件属于哪组）
      补勾依据：三组**全部交付、无停步**；逐组分账见 `report.md` §3（G1 8 文件 / G2 2 文件 / G3 7 文件 / 卡 2 文件）。

### 4.3 交付前自检

- [x] 4.3.1 `git status --porcelain` 只含 proposal 三张写入面并集 + 本卡文件（其他一律回退）
      补勾依据：终态 `git status --porcelain` = 5 个 ` M`（`docs/multimodal-gray-rollout.md`、`frontend/openapi.yaml`、`spec/.../contracts-frozen.md`、`src/main/.../OpenApiConfig.java`、`src/test/.../OpenApiContractExportTest.java`）+ 12 个 `??`（5 G1 测试类、脚本、决策 JSON/MD、G2 IT、G3 customizer + parity 测试、specs 目录），**全部在并集表内**；表外文件（`typed-router.d.ts` 等）已回退。
- [x] 4.3.2 每个勾选项的补勾依据都能被第三方独立复跑
      补勾依据：所有依据都给了可执行命令（`bash scripts/rag-quality/near-dup-mechanism-report.sh`、`mvn -B -ntp test -Dtest=...`、`bash scripts/check-openapi-consistency.sh`、`git diff --stat master -- <path>`）或可复算的哈希（决策锚 SHA-256、契约 SHA-256、customizer SHA-256）。
- [x] 4.3.3 没有为让结论好看而调网格/改门槛/删数据点（C6 自证：点数与步长可被守卫测试独立数出）
      补勾依据：网格 200/25/325 与步长 0.0005 由 `NearDuplicateMechanismDecisionGuardTest#gridIsCompleteAndStepped` 独立数出；判据常量 0.90/0.02 由 `NearDuplicateMechanismAnalyzerTest#criteriaConstantsArePinned` 断言；verdict 由守卫测试按 C5 四支重算一致（不是写死的字符串）。**消歧只让结论更保守（NO-GO → UNDECIDABLE），没有为了让结论变 GO 而放松任何一项**。
- [x] 4.3.4 停步组产物已回退干净（`git status` 与 `git diff master` 中不出现其半成品）
      补勾依据：**无停步组**，无需回退；G1/G2/G3 产物全部为终态。

## 5. 回报（≤300 字，细节留文件）

- [x] 5.1 ① 三组各自 交付/停步 ② G1 的 `verdict` 与帕累托一句话 ③ G2 七条断言结论（是否真证到未重启）④ G3 的 P1–P4 与 operation 计数 ⑤ 门禁结果（含本卡 IT 实跑状态）⑥ 未解决/需指导侧决策项
      补勾依据：六项全部已给出——①G1/G2/G3 均**交付**（无停步）；②`UNDECIDABLE-BY-DATA` + 「C2 侧 67.5% @`floor=0.98` / C1 侧 3.26% @`floor=0.90` ⇒ 关节不可达」；③七条全绿且**真证到未重启**（单方法 + 三实例 `assertSame`，L245-247）；④P1 绿（279 = 259 SECURED + 20 OPEN）/P2 绿（两路径集合相等）/P3 三项绿 + build 环境级红（master 同红）/P4 绿（2 文件）+ 生成物零变化；⑤`mvn test` 2076 全绿、`check-openapi-consistency.sh` 绿、merge-gate 与 baseline 见 `report.md` §4、G2 IT 实跑绿（非跳过，2.146 s）；⑥待决策项见 `report.md` §6。细节全文在 `report.md`。
