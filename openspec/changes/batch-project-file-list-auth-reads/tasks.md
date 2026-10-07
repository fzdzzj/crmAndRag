# tasks：batch-project-file-list-auth-reads（卡 P-t）

> 写集红线：仅任务卡 §0 登记的 ≤10 个文件。零契约变更（/query total 语义、List 形状、OpenAPI 全不动）；权限链逐行实时（不引入请求级权限快照，`auth/permission` 次数不减是预期）；三把 total 语义锁语义不变；禁 push。全部数字自跑实测，禁止照抄卡面预期值。

## 1. 阶段一：开工准备
- [x] 1.1 从 `master@a923bfd` 检出 `feature/batch-project-file-list-auth-reads`，登记工作树纯净（仅受保护未跟踪项）
- [x] 1.2 通读 `work/task-card-project-file-list-auth-batch.md` 与本三件套，确认写集与 B1 行为红线
- [x] 1.3 核对三把 total 语义锁现状（单测 `queryPageAllDeniedKeepsDatabaseTotal` / 守卫 `emptyPageKeepsDatabaseTotal` / 基准 `PFLHOT frozen` 指纹）与交错回归入口可运行性（Docker + 本地 `mysql:8.0`，不 pull）

## 2. 阶段二：批量入口设计落位
- [x] 2.1 确定批量方法签名与预取清单（activity/opportunity/contract/orderItem `selectBatchIds` + 参与人 IN），登记于 §0
- [x] 2.2 明确单行入口 `canReadProjectFile` 与共享协作类零改动边界（下载复核路径不碰）

## 3. 阶段三：实施（红→绿）
- [x] 3.1 红测试先行：批量入口缺失/列表仍逐行 user 读的反例，未改主代码基线实跑贴红
- [x] 3.2 实现批量过滤（1 次 user 读 + 整组短路 + 预取 + 逐行矩阵），`toReadableVOs` 接线；中文 Javadoc 标注「batch-project-file-list-auth-reads 任务 3.2」
- [x] 3.3 判定矩阵等价单测：活动 / 商机 / 合同 / 订单反查 / 独立上传仅本人 / 多归属任一命中 / 超管整组直通 / 冻结离职整组空 / 参与人批量边界 / 空权限链 `BaseException` 语义
- [x] 3.4 更新 `ProjectFileServiceImplTest` 调用形状锁定（records/total 语义断言不动）

## 4. 阶段四：门禁与零回归
- [x] 4.1 全量 `mvn -B -ntp test` 只增不减；四静态门禁 0 违规；`check-test-baseline.sh`（`--update` 真实写回）
- [x] 4.2 三守卫（`check-line-endings lf` / `check-write-set a923bfd` / `check-dirty`）+ 136 条门禁自测全绿
- [x] 4.3 权限零回归：`PROJECT_FILE_LIST_HOTPATH_MEASURE=1` 交错回归 `runRoleReassignInterleavingRegression` 原样绿；全量度量 run 探针/状态闸/撤权/`listBy*` 指纹与既有基线报告逐字对照；SQL 分类前后对照表（user N→1 / 维度 N→常数 / permission 不变）

## 5. 阶段五：交错 A/B 与合并
- [x] 5.1 交错 A/B（方法论沿用 `add-project-file-list-interleaved-ab-attribution`）：PRE=`a923bfd` 纯复制树 vs POST=本实现，同机交错每臂 ≥5 独立 run + 安慰剂对照 + 阴性对照（ADMIN/FROZEN/LEAVER），报告落 `docs/project-file-list-auth-batch-evaluation.md`
- [x] 5.2 本文件勾选与执行留痕（签名/预取清单、红测输出位置、门禁数字、零回归证据、A/B 结果、git 拓扑、未跑项）
- [x] 5.3 2-4 笔提交（perf 实现+测试 / chore 基线（若有）/ docs 评估报告+tasks 留痕）→ 切回 master 真 `--no-ff` 合并（禁 push，分支保留）→ 严格停步回报

## 0. 执行记录（子 agent 填写）

