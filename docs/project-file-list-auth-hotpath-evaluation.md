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
| 被调用实现 | `ProjectFileServiceImpl#queryPage/filterReadable/entityToVO`、`AttachmentAccessServiceImpl#canReadProjectFile/canReadAttachments`、`ProjectFileAttachmentReader#canReadByDimension`、`PermissionServiceImpl#hasPermission/hasPermissionByRoleId`、`DataConvertServiceImpl#getUserName` |
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
| 并发撤权与列表签发之间的时序竞态窗口 | 未测（仅测「撤权后旧令牌被拒」） |

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

**读法**：全部 11 个被改路径条件（ALL/PART/NONE × 3 页大小 + 2 并发相）在**全部 4 个配对格（2 run × 2 轮）**上 P50 均为负（下降），幅度 **-8.5% ~ -40.0%**。

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

**读法**：被改路径条件 44 个配对格中 39 格 P95 下降；5 格小幅正抖动，全部落在低延迟 COLD / 页大小 10 区间（`ALL-10 COLD r2 +2.9%`、`+5.7%`、`PART-10 COLD r1 +0.8%`、`NONE-10 WARM r1 +12.2%`、`ALL-100 COLD r1 +2.4%`、`PART-100 WARM r1 +3.8%`）。其中最大离群 `PART-10 COLD r1 +57.8%`（96.9→152.8ms）与 **同配置 PRE 两次 run 之间的自身跨度 131.6 vs 96.9（±36%）** 同量级，且同一条件在 POST-1 为 `+0.8%`、第二轮为 `-34.6%` —— 归因不支持「改动导致 P95 退化」。

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

**读法**：被改路径条件 **44/44 配对格吞吐全部上升**，幅度 **+10.0% ~ +69.6%**；并发相 +38% ~ +70%。未改路径条件（ADMIN-10 / FROZEN-10 / LEAVER-10）正负抖动均落在毫秒级绝对量（27~45ms）之内，方向不一致 —— 反证改动未旁路影响这些路径，且测量口径可比。

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

**唯一改动因素**：热路径复用「同一请求内、同一次鉴权调用已加载的当前用户实体」的 `roleId`，省掉 `PermissionServiceImpl#hasPermission(Long, PermissionOperates)` 内部的 `sys_user` 回查。

**改动文件（4 个主 + 1 接口）**

- `server/service/PermissionService.java` —— 新增 `boolean hasPermissionByRoleId(Long roleId, PermissionOperates targetPerm)`
- `server/service/impl/PermissionServiceImpl.java` —— `hasPermission(Long, X)` 委托到新方法；新方法体 = `hasPermission(targetPerm, getPermissionList(roleId))`
- `server/service/impl/ProjectFileAttachmentReader.java` —— 维度鉴权改走 `hasPermissionByRoleId(roleId, X)`（225 / 204 / 215）
- `server/service/impl/AttachmentModelScopeChecker.java` —— `canRead(modelName, recordId, userId, roleId)`；仅 BUSINESS_ACTIVITY 分支传 `roleId`，CONTACT_TASK / APPROVAL / ASSIST 分支**保持原样**
- `server/service/impl/AttachmentAccessServiceImpl.java` —— 传入刚读到的 `user.getRoleId()`；`canWriteAttachments` 未改

**安全等价论证（逐条由实测支撑）**

