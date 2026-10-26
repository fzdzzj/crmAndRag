# 任务卡 · eval-port-production-parity

## §1 一句话

给 72 例套件（`caseCount=72`，`suiteVersion=6.0`，实测 **6 条腿 / 1 条生产 + 5 条偏差 / 9 个偏差点**）**安上误差条**：稀疏腿真跑 MySQL ngram 出差异表、度量腿与归一化面改报可比口径、输入腿开真嵌入通道（opt-in）；**不替换 CI 端口**（`mvn test` 契约无 Docker，见 proposal D6），语料面留白。预算 **¥5 封顶**，默认零外呼。

## §2 为什么现在做

`eval-port-route-v1` 定案 `PARTIAL-OFF-PRODUCTION`：套件分数不可跨卡比较。于是每个「调阈值 / 调融合权重 / 换 rerank」的主张都只能靠真跑卡（花钱+慢），零成本判断做不了。近重复线六张卡全程绕开它——这就是它贵的实证。

## §3 写集（执行者只许写这些，无其他写入者）

1. `src/test/java/com/slz/crm/quality/EvalPortParitySparseTest.java`（新）
2. `src/test/java/com/slz/crm/quality/EvalPortParityRealEmbeddingReplayTest.java`（新）
3. `src/test/java/com/slz/crm/quality/EvalPortParityGuardTest.java`（新）
4. `docs/rag-quality/eval-port-parity-v1.json` + `.md`（生成器写模式产出，**禁手改**）
5. `openspec/changes/eval-port-production-parity/tasks.md`（只勾选 + 补勾依据）

**禁改**：`src/main/**`、`pom.xml`、`.github/**`、既有 45 张锚、`SparseBenchmarkRecallService`（只读复用）、`RagQualityRegressionTest` 判据。

## §4 三件必须做对的（按分量）

1. **稀疏腿必须是真的**（限本卡独立测试类，**不替换** CI 端口）：真 MySQL 8.0.36 容器 + 真 Flyway 迁移 + 真 FULLTEXT ngram；用生产同一条 `fulltextSearch` SQL。装置可照 `ChunkNgramRecallGateIT` 抄（同一套 mapper、同授权 JOIN / `is_deleted`）。**不许**拿 bigram 调参冒充。
2. **差异要量化，不要结论**：近似 vs 真的 top-k 重叠、名次位移、分数分布，逐查询落表。写「等价 / 一致 / 无差异」直接红（守卫断言）。
3. **预算前置检查**：真嵌入那一步先把「调用次数 × token 估算」打出来，再比 ¥5，**超了就不跑**。跑完记台账（次数/实际 token/耗时）。

## §5 四条红线

- `src/main` 零 diff；不改任何生产常量。
- 默认 `mvn test` 零外呼（CI 不花钱）；真跑必须 opt-in，且**只能跑一次**。
- 既有 45 张锚零字节变动（`git status` 无 `M`）。
- 不外推：不能说「生产分数分布 / 生产召回质量 / 可跨卡比较」；语料腿仍是 fixture，必须写进 `residualDeviation`。

## §6 已知坑

- `-Dfoo.bar=baz` 在 PowerShell 里必须**整串加引号**，否则 Maven 报 `Unknown lifecycle phase`。
- `bash` 解析到 WSL 会 `ERROR_FILE_NOT_FOUND`；要跑脚本用 `D:\git\Git\bin\bash.exe -lc`。
- 别用 `Get-Content -Raw` 读 UTF-8（PS5.1 按 GBK 解码，乱码不可逆）。
- Testcontainers 需 Docker；先 `docker info` 实测，别假定。
- 锚必须纯 LF：Jackson 默认缩进器走 `System.lineSeparator()`，生成器里要钉死 `\n`。

## §7 成功判据（复核者看这三条）

1. 稀疏腿 = 真 ngram（证据：容器日志 + 生产 SQL 调用点 + 差异表非空）；
2. 度量三项齐备（每行有 raw / rank / margin）；
3. 预算与台账自洽（估 ≤ ¥5，实测次数/耗时与锚内一致，默认跑 `externalCalls=0`）。

## §8 取证口令（可选，便于复核）

```bash
git diff master --stat -- src/main pom.xml .github   # 必须为空
git status --porcelain -- docs/rag-quality           # 只应有本卡两个新文件
Select-String -Path docs/rag-quality/eval-port-parity-v1.json -Pattern 'rawFusedScore|margin|residualDeviation'
```

## §9 执行者留档位

- 提交 SHA：
- 真跑台账（次数 / token / 耗时）：
- 未解决项：
