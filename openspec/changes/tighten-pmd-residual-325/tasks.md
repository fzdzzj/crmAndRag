# Tasks — tighten-pmd-residual-325

> 执行契约见 `openspec/git-workflow.md`（限路径直落 master、不 push）与 proposal.md 的分片定义。
> 硬约束：surefire 计数不变（锁 `scripts/test-baseline.txt`；**唯一例外 = 任务 6.4 起的配套反向用例，允许只增，由一次真实运行 `--update` 写入**）；台账只下调（`--update` 且只许变严）；每分片收尾 = pmd:check 实测 → --update → pom 照抄 → merge-gate 全绿 → 提交。
> 每完成一步立刻勾选并在行尾补实测证据。

## 4. 分片 D：死参/死变量/小异味（19 条 → ≈0）

- [x] 4.1 跑 `mvn -B -ntp pmd:pmd` 刷新 `target/pmd.xml`，登记分片 D 逐例清单（规则/文件/行号/是否覆写签名） ｜实测：19 条 = UnusedLocalVariable 5（UserController:96 / AiChatServiceImpl:188,203 / BusinessActivityServiceImpl:942 / ContactTaskServiceImpl:696）+ UnusedFormalParameter 9（DocumentIngestionService:412 / DocumentService:164 / KnowledgeRetrievalServiceImpl:418 各 1，AttachmentAccessServiceImpl:363,368 各 3）+ EmptyCatchBlock 2（QueryWrapperAspect:165,176）+ InsufficientStringBufferDeclaration 2 + TooManyMethods 1（AssistRequestServiceImpl:54）
- [x] 4.2 UnusedLocalVariable 5 + InsufficientStringBufferDeclaration 2：直接修（删变量 / 补初始容量），编译 + `mvn -B -ntp test` 全绿 ｜实测：5 处删死变量（含 2 处连带删除只为 isAdmin 服务的 currentUser 查询）、2 处 StringBuilder 改 `new StringBuilder(128)` + 首段显式 append；surefire 724 绿
- [x] 4.3 UnusedFormalParameter 9：逐例——非覆写删参；覆写/接口实现/回调保留参数 `@SuppressWarnings("PMD.UnusedFormalParameter")` + 中文理由（Javadoc 标注「tighten-pmd-residual-325 任务 4.3」） ｜实测：删 3（markFailed/savedChunks、parse/filename、recall/query，同步 4+1+3 处调用点）；豁免 6（AttachmentAccessServiceImpl 两个部门上司读写预留点，参数即未来实现签名契约）
- [x] 4.4 EmptyCatchBlock 2：逐例——有意吞异常豁免登记 + 中文理由（或补最小日志），非有意补处理 ｜实测：2 处同属 `findMethodWithWrapper` 反射探测（NoSuchMethodException = 正常答案），方法级 `@SuppressWarnings("PMD.EmptyCatchBlock")` + 中文理由
- [x] 4.5 TooManyMethods 1：默认 `@SuppressWarnings("PMD.TooManyMethods")` + 中文理由豁免（如 owner 想拆类另议） ｜实测：AssistRequestServiceImpl 类级豁免（拆类会切开同一聚合根事务边界）
- [x] 4.6 分片收尾：pmd:check 实测新条数 → `pmd-baseline-check.sh --update` → pom 同步 → `merge-gate.sh` 全绿 → 限路径提交（提交信息含前值→后值） ｜实测：pmd:check 306（325→306，五条规则全清零）；台账 306 / pom 306；merge-gate 8 子门禁全绿（unit 724/0/0/0、spotbugs PASS、pmd 306、baseline PASS、frontend-unit 163、hook PASS、bijection 12/12、pmd-baseline 306==306==306）

## 5. 分片 E：FieldNamingConventions（39 条）

- [x] 5.1 盘点 39 处落点（模块 × 违例属性 × 是否 pojo/序列化/反射依赖），产出处置表：可改名 / 必须豁免（pojo 序列化字段名 = API 契约，禁改名） ｜实测：39 条 = 23 个日志句柄（22×`log` + 1×`logger`，均 `private static final Logger`，**全部无 `@Slf4j`**，改名不触发 Lombok 重名）+ 1×`fileSeparator` + 2×`paymentSequence`/`invoiceSequence` + 1×`random` + 3×JFreeChartConstant（`public static` 非 final）+ 9×PrivacyGetId（`public static final`）。**pojo 命中 0 条**（`pojo/**` 无一落点，序列化契约零风险）；全仓引用半径：PrivacyGetId 9 个常量**零引用**（仅 `USER_ID_RELATED_FIELDS` 被 PrivacyAspect 用，不在违例内）、JFreeChartConstant 整类零引用、其余均为文件内私有 —— 故 39 条全部可改名，无需豁免
- [x] 5.2 非契约侧命名按 PMD 口径改名（全仓引用同步，`mvn -B -ntp test-compile` 兜底），pojo 侧 `@SuppressWarnings("PMD.FieldNamingConventions")` + 中文理由豁免 ｜实测：39 处改名（`log`→`LOG`、`logger`→`LOGGER`、`fileSeparator`→`FILE_SEPARATOR`、两序号→`PAYMENT_SEQUENCE`/`INVOICE_SEQUENCE`、`random`→`RANDOM`、PrivacyGetId 9 常量→UPPER_CASE、JFreeChartConstant 3 字段补 `final` 成真常量）；改名用字节级脚本、**故意不写裸 `\blog\b`**（PlatformAsyncConfig 有字符串 `"discard-log"`，裸词边界会误伤成语义变更）；checkstyle 0 违规、spotless 已 apply、test-compile 通过
- [x] 5.3 分片收尾：同 4.6 口径（--update → pom → merge-gate → 提交） ｜实测：pmd:check 267（306→267，FieldNamingConventions 归零）；台账 267 / pom 267；merge-gate 8 子门禁全绿（unit 724/0/0/0、spotbugs PASS、pmd 267、baseline PASS、frontend-unit PASS、hook PASS、bijection 12/12、pmd-baseline 267==267==267）

