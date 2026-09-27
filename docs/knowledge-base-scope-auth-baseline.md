# 知识库 scope 授权路径基线度量（add-knowledge-base-scope-auth-baseline）

- 日期：2026-09-27
- 工作树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（权威 master）
- 起点 HEAD：`838d5e99ca9b39c7107ca9f29749d6ad3aa7b83c`
- 度量对象：生产 [KnowledgeBaseAuthorizationService](../../src/main/java/com/slz/crm/knowledge/auth/KnowledgeBaseAuthorizationService.java)
  （`visibleKnowledgeBaseIds` / `authorizedKnowledgeBaseIds`）+ 生产 MyBatis-Plus mapper + 生产 Flyway 迁移链
- 入口：[KnowledgeBaseScopeAuthBaselineBenchmark](../../src/test/java/com/slz/crm/quality/KnowledgeBaseScopeAuthBaselineBenchmark.java)（默认不运行，显式 opt-in）
- 守卫：[KnowledgeBaseScopeAuthBaselineGuardTest](../../src/test/java/com/slz/crm/quality/KnowledgeBaseScopeAuthBaselineGuardTest.java)（默认 surefire 运行，纯 JVM）

## 0. 结论（裁决）

**性能裁决：证据不足未定（不推荐进入优化提案）。** 本度量只覆盖「授权段本身」——一次 `authorizedKnowledgeBaseIds`
调用内的 3 条授权查询 + 内存过滤；**不是请求端到端延迟，也不代表生产收益**。规模从 4 → 512 时授权段 P50 未出现单调上升
（4/128/512 分别为 8.360 / 5.308 / 7.427 ms，run2 round=2，20 样本），但单库查询的 EXPLAIN 已是 `type=ALL`（全表扫描），
且取回行数随规模线性上升（每次调用 129/129/131 行 @512）。是否值得优化需要独立提案在「真实基数 + 真实下游调用方式」下重新度量，
本案不给 GO。

**安全裁决：停止性能 GO，单列安全观察（见 §9）。** 发现授权面不一致：`visibleKnowledgeBaseIds` 的成员分支
**不 join `knowledge_base`、不校验库是否存在/未软删**，因此「库已软删但成员行存活」与「成员行指向不存在的库」的 ID 会进入
非超管可见集；而同一服务的 `canRead` 对这两种库都返回 false。按本案规格，只把现行语义记为快照并单列，**不修语义**。

## 1. 固定度量口径（实施前登记，未事后调整）

| 维度 | 取值 |
| --- | --- |
| 规模（知识库数） | 4 / 128 / 512（少/中/多） |
| 每规模基址 | 100000 / 2000000 / 30000000（避免跨规模撞号） |
| 可见来源分布 | owner / PUBLIC / member / other 各占 1/4 |
| 主体 | 非超管 subject user=50 role=7 dept=1；超管 admin user=1 role=1 |
| scope 矩阵 | single / multi / empty / duplicate / nonnumeric / overflow / mixed |
| 样本 | 预热 3 + 每格 20 次，独立两轮（round=1/2） |
| 独立执行次数 | 2（run1、run2，各自独立起容器/迁移/种数） |
| 每规模顺序 | 重置重置并种数 → 快照 → round1 → round2 → 并发相 → EXPLAIN |
| 裁决口径 | 授权段（不是请求端到端）；缺失指标写 unknown；不改语义、不加索引 |

固定反例（每规模都种，ID 见快照）：重叠库 OVERLAP（owner+PUBLIC+member 三重命中）、软删自有库 SOFTDEL_OWNED、
软删 PUBLIC 库 SOFTDEL_PUBLIC、成员行已软删的库 MEMBER_SOFTDEL_KB、库已软删但成员行存活的库 MEMBER_OF_SOFTDELETED、
孤儿成员引用 ORPHAN=900000000（成员行指向永不存在的库）。scope 侧固定：`duplicate`=[同 ID ×2]、
`nonnumeric`=["abc"]、`overflow`=["99999999999999999999999999"]、`mixed`=合法+非法混排。

