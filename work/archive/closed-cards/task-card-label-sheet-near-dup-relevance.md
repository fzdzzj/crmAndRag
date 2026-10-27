# 任务卡 · label-sheet-near-dup-relevance

## §1 一句话

把「近重复该不该压」这个问题的判据，从「机器自证」换成**30 格人标**：生成一张 15 分钟能填完的盲标表 + 一套确定性打分器，让 Owner 的半小时变成三情景并列的指标。

## §2 为什么现在做

七张卡把机器能给的东西榨干了，剩下三件事全都缺同一件东西——**人的判断**：

- `0.90` 拍板时最硬的反对话是「多压的那几张是真重复还是真不同，本语料无标签可判」；
- 刚量化出「伪向量低估 `recall@5` 约 9pp」，但这 9pp 是**装置误差**还是**质量差距**，只有人标能分开；
- 卡 36 的 `impact` 表能指出「9 张锚要重锚」，但指不出「重锚后检索会更好还是更坏」。

## §3 写集（无其他写入者）

1. `src/test/java/com/slz/crm/quality/LabelSheetNearDupRelevanceTest.java`（新，生成器 + 打分器）
2. `src/test/java/com/slz/crm/quality/LabelSheetNearDupRelevanceGuardTest.java`（新）
3. `docs/rag-quality/labels/near-dup-relevance-v1.csv`（生成物，**禁手改**）
4. `docs/rag-quality/labels/near-dup-relevance-v1-README.md`（生成物）
5. `docs/rag-quality/label-sheet-near-dup-relevance-v1.json` + `.md`（生成物）
6. `openspec/changes/label-sheet-near-dup-relevance/{proposal.md,tasks.md}`（卡面：**入列**，别再犯卡 37 的写集漏项）

**禁改**：`src/main/**`、`pom.xml`、`.github/**`、既有 46 张锚、`NearDuplicateCorpus`、`RagBenchmarkSuite`。

## §4 三件必须做对的

1. **盲标**（最容易做错）：CSV 里**不能有** `cos` / `tier` / `band` 列。给标注者看分数 = 让标签变成「机器观点的复述」，这张卡就白做了。只给：查询原文、候选文本、文件/页、现行名次。
2. **确定性**：同一提交连跑两次，CSV / README / 锚逐字节一致。语料是程序化生成的（`NearDuplicateCorpus`），重建必须稳定——卡 32–37 已七次复现，若这次不稳，说明你重建路径错了，先查再报。
3. **不许代填**：`relevance` / `isNearDuplicate` 两列只允许 Owner 填。你必须让打分器在标签为空时报 `AWAITING-HUMAN-LABELS`，并在标签非法时**逐行报错**（`annotator` 命中 `agent|ai|model` 直接拒）。为了测试打分器，可以用 `@TempDir` 里的**合成 CSV**，但那份文件不得进 `docs/`、不得出现在任何锚里（R3 守卫要断言这一点）。

## §5 三情景打分口径

| 情景 | 排序怎么来 | 用来看什么 |
| --- | --- | --- |
| 现状 | 生产 `0.982` 口径（当前 `NEAR_DUPLICATE_FLOOR`） | 现行体验 |
| 无降权 | 把 `δ` 置 0（等于不压制任何近重复） | 压制的净效果 |
| 0.90 口径 | 门槛降到 0.90 重算 `cos` 与降权 | 拍板备选 |

每情景三项指标：`precision@5`（`relevance≥1` 算相关）、`NDCG@5`（增益 `2^rel−1`）、**一致率**（被判 `Y` 的候选里，被压制过的比例）。**三情景并列，不排序、不给推荐。**

## §6 已知坑

- 参数整串加引号：`"-Dlabel.sheet.write=true"`（裸写会被 Maven 当 lifecycle phase）。
- PowerShell 里 `python -c` 写中文会踩引号/反引号；脚本落文件再跑。
- 别用 `Get-Content -Raw` 读 UTF-8（PS5.1 按 GBK 解码，不可逆）。
- 锚/CSV 必须纯 LF：Jackson 与 `Files.writeString` 都可能带 `\r`，落盘前替换。
- `bash` 解析到 WSL 会 `ERROR_FILE_NOT_FOUND`；用 `D:\git\Git\bin\bash.exe -lc`。

## §7 成功判据（复核者看三条）

1. CSV 30 行、无分数列、可读文本齐全、逐字节确定；
2. 打分器拒绝非法标签（逐条可演示）、空标签时锚为 `AWAITING` 且不含假分数；
3. 三情景并列、无倾向措辞、锚可复认证。

## §8 取证口令

```bash
git diff master --stat -- src/main pom.xml .github   # 必须为空
git status --porcelain -- docs/rag-quality           # 只应有本卡新增
head -3 docs/rag-quality/labels/near-dup-relevance-v1.csv
Select-String -Path docs/rag-quality/labels/near-dup-relevance-v1.csv -Pattern 'cos|tier|band'  # 必须 0 命中
```

## §9 执行者留档位

- 提交 SHA：
- 未填标签时的锚状态与三情景字段：
- 文本缺失行数：
