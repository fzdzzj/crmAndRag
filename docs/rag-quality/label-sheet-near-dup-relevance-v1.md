# 近重复标注最小包 · v1

- 卡：`label-sheet-near-dup-relevance` · 状态：`LABELS-COLLECTED` · 来源：`MACHINE-PRELABEL-NOT-HUMAN-GROUND-TRUTH`
- 标注表：`docs/rag-quality/labels/near-dup-relevance-v1.csv`（36 行 × 11 列，15309 字节，末 4 列已填）
- 填表说明：`docs/rag-quality/labels/near-dup-relevance-v1-README.md` · CSV sha256：`9fba76b6c893331c738c875976ff9c19638e985793e19f974965f2ebbd01c293`
- 来源锚：`docs/rag-quality/near-dup-band-entry-v1.json`（只读，sha256 `3c927e65e70083973c48f34de965d1531afd1b5cd2c86583df688f45918195ec`）
- 池深：请求 10，实测逐查询 [6,6,6,6,6,6]，合计 36 行（语料 6 条图像记录 ⇒ 每查询上限 6，不凑数）

## 材料

- 行 id `q{queryIndex}:{chunkId}`，行序 = 现状情景全池名次升序（1..k）；候选文本取自冻结 `corpusMaterial.imageRecords`，缺失 0 行。
- 融合面本地重算逐条对账：现状 30/30、无降权 30/30（图像路 15 行；0.90 口径未定档 3 行）。

## 三情景（并列，不排序）

- 现状：生产准入下限 0.982 + 三档 band 降权。
- 无降权：δ = 0，不施加 band 降权。
- 0.90 口径：准入下限降到 0.90，按同批候选重算 tier/band/weight。

## 口径

- 口径：候选全池按各情景融合分重排后取前 5（分母 = min(5, 池内条数)），NDCG@5 增益 2^relevance − 1、IDCG 取同集合 relevance 降序、折损 log2(rank+2)，Y 压制一致率 = 被压制 Y 候选数 / Y 候选总数，舍入 round(1e-6)。

## 当前产出

- 三情景：`current-0982` p@5=0.6、n@5=1.0、y一致率=0.375 ｜ `no-downweight` p@5=0.6、n@5=1.0、y一致率=0.0 ｜ `floor-090` p@5=0.6、n@5=1.0、y一致率=0.5
- 打平检测：`tieDetected`=false（三情景不全等（至少一项指标有差异））

## 可测范围

- 能答：top-5 重复占比、Y 压制一致率、降权是否让非重复候选升进 top-5（池深后此项才可观测）。
- 不能答：生产库文档；单人标注无一致性检验；结论只对 6 查询 × 36 行成立。
- 三情景排序实测并非全相同 ⇒ `invariantObserved`=false；其中降权面（现状 vs 无降权）无跨边界位移。

## 边界与复认证

- 零外呼：`externalCalls = 0`；不启 Spring、不调 provider。
- 写模式：`mvn -B -ntp test -Dtest=LabelSheetNearDupRelevanceTest "-Dlabel.sheet.write=true"`；默认复跑逐字节一致（JSON 剔除顶层 runtime）。
