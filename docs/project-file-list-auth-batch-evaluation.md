# 项目文件列表鉴权读批量化（batch-project-file-list-auth-reads）· 评估报告

> 卡 P-t / B1 保守批量化 / owner 2026-10-07 拍板。基线 `master@a923bfd`（948 单测全绿）→ 特性分支 `0c20a5e`+`9f436ff`。全部数字自跑实测（2026-10-07，本机），未照抄卡面预期值。

## 0. 摘要与裁决

**裁决：GO（本机加速成立，权限输出零回归）。**

- **交错 A/B**：PRE=`a923bfd` 纯复制树 vs POST=本实现，同机交错 5 对（AB/BA 交替）+ 3 安慰剂。5 对配对差**全部 <0**（POST 更快），几何比值 0.323～0.543、**中位 0.433**（被改路径 20 单元等权 ln P50，≈ **-57% P50**）；AB 中位 -0.847 / BA 中位 -0.812，两侧同向；安慰剂（同 PRE 代码连续 3 run）最大对数漂移 0.365，**小于中位差 0.838**。
- **SQL 形状**：ALL-100 页 auth SQL **325 → 106 次/请求（-67.7%）**——`sys_user` 100→1、维度实体 125→4（selectBatchIds）、参与人 0→1（IN）、`auth/permission` **100→100 逐行实时不减**（B1 红线达成）。
- **零回归**：探针/状态闸/撤权/`listBy*`/交错回归指纹与既有基线报告逐字一致；13 run × 52 格 `total`/`page1_records` 零 mismatch；角色改派交错回归两臂全绿。
- **全量门禁**：surefire **956/0/0/0**（948→+8，只增不减）、spotbugs/pmd 0 违规、回归基线 `--update` 948→956、三守卫 OK、门禁自测 35/101/87 全绿。

## 1. 实现形状（卡面 §4.1）

**批量入口**（`AttachmentAccessService`，列表链路专用）：

```java
List<ProjectFileEntity> filterReadableProjectFiles(List<ProjectFileEntity> files, Long userId)
```

语义：可读判定与单行入口 `canReadProjectFile` 逐行等价且保持原顺序；超管整组直通、用户缺失/冻结/离职整组空；非超管走 `ProjectFileAttachmentReader.filterReadableByDimension`。

**预取清单**（ID 去重，`prefetchDimensionData`）：
1. 活动 ID → `BusinessActivityMapper.selectBatchIds`（≤1 次）；
2. 商机 ID → `SalesOpportunityMapper.selectBatchIds`（≤1 次）；
3. 订单项 ID → `ContractOrderItemMapper.selectBatchIds`（≤1 次）；生效合同 ID 按单行 `resolveContractId` 语义逐行求值（订单项命中则覆盖 `file.contractId`）→ `ContractMapper.selectBatchIds`（≤1 次）；
4. 参与人 → 复用既有 `BusinessActivityUserMapper.selectByActivityIds` 1 次 IN，内存过滤当前用户（**mapper 零改动，写集缩至 9 文件**）。

**判定矩阵**：逐行 `hasPermission` 实时调用（activity 225 → opportunity 204 → contract 215 → 无维度无合同仅上传人 → reservedRead 恒 false），顺序与单行入口逐字一致；权限 false 的行不消费预取实体（判定等价，仅多读）。

**接线**：`ProjectFileServiceImpl.toReadableVOs` 每请求恰好调用 1 次批量入口（空列表零调用）；VO 转换/令牌签发/`total`/排序零改动；单行入口 `canReadProjectFile` 与 `PublicAttachmentController` 下载复核路径零改动。

**写集 diffstat（`git diff --stat a923bfd..HEAD`，9 文件）**：

```
 src/main/.../service/AttachmentAccessService.java          |  16 +
 src/main/.../service/impl/AttachmentAccessServiceImpl.java |  30 +-
 src/main/.../service/impl/ProjectFileAttachmentReader.java | 219 +++++++
 src/main/.../service/impl/ProjectFileServiceImpl.java      |  15 +-
 src/test/.../unit/service/AttachmentAccessServiceTest.java | 177 +++++++
 src/test/.../unit/service/ProjectFileServiceImplTest.java  | 122 ++++++--
 scripts/test-baseline.txt                                  |   6 +-
 docs/project-file-list-auth-batch-evaluation.md            | (本文件，新增)
 openspec/changes/batch-project-file-list-auth-reads/tasks.md | (勾选留痕)
```

（`BusinessActivityUserMapper.java` 未入集——复用既有 `selectByActivityIds`，卡面登记的条件性写集未触发。）