## 2. 环境与证据级别

```
KBSCOPE env: evidence_class=local-real-mysql+production-flyway+production-auth-service docker=available
             mysql_image=mysql:8.0 java=21.0.9 os=Windows 11 arch=amd64
KBSCOPE identities: subject=50 role=7 admin=1 role=1 dept=1 credentials=none (local-only, no real key read)
KBSCOPE resource: container_resources=unknown (not collected)
KBSCOPE wiring: annotation_handler=com.tangzc.mpe.magic.MyAnnotationHandler
                knowledge_base_table=knowledge_base member_table=knowledge_base_member
```

- 证据级别：**本机一次性真 MySQL + 生产 Flyway 迁移 + 生产 mapper + 生产授权服务**。库结构全部由生产迁移链建出；
  授权判定完全走生产 `KnowledgeBaseAuthorizationService`。数据全为确定性假数据。
- Docker：29.6.2 实测在线。钉扎镜像 `mysql:8.0`，本机 digest `sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b`
  （另本机存在 `mysql:8.0.36`）。**只做 inspect 只读探测，不 pull、不下载、不外发。**
- 凭据：容器内一次性帐号 `crm/crm_kb_pwd`，**未读取、未打印任何真实密钥**；不连业务库；不调用真实模型。
- 缺失指标按规格写 `unknown`（容器 CPU/内存未采集），**不填 0**。

## 3. 命令与原始输出

至少两条独立真库度量命令，逐字保留完整 stdout：

```bash
# run1
KB_SCOPE_AUTH_MEASURE=1 mvn -o -B -ntp -Dtest=KnowledgeBaseScopeAuthBaselineBenchmark test
# run2（重新起容器、重新迁移、重新种数）
KB_SCOPE_AUTH_MEASURE=1 mvn -o -B -ntp -Dtest=KnowledgeBaseScopeAuthBaselineBenchmark test
```

结果（两轮一致）：

```
run1: Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 54.87 s -- in ...KnowledgeBaseScopeAuthBaselineBenchmark
      BUILD SUCCESS    KBSCOPE done: threadlocal_clean=true failures=0
run2: Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 54.80 s -- in ...KnowledgeBaseScopeAuthBaselineBenchmark
      BUILD SUCCESS    KBSCOPE done: threadlocal_clean=true failures=0
```

原始日志（完整、未删改，stdout 全量）：

- `%TEMP%\kbscope-auth-baseline\run1.log`（72143 字节，2026-09-27 22:44:54）
- `%TEMP%\kbscope-auth-baseline\run2.log`（72146 字节，2026-09-27 22:46:09）
- 即 `C:\Users\fzdzzj\AppData\Local\Temp\kbscope-auth-baseline\run{1,2}.log`；正文检索关键字 `KBSCOPE`。

## 4. 规模与可见集快照（返回 ID 与顺序）

`visibleKnowledgeBaseIds` 非超管按 owner → PUBLIC → member 三段查询并以 `LinkedHashSet` 顺序去重。

| 规模 | 物理行 | 正常分布 owner/public/member/other | 非超管可见数 | 超管可见数 |
| --- | --- | --- | --- | --- |
| 4 | 4+5 反例 = 9 | 1/1/1/1 | 6 = 3*(4/4)+3 | 6 |
| 128 | 128+5 = 133 | 32/32/32/32 | 99 = 3*(128/4)+3 | 130 |
| 512 | 512+5 = 517 | 128/128/128/128 | 387 = 3*(512/4)+3 | 514 |

scale=4 非超管逐 ID（保持生产返回顺序）：

```
subject_visible_ids=[100000, 100005, 100001, 100002, 100009, 900000000]
  100000=自有   100005=OVERLAP(owner+PUBLIC+member 三重)   100001=PUBLIC
  100002=member 100009=MEMBER_OF_SOFTDELETED(库已软删)    900000000=ORPHAN(库不存在)
subject_first_owned=100000 overlap_id=100005
orphan_included=true  member_of_softdeleted_included=true  softdel_excluded=true
```