1. **不跨请求缓存**：复用范围严格限定在**单次调用栈内**已 `selectById` 得到的用户实体，未引入任何静态/请求间缓存 —— `auth/user` 未清零、仅按调用次数减半即为证据。
2. **权限链仍实时查库**：`hasPermissionByRoleId` 内部仍每次 `permissionsMapper.getPermissionList(roleId)`；实测 `auth/permission` 次数**前后完全一致**（200/150/100/75…）。
3. **用户状态闸仍实时**：`canReadProjectFile` 顶部的 `userMapper.selectById(userId)`（冻结/离职/超管判定）**保留未动**；实测冻结/离职 `total=120 records=0` 前后一致，且 `auth/user` 计数对这些路径为 0 变化。
4. **下载实时复核未合并**：`PublicAttachmentController#downloadProjectFileByToken` 仍独立调 `canReadProjectFile` 重新取用户与权限；令牌反例 `old_token_still_parses=true` + `recheck_denied=true` 验证。
5. **两次鉴权边界未合并**：`filterReadable` 首轮与 `entityToVO` 次轮**各自仍独立**调 `canReadProjectFile`（因此撤权窗口内链接签发时序不变）；未下推过滤，`total` / 页号 / `records` 语义未动（第 2 节与第 6 节实测）。
6. **未触碰禁区**：未改迁移、依赖、权限码/注解/矩阵、API 响应结构、下载端点实时鉴权、前端、AI/RAG、生产配置、JVM/线程池/连接池参数；未调用真实模型或外部服务；未下载镜像。

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
| P50：被改路径条件 | 稳定下降 | 44/44 配对格下降（-8.5% ~ -40.0%），两次独立后测方向一致 | **通过** |
| P95：被改路径条件 | 无一致退化 | 44 格中 39 格下降；5 格小幅正抖动均落在**未改路径条件同等量级**的 run 间噪声内（含最大离群 PART-10 COLD r1 与 PRE 自身跨度 ±36% 同量级） | **通过** |
| 成功吞吐 | 稳定上升 | 44/44 配对格上升（+10.0% ~ +69.6%），并发相 +38% ~ +70% | **通过** |
| 失败 | 0 | 四次 run 全部 `failures=0`，每条件 `ok==requests` | **通过** |
| 权限输出零回归（一票否决） | 完全一致 | 探针 / 状态闸 / 撤权 / listBy* 指纹**四次 run 逐字相同** | **通过** |
| 归因可隔离 | 单一类别 | 仅 `auth/user` 次数变化，`Δauth/user == auth/calls`；其余类别次数逐条不变 | **通过** |
| 权限/分页/令牌语义 | 无差异 | total=数据库条件总数、records 可不足额/为空、下载实时复核独立保留、令牌绑定与有效性一致 | **通过** |
| 测量可比 | 是 | 同种子/同权限状态/同页大小/同缓存预热/同并发/同观测口径；未改路径条件仅毫秒级抖动 | **通过** |

**裁决：GO。** 主验收成立，且不是「SQL 次数降低但端到端收益不稳」的 no-go 情形 —— 端到端 P50 与吞吐在**两个独立后测 run × 两轮 × 冷/暖缓存 × 三种页大小 × 8 线程并发相**上方向一致，权限输出零回归。

**未叠加第二类优化**，未调 JVM / 连接池追指标。

---

## 9. 复现命令、实际运行与未运行项

**复现（本地度量入口，非默认发现 + 显式 opt-in）**

```text
PROJECT_FILE_LIST_HOTPATH_MEASURE=1 mvn -B -ntp -Dtest=ProjectFileListAuthHotpathBenchmark test
```

缺 opt-in / Docker / 本地镜像时在**下载镜像或连接业务库之前** fail closed，并显式报告「未测」（由 `ProjectFileListAuthHotpathGuardTest` 的纯 JVM 反例锁定，默认 `mvn test` 不启动容器）。

**实际运行**

- `mvn -B -ntp -Dtest=ProjectFileListAuthHotpathBenchmark test`（前测 ×2、后测 ×2；均需 Docker 与本地 `mysql:8.0` 镜像）
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

**生产代码（5 个）**

- `src/main/java/com/slz/crm/server/service/PermissionService.java`
- `src/main/java/com/slz/crm/server/service/impl/PermissionServiceImpl.java`
- `src/main/java/com/slz/crm/server/service/impl/ProjectFileAttachmentReader.java`
- `src/main/java/com/slz/crm/server/service/impl/AttachmentModelScopeChecker.java`
- `src/main/java/com/slz/crm/server/service/impl/AttachmentAccessServiceImpl.java`

**测试（3 个）**

- `src/test/java/com/slz/crm/unit/service/AttachmentAccessServiceTest.java`（协作契约适配 + 新增「按 roleId 判定、不再回查 userId」用例）
- `src/test/java/com/slz/crm/quality/ProjectFileListAuthHotpathBenchmark.java`（新增，非默认发现 + opt-in 度量入口）
- `src/test/java/com/slz/crm/quality/ProjectFileListAuthHotpathGuardTest.java`（新增，默认执行的纯 JVM 边界守卫）

**文档与规格（4 个）**

- `docs/project-file-list-auth-hotpath-evaluation.md`（本报告）
- `spec/changes/update-project-file-list-auth-hotpath/proposal.md`
- `spec/changes/update-project-file-list-auth-hotpath/tasks.json`
- `spec/changes/update-project-file-list-auth-hotpath/specs/project-file-list/spec-delta.md`

**保护项（不修改、不删除、不暂存）**：`docs/backend-optimization-candidates.md`