## 2. 两臂身份与同构（本轮实测）

| 臂 | 代码 | 树 | 语义 |
|---|---|---|---|
| PRE（对照） | `a923bfd` | `D:\code\pflhot-ab-pre`（git worktree，测后已删） | 逐行判定：每行 1 次 `sys_user` 读 + 每维度 1 次 `selectById`/`exists` |
| POST（被测） | `0c20a5e`+`9f436ff` | 权威工作树 feature 分支 | 批量过滤：整组 1 次 user 读 + 预取 + 逐行矩阵 |

- `git diff a923bfd..0c20a5e -- src/main`：4 文件、+274/-6，全部落在卡面写集；除此之外 `src/main` 无差异。
- **度量入口字节同构**：两臂 `ProjectFileListAuthHotpathBenchmark.java` 工作树 `git hash-object` 均为 `07e17629b30defc3c4d01806cce1e62330ba1c32`（= git 版 `966e8c54…` + `REPORT_KEYS`/`LEAF_NANOS_KEYS` 扩展 `req/user`、`req/permission`、`req/activity`、`req/activityUser`、`req/opportunity`、`req/contract`、`req/orderItem` 共 7 键，+15/-1）。该扩展**仅工作树临时调整、不入库**（git 版 benchmark 在两臂 HEAD 均无改动，测后已恢复）：批量入口走列表请求主链、无 auth scope，其 SQL 以 `req/*` 键记账；PRE 侧这些键恒 0 不打印。两臂 `mvn test-compile` 均成功。**遗留**：该键列表扩展与批量入口的 auth attribution 壳应另案正式入库（见 §11）。

## 3. 环境（当轮实测）

| 项 | 值 |
|---|---|
| Docker | `29.6.2`（在线） |
| 镜像 | `mysql:8.0`（本地已有，未 pull） |
| JDK / Maven | 21（Maven 运行时）/ 3.9.4 |
| 负载 | `page_sizes=[10,50,100] warmup=3 samples=15 rounds=2 c8=8×20 group_size=120 probe_rows=15`（基准默认口径，两臂一致） |

## 4. 安全门禁（先于性能测量）

两臂各实跑 `ProjectFileListAuthHotpathBenchmark#runRoleReassignInterleavingRegression`（36.66s / 36.25s，`Tests run: 1, Failures: 0, Errors: 0`），8 行 `PFLHOT interleave:` 输出两臂**逐字一致**且与既有基线报告一致：

```
baseline_old_role_readable=true
list_after_reassign total=3 records=[]
download_recheck_after_reassign denied=true token_binding_unchanged=true
dimension_rows_after_reassign all_denied=true
shared_activity_entry_after_reassign denied=true
isolation role_only=true status=1 participation_unchanged=true
semantics empty_role_throws=true revoke_dimension_isolated=true
all role-reassign interleaving assertions passed
```

关键点：批量路径的整组 user 读同样落在「首次用户读取返回后」交错窗内，改派后所有行判定用当前角色 → `records=[]`；单行下载复核路径未动，`denied=true`。**两臂全绿方进入性能测量。**

## 5. 预注册设计

- **主指标**：每 run 第 2 轮「被改路径 20 单元」（SALES 9 条件 × COLD/WARM = 18 + PART-50-C8 + NONE-50-C8）的 `e2e_p50_ms` 等权 ln 均值；配对差 `d = lnPOST − lnPRE`（负 = POST 快）。
- **执行顺序**（固定）：5 对交错 `PRE,POST / POST,PRE / …`（run 1–10）+ 安慰剂 PRE×3（run 11–13）。
- **判据**：①5 对全部 `d<0`；②AB/BA 中位均 `<0`；③`|中位差|` > 安慰剂最大相邻漂移；④失败=0；⑤页输出（52 格 total/page1_records）13 run 逐格一致。分层对照（GATE/ADMIN 对批量化的响应方向）作佐证不作门禁——本案批量化覆盖全部 12 条件（user 读 N→1 对 ADMIN/FROZEN/LEAVER 同样生效），**不存在纯未改路径的 e2e 阴性对照**（与上轮 A/B 的关键差异，见 §9）。
- **失败处置**：单 run 非 `BUILD SUCCESS`+`failures=0` 即停。

## 6. 原始数据