- 重叠库 100005 只出现一次（`LinkedHashSet` 去重生效，位于公有段首次命中位置）。
- 软删被排除：SOFTDEL_OWNED、SOFTDEL_PUBLIC、MEMBER_OF_SOFTDELETED 的库行均不在集合。
- 成员行已软删的库（MEMBER_SOFTDEL_KB）被排除（成员查询带 `is_deleted=0`）。
- **未被排除**：ORPHAN（库根本不存在）与 MEMBER_OF_SOFTDELETED（库已软删）——见 §9。

超管（scale=4 empty scope）返回 `[100000, 100001, 100002, 100003, 100005, 100008]`，即 9 行里所有未软删的 6 行，
顺序为物理顺序；软删 100006/100007/100009 被 `@TableLogic` 排除。

## 5. scope 矩阵逐格（最强反例，run2 round=2）

| 规模 | 用户 | scope | 解析结果 | 返回数 |
| --- | --- | --- | --- | --- |
| 4 | subject | single=[100000] | [100000] | 1 |
| 4 | subject | multi=[100000,100005,100002] | 同左 | 3 |
| 4 | subject | empty | [] | 6（=全部可见） |
| 4 | subject | duplicate=[100000,100000] | [100000] | 1（去重） |
| 4 | subject | nonnumeric=["abc"] | [] | 0（忽略非法） |
| 4 | subject | overflow=["99999999999999999999999999"] | [] | 0（忽略溢出） |
| 4 | subject | mixed=[合法,非法,非法] | [合法] | 1 |
| 4 | admin | empty | [] | 6 |
| 4 | admin | nonnumeric/overflow | [] | 0 |
| 128 | subject | duplicate / nonnumeric / overflow | [2000000] / [] / [] | 1 / 0 / 0 |
| 512 | subject | duplicate / nonnumeric / overflow | [30000000] / [] / [] | 1 / 0 / 0 |

结论（与冻结语义一致）：`empty`/`null` scope 表示"可见全部"（**不是**"无权限"，也不是 0 条）；
`duplicate` 不重复；`nonnumeric`/`overflow` 经 `Long.valueOf` 抛 `NumberFormatException` 后**被忽略**，
既不放大也不报错，`mixed` 按合法子集求交。三规模行为一致，无规模相关语义漂移。

## 6. 三类授权 SQL 的调用与取回行数（样本 20，run2 round=2）

每格 20 次调用；`rows_*` 为该格 20 次累计取回行数，括号内为单次均值。

| 规模 | 用户 | owner calls/rows | PUBLIC calls/rows | member calls/rows | adminAll calls/rows |
| --- | --- | --- | --- | --- | --- |
| 4 | subject | 20 / 40 (2) | 20 / 40 (2) | 20 / 80 (4) | 0 / 0 |
| 128 | subject | 20 / 660 (33) | 20 / 660 (33) | 20 / 700 (35) | 0 / 0 |
| 512 | subject | 20 / 2580 (129) | 20 / 2580 (129) | 20 / 2620 (131) | 0 / 0 |
| 4 | admin | 0 / 0 | 0 / 0 | 0 / 0 | 20 / 120 (6) |
| 128 | admin | 0 / 0 | 0 / 0 | 0 / 0 | 20 / 2600 (130) |
| 512 | admin | 0 / 0 | 0 / 0 | 0 / 0 | 20 / 10280 (514) |

- 非超管**固定 3 条查询**（owner/PUBLIC/member 各 1），与规模无关；超管**固定 1 条**（adminAll）。
- 取回行数随规模**线性上升**：单次 owner 2→33→129、PUBLIC 2→33→129、member 4→35→131、adminAll 6→130→514。
- 授权段成本由「固定条数查询 × 线性取回行数」构成；512 档单次共取回约 389 行（非超管）/514 行（超管）后做内存过滤。

## 7. EXPLAIN（`ANALYZE TABLE` 刷新统计后；run2）

