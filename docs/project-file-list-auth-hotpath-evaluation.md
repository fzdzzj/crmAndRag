# 项目文件列表鉴权热路径 · 前测/后测评估报告

提案：`update-project-file-list-auth-hotpath`（spec/changes/update-project-file-list-auth-hotpath）
权威工作树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`，基线 `master@31ba8c403d876f80b4bbb1cc5e6f9576e047e6dd`
度量入口：`src/test/java/com/slz/crm/quality/ProjectFileListAuthHotpathBenchmark.java`（非默认发现 + 显式 opt-in）

---

## 1. 证据级别与观测口径

**证据级别 A：本机真实存储 + 生产记录级鉴权。**

| 维度 | 实际取值 |
|---|---|
| 数据库 | 一次性 Testcontainers MySQL `mysql:8.0`（本机既有镜像，只读 `inspect` 预检，**未 pull**） |
| 建库方式 | **生产 Flyway 迁移链**（V1 基线 + V3/V4/V4_1/V5/V6 + V21..V28） |
| 被调用实现 | `ProjectFileServiceImpl#queryPage/filterReadable/entityToVO`、`AttachmentAccessServiceImpl#canReadProjectFile/canReadAttachments`、`ProjectFileAttachmentReader#canReadByDimension`、`PermissionServiceImpl#hasPermission`（经 `PermissionsMapper#getPermissionListByUserId` 联查当前角色）、`DataConvertServiceImpl#getUserName` |
| Mapper / SQL | 真实 Mapper 与真实 SQL（MyBatis `Interceptor` 按 `MappedStatement.getId()` 分类计数与计时） |
| 身份与数据 | 全部为**确定性假身份/假业务记录**（`ADMIN=1 / SALES=50 / FROZEN=51 / LEAVER=52 / OTHER=99`，业务记录 500–801，文件组 1000/2000/3000/4000） |
| 令牌 | 本文件内 16 字节**本地常量密钥**签发；**不读任何真实凭据、不连业务库、不调用外部服务、不下载镜像** |
| 表名接线 | `PFLHOT wiring: annotation_handler=com.tangzc.mpe.magic.MyAnnotationHandler project_file_table=project_file` |

**已知口径偏离（前后测一致，不影响相对归因）**

1. 本装配**无 Spring 事务代理**，`@Transactional` 不生效 —— 每条语句各自取连接，端到端耗时**已包含**该开销；
2. `@Cacheable("userName")`（Caffeine）由同语义本地壳复现冷/暖两态；
3. `PermissionsInterceptor` 鉴权拦截器**不在**列表服务调用路径内，未计入。

**未观测项（一律记 unknown，不当作实测瓶颈）**

| 项 | 状态 |
|---|---|
| MySQL 容器侧 CPU / 内存 / 磁盘 IO | `PFLHOT resource: container_resources=unknown (not collected)` |
| JVM 分代 / 编译器明细 | unknown（仅采集 `heap_peak_mb`、GC 次数与毫秒差） |
| 生产 `@Cacheable` 真实 Spring 缓存管理器行为 | 以同语义本地壳近似，非生产实测 |
| 网络 / 反向代理层耗时 | 不含 |
| 生产真实数据规模（远超 120 条/组） | 未测 |
| 并发改派角色与列表签发 / 下载复核之间的时序竞态窗口 | **已测**（确定性交错回归，见第 5.2 节：另一条独立连接在固定交错点改派并提交） |

**固定负载**（`PFLHOT load`）：页大小 `[10, 50, 100]`、预热 3 次、每条件每轮采样 15 次、**每 run 2 轮独立执行**、并发相 8 线程 × 20 次、组大小 120 条/组、探针组 15 条。

**四次独立 run**（同种子、同权限状态、同页大小、同缓存预热、同并发、同观测口径）：

| 代号 | 日志 | 轮次 | 结果 |
|---|---|---|---|
| PRE-2 | `%TEMP%\pflhot-pre2.log` | 2 | `Tests run: 1, Failures: 0`，`done: failures=0`，412.9s |
| PRE-final | `%TEMP%\pflhot-pre-final.log` | 2 | `Tests run: 1, Failures: 0`，`done: failures=0`，402.9s |
| POST-1 | `%TEMP%\pflhot-post.log` | 2 | `Tests run: 1, Failures: 0`，`done: failures=0`，308.7s |
| POST-2 | `%TEMP%\pflhot-post2.log` | 2 | `Tests run: 1, Failures: 0`，`done: failures=0`，329.0s |

> PRE-2 与 PRE-final 是**改动前的两次独立 run**；POST-1 与 POST-2 是**改动后的两次独立 run**。四次 run 的探针、状态闸、撤权反例输出**逐字相同**（见第 6 节）。

**失败与吞吐口径**：所有条件 `ok == requests`（15/15，并发相 160/160），四次 run 均 `failures=0`。

---

## 2. 冻结行为基线（改动前后均成立，实测）

`PFLHOT frozen: queryPage=db_page_then_row_filter; total=db_condition_count; records=readable_subset_of_current_page (may underfill or be empty); token_signing=per_row_in_VO_assembly; download_recheck=separate_boundary (PublicAttachmentController unchanged)`

分页语义实测（每条件 `total` 均为数据库条件总数 120）：

| 条件 | 页大小 | total | 首页 records |
|---|---|---|---|
| ALL | 10 / 50 / 100 | 120 | 10 / 50 / 100（足额） |
| PART | 10 / 50 / 100 | 120 | 5 / 25 / 50（隔行可读，**不足额**） |
| NONE | 10 / 50 / 100 | 120 | 0 / 0 / 0（**records 为空而 total 非零**） |
| ADMIN-10（超管直通） | 10 | 120 | 10 |
| FROZEN-10 / LEAVER-10 | 10 | 120 | 0 / 0 |

判定维度实测：活动 = `SALES_VIEW_BUSINESS_ACTIVITY(225)`、商机 = `SALES_VIEW_SALE_OPPORTUNITY(204)`、合同 = `SALES_VIEW_CONTRACT(215)`、订单经 `contractOrderItemMapper` 反查 contractId。

两个边界**未被合并**：列表签发令牌（`entityToVO` 内逐行签发）与下载时按当前权限重新校验（`PublicAttachmentController` 调用同一 `canReadProjectFile` 入口）各自独立保留。

---

## 3. 主验收表（请求端到端 P50 / P95 / 吞吐）

每格 = **第 1 轮 / 第 2 轮**（两次独立执行）。`d1 = PRE-final → POST-1`，`d2 = PRE-final → POST-2`（取第 1 轮值配对，第 2 轮值配对；两轮都列在 `d1(r1/r2)`、`d2(r1/r2)` 括注内的形式为「同轮配对」）。

### 3.1 P50（`e2e_p50_ms`）

| 条件 | 缓存 | PRE-2 r1/r2 | PRE-final r1/r2 | POST-1 r1/r2 | POST-2 r1/r2 | d1(r1/r2) | d2(r1/r2) |
|---|---|---|---|---|---|---|---|
| ALL-10 | COLD | 172.3/136.7 | 142.1/129.1 | 108.4/99.1 | 116.3/103.0 | -23.7%/-23.3% | -18.2%/-20.2% |
| ALL-10 | WARM | 127.6/122.6 | 87.0/123.6 | 78.9/87.1 | 86.9/89.8 | -9.3%/-29.6% | -0.1%/-27.4% |
| ALL-50 | COLD | 721.0/656.5 | 662.2/643.3 | 466.8/502.1 | 539.1/522.8 | -29.5%/-22.0% | -18.6%/-18.7% |
| ALL-50 | WARM | 582.6/589.7 | 627.0/568.0 | 433.0/451.9 | 439.8/418.2 | -30.9%/-20.4% | -29.8%/-26.4% |
| ALL-100 | COLD | 1298.9/1352.4 | 1268.0/1335.9 | 903.9/1045.2 | 1057.0/1036.3 | -28.7%/-21.8% | -16.6%/-22.4% |
| ALL-100 | WARM | 1119.2/1106.0 | 1106.9/1177.6 | 872.8/877.5 | 854.4/896.3 | -21.2%/-25.5% | -22.8%/-23.9% |
| PART-10 | COLD | 96.6/89.8 | 87.1/93.4 | 71.6/72.3 | 73.7/69.2 | -17.8%/-22.6% | -15.4%/-25.9% |
| PART-10 | WARM | 92.1/88.9 | 79.1/76.6 | 52.9/64.7 | 71.3/70.2 | -33.2%/-15.6% | -9.9%/-8.5% |
| PART-50 | COLD | 441.0/462.3 | 462.9/499.0 | 350.4/364.7 | 377.8/369.5 | -24.3%/-26.9% | -18.4%/-25.9% |
| PART-50 | WARM | 342.0/441.9 | 412.7/450.2 | 327.6/295.0 | 345.9/341.9 | -20.6%/-34.5% | -16.2%/-24.0% |
| PART-100 | COLD | 964.2/984.6 | 901.4/931.4 | 678.5/701.8 | 711.9/762.1 | -24.7%/-24.6% | -21.0%/-18.2% |
| PART-100 | WARM | 876.4/824.1 | 857.9/862.8 | 627.5/625.4 | 669.8/703.3 | -26.9%/-27.5% | -21.9%/-18.5% |
| NONE-10 | COLD | 53.9/53.4 | 61.4/64.0 | 41.9/47.9 | 51.1/49.3 | -31.7%/-25.2% | -16.8%/-23.0% |
| NONE-10 | WARM | 59.8/67.6 | 57.8/57.9 | 47.9/44.8 | 50.2/46.7 | -17.1%/-22.5% | -13.2%/-19.3% |
| NONE-50 | COLD | 280.6/324.0 | 308.3/313.4 | 224.4/226.4 | 262.3/235.3 | -27.2%/-27.8% | -14.9%/-24.9% |
| NONE-50 | WARM | 301.5/311.9 | 285.6/309.6 | 209.1/228.0 | 217.8/255.4 | -26.8%/-26.4% | -23.8%/-17.5% |
| NONE-100 | COLD | 592.3/647.0 | 579.2/610.2 | 454.9/460.1 | 468.0/470.3 | -21.5%/-24.6% | -19.2%/-22.9% |
| NONE-100 | WARM | 590.7/620.1 | 591.0/605.8 | 460.9/447.1 | 479.4/457.1 | -22.0%/-26.2% | -18.9%/-24.5% |
| ADMIN-10（未改路径） | COLD | 31.6/42.4 | 46.4/31.7 | 39.0/41.9 | 40.1/41.8 | -16.0%/32.1% | -13.7%/32.0% |
| ADMIN-10（未改路径） | WARM | 25.4/30.5 | 28.8/27.5 | 26.1/29.3 | 27.8/29.6 | -9.2%/6.5% | -3.3%/7.8% |
| FROZEN-10（未改路径） | COLD | 16.8/16.1 | 13.9/15.5 | 12.7/15.8 | 10.2/17.1 | -8.5%/1.7% | -27.0%/9.8% |
| FROZEN-10（未改路径） | WARM | 16.2/16.9 | 13.7/14.7 | 13.0/16.3 | 9.6/15.8 | -4.9%/10.9% | -29.9%/7.7% |
| LEAVER-10（未改路径） | COLD | 16.9/15.6 | 19.7/16.0 | 15.0/15.8 | 11.5/15.6 | -23.7%/-1.4% | -41.6%/-2.5% |
| LEAVER-10（未改路径） | WARM | 15.9/14.7 | 16.1/15.9 | 13.4/15.8 | 16.2/16.6 | -16.5%/-0.6% | 1.0%/4.8% |
| PART-50-C8（8 线程） | WARM | 447.5/425.8 | 430.6/381.4 | 275.6/273.0 | 258.5/274.0 | -36.0%/-28.4% | -40.0%/-28.2% |
| NONE-50-C8（8 线程） | WARM | 254.4/251.1 | 258.3/240.8 | 183.2/171.1 | 170.6/180.0 | -29.1%/-28.9% | -34.0%/-25.3% |