| run | 臂 | wall(s) | run | 臂 | wall(s) |
|---|---|---|---|---|---|
| 1 | PRE | 262 | 8 | PRE | 164 |
| 2 | POST | 166 | 9 | PRE | 191 |
| 3 | POST | 92 | 10 | POST | 138 |
| 4 | PRE | 160 | 11 | PRE（安慰剂） | 267 |
| 5 | PRE | 158 | 12 | PRE（安慰剂） | 355 |
| 6 | POST | 88 | 13 | PRE（安慰剂） | 285 |
| 7 | POST | 93 | | | |

13 run 全部首次尝试 `rc=0` + `PFLHOT done: …failures=0`，无重跑。逐 run 几何 P50（20 单元，第 2 轮）：

| run | 臂 | geoP50(ms) | run | 臂 | geoP50(ms) |
|---|---|---|---|---|---|
| 1 | PRE | 152.3 | 8 | PRE | 143.8 |
| 2 | POST | 61.4 | 9 | PRE | 148.4 |
| 3 | POST | 61.3 | 10 | POST | 66.1 |
| 4 | PRE | 140.9 | 11 | PRE | 146.6 |
| 5 | PRE | 136.4 | 12 | PRE | 211.1 |
| 6 | POST | 64.4 | 13 | PRE | 164.7 |
| 7 | POST | 57.7 | | | |

## 7. 主指标与判据裁决

**5 对配对差（d = lnPOST − lnPRE）**：

| pair | 顺序 | d | 比值 |
|---|---|---|---|
| 1 | PRE→POST | -1.1297 | 0.3231 |
| 2 | POST→PRE | -0.7852 | 0.4560 |
| 3 | PRE→POST | -0.8469 | 0.4288 |
| 4 | POST→PRE | -0.8379 | 0.4326 |
| 5 | PRE→POST | -0.6106 | 0.5430 |

| 判据 | 阈值 | 实测 | 结论 |
|---|---|---|---|
| ① 配对差全负 | 5/5 | 5/5 负 | **通过** |
| ② AB/BA 中位 | 均 <0 | AB -0.847 / BA -0.812（全对中位 -0.838） | **通过** |
| ③ 大于安慰剂漂移 | \|中位差\| > max\|安慰剂\| | 0.838 > 0.365 | **通过**（本机时间漂移可观——placebo-12 整体慢 ~20%——中位差仍 2.3 倍于它） |
| ④ 失败 | 0 | 13 run 全 0 | **通过** |
| ⑤ 页输出逐格一致 | 0 mismatch | 52 格 × 13 run = 0 | **通过** |

**几何加速比 0.433（≈ P50 -57%）**。分层对照（5 对几何均比值）：SALES-18=0.431、GATE(FROZEN/LEAVER)=0.360、ADMIN=0.467——GATE 最快与「整组短路砍掉全部维度判定」一致，方向合理。

## 8. SQL 分类前后对照（ALL-100-SALES，第 2 轮 WARM，次数/每请求，实测）

| 类别 | PRE（a923bfd） | POST（批量） | 变化 |
|---|---|---|---|
| `sys_user` 状态闸（UserMapper.selectById） | 100 | **1** | N→1 |
| 参与人查询（exists / IN，实测归 user 类，见下注） | 0（creator 全命中短路） | **1** | 1 次 IN |
| 权限链联查（PermissionsMapper.getPermissionListByUserId） | 100 | **100** | **逐行实时不减（B1 红线）** |
| 活动实体（BusinessActivityMapper） | 25 | **1** | selectBatchIds |
| 商机实体（SalesOpportunityMapper） | 25 | **1** | selectBatchIds |
| 合同实体（ContractMapper，含订单反查共用） | 50 | **1** | selectBatchIds |
| 订单项（ContractOrderItemMapper） | 25 | **1** | selectBatchIds |
| **auth SQL 合计** | **325** | **106** | **-67.7%** |

> 注：度量侧 `categorize` 以 `contains("UserMapper.")` 先于 `BusinessActivityUserMapper.` 匹配，参与人查询（`existsByActivityIdAndUserId`/`selectByActivityIds`）历史上即被归入 user 类——PRE 的 NONE-100 页 `auth/user=150`（100 状态闸 + 50 exists）可佐证。两臂口径一致，对照可比性不受影响。POST 的 `req/user=2` = 状态闸 1 + 参与人 IN 1；GATE/ADMIN 整组短路路径 `req/user=1`（无参与人查询）。
> NONE-100 页对照：PRE 175（150 user + 100 permission + 50 activity + 50 opportunity…实测 `auth/user=150/auth/permission=100/auth/activity=50/auth/opportunity=50`）→ POST 106 同形收敛（user 2 + permission 100 + activity 1 + opportunity 1）。

## 9. 权限零回归证据（一票否决项）

