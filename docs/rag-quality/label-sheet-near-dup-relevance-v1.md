# 近重复人标最小包 · v1

- 卡：`label-sheet-near-dup-relevance`
- 状态：`AWAITING-HUMAN-LABELS`
- 标注表：`docs/rag-quality/labels/near-dup-relevance-v1.csv`（30 行 × 11 列，末 4 列待人填）
- 填表说明：`docs/rag-quality/labels/near-dup-relevance-v1-README.md`
- CSV sha256：`a6785fbbc2ef01e090379fd00af10bb19073be1ba34883479d7feed45bc8450e`
- 来源锚：`docs/rag-quality/near-dup-band-entry-v1.json`（只读，sha256 `3c927e65e70083973c48f34de965d1531afd1b5cd2c86583df688f45918195ec`）

## 材料

- 6 条查询 × 现行前 5 候选 = 30 行；行 id `q{queryIndex}:{chunkId}`，行序 = 现行名次（不打乱、不按相似度排）。
- 候选可读文本取自冻结 `corpusMaterial.imageRecords`，缺失 0 行。
- 图像路 cos/tier/band/r 由本地生产类重算，与来源锚逐条对账 15/15。

## 三情景（并列，不排序）

| 情景 | 排序口径 |
| --- | --- |
| 现状 | 生产准入下限 0.982 + 三档 band 降权 |
| 无降权 | δ = 0，不施加 band 降权 |
| 0.90 口径 | 准入下限降到 0.90，按同批候选重算 tier/band/weight |

## 当前产出

- 标签未填：三情景指标一律留空，不写任何占位分数。

## 边界

- 本锚只对本次 6 查询 × 30 候选成立；不外推到其它语料、其它查询或线上体验。
- 零外呼：`externalCalls = 0`；不启 Spring、不调 provider。

## 复认证

- 写模式：`mvn -B -ntp test -Dtest=LabelSheetNearDupRelevanceTest "-Dlabel.sheet.write=true"`
- 默认复跑逐字节一致（JSON 剔除顶层 runtime）。