**读法**：全部 11 个被改路径条件（ALL/PART/NONE × 3 页大小 + 2 并发相）在 **WARM（稳态）的 4 个配对格（2 run × 2 轮：`d1` r1/r2、`d2` r1/r2）** 上 P50 均为负（下降），幅度 **-8.5% ~ -40.0%**。配对格统一取 WARM，以与并发相（仅 WARM）口径一致；COLD 行仅作原始数据留档，不计入本汇总。

### 3.2 P95（`e2e_p95_ms`）

| 条件 | 缓存 | PRE-2 r1/r2 | PRE-final r1/r2 | POST-1 r1/r2 | POST-2 r1/r2 | d1(r1/r2) | d2(r1/r2) |
|---|---|---|---|---|---|---|---|
| ALL-10 | COLD | 199.4/186.8 | 178.5/148.2 | 119.3/152.5 | 154.2/156.6 | -33.2%/2.9% | -13.6%/5.7% |
| ALL-10 | WARM | 147.1/149.3 | 137.2/145.7 | 113.5/111.1 | 103.4/102.3 | -17.2%/-23.7% | -24.6%/-29.8% |
| ALL-50 | COLD | 865.0/722.7 | 692.9/683.2 | 527.5/560.7 | 568.1/645.7 | -23.9%/-17.9% | -18.0%/-5.5% |
| ALL-50 | WARM | 652.5/690.9 | 681.6/655.4 | 478.4/508.8 | 538.4/500.2 | -29.8%/-22.4% | -21.0%/-23.7% |
| ALL-100 | COLD | 1432.8/1433.2 | 1318.8/1427.1 | 1080.1/1122.6 | 1350.3/1124.8 | -18.1%/-21.3% | 2.4%/-21.2% |
| ALL-100 | WARM | 1222.6/1184.2 | 1192.4/1248.5 | 973.3/970.1 | 944.3/975.3 | -18.4%/-22.3% | -20.8%/-21.9% |
| PART-10 | COLD | 131.6/128.4 | 96.9/128.5 | 97.6/78.0 | 152.8/84.1 | 0.8%/-39.3% | 57.8%/-34.6% |
| PART-10 | WARM | 103.5/94.1 | 116.6/104.8 | 78.4/75.9 | 109.9/103.8 | -32.8%/-27.6% | -5.8%/-0.9% |
| PART-50 | COLD | 547.1/558.2 | 534.0/541.4 | 411.8/398.9 | 483.6/426.5 | -22.9%/-26.3% | -9.4%/-21.2% |
| PART-50 | WARM | 446.3/585.3 | 466.0/512.4 | 370.8/338.7 | 404.8/428.3 | -20.4%/-33.9% | -13.1%/-16.4% |
| PART-100 | COLD | 1065.1/1035.2 | 956.1/1028.4 | 718.9/775.7 | 859.7/860.6 | -24.8%/-24.6% | -10.1%/-16.3% |
| PART-100 | WARM | 965.0/1025.3 | 939.9/1009.9 | 705.7/665.0 | 975.3/802.6 | -24.9%/-34.1% | 3.8%/-20.5% |
| NONE-10 | COLD | 63.7/67.0 | 95.9/84.6 | 65.6/55.2 | 58.1/73.2 | -31.6%/-34.7% | -39.5%/-13.4% |
| NONE-10 | WARM | 158.5/87.0 | 66.0/64.4 | 56.7/55.5 | 74.0/65.2 | -14.1%/-13.9% | 12.2%/1.2% |
| NONE-50 | COLD | 340.8/350.6 | 335.9/349.2 | 265.8/263.1 | 307.1/264.5 | -20.9%/-24.6% | -8.6%/-24.3% |
| NONE-50 | WARM | 366.8/399.0 | 338.7/380.8 | 267.3/278.2 | 256.6/288.4 | -21.1%/-26.9% | -24.2%/-24.3% |
| NONE-100 | COLD | 634.1/697.3 | 685.8/674.2 | 539.3/483.5 | 635.1/514.9 | -21.4%/-28.3% | -7.4%/-23.6% |
| NONE-100 | WARM | 693.7/739.1 | 640.5/664.2 | 529.5/501.3 | 586.8/503.5 | -17.3%/-24.5% | -8.4%/-24.2% |
| ADMIN-10（未改路径） | COLD | 34.4/56.0 | 56.6/40.7 | 56.1/47.7 | 42.2/61.4 | -1.0%/17.0% | -25.4%/50.7% |
| ADMIN-10（未改路径） | WARM | 46.5/32.1 | 30.2/29.5 | 34.7/44.0 | 34.4/35.3 | 15.0%/49.2% | 14.0%/19.8% |
| FROZEN-10（未改路径） | COLD | 56.0/27.1 | 16.5/18.9 | 15.0/21.4 | 17.9/24.2 | -9.0%/13.4% | 8.6%/28.2% |
| FROZEN-10（未改路径） | WARM | 18.1/39.9 | 17.5/17.4 | 16.2/22.1 | 20.2/20.8 | -7.3%/27.0% | 15.3%/20.0% |
| LEAVER-10（未改路径） | COLD | 20.3/33.9 | 33.2/19.5 | 17.1/16.4 | 14.5/23.3 | -48.4%/-15.8% | -56.3%/19.7% |
| LEAVER-10（未改路径） | WARM | 19.0/18.8 | 20.9/23.7 | 17.6/19.3 | 68.7/20.4 | -15.9%/-18.9% | 228.2%/-14.2% |
| PART-50-C8（8 线程） | WARM | 536.3/569.3 | 638.0/448.7 | 404.7/323.6 | 401.3/344.7 | -36.6%/-27.9% | -37.1%/-23.2% |
| NONE-50-C8（8 线程） | WARM | 310.1/299.9 | 352.0/310.5 | 229.0/192.8 | 222.4/205.7 | -34.9%/-37.9% | -36.8%/-33.8% |

**读法**：被改路径条件在 **WARM（稳态）的 44 个配对格中 41 格 P95 下降、3 格小幅正抖动、0 格持平**。3 格正抖动是 `PART-100` 的 `d2` r1 `+3.8%`（939.9→975.3ms）、`NONE-10` 的 `d2` r1 `+12.2%`（66.0→74.0ms）与 r2 `+1.2%`（64.4→65.2ms）—— 均落在 PRE 两次 run 在同一条件上的自身跨度量级内（同条件 `NONE-10 WARM` PRE 两次 run 首轮为 158.5 vs 66.0ms，约 2.4×），绝对量最大仅 +35ms，且同条件其他格为正负交替，归因不支持「改动导致 P95 退化」。配对格统一取 WARM，以与并发相（仅 WARM）口径一致；COLD 行仅作原始数据留档，不计入本汇总。

### 3.3 成功吞吐（`throughput_req_s`）

