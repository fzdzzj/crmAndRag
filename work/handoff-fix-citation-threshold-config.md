# handoff · fix-citation-threshold-config（池账 #27 / C-2，B 档）

- 权威树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- 分支：`feature/fix-citation-threshold-config`（自 master@2c8a148 检出，**未 merge、未 push、分支保留**）
- 提交：`650b501` fix（代码+测试）→ `bdc4720` test(baseline)（基线刷新）→ docs 笔＝本文件所在提交
- 物料：`openspec/changes/fix-citation-threshold-config/`（proposal.md + tasks.md，已勾选）；`work/task-card-fix-citation-threshold-config.md`（未入库）；日志 `work/{targeted-citation-threshold,verify-fix-citation-threshold,baseline-update,baseline-check,merge-gate}.log`（未入库）

---

## 1. 阈值注入路径图（配置键 → 解析点 → Aligner 重载 → 决策）

```
① 键 schema（唯一真相源，写入期护栏）
   DynamicConfigKeyRegistry.catalog()
     rag.retrieval.citation.keepMin   Double 默认 "0.12" 范围 0.0~1.0
     rag.retrieval.citation.remapMin  Double 默认 "0.22" 范围 0.0~1.0
     rag.retrieval.citation.tieMargin Double 默认 "0.08" 范围 0.0~1.0
        │ validate(key, raw) → 类型/范围护栏（越界即拒绝写入）
        ▼
② 运行期读取（沿用既有 rag.retrieval.* 消费范式）
   platform.contract.DynamicConfigService（冻结契约，纯接口）
        ▲ ObjectProvider<DynamicConfigService>.getIfAvailable()（惰性读，字段注入完成后再取值）
        │
   AiChatStreamLifecycle（@Component）
     @Autowired(required=false) ObjectProvider<DynamicConfigService> dynamicConfigProvider
        │ 构造参数：Supplier<CitationThresholds> =
        │   () -> CitationThresholdsResolver.resolve(provider.getIfAvailable())
        ▼
③ 收尾时解析（每个流一次，运营改配置热生效）
   AiChatStreamFinalizer#finishStreamNormally
     resolveCitationThresholds()：supplier.get() == null ? defaults() : 值
        │
        ▼
④ 对齐决策（核心类零 Spring、零外呼）
   CitationAligner.align(answer, sources, thresholds)      ← 两参形态 = align(answer, sources, defaults())
     decide(original, clause, sources, thresholds)
       KEEP  : scoreOrig >= keepMin && scoreOrig + tieMargin >= best
       REMAP : best >= remapMin && (原号越界 || scoreOrig < keepMin || best - scoreOrig >= tieMargin)
       DROP  : 其余
        │
        ▼
   Alignment(text, citations) → 落库 / SSE references / 评测 CaseOutcome.citations
```

**核心类零 Spring 证据**：`CitationAligner` / `CitationSupport` / `CitationThresholds` / `CitationThresholdsResolver`
对 `org.springframework` 零 import；Resolver 只依赖 `com.slz.crm.platform.contract.DynamicConfigService`（纯接口），
Spring 事实只出现在调用侧 `AiChatStreamLifecycle`。

## 2. 三键 registry 登记对照表（含 tier 归属依据）

| 键 | 类型 | 登记默认值 | 范围 | tier | 归属依据（实读对表） |
|---|---|---|---|---|---|
| `rag.retrieval.citation.keepMin` | Double | `0.12`（= `CitationAligner.KEEP_MIN`） | 0.0~1.0 | OPERATIONAL | 与 `rag.retrieval.minScore`(0.20/0.0~1.0/OPERATIONAL) 同类：检索/引用质量的浮点阈值，热调无模型边际成本、不改库结构与入库语义 → 非 COST（不产生外呼费用）、非 STRUCTURAL（不造成新旧切片/嵌入标准不一致） |
| `rag.retrieval.citation.remapMin` | Double | `0.22`（= `CitationAligner.REMAP_MIN`） | 0.0~1.0 | OPERATIONAL | 同上（同一决策族，三键同档避免单键跨档造成运维语义割裂） |
| `rag.retrieval.citation.tieMargin` | Double | `0.08`（= `CitationAligner.TIE_MARGIN`） | 0.0~1.0 | OPERATIONAL | 同上；平局带宽只影响编号保/换，不涉成本与结构 |

- 定级封闭表 `ConfigKeyTierPolicy`：OPERATIONAL 32→**35**，COST 23、STRUCTURAL 5 不变；census 全集 60→**63**（`ConfigKeyTierPolicyTest` 锁死，2/2 绿）。
- 范围/默认值与 `CitationThresholds` 构造校验同界（[0,1] 有限数）；registry 写入期拒绝越界，Resolver 读侧再做一次越界回落（双保险）。
- 文档同步：`docs/dynamic-config-keys.md` 新增 `rag.retrieval.citation.*` 小节三行 + 计数与 OPERATIONAL 全清单更新（60→63 / 32→35）。