## 6. 分片 F：复杂度类 126 + OnlyOneReturn 残留 141（拍板前置，未拍板前禁动代码）

- [x] 6.1 盘点 267 条逐例处置表：位置 / 复杂度成因 / 候选处置（拆方法 / 拆 helper 类 / 豁免+理由 / 保留不修），落 `work/pmd-residual-f.md`，产出拍板输入报告后**停下等 owner 拍板** ｜实测：`work/pmd-residual-f.md`（336 行）+ 复算脚本 `work/_pmd_f_dump.py` / `_pmd_f_enrich.py` / `_pmd_f_report.py` + 明细 `work/pmd-residual-f.tsv`。实测 267 = CyclomaticComplexity 75（方法 56/类 19）+ NcssCount 29（方法 2/类 27）+ CognitiveComplexity 22 + OnlyOneReturn 141；**OnlyOneReturn 141 条实际只落在 66 个方法**（单方法最多 8 处早返回）；临界（超阈值 +1..3）合计 40 条；类级 46 条集中在 28 个类，最大者 AssistRequestServiceImpl（NCSS 830/圈 442）与分片 D 已豁免的 TooManyMethods 同源。**未动任何代码**，决策点见报告 §4（Q-F1..Q-F5）
- [ ] 6.2 F-1 批（方法级 80 条：圈 56 + 认知 22 + NCSS 2；66 个 OnlyOneReturn 方法随拆顺带单出口化）：行为等价拆方法，不改公共签名；守卫式早返回经拆分仍无法等价者 `@SuppressWarnings("PMD.OnlyOneReturn")` + 中文理由；批收尾 = pmd:check → --update → pom → merge-gate 全绿 → 限路径提交 ｜实测：＿＿
- [ ] 6.3 F-2 批（F-1 合入后**新鲜 pmd 实测重排**类级剩余项）：拆无行为敏感标记的类为 helper/协作类，不改公共 API 与冻结契约 ｜实测：＿＿
- [ ] 6.4 F-3 批（高风险类：AiChatStreamLifecycle / AiChatSseEventWriter / DataScopeServiceImpl（超集不变量保持）/ AttachmentAccessServiceImpl / ModelProviderImpl）：单独拆分并**配套反向用例**（SseContractTest 必须全绿）；本批起 surefire 允许只增，基线由一次真实运行 `--update` 写入 ｜实测：＿＿
- [ ] 6.5 F-4 批（AssistRequestServiceImpl 巨类单批拆分）：拆完**同步移除分片 D 任务 4.5 的 `PMD.TooManyMethods` 豁免**（口径自洽） ｜实测：＿＿
- [ ] 6.6 F-5 全案收尾：pmd 终值台账（接近 0，残留必须逐条豁免留痕）；HANDOFF §3 补本提案记录；台账 source-revision 更新；汇报含 325→终值全程阶梯与豁免清单 ｜实测：＿＿

## Git 操作

- 提交直落 master（沿用第一轮分片模式，无需 feature 分支），限路径：`src/main/java` + 台账 + pom + 本 tasks.md。
- 提交信息 `refactor(pmd): 分片D收紧死参与小异味，基线325→N`（type 沿用 refactor/docs）。
- **不 push**（无 remote，推送需用户显式授权）。
- 环境：Shell 是 PowerShell；跑 .sh 脚本用 `D:\git\Git\bin\bash.exe -c "..."`（裸 `bash` 指向已损坏的 WSL，禁用）；Maven 本地库离线优先，缺 artifact 停下报告不联网。
- **本轮各分片提交实况**：分片 D = `9eb7bfa`（信息含 325→306）；分片 E = `8656919`（信息含 306→267）；盘点留痕 = `a82f8bb`。**插曲**：分片 E 的暂存内容一度被**并发会话**的无 pathspec `git commit` 卷入 `99ccc7e`（该会话随后自行 `reset --soft` 重做为自己的 `06d04a8`，只含其 4 份文档），分片 E 遂由本 lane 以 `8656919` 独立提交——**本 lane 全程未对被卷入的提交做 reset / amend**（历史改写属需用户显式授权的动作）。
- **本机 git 两个坑（本轮实测，会影响后续执行者）**：
  1. 环境变量 `MSYS_NO_PATHCONV=1` + `MSYS2_ARG_CONV_EXCL=*` 会关掉 POSIX→Windows 路径转换，导致 `.git/hooks/pre-commit` 转发器里的 `git -C "$frontend_dir"` 直接 `fatal: cannot change to '<posix path>'`，**提交被误判为前端钩子失败**；提交前用 `env -u MSYS_NO_PATHCONV -u MSYS2_ARG_CONV_EXCL git commit ...` 即可让钩子正常工作（同因也让裸 `mvn` shell 脚本失效——须走 `mvn.cmd` 包装器 + `JAVA_HOME=<JDK21>`）。
  2. 并发会话可能把他人已 `git add` 的内容一起提交，**提交一律带显式 pathspec**（`git commit -F msg -- <paths>`）以自保。