POST run（run-02）的冻结行为/权限反例段与既有基线报告（`project-file-list-auth-hotpath-evaluation.md` §6）**逐字对照**：

| 指纹 | 基线报告 §6 | 本轮 POST run-02 | 结论 |
|---|---|---|---|
| probe readable/denied | `[4013,4012,4010,4008,4007,4005,4004,4003,4001,4000] / [4002,4006,4009,4011,4014]` | 同 | 一致 |
| gate | `frozen/leaver total=120 records=0` | 同 | 一致 |
| listby activity/opportunity/contract/order | `n91/n32/n31/n31, urls==n, bind=ok` | 同 | 一致 |
| revocation | `old_token_still_parses=true recheck_denied=true list_excludes_opportunity_rows=true window_total=15 window_readable=[4013,4010,4008,4007,4001,4000]` | 同 | 一致 |
| permission_restored | `full_readable_set_restored=[4013,4012,4010,4008,4007,4005,4004,4003,4001,4000]` | 同 | 一致 |
| interleave 8 行 | §6 表 | 两臂逐字一致 | 一致 |
| `PFLHOT frozen: all … passed` | 有 | 有 | 一致 |

B1 红线的三重证据：①交错回归两臂全绿（改派后逐行拒绝、无请求级快照）；②单测锁 `batchFilterPermissionChangeMidListIsHonoredPerRow`（列表中途回收权限按行生效）与 `batchFilterPermissionChainStaysPerRowRealtime`（3 行 → `hasPermission` 恰 3 次）；③实测 `auth/permission` 100→100 不减。

## 10. 门禁与测试台账

- 全量 `mvn -B -ntp test`：**956/0/0/0**（基线 948 → +8：判定矩阵等价 7 + 列表形状 1）；merge-gate `[unit]` 同数。
- 四静态门禁：spotbugs **0**、pmd **0**（实测 0 == 台账 0 == pom 阈值 0）、spotless 0、checkstyle 0（随 test 期门禁通过）。
- `check-test-baseline.sh --update`：surefire 948→**956** 真实写回（reports=166、failsafe 24/83/6 不变）。
- 三守卫：`check-write-set a923bfd` **7 files OK**（提交面，不含 docs 笔）、`check-line-endings lf` OK、`check-dirty` **CLEAN**。
- 门禁自测：agent-helper **35**、check-test-baseline **101**、merge-gate **87**，全绿。
- 红测试先行（未改主代码基线实录）：①运行红 `queryPageAuthUserReadIsBatchedToSingleRead`——`TooManyActualInvocations: userMapper.selectById(7L); Wanted 1 time: But was 3 times`；②编译红 `AttachmentAccessServiceTest`——`找不到符号 filterReadableProjectFiles`（批量入口缺失）。

## 11. 边界、未跑项与遗留

- **未跑**：`mvn verify` failsafe IT（Testcontainers 系列，与本卡改动面无关）；真实外部模型/生产环境/生产数据规模；容器侧资源采集（unknown，未填 0）。
- **度量基建遗留（建议另案入库）**：①`REPORT_KEYS`/`LEAF_NANOS_KEYS` 的 `req/*` 键扩展（本轮仅工作树，hash `07e17629`，测后已恢复）；②批量入口的 auth attribution 壳（`TimedAccessService` override `filterReadableProjectFiles`，因 PRE 臂编译约束本轮未采用）；③`categorize` 的 `BusinessActivityUserMapper` 前缀误归 user 类（历史存在，改动会破坏既有基线口径对照，需 owner 拍板）。
- **已知取舍（卡面 §2 登记）**：单行路径维度权限 false 时不查实体，批量路径无条件预取（多读但预取数据不参与无权限行判定）；参与人查询从「逐行 exists」改为「1 次 IN + 内存过滤」，参与关系以预取时点为准（同一请求内，判定等价）。
- **诚实披露**：本轮 A/B 为第二轮——第一轮 13 run 因 POST 臂 auth 归类缺失（`TimedAccessService` 未包批量入口）作废，修正键列表扩展后全量重跑；两臂 benchmark 字节同构为工作树调整而非 git 版本，已在 §2 登记 hash 与理由。

## 12. git 拓扑（合入前）

```
a923bfd (master) ── 0c20a5e perf(projectfile) ── 9f436ff chore(baseline) ── [docs 笔] ── --no-ff→ master
```

（第三笔 docs 提交与本报告、tasks.md 留痕；合并后分支 `feature/batch-project-file-list-auth-reads` 保留、**禁 push**。）