| 条件 | 缓存 | PRE-2 r1/r2 | PRE-final r1/r2 | POST-1 r1/r2 | POST-2 r1/r2 | d1(r1/r2) | d2(r1/r2) |
|---|---|---|---|---|---|---|---|
| ALL-10 | COLD | 5.8/7.3 | 7.0/7.7 | 9.2/10.1 | 8.6/9.7 | 31.4%/31.2% | 22.9%/26.0% |
| ALL-10 | WARM | 7.8/8.2 | 11.5/8.1 | 12.7/11.5 | 11.5/11.1 | 10.4%/42.0% | 0.0%/37.0% |
| ALL-50 | COLD | 1.4/1.5 | 1.5/1.6 | 2.1/2.0 | 1.9/1.9 | 40.0%/25.0% | 26.7%/18.7% |
| ALL-50 | WARM | 1.7/1.7 | 1.6/1.8 | 2.3/2.2 | 2.3/2.4 | 43.7%/22.2% | 43.7%/33.3% |
| ALL-100 | COLD | 0.8/0.7 | 0.8/0.7 | 1.1/1.0 | 0.9/1.0 | 37.5%/42.9% | 12.5%/42.9% |
| ALL-100 | WARM | 0.9/0.9 | 0.9/0.8 | 1.1/1.1 | 1.2/1.1 | 22.2%/37.5% | 33.3%/37.5% |
| PART-10 | COLD | 10.4/11.1 | 11.5/10.7 | 14.0/13.8 | 13.6/14.5 | 21.7%/29.0% | 18.3%/35.5% |
| PART-10 | WARM | 10.9/11.2 | 12.6/13.0 | 18.9/15.5 | 14.0/14.3 | 50.0%/19.2% | 11.1%/10.0% |
| PART-50 | COLD | 2.3/2.2 | 2.2/2.0 | 2.9/2.7 | 2.6/2.7 | 31.8%/35.0% | 18.2%/35.0% |
| PART-50 | WARM | 2.9/2.3 | 2.4/2.2 | 3.1/3.4 | 2.9/2.9 | 29.2%/54.5% | 20.8%/31.8% |
| PART-100 | COLD | 1.0/1.0 | 1.1/1.1 | 1.5/1.4 | 1.4/1.3 | 36.4%/27.3% | 27.3%/18.2% |
| PART-100 | WARM | 1.1/1.2 | 1.2/1.2 | 1.6/1.6 | 1.5/1.4 | 33.3%/33.3% | 25.0%/16.7% |
| NONE-10 | COLD | 18.5/18.7 | 16.3/15.6 | 23.9/20.9 | 19.6/20.3 | 46.6%/34.0% | 20.2%/30.1% |
| NONE-10 | WARM | 16.7/14.8 | 17.3/17.3 | 20.9/22.3 | 19.9/21.4 | 20.8%/28.9% | 15.0%/23.7% |
| NONE-50 | COLD | 3.6/3.1 | 3.2/3.2 | 4.5/4.4 | 3.8/4.2 | 40.6%/37.5% | 18.7%/31.2% |
| NONE-50 | WARM | 3.3/3.2 | 3.5/3.2 | 4.8/4.4 | 4.6/3.9 | 37.1%/37.5% | 31.4%/21.9% |
| NONE-100 | COLD | 1.7/1.5 | 1.7/1.6 | 2.2/2.2 | 2.1/2.1 | 29.4%/37.5% | 23.5%/31.2% |
| NONE-100 | WARM | 1.7/1.6 | 1.7/1.7 | 2.2/2.2 | 2.1/2.2 | 29.4%/29.4% | 23.5%/29.4% |
| ADMIN-10（未改路径） | COLD | 31.6/23.6 | 21.5/31.6 | 25.6/23.9 | 25.0/23.9 | 19.1%/-24.4% | 16.3%/-24.4% |
| ADMIN-10（未改路径） | WARM | 39.4/32.7 | 34.8/36.4 | 38.3/34.1 | 35.9/33.7 | 10.1%/-6.3% | 3.2%/-7.4% |
| FROZEN-10（未改路径） | COLD | 59.7/62.2 | 71.9/64.4 | 78.5/63.3 | 98.4/58.6 | 9.2%/-1.7% | 36.9%/-9.0% |
| FROZEN-10（未改路径） | WARM | 61.6/59.1 | 73.2/68.1 | 77.0/61.5 | 104.5/63.3 | 5.2%/-9.7% | 42.8%/-7.0% |
| LEAVER-10（未改路径） | COLD | 59.3/64.0 | 50.8/62.6 | 66.6/63.4 | 87.1/64.1 | 31.1%/1.3% | 71.5%/2.4% |
| LEAVER-10（未改路径） | WARM | 62.9/68.1 | 62.3/62.9 | 74.6/63.3 | 61.7/60.1 | 19.7%/0.6% | -1.0%/-4.5% |
| PART-50-C8（8 线程） | WARM | 2.2/2.3 | 2.3/2.6 | 3.6/3.7 | 3.9/3.6 | 56.5%/42.3% | 69.6%/38.5% |
| NONE-50-C8（8 线程） | WARM | 3.9/4.0 | 3.9/4.2 | 5.5/5.8 | 5.9/5.6 | 41.0%/38.1% | 51.3%/33.3% |

**读法**：被改路径条件在 WARM（稳态）的 44 个配对格中 **43 格吞吐上升、1 格持平（`ALL-10` WARM `d2` r1 `0.0%`，11.5→11.5 req/s，处于低延迟小页区间的测量步进粒度内）、0 格下降**，上升幅度 **+10.0% ~ +69.6%**；并发相 +33.3% ~ +69.6%。未改路径条件（ADMIN-10 / FROZEN-10 / LEAVER-10）正负抖动均落在毫秒级绝对量（27~45ms）之内，方向不一致 —— 反证改动未旁路影响这些路径，且测量口径可比。

---

## 4. 单因素归因：每类 SQL 调用次数与耗时

格式 `次数/耗时ms`，取自**第 2 轮 WARM**（稳态，名称缓存已暖）。`n/a` = 该类别在该条件该轮次计数为 0，未打印。

| 条件 | 类别 | PRE-2 | PRE-final | POST-1 | POST-2 |
|---|---|---|---|---|---|
| ALL-100 | req/pagingCount | 1/1.35 | 1/1.55 | 1/1.55 | 1/1.47 |
| ALL-100 | req/other | 1/2.07 | 1/2.10 | 1/2.29 | 1/1.98 |
| ALL-100 | auth/calls | 200/1084.33 | 200/1157.31 | 200/872.82 | 200/888.62 |
| ALL-100 | **auth/user** | **400/472.18** | **400/504.80** | **200/248.79** | **200/258.11** |
| ALL-100 | auth/permission | 200/268.24 | 200/286.33 | 200/283.62 | 200/286.17 |
| ALL-100 | auth/activity | 50/61.04 | 50/65.38 | 50/63.17 | 50/63.69 |
| ALL-100 | auth/opportunity | 50/61.91 | 50/65.90 | 50/67.81 | 50/68.97 |
| ALL-100 | auth/contract | 100/122.91 | 100/129.46 | 100/131.52 | 100/132.27 |
| ALL-100 | auth/orderItem | 50/57.97 | 50/63.79 | 50/60.82 | 50/62.76 |
| ALL-100 | name/calls | 100/0.04 | 100/0.05 | 100/0.05 | 100/0.03 |
| ALL-100 | token/calls | 100/4.89 | 100/5.05 | 100/4.61 | 100/4.62 |
| ALL-100 | residual_ms | 43.21 | 45.00 | 20.47 | 20.17 |
| PART-100 | req/pagingCount | 1/1.31 | 1/1.58 | 1/1.26 | 1/1.48 |
| PART-100 | req/other | 1/2.34 | 1/2.46 | 1/2.02 | 1/2.13 |
| PART-100 | auth/calls | 150/842.10 | 150/862.56 | 150/587.43 | 150/707.14 |
| PART-100 | **auth/user** | **350/419.11** | **350/429.43** | **200/221.36** | **200/263.93** |
| PART-100 | auth/permission | 150/205.40 | 150/213.85 | 150/187.49 | 150/225.81 |
| PART-100 | auth/activity | 150/187.18 | 150/188.46 | 150/166.86 | 150/204.09 |
| PART-100 | name/calls | 50/0.02 | 50/0.03 | 50/0.02 | 50/0.02 |
| PART-100 | token/calls | 50/2.36 | 50/2.44 | 50/2.05 | 50/2.22 |
| PART-100 | residual_ms | 32.58 | 33.27 | 13.85 | 15.77 |
| NONE-100 | req/pagingCount | 1/1.41 | 1/1.29 | 1/1.35 | 1/1.49 |
| NONE-100 | req/other | 1/2.16 | 1/2.64 | 1/2.09 | 1/2.12 |
| NONE-100 | auth/calls | 100/615.27 | 100/597.96 | 100/447.44 | 100/447.45 |
| NONE-100 | **auth/user** | **250/317.39** | **250/305.96** | **150/176.11** | **150/177.48** |
| NONE-100 | auth/permission | 100/144.99 | 100/139.32 | 100/138.82 | 100/135.21 |
| NONE-100 | auth/activity | 50/66.32 | 50/65.07 | 50/60.15 | 50/60.74 |
| NONE-100 | auth/opportunity | 50/64.72 | 50/65.87 | 50/63.23 | 50/65.18 |
| NONE-100 | residual_ms | 23.11 | 22.92 | 10.49 | 10.26 |
| PART-50-C8 | req/pagingCount | 1/1.66 | 1/1.60 | 1/2.33 | 1/1.85 |
| PART-50-C8 | req/other | 1/1.60 | 1/1.72 | 1/1.55 | 1/1.59 |
| PART-50-C8 | auth/calls | 75/446.16 | 75/386.36 | 75/273.86 | 75/273.50 |
| PART-50-C8 | **auth/user** | **175/185.63** | **175/165.70** | **100/92.48** | **100/92.10** |
| PART-50-C8 | auth/permission | 75/83.97 | 75/76.33 | 75/75.58 | 75/75.49 |
| PART-50-C8 | auth/activity | 75/78.33 | 75/70.51 | 75/70.18 | 75/70.57 |
| PART-50-C8 | name/calls | 25/0.00 | 25/0.01 | 25/0.01 | 25/0.00 |
| PART-50-C8 | token/calls | 25/0.72 | 25/0.63 | 25/0.65 | 25/0.62 |
| PART-50-C8 | residual_ms | 100.98 | 76.72 | 39.56 | 39.07 |
| ADMIN-10 | req/pagingCount | 1/1.46 | 1/1.36 | 1/1.32 | 1/1.56 |
| ADMIN-10 | req/other | 1/1.75 | 1/1.57 | 1/1.63 | 1/1.74 |
| ADMIN-10 | auth/calls | 20/25.83 | 20/22.36 | 20/36.65 | 20/26.69 |
| ADMIN-10 | auth/user | 20/25.34 | 20/21.95 | 20/36.19 | 20/26.23 |
| ADMIN-10 | name/calls | 10/0.01 | 10/0.01 | 10/0.00 | 10/0.00 |
| ADMIN-10 | token/calls | 10/0.43 | 10/0.35 | 10/0.40 | 10/0.41 |
| ADMIN-10 | residual_ms | 1.64 | 1.42 | 1.75 | 1.94 |
| FROZEN-10 | req/pagingCount | 1/2.42 | 1/1.28 | 1/1.71 | 1/1.61 |
| FROZEN-10 | req/other | 1/1.94 | 1/1.45 | 1/1.74 | 1/1.77 |
| FROZEN-10 | auth/calls | 10/15.47 | 10/11.64 | 10/12.54 | 10/12.77 |
| FROZEN-10 | auth/user | 10/15.21 | 10/11.41 | 10/12.31 | 10/12.53 |
| FROZEN-10 | residual_ms | 1.30 | 1.19 | 1.40 | 1.26 |