```
scale=4   owner    : type=ALL,key=null,rows=9,  filtered=11.11
scale=4   public   : type=ALL,key=null,rows=9,  filtered=11.11
scale=4   member   : type=ref,key=idx_knowledge_base_member_user,rows=5,  filtered=20.0
scale=4   adminAll : type=ALL,key=null,rows=9,  filtered=11.11
scale=128 owner/public/adminAll: type=ALL,key=null,rows=133,filtered=1.0 / member: type=ref,key=idx_knowledge_base_member_user,rows=36,filtered=10.0
scale=512 owner/public/adminAll: type=ALL,key=null,rows=517,filtered=1.0 / member: type=ref,key=idx_knowledge_base_member_user,rows=132,filtered=10.0
```

- `knowledge_base` 的 owner / PUBLIC / adminAll 三条**均为全表扫描**（`type=ALL`，`key=null`），扫描行数等于表行数（9/133/517）。
  这与迁移链现状一致：`knowledge_base` 上只有 `name` 唯一键，没有 `owner_user_id`、`visibility`、`is_deleted` 上的索引。
- `knowledge_base_member` 的 user_id 查询走 `idx_knowledge_base_member_user`（`type=ref`），但 `rows` 随该用户成员行数增长（5/36/132）。
- EXPLAIN 为独立 `ANALYZE TABLE` 后的一次性快照，不代表运行期优化器每次都会选同一计划。

## 8. 授权段延迟、吞吐与连接等待（20 样本/格，两轮）

run2 round=2（主口径，单位 ms）：

| 规模 | 用户 | scope(single) P50/P95/P99 | throughput | conn_avg | scope(empty) P50/P95/P99 |
| --- | --- | --- | --- | --- | --- |
| 4 | subject | 8.360 / 10.121 / 10.190 | 119.6 rps | 0.0103 | 7.221 / 10.078 / 10.426 |
| 128 | subject | 5.308 / 6.540 / 7.562 | 188.4 rps | 0.0075 | 4.892 / 6.052 / 6.315 |
| 512 | subject | 7.427 / 8.228 / 9.032 | 134.6 rps | 0.0076 | 6.843 / 10.022 / 13.891 |
| 4 | admin | 2.307 / 2.905 / 3.031 | 433.4 rps | 0.0076 | 2.434 / 3.330 / 3.338 |
| 128 | admin | 2.109 / 3.024 / 3.302 | 474.1 rps | 0.0053 | 3.044 / 3.562 / 3.795 |
| 512 | admin | 2.423 / 3.793 / 4.295 | 412.8 rps | 0.0061 | 2.046 / 3.286 / 3.453 |

run1 round=2（复核，同口径）：subject single P50/P95 = 5.860/7.331（4）、5.532/7.082（128）、6.628/8.213（512）；
admin single P50/P95 = 2.165/2.630（4）、1.568/2.500（128）、2.245/3.611（512）。

- 两轮量级一致，**未观察到随规模单调上升**；同格轮间抖动（如 subject@4 的 5.86 与 8.36）大于规模间差异，
  说明该量纲下**噪声主导**，不足以支撑"规模导致授权段劣化"的结论。
- 非超管 latency 高于超管约 2–3 倍，与"3 条查询 vs 1 条查询"相符；`conn_avg` 均在 0.003–0.017 ms，连接获取不是瓶颈。
- 逐格完整 P50/P95/P99、吞吐、失败数见原始日志 `KBSCOPE cell:` 行（每规模每格 1 行，共 3×2×7×2 = 84 行）。

并发相（8 线程 × 20 次 = 160 请求，run2）：

| 规模 | 请求/成功/失败 | P50 | P95 | 吞吐 | conn_wait_avg |
| --- | --- | --- | --- | --- | --- |
| 4 | 160 / 160 / 0 | 112.715 | 223.681 | 67.4 rps | 8.6512 |
| 128 | 160 / 160 / 0 | 93.990 | 139.514 | 83.9 rps | 6.2446 |
| 512 | 160 / 160 / 0 | 83.562 | 124.148 | 114.8 rps | 4.3016 |

run1 复核：P50 = 112.761 / 86.496 / 65.669，吞吐 = 67.8 / 89.4 / 138.9 rps，均 0 失败。