- **分支与基线**：`feature/batch-project-file-list-auth-reads` @ `master` `a923bfd`（948 单测全绿）检出；终态 `0c20a5e`（perf）+ `9f436ff`（chore 基线）+ docs 笔。
- **批量方法签名与预取清单**：`AttachmentAccessService.filterReadableProjectFiles(List<ProjectFileEntity>, Long)` → 整组 1 次 `userMapper.selectById`（null/status≠1 整组空、roleId=1 整组直通）→ 非超管 `ProjectFileAttachmentReader.filterReadableByDimension`：`DimensionPrefetch`（record）= activity/opportunity/orderItem/contract 各 ≤1 次 `selectBatchIds` + 参与人复用既有 `selectByActivityIds` 1 次 IN（**`BusinessActivityUserMapper` 零改动**，写集缩至 9 文件）；随后逐行矩阵（activity 225→opportunity 204→contract 215→独立上传→reservedRead）与单行入口逐字一致，`hasPermission` 逐行实时。`toReadableVOs` 每请求恰调 1 次（空列表零调用）；单行入口与下载复核路径零改动。
- **已知取舍（卡面 §2 字面登记）**：批量路径无条件预取维度实体——单行路径在维度权限 false 时不查实体的短路（`hasViewPermission ? selectById : null`）不保留，多读但预取数据不参与无权限行的判定（判定等价）；参与人查询由逐行 `exists` 改 1 次 IN + 内存过滤，同理（同一请求内，参与关系以预取时点为准）。该取舍同时在 spec-delta（条件段）、`ProjectFileAttachmentReader` Javadoc 与评估报告 §11 有登记，此处为卡面 §2「须登记于 tasks.md §0」的字面落位。
- **红测试输出位置**：①运行红 `ProjectFileServiceImplTest.queryPageAuthUserReadIsBatchedToSingleRead`——`TooManyActualInvocations: userMapper.selectById(7L); Wanted 1 time: But was 3 times`（未改主代码基线实录）；②编译红 `AttachmentAccessServiceTest`——`找不到符号 filterReadableProjectFiles`（入口缺失）。转绿后 37/37（28+9）。
- **门禁实测数字**：全量 surefire **956/0/0/0**（948→+8：矩阵等价 7 + 列表形状 1）；merge-gate 八门禁全绿（`work/pt-mergegate2.log`，EXIT=0）：[unit] 956 / [spotbugs] 0 / [pmd] 0 / [baseline] / [frontend-unit] 179 / [hook] / [bijection] 12↔12 / [pmd-baseline] 0==0；`check-test-baseline.sh --update` 948→956 写回；三守卫 `WRITE_SET_OK: 7 files` / `LINE_ENDINGS_OK` / `STATUS: CLEAN`；门禁自测 35+101+87 全绿（卡面「136 条」为历史口径，按当轮实测三套全绿执行）。
- **零回归证据**：①两臂交错回归全绿且 8 行 `PFLHOT interleave:` 逐字一致（`work/pt-safety-post.log` / `work/pt-safety-pre.log`）；②POST run 指纹（probe/gate/listby n91-n32-n31-n31/revocation/permission_restored）与 `docs/project-file-list-auth-hotpath-evaluation.md` §6 逐字一致；③SQL 分类对照（ALL-100 第 2 轮 WARM，次/请求）：user 100→1、参与人 0→1(IN)、permission **100→100 不减**、activity 25→1、opportunity 25→1、contract 50→1、orderItem 25→1，auth SQL 合计 **325→106（-67.7%）**。
- **交错 A/B 结果与裁决**：13 run（5 对 AB/BA 交错 + 3 安慰剂）全部 `failures=0`；5 对配对差全负（几何比值 0.323/0.456/0.429/0.433/0.543，**中位 0.433 ≈ P50 -57%**）；AB 中位 -0.847 / BA 中位 -0.812 同向；安慰剂最大漂移 0.365 < 中位差 0.838；52 格 × 13 run 页输出零 mismatch；分层 GATE 0.360 / SALES 0.431 / ADMIN 0.467 方向合理。**裁决 GO（本机加速成立）**。原始日志 `D:\code\pflhot-ab-logs-pt\`（13 份，不入库）。完整报告 `docs/project-file-list-auth-batch-evaluation.md`。
- **git 拓扑**：`a923bfd(master) → 0c20a5e(perf, 6 文件 +629/-43) → 9f436ff(chore, 基线 956) → docs 笔 → --no-ff 合入 master`；分支保留，**未 push**。
- **未跑项与假设**：failsafe IT 未跑（与改动面无关、默认门禁不含）；生产未测；资源采集 unknown。偏差登记：①PMD 首轮 7 违规（OnlyOneReturn×5 + 复杂度×2）经单出口重构 + `DimensionPrefetch` 拆分归零，台账未动；②A/B 第一轮 13 run 因 POST 臂 auth 归类缺失作废，`REPORT_KEYS`/`LEAF_NANOS_KEYS` 扩展 7 个 `req/*` 键（工作树临时调整、两臂同构 hash `07e17629`、测后恢复、git 零变化）后全量重跑；③categorize 的 `BusinessActivityUserMapper` 前缀误归 user 类为历史存量（两臂一致，不影响对照），与批量 attribution 壳一并列为度量基建遗留建议另案入库；④本案批量化覆盖全部列表条件（ADMIN/FROZEN/LEAVER 的 user 读同样 N→1），无纯未改路径 e2e 阴性对照，改用分层方向佐证（报告 §5/§7）。