**唯一变动类别 = `auth/user`**，其余类别次数**逐条不变**，耗时在噪声内一致。

### 4.1 前测点名的主因（改动前）

**`auth/user` —— 同一请求内对「当前用户 `sys_user` 行」的重复读**，占被改路径条件端到端的 **38–50%**：

- ALL-100：`auth/user` 400 次 / 472–505ms（e2e 1119–1178ms）→ **42–45%**
- PART-100：350 次 / 419–429ms（e2e 858–863ms）→ **49–50%**
- NONE-100：250 次 / 306–317ms（e2e 591–606ms）→ **52%**

**结构来源**：`ProjectFileServiceImpl` 对每一页先 `filterReadable` 逐行调 `canReadProjectFile`，再在 `entityToVO` 对可读行**第二次**调同一入口；而每次 `canReadProjectFile` 内部对**同一当前用户**重复读 2 次 `sys_user`（1 次在 `AttachmentAccessServiceImpl#canReadProjectFile` 顶部的状态闸/超管闸 + 1 次在 `PermissionServiceImpl#hasPermission` 内解析 roleId）。

**实测验证的定量关系**：`Δauth/user == auth/calls`（每次鉴权调用恰好减掉 1 次重复用户读）：

| 条件 | auth/calls（鉴权调用次数） | auth/user 前 → 后 | Δ |
|---|---|---|---|
| ALL-100 | 200 | 400 → 200 | 200 |
| PART-100 | 150 | 350 → 200 | 150 |
| NONE-100 | 100 | 250 → 150 | 100 |
| PART-50-C8 | 75 | 175 → 100 | 75 |
| NONE-50-C8 | 50 | 125 → 75 | 50 |
| ADMIN-10（未改路径） | 20 | 20 → 20 | 0 |
| FROZEN-10（未改路径） | 10 | 10 → 10 | 0 |

ADMIN/FROZEN 的 `auth/user` 前后均为调用次数本身（超管/状态闸在 `hasPermission` 之前短路，本来就只有 1 次读）—— 这正是「改动只作用于 `hasPermission` 内部的重复读」的直接证据。

---

## 5. 单因素优化与安全等价论证

### 5.1 唯一改动因素（S2 定稿）

**唯一改动因素**：`PermissionServiceImpl#hasPermission(Long, PermissionOperates)` 内部由「先 `sys_user` 回查 `roleId`、再按 `roleId` 取角色权限链」改为「一次联查 `sys_user → 当前 role_id → role_permissions → permissions`，取得**判定时刻当前角色**的权限链」。

由此热路径每行的 `auth/user`（`sys_user` 读取）仍由 2 次降为 1 次、`auth/permission` 仍为 1 次 —— 收益来源与原始方案**完全相同**（`auth/user` 次数减半），只是把「省下的那次 `sys_user` 回读」由「缓存进请求的旧 roleId」改为「并入权限查询的联查」。

**改动文件（相对 31ba8c4；4 个生产 + 3 个测试）**

- `server/mapper/PermissionsMapper.java` —— 新增 `List<PermissionsEntity> getPermissionListByUserId(Long userId)`
- `server/resources/mapper/PermissionsMapper.xml` —— 新增 `getPermissionListByUserId` select（`permissions p` join `role_permissions rp` join `sys_role r` join `sys_user u on u.role_id = rp.role_id`，`where u.id = #{userId} and r.is_deleted = 0`；与既有 `getPermissionList` 口径一致，**不**过滤 `rp.is_deleted`）
- `server/service/impl/PermissionServiceImpl.java` —— `hasPermission(Long, X)` 改为 `hasPermission(X, permissionsMapper.getPermissionListByUserId(userId))`；**删除** `hasPermissionByRoleId`
- `server/service/PermissionService.java` —— **删除** `hasPermissionByRoleId` 接口方法及其旧 javadoc
- `ProjectFileAttachmentReader` / `AttachmentModelScopeChecker` / `AttachmentAccessServiceImpl` —— **整体回退**到 31ba8c4 原样（roleId 传递链撤除，判定回到 `permissionService.hasPermission(userId, …)`）

### 5.2 被否决的 S1 方案与确定性反例

S1（ce85e09）在同一次鉴权调用内复用**首次状态闸读到的** `user.getRoleId()`：`canReadProjectFile` 先 `userMapper.selectById(userId)`（读 `status` / `roleId`），再把该 `roleId` 传进 `hasPermissionByRoleId`。若在「首次用户读取返回」与「维度权限判定」之间，另一连接把该用户改派为无权角色并提交，判定仍按**过期角色快照**放行。

该反例已用**确定性交错回归**实测复现，不依赖线程调度：`ProjectFileListAuthHotpathBenchmark#runRoleReassignInterleavingRegression` 用 JDK 动态代理包装真实 `UserMapper`，在首次 `selectById(改派目标用户)` 返回处，以另一条独立连接 `UPDATE sys_user SET role_id = <无权角色>` 并提交（默认 autocommit），再把旧快照返回给调用方；**只改 `role_id`**，用户保持在职（`status=1`）、业务参与关系不变，隔离「角色变化」这一个因素。

- **红灯（S1 = ce85e09）**：`PFLHOT interleave: list_after_reassign total=3 records=[5000]`，断言失败于 `verifyReassignListAndSigning`（改派后列表仍返回第 1 行并为其签发下载链接），`Tests run: 1, Failures: 1`。
- **绿灯（S0 = 31ba8c4，同一 benchmark 文件、同一测试方法）**：`records=[]`，且 `download_recheck_after_reassign denied=true`、`dimension_rows_after_reassign all_denied=true`、`shared_activity_entry_after_reassign denied=true`、`isolation role_only=true status=1 participation_unchanged=true`、`semantics empty_role_throws=true revoke_dimension_isolated=true`，`Tests run: 1, Failures: 0` —— 证明回归确实卡在上述窗口、且旧路径（按 userId 实时回查）本可正确处理。
- **修复后（S2）**：同一测试在修复实现上 `Tests run: 1, Failures: 0`，`records=[]` 且上述全部反例维持绿灯（原始输出见第 6 节）。

### 5.3 安全等价论证（逐条由实测支撑）

1. **判定用当前角色（本次修复的要点）**：`hasPermission` 的权限链由 `sys_user u` ⋈ `role_permissions` 按**同一时刻**的 `role_id` 联查取得，请求内不存在角色快照。交错回归的四条断言（列表 `records` / 已签发令牌下载复核 / 三维度行 / 共享活动附件入口）在修复后全部转绿，且在 S0 上同样为绿、在 S1 上为红。
2. **不跨请求缓存**：未引入任何静态 / 请求间缓存；`auth/user` 未清零、仅按调用次数减半即为证据。
3. **权限链仍实时查库**：`getPermissionListByUserId` 每次真实查库；实测 `auth/permission` 次数**前后完全一致**（200/150/100/75…）。
4. **用户状态闸仍实时**：`canReadProjectFile` 顶部的 `userMapper.selectById(userId)`（冻结 / 离职 / 超管判定）**保留未动**；实测冻结 / 离职 `total=120 records=0` 前后一致，`auth/user` 计数对这些路径 0 变化。
5. **下载实时复核未合并**：`PublicAttachmentController#downloadProjectFileByToken` 仍独立调 `canReadProjectFile` 重新取用户与权限；令牌反例 `old_token_still_parses=true` + `recheck_denied=true` 验证，并新增「改派后已签发令牌复核被拒且令牌绑定不变」反例。
6. **两次鉴权边界未合并**：`filterReadable` 首轮与 `entityToVO` 次轮**各自仍独立**调 `canReadProjectFile`（撤权窗口内链接签发时序不变）；未下推过滤，`total` / 页号 / `records` 语义未动（第 2 节与第 6 节实测）。
7. **异常 / 空权限 / 撤权语义与旧实现一致**：交错回归 `verifyReassignPermissionSemantics` 实测 —— 有权限链但不含目标权限 → `false`（不抛）；零权限角色 → `BaseException("该用户没有权限")`；显式删除 `role_permissions`(225) 后活动维度拒绝而商机 / 合同维度不受影响、恢复后复可读。三者在 31ba8c4 与修复实现上输出一致。
   - **已知差异（诚实记录）**：对**不存在的用户**，旧实现 `userMapper.selectOne(...).getRoleId()` 抛 NPE，新实现联查得空权限链后抛 `BaseException("该用户没有权限")`；两者均为拒绝。生产调用方（`PrivacyAspect` ×2、`PendingActionGuards` ×1、`AttachmentModelScopeChecker` ×2）均传现存用户 id，无行为差异。
8. **未触碰禁区**：未改迁移、依赖、权限码 / 注解 / 矩阵、API 响应结构、下载端点实时鉴权、前端、AI/RAG、生产配置、JVM/线程池/连接池参数；未调用真实模型或外部服务；未下载镜像。

---

## 6. 权限 / 分页 / 令牌反例（四次 run 输出逐字相同）

```
PFLHOT probe: readable=[4013, 4012, 4010, 4008, 4007, 4005, 4004, 4003, 4001, 4000] denied=[4002, 4006, 4009, 4011, 4014]
PFLHOT gate: frozen/leaver total=120 records=0
PFLHOT listby: activity=n91 urls=91 bind=ok ids=[...逐字一致...]
PFLHOT listby: opportunity=n32 urls=32 bind=ok ids=[...逐字一致...]
PFLHOT listby: contract=n31 urls=31 bind=ok ids=[...逐字一致...]
PFLHOT listby: order=n31 urls=31 bind=ok ids=[...逐字一致...]
PFLHOT revocation: old_token_still_parses=true recheck_denied=true list_excludes_opportunity_rows=true window_total=15 window_readable=[4013, 4010, 4008, 4007, 4001, 4000]
PFLHOT revocation: permission_restored=true full_readable_set_restored=[4013, 4012, 4010, 4008, 4007, 4005, 4004, 4003, 4001, 4000]
PFLHOT frozen: all frozen-behavior and permission probes passed
```

**角色改派交错回归（安全等价复核；修复后 S2 原始输出，S0 对照同形且同样全绿，S1 在 `list_after_reassign` 处红灯）**