- 并发相 P50 远高于单线程串行相（数百 ms vs 数 ms），且**随规模下降**，说明瓶颈在 8 线程共享连接池的排队，
  而非授权查询本身；`conn_wait_avg` 8.65 → 4.30 ms 与之相符。该相**不作为规模劣化的证据**。

## 9. 单列安全问题（不在本案修正）

以下为**现行生产实现**观察到的事实快照，非本案引入，也**不在本案修改**：

1. `visibleKnowledgeBaseIds` 的成员分支（[KnowledgeBaseAuthorizationService#L89-L94](../../src/main/java/com/slz/crm/knowledge/auth/KnowledgeBaseAuthorizationService.java#L89-L94)）
   只查 `knowledge_base_member`（`is_deleted=0 AND user_id=?`），**不 join `knowledge_base`、不校验目标库是否存在或已软删**。
   因此：
   - **MEMBER_OF_SOFTDELETED**（库行 `is_deleted=1`，成员行存活）的 ID 会进入可见集（实测 scale=4 = `100009`）；
   - **ORPHAN**（成员行指向根本不存在的库）的 ID 也会进入可见集（实测 scale=4 = `900000000`）。
2. 同服务的读判定 `canRead`（[L33-L47](../../src/main/java/com/slz/crm/knowledge/auth/KnowledgeBaseAuthorizationService.java#L33-L47)）
   对这两类库都返回 false：软删库经 `@TableLogic` 取不到实体，孤儿库不存在实体。
   即"列表可见集"比"单库可读判定"**更宽**，两条路径口径不一致。
3. 是否构成实际越权取决于**下游用法**：若下游按 `id IN (...)` 直接取数且不再叠加 `is_deleted=0`/存在性校验，
   软删库数据存在被取回的风险；若下游仍按实体/`canRead` 复核，则仅是多出无意义 ID。
   **本案不下这一结论**，仅记录不一致与上界风险。
4. 按规格：**停止性能 GO**，安全问题单列，交由独立提案定夺；本案不"顺手"修改语义、不加索引、不改 `src/main`。

## 10. 证据级别、未知与未跑项

- **证据级别**：本机、一次性容器、确定性假数据、生产 SQL 与生产授权代码；可复现（§3 命令）。
- **unknown（按规格不填 0）**：
  - `container_resources=unknown`：容器 cgroup CPU/内存限额未采集；
  - 生产真实数据基数分布、真实网络 RTT、真实下游调用方式（是否复核 `canRead`、是否叠加 `is_deleted`）**unknown**；
  - 生产环境索引现状与统计信息 **unknown**（本案库由迁移链建出，仅代表该链）。
- **未跑项**：
  - 未做请求端到端/接口级延迟度量 → 本报告**不冒充**请求端到端或生产收益；
  - 未做真实模型调用（本案不涉及）；未做生产库验证；
  - 未改动任何 `src/main`、已合入迁移、依赖、CI、授权行为、JVM/池参数（全程无此类改动）。
- **度量自身局限**：单机 Windows、单容器 MySQL、样本 20/格、两轮；同格轮间抖动大于规模间差异，规模趋势不可判。

## 11. 复现

```bash
# 默认门禁（纯 JVM、无 Docker、无外网）：守卫测试会随 surefire 一起跑
mvn -o -B -ntp -Dtest=KnowledgeBaseScopeAuthBaselineGuardTest test

# 真库度量（需本机 Docker + 本地已存在钉扎镜像 mysql:8.0；缺前提 fail closed，不自动拉取）
KB_SCOPE_AUTH_MEASURE=1 mvn -o -B -ntp -Dtest=KnowledgeBaseScopeAuthBaselineBenchmark test
# stdout 检索 KBSCOPE；镜像可覆盖 -Dkbscope.mysql.image=<tag>（仍要求本地已有，不 pull）
```

未设 `KB_SCOPE_AUTH_MEASURE=1`、Docker 不可用或本地镜像缺失时，入口**显式失败并报告"未测"**，
不自动 pull、不连业务库、不假装成功。