## 3. `align(` 调用点清单（生产/评测/测试逐处处置）

| 位置 | 形态 | 处置 |
|---|---|---|
| `AiChatStreamFinalizer#finishStreamNormally`（唯一生产调用点） | 原来是两参 | 改三参：`align(content, sources, resolveCitationThresholds())`，阈值来自动态配置 |
| `RagRealRetrievalBenchmarkIT#evaluateCase`（评测适配层） | 已两参（56f1297 引入） | 显式三参 `CitationThresholds.defaults()` + 注释说明「评测钉生产默认阈值保证跨次可比」 |
| `CitationAlignerTest`（6 处） | 两参 | **零改动**（零漂移回归的既有护栏） |
| `PerfBaselineSmokeTest`（2 处） | 两参 | 零改动 |
| `CitationThresholdsTest`（4 处，新增） | 两参 + 三参 | 新增：三态边界与默认等价断言 |
| `CitationPrecisionBackfillEvidenceTest`（2 处，新增） | 三参 | 新增：回灌证据（DROP → citP 下降） |
| `RuleSelfRagReflector` | 只用 `clauseAround` + `CitationAligner.KEEP_MIN` 常量 | **未改**（非 align 调用点，见偏差 3） |
| `LlmSelfRagReflector` | 只用 `clauseAround` | 未改 |

## 4. 回灌证据单测断言清单（`CitationPrecisionBackfillEvidenceTest`，2/2 绿）

1. `goldenCitationDroppedByAlignerLowersCitationPrecision`
   - fixture：`expectedChunkIds={gold-1}`、`retrieved=["gold-1"]`、黄金块 excerpt = 无关文本，答案 `今天天气晴朗适合户外活动[1]。`
   - 缺口态断言：不做对齐、citations=`[1]` → `citationPrecision = 1.0`、`meanCitationPrecision = 1.0`（误杀也满分 = C-2 形态）
   - 对齐断言：`CitationAligner.align(raw, sources, defaults())` → `citations()` 空、`text()` 不含 `[1]`
   - 闭环断言：上报对齐后 citations（空）→ `citationPrecision = 0.0`、`meanCitationPrecision = 0.0`
   - 无关性断言：`recallAtK` 与 `answerConsistency` 对齐前后相等（对齐只改编号）
2. `supportedGoldenCitationSurvivesAlignment`（对照组，防「回灌一律清零」误判）
   - 有支撑的 `[1]` 经对齐仍 KEEP `[1]`，`text()` 与原文逐字节相同，citP 仍 1.0

## 5. 默认零漂移论证

1. **常量未动**：`CitationAligner.KEEP_MIN/REMAP_MIN/TIE_MARGIN` = 0.12/0.22/0.08 原样保留（`CitationAlignerTest` 读的也是行为，不是数值）。
2. **两参 = 默认值**：`align(answer, sources)` 直接 `return align(answer, sources, CitationThresholds.defaults())`，`defaults()` 返回的三个值就是上述常量 → 决策表达式逐项相同（同一组 `>=` 比较与同一 tieMargin）。
3. **注册默认值 = 常量**：registry 三键登记默认值 `"0.12"/"0.22"/"0.08"`，由 `CitationThresholdKeysRegistryTest`/`CitationThresholdsResolverTest` 断言 `Double.parseDouble(defaultValue) == 常量`（默认值漂移即红）。
4. **读侧兜底**：Resolver 在无配置服务 / 键缺失 / 类型不符 / 非有限数 / 越出 [0,1] 时逐键回落常量——运营未配置时生产行为与升级前一致。
5. **测试实证**：两参与三参默认形态在 `text()` + `citations()` 双口径逐个相等；既有 `CitationAlignerTest` 6 个用例、`AiChatServiceImplTest` 30 个用例、`PerfBaselineSmokeTest` 全部零改动全绿。

## 6. 定向测试清单与结果

`mvn -B -ntp "-Dtest=CitationThresholdsTest,CitationAlignerTest,CitationThresholdsResolverTest,CitationThresholdKeysRegistryTest,ConfigKeyTierPolicyTest,DynamicConfigKeyRegistryTest,CitationPrecisionBackfillEvidenceTest,AiChatServiceImplTest,AiChatStreamLifecycleHeartbeatTest,AiChatStreamLifecycleTimeoutTest,AiChatStreamTerminalGuaranteeTest,RagQualityEvaluatorTest,PerfBaselineSmokeTest" test`