```
PFLHOT interleave: begin window=after_first_user_read_before_dimension_permission_check
PFLHOT interleave: baseline_old_role_readable=true
PFLHOT interleave: list_after_reassign total=3 records=[]
PFLHOT interleave: download_recheck_after_reassign denied=true token_binding_unchanged=true
PFLHOT interleave: dimension_rows_after_reassign all_denied=true
PFLHOT interleave: shared_activity_entry_after_reassign denied=true
PFLHOT interleave: isolation role_only=true status=1 participation_unchanged=true
PFLHOT interleave: semantics empty_role_throws=true revoke_dimension_isolated=true
PFLHOT interleave: all role-reassign interleaving assertions passed
```

**覆盖的正反回归**

| # | 反例 | 实测结论 |
|---|---|---|
| 1 | 全可见（ALL） | 3 页大小 × 逐页 records 足额、顺序由 uploadTime 倒序确定 |
| 2 | 部分可见（PART） | 隔行可读，首页 5/25/50 条（**不足额**），total 仍 120 |
| 3 | 空页但 total 非零（NONE） | records=0，**total=120**（数据库条件总数口径冻结） |
| 4 | 活动参与（225） | 活动维度命中/未命中与探针期望逐位一致 |
| 5 | 商机归属（204） | 同上；撤权 204 后仅商机依赖行消失 |
| 6 | 合同 / 订单反查（215，orderId→contractId） | 合同与订单维度各 100/50 次判定，探针一致 |
| 7 | 一文件多归属 | 探针组/列表组内多维度文件的可读性由「任一维度命中即读」判定，前后一致 |
| 8 | 独立上传（上传人自身） | `UPLOADER_IDS={60..64}` 行可读性前后一致 |
| 9 | 超管（roleId=1） | 直通整页可见（ADMIN-10 records=10），且不改路径 |
| 10 | 冻结 / 离职 | `total=120 records=0`，前后一致 |
| 11 | 撤权后旧令牌被拒 | 旧令牌仍可解析但 `recheck_denied=true`，列表剔除商机依赖行，`window_total` 不变 |
| 12 | 共享授权协作类 `listBy*` 四条路径 | 输出条数 / ID 序列**逐字不变**；可读行**全部**签发下载链接（urls==n）；令牌用户绑定 `userId=SALES_USER_ID`、`fileType=project_file`、未过期（`bind=ok`）。
| 13 | **角色改派交错**（本次新增） | 首次用户读取返回后、维度权限判定前由另一连接改派为无权角色并提交：修复后列表 `records=[]`（不再签发）、已签发令牌下载复核 `denied=true` 且绑定不变、活动/商机/合同三维度行 `all_denied=true`、共享活动附件入口 `denied=true`；S1 在列表 `records=[5000]` 处红灯 |

**令牌比较口径**：令牌含时间与随机内容，故**不比较密文逐字相等**，只比较「签发资格（可读行才签发，urls==n）」「用户绑定（userId/fileType/attachmentId 一致）」「有效性（未过期）」—— 四项在前后测与四次 run 中全部一致。

---

## 7. 资源窗口

| 相 | PRE-final | POST-1 | POST-2 |
|---|---|---|---|
| PART-50-C8 r1/r2 `process_cpu_cores_avg` | 1.713 / 1.562 | 1.699 / 1.614 | 1.700 / 1.681 |
| NONE-50-C8 r1/r2 `process_cpu_cores_avg` | 1.755 / 1.690 | 1.615 / 1.567 | 1.593 / 1.568 |
| PART-50-C8 r1/r2 `heap_peak_mb` | 123.282 / 123.136 | 107.535 / 107.239 | 103.050 / 103.053 |
| NONE-50-C8 r1/r2 `heap_peak_mb` | 122.648 / 123.034 | 107.094 / 107.069 | 102.292 / 102.945 |
| PART-50-C8 r1/r2 `gc_count_delta` | 43 / 25 | 21 / 21 | 22 / 23 |
| PART-50-C8 r1/r2 `gc_ms_delta` | 68 / 22 | 21 / 19 | 21 / 21 |
| NONE-50-C8 r1/r2 `gc_ms_delta` | 16 / 16 | 41 / 13 | 22 / 14 |
| 容器侧 CPU/内存/IO | unknown | unknown | unknown |

后测堆峰值与 GC 次数/耗时同步下降（GC 压力随分配量下降），CPU 核心占用两侧相当（1.56–1.76）—— 与「减少对象分配与 JDBC 往返」一致，非 JVM/连接池调参所致。

---

## 8. 主验收裁决

**主验收 = 请求端到端 P50 / P95 与成功吞吐；权限输出零回归为一票否决。**

| 判据 | 阈值 | 实测 | 结论 |
|---|---|---|---|
| P50：被改路径条件 | 稳定下降 | WARM 稳态 44/44 配对格下降（-8.5% ~ -40.0%），两次独立后测方向一致 | **通过** |
| P95：被改路径条件 | 无一致退化 | WARM 稳态 44 格中 41 格下降、3 格小幅正抖动（+1.2% ~ +12.2%，绝对量 ≤ +35ms），均落在**未改路径条件同等量级**的 run 间噪声内 | **通过** |
| 成功吞吐 | 稳定上升 | WARM 稳态 43/44 配对格上升、1 格持平（0.0%）、0 格下降（+10.0% ~ +69.6%），并发相 +33.3% ~ +69.6% | **通过** |
| 失败 | 0 | 四次 run 全部 `failures=0`，每条件 `ok==requests` | **通过** |
| 权限输出零回归（一票否决） | 完全一致 | 探针 / 状态闸 / 撤权 / listBy* 指纹**四次 run 逐字相同** | **通过** |
| 归因可隔离 | 单一类别 | 仅 `auth/user` 次数变化，`Δauth/user == auth/calls`；其余类别次数逐条不变 | **通过** |
| 权限/分页/令牌语义 | 无差异 | total=数据库条件总数、records 可不足额/为空、下载实时复核独立保留、令牌绑定与有效性一致 | **通过** |
| 测量可比 | 部分 | 负载与观测口径相同（同种子/同权限状态/同页大小/同缓存预热/同并发）；但**未改路径条件也存在非零抖动**（如 ADMIN-10 WARM 两次配对 -9.2%/-16.5%），本行只保证**口径可比**，不含「噪声可忽略」 | **部分通过（口径可比，噪声不可忽略）** |

**裁决（历史，仅覆盖原始 ce85e09 口径）：GO。** 主验收成立，且不是「SQL 次数降低但端到端收益不稳」的 no-go 情形 —— WARM 稳态下端到端 P50 在**两个独立后测 run × 两轮 × 三种页大小 × 8 线程并发相**共 44 个配对格上全部下降，吞吐 43 升 / 1 持平 / 0 降，P95 仅 3 格小幅正抖动且绝对量 ≤ +35ms；权限输出零回归。本裁决不涵盖安全等价修复后的实现；其中「区间分离」表述已按第 11.1 节**严格不重叠**定义更正，S2 复测后的**总裁决见第 11.6.4 节（已下调为「有条件 / 未定」）**——本节 GO 仅在原始 ce85e09 口径下成立、不得外推，且 ce85e09 已因安全等价缺陷回退。

**未叠加第二类优化**，未调 JVM / 连接池追指标。

---

## 9. 复现命令、实际运行与未运行项

**复现（本地度量入口，非默认发现 + 显式 opt-in）**

```text
PROJECT_FILE_LIST_HOTPATH_MEASURE=1 mvn -B -ntp -Dtest=ProjectFileListAuthHotpathBenchmark test
```

缺 opt-in / Docker / 本地镜像时在**下载镜像或连接业务库之前** fail closed，并显式报告「未测」（由 `ProjectFileListAuthHotpathGuardTest` 的纯 JVM 反例锁定，默认 `mvn test` 不启动容器）。

**实际运行**

- `mvn -B -ntp -Dtest=ProjectFileListAuthHotpathBenchmark test`（原始前测 ×2、后测 ×2，口径 S0 → S1；需 Docker 与本地 `mysql:8.0` 镜像，未 pull）
- `PROJECT_FILE_LIST_HOTPATH_MEASURE=1 mvn -B -ntp -Dtest='ProjectFileListAuthHotpathBenchmark#runRoleReassignInterleavingRegression' test`（确定性交错回归：S1 红灯、S0 对照绿灯、S2 绿灯）
- `mvn -B -ntp -Dtest='ProjectFileListAuthHotpathBenchmark#runProjectFileListAuthHotpathMeasurement' test`（安全等价修复后复测：S0 前测 ×2 + S2 后测 ×2，同种子、同条件、同测量口径）
- 定向单测与守卫（见第 10 节）
- `bash scripts/merge-gate.sh` 默认序列
- `git diff --check`

**未运行项**

- `mvn verify` / failsafe `*IT`（Testcontainers MySQL 系列）：**未跑**（默认 merge-gate 不含 `[it]`；需 `--with-verify`，且本机容器型 IT 与本案无关）
- 真实外部模型 / `RAG_BENCHMARK_REAL=1`：**未跑**（本案不调用外部服务）
- 生产环境与生产数据规模：**未测**
- 容器侧资源采集：**未采集（unknown）**

---

## 10. 改动文件与提交

**生产代码（4 个，相对 31ba8c4）**

- `src/main/java/com/slz/crm/server/mapper/PermissionsMapper.java`（新增 `getPermissionListByUserId`）
- `src/main/resources/mapper/PermissionsMapper.xml`（新增 `getPermissionListByUserId` select）
- `src/main/java/com/slz/crm/server/service/impl/PermissionServiceImpl.java`（`hasPermission` 改为联查当前角色权限；删除 `hasPermissionByRoleId`）
- `src/main/java/com/slz/crm/server/service/PermissionService.java`（删除 `hasPermissionByRoleId` 接口方法）

> `ProjectFileAttachmentReader` / `AttachmentModelScopeChecker` / `AttachmentAccessServiceImpl` 相对 31ba8c4 **无差异**：S1 引入的 roleId 传递链已整体回退（S1 的 `hasPermissionByRoleId` 已被删除，不存在遗留的危险调用点）。

**测试（3 个）**

