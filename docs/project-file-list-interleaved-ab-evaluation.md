# 项目文件列表鉴权热路径 · 交错 A/B 归因复测报告

> 提案 `add-project-file-list-interleaved-ab-attribution`，2026-09-27。本报告只为原 `update-project-file-list-auth-hotpath` 的 S2 **本机**性能裁决补端到端因果证据；不改生产代码，不宣称生产收益。
> 第 2–5 节为**看结果前**写定的身份、环境、安全门禁与预注册设计（预注册副本 `D:\code\pflhot-ab-logs\pre-registration.md`，时间戳早于任一测量 run）；第 6–10 节按实测填入。

## 0. 摘要与裁决

- **裁决：有条件 GO（仅限本机）。** 六对交错 run 的被改路径 P50 对数差**全部为负**（B 更快），AB / BA 各自中位均为负，六对中位幅度 `0.255` 是四次 A 安慰剂最大相邻漂移 `0.0116` 的约 22 倍；P95 改善 6/6、吞吐 6/6 上升，失败 0；未改路径阴性对照未出现可比幅度的共同改善；两臂安全回归全绿。
- **不宣称生产加速。** 本结论只把 S2 的**本机**性能裁决从「有条件 / 未定」升为有条件 GO；生产环境收益仍待生产验证。
- **安全修复与性能裁决分开**：当前角色联查的安全修复（`d67c7e9`）已合入，本报告不改动、也不因性能结论回退它。

## 1. 目的

`docs/project-file-list-auth-hotpath-evaluation.md` §11.6.4 把 S2 性能裁决下调为「有条件 / 未定」：修正区间分离公式后，被改路径 20 单元 P50/P95/吞吐各只有 6/20 严格不重叠，而同代码安慰剂 P95 却有 8/20，前测自身聚合 P50 已随时间下降 10.7%。那些都是既有日志的只读复算。本报告用**同机交错、配对 run** 的新测量重新归因。

## 2. 两臂身份与字节同构（本轮实测）

| 臂 | ref | worktree | 语义 |
|---|---|---|---|
| A（对照） | `31ba8c4` | `D:\code\pflhot-ab-A` | 优化前基线；`hasPermission` 先按 userId 回查 roleId，再取角色权限链 |
| B（被测） | `d67c7e9` | `D:\code\pflhot-ab-B` | 安全等价修复后；一次联查 `sys_user -> 当前 role_id -> role_permissions -> permissions` |

- **测试侧度量入口字节同构**：两臂 `src/test/java/com/slz/crm/quality/ProjectFileListAuthHotpathBenchmark.java` 的 `git hash-object` 均为 `966e8c541165a6927031aeb8e6292d955ad8f406`。A 原提交不含该文件（它由优化提交引入），按提案要求把 B 的同字节文件复制进 A 臂工作树，使两臂的负载参数、种子、分组、测量代码完全一致。两臂均 `mvn -o test-compile` 成功，证明同构可编译。
- **A/B 生产差异**（`git diff 31ba8c4 d67c7e9 -- src/main`，共 4 文件、+27/-7）：`PermissionsMapper.java`（新增 `getPermissionListByUserId`）、`PermissionService.java`（Javadoc）、`PermissionServiceImpl.java`（改用联查）、`PermissionsMapper.xml`（新增联查 select）。除此之外 `src/main` 无差异。
- **拒绝 S1**：`ce85e09`（旧角色复用）角色改派回归为红，不作任何臂的基线。

## 3. 环境（当轮实测）

| 项 | 值 |
|---|---|
| Docker | `docker info` ServerVersion `29.6.2`（在线） |
| 镜像 | `mysql:8.0`，`sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`（本地已存在，**未拉取**） |
| JDK | Maven 运行时 `21.0.9`（`D:\develop1\jdk21`） |
| Maven | `3.9.4` |
| 接线 | `annotation_handler=com.tangzc.mpe.magic.MyAnnotationHandler`、`project_file_table=project_file`；`PooledDataSource` 生产接线复刻 |
| 负载行 | `PFLHOT load: page_sizes=[10, 50, 100] warmup=3 samples=15 rounds=2 c8_threads=8 c8_per_thread=20 group_size=120 probe_rows=15` |
| 未采集资源 | `container_resources=unknown (not collected)` —— 记 unknown，**不填 0**；资源采样不与请求计时同步阻塞 |

## 4. 安全门禁（先于性能测量）