→ `Tests run: 82, Failures: 0, Errors: 0, Skipped: 0` / `BUILD SUCCESS`（日志 `work/targeted-citation-threshold.log`；PowerShell 管末 `exit code: 1` 为 Tee-Object 假信号，日志内 BUILD SUCCESS 为准）。

## 7. 基线四元组与门禁

- Docker：`docker info` → `29.6.2`（本机 Docker Desktop 由 Stoped 启动后在线）。
- 全量：`mvn -B -ntp clean verify` → `BUILD SUCCESS`（12:25 min）：
  - surefire：**296 报告 / 1838 用例 / 0 skip**（基线 292/1827 → 只增不减）
  - failsafe：**32 报告 / 119 用例 / 7 skip**（与基线相同，零真跑：未设 `RAG_BENCHMARK_REAL`）
  - 静态分析：checkstyle / spotbugs / spotless / PMD 全绿（PMD 首轮因类注释超 `CommentSize` 20 行被拦，已回退该注释增量后复跑绿）
- 基线：`D:\git\Git\bin\bash.exe scripts/check-test-baseline.sh --update`（树净，`source-revision=650b501`）
  → `surefire.reports=296 / tests=1838 / skipped=0`、`failsafe.reports=32 / tests=119 / skipped=7`；
  复跑校验模式 `EXIT=0`「回归基线门禁通过」。
- merge-gate：`D:\git\Git\bin\bash.exe scripts/merge-gate.sh` → **10/10 PASS**
  `[unit] [checkstyle] [spotbugs] [spotless] [pmd] [baseline] [frontend-unit] [hook] [bijection] [pmd-baseline]`，
  末行「merge-gate 通过」；`[it]` 默认序列不跑（已由全量 verify 单独覆盖）。
  `frontend/typed-router.d.ts` 生成物噪声已 `git checkout --` 还原（`frontend/` 零 diff）。

## 8. 偏差清单

1. **2.1「评测同源」前置已具备**：评测适配层早在 56f1297 就调用了 `CitationAligner.align`（同一代码路径），本卡不新增第二条路径，只把它显式钉到 `CitationThresholds.defaults()` 并注释说明——因此 2.1 记为「前置已具备 + 显式化」，未产生行为变化。
2. **评测侧有意不读动态配置**：`RagRealRetrievalBenchmarkIT` 钉生产默认阈值，保证报告跨次可比；生产读实时配置。二者是「同一代码路径、不同配置来源」——若运营把阈值调离默认，评测仍按默认判分（这是可复现性取舍，非疏漏）。如需「按当前配置评估」，须另开卡并在报告中登记实际生效阈值。
3. **`RuleSelfRagReflector` 仍用编译期 `KEEP_MIN`（0.12）**做支持度判定。它是 Self-RAG 的「引用是否有支撑」判定，不走 `align`，不在本卡「align 调用点」范围；阈值配置化后，运营调 `keepMin` 会让两个消费者分叉（对齐器用新值、Self-RAG 用 0.12）。**风险登记**：建议后续卡把该判定也改为同一 Resolver 注入（需先定「Self-RAG 是否应跟随对齐阈值」）。
4. **`DynamicConfigKeyRegistry` 类 javadoc 未追加本卡变更记录行**：追加后触发 PMD `CommentSize`（maxLines=20）单条违规，按「不改规则、不为压数放宽」原则回退注释增量；键语义由 `ConfigKeyDefinition.description`（registry 内逐键中文说明）与 `docs/dynamic-config-keys.md` 承载。这是唯一因门禁做的取舍。
5. **docs 笔排序**：按卡要求 docs/ 提交排在基线提交之后；基线 `--update` 时把 docs 改动临时 `git stash`，跑完即 `stash pop` 恢复（无内容丢失，`git stash list` 已空）。
6. **文档既有失真未动**：`docs/dynamic-config-keys.md` 成本键审批流小节里「COST 25 键 / OPERATIONAL 34 / STRUCTURAL 7」为历史遗留口径（与本表 23/35/5 不符，且本卡之前即已不符），不在本卡范围，未改，避免扩大 diff。建议后续文档卡统一。
7. **未使用 openapi/spec 契约轨**：本卡无契约变更（三键走 openspec 变更卡轨，registry 为配置 schema 非冻结契约）。

## 9. 停步状态

- 未 `git merge`、未 `git push`、未 `reset/rebase/amend`、未删分支；无数据库迁移、无 `pom.xml` 改动、无新增错误码、`frontend/` 零 diff。
- 分支 HEAD（docs 笔）：见 `git log --oneline -3`；等待 owner/主 Agent 复核与合入授权。