- `src/test/java/com/slz/crm/unit/service/AttachmentAccessServiceTest.java`（桩改回 `hasPermission(userId, …)`；原「按已加载 roleId 判定」缺陷契约用例改写为「按判定时刻当前用户判定」）
- `src/test/java/com/slz/crm/quality/ProjectFileListAuthHotpathBenchmark.java`（度量入口 + 确定性角色改派交错回归，非默认发现 + opt-in）
- `src/test/java/com/slz/crm/quality/ProjectFileListAuthHotpathGuardTest.java`（默认执行的纯 JVM 边界守卫）

**文档与规格（4 个）**

- `docs/project-file-list-auth-hotpath-evaluation.md`（本报告）
- `spec/changes/update-project-file-list-auth-hotpath/proposal.md`
- `spec/changes/update-project-file-list-auth-hotpath/tasks.json`
- `spec/changes/update-project-file-list-auth-hotpath/specs/project-file-list/spec-delta.md`

**保护项（不修改、不删除、不暂存）**：`docs/backend-optimization-candidates.md`

---

---

## 11. 安全等价修复（S2）复测与最终裁决

> 本节覆盖第 3–8 节的原始口径（S0 → S1，裁决见第 8 节末尾「仅覆盖原始 ce85e09 口径」）。本节把被测实现换成**保留安全等价性**的 S2 定稿，重新做同种子、同条件、同测量口径的两次独立前测 + 两次独立后测。

### 11.1 复测口径

**被测实现（S2）**：权威工作树未提交改动，`PermissionServiceImpl#hasPermission(Long, X)` 由「先按 `userId` 回查 `roleId`、再按 `roleId` 取角色权限链」改为「一次联查 `sys_user → 当前 role_id → role_permissions → permissions`，取**判定时刻当前角色**的权限链」。`ProjectFileAttachmentReader` / `AttachmentModelScopeChecker` / `AttachmentAccessServiceImpl` 相对 `31ba8c4` 无差异（S1 的 `hasPermissionByRoleId` 传递链已整体回退，工作树中不存在该方法的任何引用）。

**前测树身份（本 turn 实测）**：`D:\code\pflhot-pre-31ba8c4` 是 `31ba8c4` 的纯复制 —— `31ba8c4:src/main` 下 685 个文件逐一 `git hash-object` 与基线对象**全等（mismatch=0 / missing=0）**；整树 `hasPermissionByRoleId` 引用数为 0；其度量入口 `ProjectFileListAuthHotpathBenchmark.java` 与权威树**字节相同**。

**四次独立 run**（同种子、同权限状态、同页大小 `[10, 50, 100]`、同预热 3、同每条件采样 15、同每 run 2 轮、同并发相 8 线程 × 20、同观测口径）：