两臂各实跑 `ProjectFileListAuthHotpathBenchmark#runRoleReassignInterleavingRegression`，日志 `D:\code\pflhot-ab-logs\safety-A.log` / `safety-B.log`。

- 覆盖：角色改派交错（列表 records/total、下载令牌复核、三维度、共享活动附件入口、隔离性、空角色语义）、撤权复核、分页 total/records。
- 结果：两臂均 `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，逐行 `PFLHOT interleave: ... passed`：`baseline_old_role_readable=true`、`list_after_reassign total=3 records=[]`、`download_recheck_after_reassign denied=true token_binding_unchanged=true`、`dimension_rows_after_reassign all_denied=true`、`shared_activity_entry_after_reassign denied=true`、`isolation role_only=true status=1 participation_unchanged=true`、`semantics empty_role_throws=true revoke_dimension_isolated=true`。**两臂全绿方进入性能测量。**

## 5. 预注册设计（看结果前写定，事后未改序、未删窗）

**负载**：基准默认口径 —— 页 `{10,50,100}`、权限分布 ALL/PART/NONE、`warmup=3`、`samples=15`、每 run `rounds=2`、并发相 `8 线程 × 20`（PART-50-C8 / NONE-50-C8）、`group_size=120`、`probe_rows=15`；冷/暖两态。

**执行顺序（严格固定）**：

| pair | 顺序 | run | arm |
|---|---|---|---|
| 1 | A->B | 1, 2 | A, B |
| 2 | B->A | 3, 4 | B, A |
| 3 | A->B | 5, 6 | A, B |
| 4 | B->A | 7, 8 | B, A |
| 5 | A->B | 9, 10 | A, B |
| 6 | B->A | 11, 12 | B, A |
| 安慰剂 | A×4 | 13–16 | A, A, A, A |

每臂 6 次独立 run（每 run 独立复位的一次性容器）；安慰剂 = 同一 A 代码连续 4 次 run，量化同代码时间漂移。

**主指标**：每 run 取**第 2 轮**「被改路径 20 单元」（= SALES 的 9 个条件 × COLD/WARM 共 18，加 PART-50-C8 / NONE-50-C8 共 2）的 `e2e_p50_ms`，对每个配对计算逐单元 `ln(B_p50 / A_p50)` 的**等权均值**，得 6 个配对差（负值 = B 更快）。条件格不是独立实验；重采样以**配对 run** 为抽样单位。

**保守 GO 判据（六条全满足才有条件 GO）**：①六个配对差全部 `<0`；②AB 三对中位 `<0` 且 BA 三对中位 `<0`；③`|六对中位差|` 大于四次 A 安慰剂相邻 run 最大绝对同代码对数 P50 漂移；④P95 改善 ≥4 对、吞吐不降 ≥4 对且 AB/BA 各自中位均不退化；⑤失败为 0；⑥未改路径阴性对照（ADMIN-10/FROZEN-10/LEAVER-10 的 6 单元）无同量级共同改善。不满足即记未定或 no-go；SQL 减次、少数严格不重叠或单页变快都不能单独恢复 GO。

**失败处置（事先固定）**：单 run 若不满足 `BUILD SUCCESS` + `Tests run: 1, Failures: 0, Errors: 0` + `PFLHOT done: ...failures=0`，或逐条件 `ok != requests`，则原顺序整对重跑一次；仍失败即停止记 no-go。

## 6. 原始数据与逐 run 校验

### 6.1 run 顺序、耗时、代码身份

| run | arm | wall(s) | run | arm | wall(s) |
|---|---|---|---|---|---|
| 1 | A | 404 | 9 | A | 351 |
| 2 | B | 312 | 10 | B | 335 |
| 3 | B | 312 | 11 | B | 289 |
| 4 | A | 391 | 12 | A | 350 |
| 5 | A | 384 | 13 | A（安慰剂） | 345 |
| 6 | B | 309 | 14 | A（安慰剂） | 356 |
| 7 | B | 317 | 15 | A（安慰剂） | 351 |
| 8 | A | 400 | 16 | A（安慰剂） | 347 |

wall = 整 `mvn test`（含 Maven 启动/容器/迁移），只作进度记录，**不是**被比较的指标。16 run 全部首次尝试即 `RESULT=ok`，无整对重跑（`sequence_end ... stop=False`）。

### 6.2 逐 run 校验（解析脚本 `analyze.py`）

- 每 run `cells=52`（26 单元 × 2 轮），`PFLHOT done: ...failures=0`，无 `PFLHOT failure:` 行，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`。
- **逐条件 `ok == requests`**：列表相 15/15，并发相 160/160，全部成立。
- **页输出一致性**：26 单元 × 2 轮的 `page1_records` 与 `total` 在 16 run 间**逐格一致**（不一致单元数 = 0）；`verifyPage` 断言（total 保留数据库条件总数、records 顺序与下载链接）在每 run 通过（否则测试会红）。
- **未采集资源**：`container_resources=unknown`。