| 代号 | 日志 | 被测树 | 轮次 | 结果 |
|---|---|---|---|---|
| PRE-1 | `D:\code\pflhot-logs\s2pre1.log` | S0（`31ba8c4` 纯复制） | 2 | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`；`done: failures=0`；07:19 min |
| PRE-2 | `D:\code\pflhot-logs\s2pre2.log` | S0 | 2 | 同上；06:28 min |
| POST-1 | `D:\code\pflhot-logs\s2post1.log` | S2（权威工作树） | 2 | 同上；05:36 min |
| POST-2 | `D:\code\pflhot-logs\s2post2.log` | S2 | 2 | 同上；05:54 min |

**失败口径**：四次 run 全部 `failures=0`，每条件 `ok==requests`（列表相 15/15，并发相 160/160）。`total=120`、`records` 的足额/不足额/为空形态在四次 run 逐格一致。

**表格格式**：每格 = 第 1 轮 / 第 2 轮。**严格区间分离列** = 把两次前测的 4 个样本与两次后测的 4 个样本各当作一个区间，检查两区间**完全不重叠**：

- 下降型指标（P50 / P95）：`max(POST-1, POST-2, 两轮) < min(PRE-1, PRE-2, 两轮)`；
- 上升型指标（吞吐）：`min(POST-1, POST-2, 两轮) > max(PRE-1, PRE-2, 两轮)`。

括号内比值：P50/P95 = `max POST ÷ min PRE`（< 1 才算分离）；吞吐 = `min POST ÷ max PRE`（> 1 才算分离）。

> **口径更正（2026-09-27，只读复算）**：本节此前把「区间分离」写成 `min(POST) < max(PRE)`（只比较两端极值、未要求两区间不重叠），该式在两组区间明显重叠时仍可成立，**不是**区间分离。按上式严格判据更正后，被改路径 20 单元的 P50 / P95 分离由原记 **20/20 降为 6/20**；吞吐列原式 `min(POST) > max(PRE)` 本已是严格式，计数 6/20 不变。详见第 11.6.3 节。

### 11.2 P50 原始表（26 行：12 条件 × COLD/WARM + 2 并发相）

| 条件 | 缓存 | PRE-1 r1/r2 | PRE-2 r1/r2 | POST-1 r1/r2 | POST-2 r1/r2 | max(POST) vs min(PRE)（严格；<1 才分离） |
|---|---|---|---|---|---|---|
| ALL-10 | COLD | 124.8/113.0 | 144.3/86.2 | 111.7/91.2 | 129.5/90.9 | 否（1.503） |
| ALL-10 | WARM | 102.8/97.4 | 109.1/89.1 | 97.3/88.4 | 100.7/91.8 | 否（1.131） |
| ALL-50 | COLD | 537.1/650.9 | 656.7/509.9 | 487.6/513.6 | 517.4/533.4 | 否（1.046） |
| ALL-50 | WARM | 578.7/528.8 | 548.8/477.0 | 446.5/378.3 | 456.6/422.2 | 是（0.957） |
| ALL-100 | COLD | 1177.6/1399.6 | 1261.6/893.1 | 1001.0/1067.0 | 1147.4/1075.5 | 否（1.285） |
| ALL-100 | WARM | 1021.5/1281.9 | 1192.4/919.6 | 879.5/775.9 | 887.3/926.6 | 否（1.008） |
| PART-10 | COLD | 93.2/100.5 | 99.7/63.1 | 72.6/70.4 | 74.9/67.8 | 否（1.188） |
| PART-10 | WARM | 85.4/103.3 | 87.4/66.1 | 66.8/63.2 | 103.3/71.6 | 否（1.561） |
| PART-50 | COLD | 448.9/540.5 | 494.9/323.4 | 367.8/308.7 | 338.8/371.6 | 否（1.149） |
| PART-50 | WARM | 365.1/526.4 | 428.9/337.3 | 299.8/293.5 | 296.5/316.4 | 是（0.938） |
| PART-100 | COLD | 729.8/949.6 | 1006.3/630.9 | 622.7/733.3 | 745.1/790.6 | 否（1.253） |
| PART-100 | WARM | 867.5/976.4 | 848.3/596.1 | 573.0/729.8 | 701.7/691.7 | 否（1.224） |
| NONE-10 | COLD | 57.7/60.1 | 61.2/40.3 | 38.3/48.4 | 53.1/60.4 | 否（1.498） |
| NONE-10 | WARM | 64.8/85.4 | 55.5/38.4 | 46.2/50.5 | 50.8/52.2 | 否（1.359） |
| NONE-50 | COLD | 311.1/264.9 | 315.9/244.6 | 194.7/221.0 | 258.1/255.3 | 否（1.055） |
| NONE-50 | WARM | 327.7/354.4 | 301.0/298.0 | 183.0/215.6 | 239.0/259.6 | 是（0.871） |
| NONE-100 | COLD | 607.7/557.9 | 599.2/613.6 | 474.6/423.4 | 469.4/478.9 | 是（0.858） |
| NONE-100 | WARM | 548.7/617.0 | 561.7/589.2 | 419.2/464.5 | 415.3/482.8 | 是（0.880） |
| ADMIN-10（未改路径） | COLD | 33.0/36.8 | 40.7/39.9 | 46.8/39.1 | 41.5/38.9 | 否（1.419） |
| ADMIN-10（未改路径） | WARM | 21.4/27.7 | 28.4/28.3 | 29.3/27.1 | 27.8/37.4 | 否（1.748） |
| FROZEN-10（未改路径） | COLD | 10.9/12.4 | 15.2/14.5 | 18.0/18.8 | 16.3/18.9 | 否（1.733） |
| FROZEN-10（未改路径） | WARM | 11.1/18.5 | 13.0/17.0 | 14.4/15.2 | 16.2/18.7 | 否（1.691） |
| LEAVER-10（未改路径） | COLD | 11.9/13.6 | 14.1/13.4 | 13.1/19.3 | 15.6/16.4 | 否（1.620） |
| LEAVER-10（未改路径） | WARM | 11.1/14.7 | 14.5/11.3 | 19.3/18.1 | 14.5/17.9 | 否（1.749） |
| PART-50-C8（8 线程） | WARM | 482.1/706.4 | 481.4/395.8 | 433.6/253.4 | 394.2/233.9 | 否（1.095） |
| NONE-50-C8（8 线程） | WARM | 291.4/320.6 | 305.6/255.2 | 234.1/164.0 | 237.4/152.9 | 是（0.930） |

### 11.3 P95 原始表

| 条件 | 缓存 | PRE-1 r1/r2 | PRE-2 r1/r2 | POST-1 r1/r2 | POST-2 r1/r2 | max(POST) vs min(PRE)（严格；<1 才分离） |
|---|---|---|---|---|---|---|
| ALL-10 | COLD | 146.2/144.6 | 213.9/98.7 | 146.1/114.4 | 186.2/125.6 | 否（1.887） |
| ALL-10 | WARM | 129.9/140.2 | 153.8/113.0 | 106.8/139.1 | 147.1/115.3 | 否（1.302） |
| ALL-50 | COLD | 685.4/898.5 | 815.5/812.1 | 588.8/614.4 | 681.3/635.4 | 是（0.994） |
| ALL-50 | WARM | 709.7/635.9 | 594.0/623.1 | 544.3/528.0 | 528.8/542.3 | 是（0.916） |
| ALL-100 | COLD | 1384.1/1639.1 | 1544.7/1035.4 | 1142.1/1157.0 | 1816.0/1161.4 | 否（1.754） |
| ALL-100 | WARM | 1220.7/1350.0 | 1390.7/1115.6 | 1024.9/901.9 | 955.5/1044.5 | 是（0.936） |
| PART-10 | COLD | 176.2/116.5 | 110.4/80.3 | 88.6/73.5 | 88.4/85.3 | 否（1.103） |
| PART-10 | WARM | 109.2/120.0 | 121.1/80.9 | 79.1/73.1 | 143.4/80.2 | 否（1.773） |
| PART-50 | COLD | 574.9/563.6 | 526.2/398.3 | 439.7/357.1 | 483.8/561.4 | 否（1.409） |
| PART-50 | WARM | 492.4/609.9 | 456.6/372.6 | 504.4/369.1 | 367.3/393.9 | 否（1.354） |
| PART-100 | COLD | 850.1/1231.2 | 1176.5/696.9 | 806.4/928.1 | 792.2/867.5 | 否（1.332） |
| PART-100 | WARM | 962.8/1201.4 | 944.1/681.2 | 807.4/784.6 | 759.4/874.2 | 否（1.283） |
| NONE-10 | COLD | 79.0/106.6 | 81.9/47.6 | 60.5/61.4 | 59.7/81.7 | 否（1.716） |
| NONE-10 | WARM | 89.8/117.8 | 68.6/61.0 | 55.1/68.1 | 62.3/64.6 | 否（1.115） |
| NONE-50 | COLD | 401.0/363.6 | 349.6/303.7 | 231.0/309.3 | 289.7/271.0 | 否（1.019） |
| NONE-50 | WARM | 346.7/445.9 | 364.2/404.8 | 227.7/274.6 | 274.2/298.9 | 是（0.862） |
| NONE-100 | COLD | 752.7/771.8 | 707.0/772.5 | 545.8/515.2 | 521.2/645.2 | 是（0.913） |
| NONE-100 | WARM | 601.4/682.5 | 665.0/687.1 | 482.6/519.2 | 542.6/535.1 | 是（0.902） |
| ADMIN-10（未改路径） | COLD | 41.9/70.3 | 45.1/73.0 | 59.7/65.2 | 63.1/44.2 | 否（1.557） |
| ADMIN-10（未改路径） | WARM | 27.8/32.9 | 33.4/45.0 | 33.7/31.9 | 30.5/54.2 | 否（1.946） |
| FROZEN-10（未改路径） | COLD | 20.8/30.1 | 38.4/15.7 | 19.8/21.5 | 18.0/20.2 | 否（1.367） |
| FROZEN-10（未改路径） | WARM | 12.9/23.8 | 14.9/44.2 | 16.0/16.8 | 20.1/20.8 | 否（1.610） |
| LEAVER-10（未改路径） | COLD | 24.1/19.2 | 16.0/25.6 | 20.6/23.3 | 17.5/17.8 | 否（1.457） |
| LEAVER-10（未改路径） | WARM | 13.5/22.5 | 17.2/12.6 | 25.8/19.4 | 18.8/22.0 | 否（2.046） |
| PART-50-C8（8 线程） | WARM | 649.5/1519.1 | 1203.5/463.4 | 966.4/299.5 | 580.3/350.0 | 否（2.085） |
| NONE-50-C8（8 线程） | WARM | 447.0/586.9 | 430.6/335.3 | 383.9/234.8 | 356.6/199.3 | 否（1.145） |

### 11.4 成功吞吐原始表（`throughput_req_s`）

| 条件 | 缓存 | PRE-1 r1/r2 | PRE-2 r1/r2 | POST-1 r1/r2 | POST-2 r1/r2 | min(POST) vs max(PRE)（严格；>1 才分离） |
|---|---|---|---|---|---|---|
| ALL-10 | COLD | 8.0/8.9 | 6.9/11.6 | 9.0/11.0 | 7.7/11.0 | 否（0.664） |
| ALL-10 | WARM | 9.7/10.3 | 9.2/11.2 | 10.3/11.3 | 9.9/10.9 | 否（0.884） |
| ALL-50 | COLD | 1.9/1.5 | 1.5/2.0 | 2.1/1.9 | 1.9/1.9 | 否（0.950） |
| ALL-50 | WARM | 1.7/1.9 | 1.8/2.1 | 2.2/2.6 | 2.2/2.4 | 是（1.048） |
| ALL-100 | COLD | 0.8/0.7 | 0.8/1.1 | 1.0/0.9 | 0.9/0.9 | 否（0.818） |
| ALL-100 | WARM | 1.0/0.8 | 0.8/1.1 | 1.1/1.3 | 1.1/1.1 | 否（1.000） |
| PART-10 | COLD | 10.7/9.9 | 10.0/15.9 | 13.8/14.2 | 13.3/14.7 | 否（0.836） |
| PART-10 | WARM | 11.7/9.7 | 11.4/15.1 | 15.0/15.8 | 9.7/14.0 | 否（0.642） |
| PART-50 | COLD | 2.2/1.9 | 2.0/3.1 | 2.7/3.2 | 3.0/2.7 | 否（0.871） |
| PART-50 | WARM | 2.7/1.9 | 2.3/3.0 | 3.3/3.4 | 3.4/3.2 | 是（1.067） |
| PART-100 | COLD | 1.4/1.1 | 1.0/1.6 | 1.6/1.4 | 1.3/1.3 | 否（0.813） |
| PART-100 | WARM | 1.2/1.0 | 1.2/1.7 | 1.7/1.4 | 1.4/1.4 | 否（0.824） |
| NONE-10 | COLD | 17.3/16.6 | 16.3/24.8 | 26.1/20.7 | 18.8/16.6 | 否（0.669） |
| NONE-10 | WARM | 15.4/11.7 | 18.0/26.0 | 21.7/19.8 | 19.7/19.1 | 否（0.735） |
| NONE-50 | COLD | 3.2/3.8 | 3.2/4.1 | 5.1/4.5 | 3.9/3.9 | 否（0.951） |
| NONE-50 | WARM | 3.1/2.8 | 3.3/3.4 | 5.5/4.6 | 4.2/3.9 | 是（1.147） |
| NONE-100 | COLD | 1.6/1.8 | 1.7/1.6 | 2.1/2.4 | 2.1/2.1 | 是（1.167） |
| NONE-100 | WARM | 1.8/1.6 | 1.8/1.7 | 2.4/2.2 | 2.4/2.1 | 是（1.167） |
| ADMIN-10（未改路径） | COLD | 30.3/27.2 | 24.6/25.0 | 21.4/25.6 | 24.1/25.7 | 否（0.706） |
| ADMIN-10（未改路径） | WARM | 46.7/36.1 | 35.2/35.4 | 34.1/36.8 | 36.0/26.7 | 否（0.572） |
| FROZEN-10（未改路径） | COLD | 91.7/80.6 | 65.6/68.9 | 55.7/53.1 | 61.3/52.9 | 否（0.577） |
| FROZEN-10（未改路径） | WARM | 90.5/54.1 | 76.7/59.0 | 69.4/65.6 | 61.6/53.5 | 否（0.591） |
| LEAVER-10（未改路径） | COLD | 83.9/73.3 | 71.0/74.7 | 76.5/51.8 | 64.1/61.1 | 否（0.617） |
| LEAVER-10（未改路径） | WARM | 90.4/68.0 | 69.1/88.2 | 51.7/55.4 | 69.0/55.8 | 否（0.572） |
| PART-50-C8（8 线程） | WARM | 2.1/1.4 | 2.1/2.5 | 2.3/3.9 | 2.5/4.3 | 否（0.920） |
| NONE-50-C8（8 线程） | WARM | 3.4/3.1 | 3.3/3.9 | 4.3/6.1 | 4.2/6.5 | 是（1.077） |

### 11.5 SQL 分类（第 2 轮 WARM，格式 `次数/耗时ms`）

| 条件 | 类别 | PRE-1 | PRE-2 | POST-1 | POST-2 |
|---|---|---|---|---|---|
| ALL-100 | auth/calls | 200.00/1245.51 | 200.00/925.22 | 200.00/779.71 | 200.00/886.04 |
| ALL-100 | auth/user | 400.00/537.20 | 400.00/405.93 | 200.00/218.90 | 200.00/249.58 |
| ALL-100 | auth/permission | 200.00/302.11 | 200.00/227.24 | 200.00/254.73 | 200.00/295.79 |
| ALL-100 | residual_ms | 62.21 | 34.76 | 26.53 | 21.97 |
| PART-100 | auth/calls | 150.00/965.14 | 150.00/600.68 | 150.00/705.19 | 150.00/680.86 |
| PART-100 | auth/user | 350.00/486.92 | 350.00/304.25 | 200.00/257.63 | 200.00/251.12 |
| PART-100 | auth/permission | 150.00/235.37 | 150.00/145.17 | 150.00/227.96 | 150.00/222.13 |
| PART-100 | residual_ms | 35.02 | 22.59 | 19.38 | 18.23 |
| NONE-100 | auth/calls | 100.00/619.64 | 100.00/593.51 | 100.00/464.34 | 100.00/483.16 |
| NONE-100 | auth/user | 250.00/313.11 | 250.00/304.91 | 150.00/179.58 | 150.00/189.71 |
| NONE-100 | auth/permission | 100.00/142.25 | 100.00/140.19 | 100.00/143.78 | 100.00/150.75 |
| NONE-100 | residual_ms | 34.40 | 22.39 | 13.82 | 11.71 |
| PART-50-C8（8 线程） | auth/calls | 75.00/820.31 | 75.00/391.36 | 75.00/247.73 | 75.00/240.13 |
| PART-50-C8（8 线程） | auth/user | 175.00/278.60 | 175.00/167.15 | 100.00/87.92 | 100.00/81.88 |
| PART-50-C8（8 线程） | auth/permission | 75.00/120.40 | 75.00/76.61 | 75.00/75.44 | 75.00/71.52 |
| PART-50-C8（8 线程） | residual_ms | 308.79 | 78.82 | 19.91 | 25.61 |
| NONE-50-C8（8 线程） | auth/calls | 50.00/331.52 | 50.00/258.23 | 50.00/172.88 | 50.00/154.00 |
| NONE-50-C8（8 线程） | auth/user | 125.00/137.35 | 125.00/118.23 | 75.00/64.73 | 75.00/56.75 |
| NONE-50-C8（8 线程） | auth/permission | 50.00/58.88 | 50.00/51.48 | 50.00/50.86 | 50.00/45.65 |
| NONE-50-C8（8 线程） | residual_ms | 82.38 | 41.59 | 13.21 | 12.65 |
| ADMIN-10 | auth/calls | 20.00/23.15 | 20.00/26.89 | 20.00/22.77 | 20.00/32.53 |
| ADMIN-10 | auth/user | 20.00/22.75 | 20.00/26.43 | 20.00/22.24 | 20.00/31.93 |
| ADMIN-10 | auth/permission | n/a | n/a | n/a | n/a |
| ADMIN-10 | residual_ms | 1.35 | 1.66 | 1.87 | 2.15 |
| FROZEN-10 | auth/calls | 10.00/15.25 | 10.00/19.80 | 10.00/11.08 | 10.00/13.99 |
| FROZEN-10 | auth/user | 10.00/14.99 | 10.00/19.54 | 10.00/10.81 | 10.00/13.66 |
| FROZEN-10 | auth/permission | n/a | n/a | n/a | n/a |
| FROZEN-10 | residual_ms | 1.19 | 1.23 | 1.61 | 1.90 |

**程序化核对**（被改路径 11 单元 + 未改路径 2 单元）：`auth/calls` 与 `auth/permission` 的次数在四次 run 逐条不变；被改路径条件的 `auth/user` 满足 `PRE(auth/user) − POST(auth/user) == auth/calls`；未改路径条件（ADMIN/FROZEN）四次 run 均 `auth/user == auth/calls` 且无 `auth/permission`。 本 turn 实测结论：**全部成立**。

### 11.6 稳健性检验与复测裁决

#### 11.6.1 逐格方向（被改路径 11 单元 × 4 格 = 44 格）

| 指标 | 下降 | 上升 | 持平 | 合计 | 判定方向 |
|---|---|---|---|---|---|
| P50 | 43 | 1 | 0 | 44 | 下降为好 |
| P95 | 40 | 4 | 0 | 44 | 下降为好 |
| 吞吐 | 1 | 43 | 0 | 44 | 上升为好 |

> 本表是**逐格同轮方向**（每格 POST 与基准 PRE 比大小，基准取 PRE-1），**不等于**区间分离：方向计数一致并不排除两组区间大幅重叠。两者必须分开陈述，见第 11.6.3 节。

#### 11.6.2 噪声地板（PRE-1 vs PRE-2，26 单元 × 2 同轮格 = 52 格）

| 指标 | 下降 | 上升 | 持平 |
|---|---|---|---|
| P50 | 28 | 24 | 0 |
| P95 | 30 | 22 | 0 |
| 吞吐 | 21 | 26 | 5 |

#### 11.6.3 严格区间分离、安慰剂对照与阴性对照

严格判据见第 11.1 节（下降型 `max(POST) < min(PRE)`；吞吐 `min(POST) > max(PRE)`）。每单元以前测两次 run × 两轮共 4 个样本构成前测区间、后测两次 run × 两轮共 4 个样本构成后测区间。

| 指标 | 被改路径 20 单元 | 全 26 单元 | 阴性对照（ADMIN/FROZEN/LEAVER 6 单元） | 安慰剂：同一未改动 S0 树内 PRE-1↔PRE-2（被改路径 20 单元） |
|---|---|---|---|---|
| P50 | 6/20 | 6/26 | 0/6 | 4/20（反向 0/20） |
| P95 | 6/20 | 6/26 | 0/6 | **8/20**（反向 0/20） |
| 吞吐 | 6/20 | 6/26 | 0/6 | 2/20（反向 0/20） |

**读数**：被改路径的严格分离三项均为 6/20；而**安慰剂对照（同一份未改动代码，把 PRE-2 当后测）在 P95 上得到 8/20，高于真实改动的 6/20**。即真实改动组与对照组的分离计数同量级、甚至更低 —— 现有前后测设计无法把端到端变化与时间/环境漂移区分开。

**三项同时严格分离的单元**：仅 4 个（ALL-50 WARM、NONE-50 WARM、NONE-100 COLD、NONE-100 WARM）。

**聚合 P50 均值（被改路径 11 单元 × 两轮 = 40 个值）**：PRE-1 `476.5`、PRE-2 `425.7` → POST-1 `350.1`、POST-2 `376.1` ms。两次后测均值均低于两次前测；但**两次前测之间（同一份未改动代码）均值已自降 10.7%**，方向与后测下降相同，说明该「下降」至少有一部分来自 run 间漂移而非改动。

#### 11.6.4 裁决

**裁决：有条件 / 未定（本机观测到改善，但归因强度不足）。** 不再维持原「GO（S2 口径）」：该 GO 依赖的「20 个被改路径单元上 P50/P95 区间分离全部成立」系第 11.1 节旧定义（`min POST < max PRE`），按严格不重叠判据更正后只剩 6/20，且安慰剂对照（同代码 PRE-1↔PRE-2）在 P95 上得到 8/20 —— **对照组与真实改动组的分离计数同量级甚至更高**，现有数据无法把端到端改善与时间/环境漂移区分开。

**为什么不能维持 GO（诚实说明）**：

1. 严格区间分离被改路径 20 单元仅 **P50 6/20、P95 6/20、吞吐 6/20**，三项同时分离的单元只有 4 个；按原案「主验收是列表请求端到端」的口径，这不足以宣称稳定改善。
2. **安慰剂对照**（同一未改动 S0 树内 PRE-1↔PRE-2 做严格分离）P50 4/20、**P95 8/20**、吞吐 2/20；反向 PRE-2↔PRE-1 为 0/20、0/20、0/20。漂移方向性不对称，量级与真实改动相当。
3. **前测自身漂移**：两次前测（同代码）聚合 P50 均值自降 10.7%，与后测下降同向。
4. **阴性对照**：严格判据下未改路径 6 单元为 0/6（第 11.6.3 节旧弱判据曾记 P50 5/6、P95 6/6，属假阳性），说明弱判据不能区分「改」与「未改」，反过来也否定了旧口径。
5. **SQL 证据仍在**：`auth/user` 次数由 `2×auth/calls` 降为 `1×auth/calls`（第 11.5 节），是确定性的形状改变；但按原案明文「不能以『少查了 N 次』追认加速」，SQL 降次**不能单独替代端到端验收**。

**补证办法（下一轮可执行，本轮未做）**：

- **同机交错顺序 A/B**：把 S0 与 S2 两棵树按 `PRE, POST, PRE, POST, …` 交替跑同一 opt-in 基准，用顺序交错抵消单向时间漂移；每臂至少 5 个独立 run，报每臂均值/中位数与区间（如 bootstrap 95% CI），而不是只比极值。
- **先标定漂移斜率**：用**同一份未改动代码**先跑一组 placebo（≥4 run），量化 run 序号与 P50 的关系，再据此校正真实组。
- **降低单 run 噪声**：固定 CPU 亲和、关闭后台负载、采集容器侧资源（当前 `container_resources=unknown`），上调每条件采样次数，并让吞吐用原始耗时比而非 1 位小数四舍五入。
- 只有对照与统计**能把漂移扣除**、且校正后仍稳定分离，才可恢复 GO；否则维持「未定」。**本轮不宣称任何生产加速。**

**本轮只读预检**：本机 Docker 在线（`docker info` 实测 ServerVersion `29.6.2`），钉扎镜像 `mysql:8.0`（`7dcddc01f13b`）本地存在，具备补测条件；但本轮未改代码、也未交替跑新 run，故上述补证**未执行**。本节所有数字均来自既有四个 S2 日志与四个原始日志的**只读复算**；第 11.5 节 SQL 形状结论来自既有日志的 `Interceptor` 计数，非新测量。

> **后续补证（2026-09-27）**：交错 A/B 同机复测已完成，独立报告 `docs/project-file-list-interleaved-ab-evaluation.md`（提案 `add-project-file-list-interleaved-ab-attribution`）：六对交错 P50 配对差全部为负、安慰剂漂移远小于效应、阴性对照无共同改善，本机裁决升为**有条件 GO**。本节原始表不因新结论改写。

#### 11.6.5 §3.2 P95 汇总的独立重算

用原始四次 run 日志（`%TEMP%\pflhot-pre2.log`、`pflhot-pre-final.log`、`pflhot-post.log`、`pflhot-post2.log`）按第 3 节同一口径（基准 = PRE-final，`d1 = PRE-final → POST-1`、`d2 = PRE-final → POST-2`，仅取 WARM 稳态被改路径条件）逐格重算：

| 指标 | 下降 | 上升 | 持平 | 合计 |
|---|---|---|---|---|
| P50 | 44 | 0 | 0 | 44 |
| P95 | 41 | 3 | 0 | 44 |
| 吞吐 | 0 | 43 | 1 | 44 |

重算与第 3.2 节表格一致：P95 为 **41 格下降 / 3 格上升 / 0 格持平**（不是 39/5）；P50 **44/44 下降**；吞吐 **43 升 / 1 持平 / 0 降**。

**严格区间分离**（第 11.1 节判据；基准合并 PRE-2 + PRE-final）：

| 口径 | P50 | P95 | 吞吐 |
|---|---|---|---|
| 被改路径 20 单元 | 18/20（0.723–1.033） | 12/20（0.763–1.578） | 18/20（0.965–1.385） |
| 被改路径 WARM 11 单元 | 9/11（0.723–1.033） | 8/11（0.763–1.167） | 9/11（0.965–1.385） |

同一未改动前测树内的**安慰剂对照**（PRE-2 ↔ PRE-final 互为前后）：WARM 11 单元 P50 2/11 与 0/11、P95 3/11 与 2/11、吞吐 2/11 与 0/11；20 单元 P50 3/20 与 2/20、P95 8/20 与 3/20、吞吐 3/20 与 1/20。

**结论**：原始 ce85e09 口径的严格分离（WARM 11 单元 9/11、8/11、9/11）高于其安慰剂（0–3/11），比 S2 复测（6/20）强；但 ce85e09 已因安全缺陷回退，**不能据此恢复 GO**，总裁决仍以第 11.6.4 节的「有条件 / 未定」为准。本重算只取 WARM 稳态被改路径条件，**未混入 ADMIN/FROZEN/LEAVER 未改路径条件**。

> 汇总日期：2026-09-27。本节所有数字均由本 turn 从上述日志文件逐行解析生成，无手工誊抄。