### 6.3 原始日志路径与摘要哈希（SHA-256 前 16 位）

| 日志 | 哈希 | 日志 | 哈希 |
|---|---|---|---|
| run-01-A.log | 8B36FCDF05A8902F | run-09-A.log | 2B458667E5C103DA |
| run-02-B.log | EB562E29A94DF106 | run-10-B.log | 0C92EDB3DFCDBE0C |
| run-03-B.log | E2F350E39F7E1CEB | run-11-B.log | E25B7C87EE320CF7 |
| run-04-A.log | D115D7FF111F80D1 | run-12-A.log | F073217EF50EA1F5 |
| run-05-A.log | 18C20DFC3B8F7FB5 | run-13-A.log | A9BF5D36041BFA50 |
| run-06-B.log | 253F586ADF718C0A | run-14-A.log | 2145D40E6C862E3A |
| run-07-B.log | 8432544DE3CA2BD3 | run-15-A.log | F24225926EDB2177 |
| run-08-A.log | 4F6BC54845B4278B | run-16-A.log | 5429A663EF543144 |

安全日志：safety-A.log `8F057A245EEA8AA8`，safety-B.log `DF309DB430E75460`。全部位于 `D:\code\pflhot-ab-logs\`（体积大，不入库）。

### 6.4 环境 fail closed（本轮实测）

| 场景 | 实测结果 |
|---|---|
| 缺 `PROJECT_FILE_LIST_HOTPATH_MEASURE` | `IllegalStateException: 未测：缺少独立 opt-in 开关 ...`，`BUILD FAILURE`，**未进入容器/装配** |
| 指定不存在的镜像 `-Dpflhot.mysql.image=mysql:0.0-nonexistent` | `IllegalStateException: 未测：本地镜像缺失 [mysql:0.0-nonexistent]`，**未拉取镜像** |
| 默认发现 | 类名不以 `Test/IT` 结尾，surefire/failsafe 默认发现不到（由默认 `merge-gate.sh` 与既有守卫测试佐证，见第 10 节） |

## 7. 独立分析与复算（脚本 `analyze.py` / `ext.py`）

### 7.1 主指标（每 run 第 2 轮，20 单元，等权 ln P50）

| run | arm | lnP50 均值 | 几何 P50(ms) | run | arm | lnP50 均值 | 几何 P50(ms) |
|---|---|---|---|---|---|---|---|
| 1 | A | 5.723403 | 305.94 | 9 | A | 5.526353 | 251.23 |
| 2 | B | 5.385497 | 218.22 | 10 | B | 5.349088 | 210.42 |
| 3 | B | 5.415572 | 224.88 | 11 | B | 5.296845 | 199.71 |
| 4 | A | 5.694295 | 297.17 | 12 | A | 5.586030 | 266.68 |
| 5 | A | 5.656380 | 286.11 | 13 | A | 5.563703 | 260.79 |
| 6 | B | 5.425392 | 227.10 | 14 | A | 5.575317 | 263.83 |
| 7 | B | 5.449663 | 232.68 | 15 | A | 5.568604 | 262.07 |
| 8 | A | 5.608719 | 272.80 | 16 | A | 5.568759 | 262.11 |

**配对差（B 相对 A，<0 = B 更快）**：

| pair | 顺序 | A run | B run | 差 | 比值 exp(差) |
|---|---|---|---|---|---|
| 1 | AB | 1 | 2 | -0.337906 | 0.7133 |
| 2 | BA | 4 | 3 | -0.278723 | 0.7567 |
| 3 | AB | 5 | 6 | -0.230988 | 0.7937 |
| 4 | BA | 8 | 7 | -0.159056 | 0.8529 |
| 5 | AB | 9 | 10 | -0.177265 | 0.8376 |
| 6 | BA | 12 | 11 | -0.289184 | 0.7489 |

中位：AB `-0.230988`、BA `-0.278723`、全部 `-0.254855`（均值 `-0.245520`）。**六个配对差全部 < 0**。A 臂均值 `5.632530`、B 臂均值 `5.387009`。

### 7.2 安慰剂漂移（同一 A 代码，连续 4 run）

lnP50：run13 `5.563703`、run14 `5.575317`、run15 `5.568604`、run16 `5.568759`。
相邻绝对漂移：`0.011614`、`0.006714`、`0.000156` → **最大 0.011614**。六对中位幅度 `0.254855` 是其约 **22 倍**。
> **顺序披露（诚实说明）**：预注册设计把安慰剂排在第 13–16 位（交错六对**之后**），并非置于最前。故该 `0.011614` 量化的是序列尾段的同代码漂移，可能低估交错窗口内的漂移；判据③的严格性由此受影响。对冲是 §7.3 已披露 A 臂跨序列单向变快趋势（run1→run12 约 `-0.137`），且最关键的证伪来自 BA 三对（B 先跑、A 后跑，B 仍三对全快），该论证**不依赖**安慰剂阈值。

### 7.3 次序效应与反漂移论证

- AB 三对（A 在前）差：-0.337906 / -0.230988 / -0.177265；BA 三对（**B 在前**）差：-0.278723 / -0.159056 / -0.289184。
- **关键证伪**：BA 对里 B 先跑、A 后跑，若差异由单向时间漂移（越晚越快）造成，B 应先慢；实测三对 BA 差**全部为负**（B 仍更快）。故本效应不能由「B 恰好跑在后/在冷机更慢的 A 之后」解释。
- 诚实披露：A 臂跨序列确有单向变快趋势（run1 `5.723403` -> run12 `5.586030`，约 -0.137），方向与 B 更快一致。但最保守地把该趋势全部扣除后，B 相对 A 的六对中位仍为负（-0.255 + 0.137 ≈ -0.118 的量级留待读者判断，且 BA 对的证伪不依赖该扣除）。预注册判据用的是相邻安慰剂漂移（0.0116），已满足。

### 7.4 P95、吞吐、失败

| 指标 | 6 对方向 | AB 中位(ln) | BA 中位(ln) |
|---|---|---|---|
| P95 | 6/6 改善 | -0.1822 | -0.2320 |
| 吞吐 | 6/6 不降（均上升） | +0.2307 | +0.2827 |

P95 比值：0.7274 / 0.7493 / 0.8575 / 0.8785 / 0.8335 / 0.7930。吞吐比值：1.4006 / 1.3267 / 1.2595 / 1.1765 / 1.1924 / 1.3368。失败 = 0。

### 7.5 阴性对照（未改路径 ADMIN-10 / FROZEN-10 / LEAVER-10 的 6 单元）

配对差：`+0.002452`、`+0.129482`、`+0.132647`、`-0.181247`、`+0.314786`、`-0.115706`；中位 `+0.065967`、均值 `+0.047069`。方向**与真实改动相反**（B 略慢），且中位幅度 `0.066` 远小于主指标 `0.255`；符号混杂（4 正 2 负）。**未出现可比幅度的共同改善**。（该三条件极快，10–40ms，对数比噪声天然偏大，单格极值不能当效应。）

### 7.6 SQL 形状（第 2 轮 WARM，`次数` 已按 ok 归一）

| 条件 | 臂 | auth/calls | auth/user | auth/permission |
|---|---|---|---|---|
| ALL-100-SALES | A | 200 | 400 | 200 |
| ALL-100-SALES | B | 200 | 200 | 200 |
| PART-50-C8 | A | 75 | 175 | 75 |
| PART-50-C8 | B | 75 | 100 | 75 |

16 run 逐条不变：被改路径 A 的 `auth/user = 2×auth/calls`，B 的 `auth/user = auth/calls`；`auth/calls`、`auth/permission` 两臂相同。这是确定性的形状改变（A 每次判定多一次用户回查）。按原案口径，SQL 减次**不单独**构成验收，仅作机理佐证。

### 7.7 第一轮与 bootstrap

- 第一轮（同口径）：A 均值 `5.660263`、B 均值 `5.420112`、差 `-0.240151`；六对第一轮差**全部为负**（-0.3238/-0.2608/-0.2566/-0.1248/-0.2756/-0.1995），与第二轮同向。
- 以**配对 run** 为重采样单位（6 个配对差，20000 次，种子 20260927）：配对均值差 95% CI `[-0.293982, -0.195768]`，`frac<0 = 1.0000`；6/6 负的符号检验单侧 `p = 0.01562`。样本仅 6 对，CI 只作不确定性披露，不作因果强主张。

## 8. 预注册判据逐条裁决

| # | 判据 | 实测 | 判定 |
|---|---|---|---|
| 1 | 六对主指标差全部 <0 | -0.3379/-0.2787/-0.2310/-0.1591/-0.1773/-0.2892 | 通过 |
| 2 | AB、BA 各自中位 <0 | AB -0.2310、BA -0.2787 | 通过 |
| 3 | 中位幅度 > 安慰剂最大漂移 | 0.254855 > 0.011614 | 通过 |
| 4 | P95 改善 ≥4、吞吐不降 ≥4，两序中位不退化 | P95 6/6、吞吐 6/6；中位均向好 | 通过 |
| 5 | 失败为 0 | 16 run 全 `failures=0`、`ok==requests` | 通过 |
| 6 | 阴性对照无可比共同改善 | 中位 +0.066（方向相反） | 通过 |

**六条全过 → 有条件 GO（本机）。**

## 9. 结论、边界与未跑项

**结论**：在本次同机交错、配对 run 的设计下，B 相对 A 的被改路径第 2 轮 P50 几何均值降低约 `0.71–0.85`（中位比值约 `0.775`），且该方向在两种执行顺序、两轮、P95、吞吐、bootstrap、符号检验上一致；安慰剂漂移比效应小约 22 倍；阴性对照无共同改善。据此把 S2 的**本机**性能裁决从「有条件 / 未定」升为**有条件 GO**。SQL 形状改变（`auth/user` 减半）是可解释的机理。

**边界与诚实说明**：

- 只作**本机**裁决，**不宣称生产加速**；生产环境收益仍待生产验证。
- 样本有限（6 对 + 4 安慰剂）；A 臂存在跨序列单向变快趋势，虽 BA 对与安慰剂判据已排除其主要解释，仍不构成强因果证明。
- 未采集容器/CPU/GC 资源（unknown），未做 CPU 亲和/关后台负载；吞吐为展示值口径。
- `page1_records`/`total` 与 SQL 计数来自基准自身账本，非外部真库审计。

**未跑项**：生产环境验证；真实业务库；真实模型外呼（本轮零外发）；默认门禁下的 failsafe `[it]` 仅按第 10 节口径；容器侧资源指标。

## 10. 复现与本轮门禁

- 主测量：两臂各自 `PROJECT_FILE_LIST_HOTPATH_MEASURE=1 mvn -B -ntp -o -Dtest=ProjectFileListAuthHotpathBenchmark#runProjectFileListAuthHotpathMeasurement test`（`DASHSCOPE_API_KEY` 置空）；安全：`... #runRoleReassignInterleavingRegression`。
- 解析/复算：`D:\code\pflhot-ab-logs\analyze.py`（逐 run 校验 + 配对差 + 安慰剂 + 次指标 + 阴性对照 + SQL 形状）、`ext.py`（bootstrap / 符号检验 / 次序论证）、`pagecheck.py`（页输出一致性）。
- 默认门禁不发现真库入口：基准类名不匹配 `**/*Test.java`、`**/*IT.java`。
- **本轮门禁实测**（权威树 `master@b4db7b5`，日志在 `D:\code\pflhot-ab-logs\`）：
  - 定向守卫 `mvn -B -ntp -o -Dtest=ProjectFileListAuthHotpathGuardTest test`：`Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`，`BUILD SUCCESS`（`guards-authoritative.log`）。
  - 角色改派安全回归（`PROJECT_FILE_LIST_HOTPATH_MEASURE=1`，权威树）：全部 `PFLHOT interleave: ... passed`，`Tests run: 1, Failures: 0, Errors: 0`，`BUILD SUCCESS`（`safety-authoritative.log`）。
  - 默认 `bash scripts/merge-gate.sh`：`[unit]` `Tests run: 832, Failures: 0, Errors: 0, Skipped: 0`；`[spotbugs]`/`[pmd]` `BUILD SUCCESS`；`[baseline]` surefire 834（基线 ≥773）、failsafe 74/跳过 6（基线 ≥66/≤6）；`[frontend-unit]` 165 passed；`[hook]`/`[bijection]`（12=12）/`[pmd-baseline]`（0==0==0）PASS；整体 `merge-gate-exit=0`（`merge-gate-default.log`）。`[it]` failsafe 默认未执行（未加 `--with-verify`）。