# A 档独立复核报告 · harden-consistency-datascope-and-gate

- **复核结论**：GO（CRITICAL 0 / MAJOR 0 / MINOR 2）
- **复核对象**：`feature/harden-consistency-datascope-and-gate` @ `cc3491f`（基于 master `80ffd6f`）
- **复核工作树**：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- **复核时间**：2026-10-18

全部六组核验在权威工作树 `D:\code\crmAndRag-merge-add-knowledge-admin-api` 上独立实跑完成（分支 `feature/harden-consistency-datascope-and-gate`，tip `cc3491f`，merge-base 实测 = master tip `80ffd6f`）。靶向单测、SpotBugs、PMD、`merge-gate.sh`、`check-test-baseline.sh`、`merge-gate-selftest.sh` 均为本轮真跑，非采信执行方台账；全程未执行 merge/push、未改任何源码与台账，复核结束后工作树 git status 0 行干净。

---

## 逐项核验结果

| 项 | 实测证据 | 结论 |
| :--- | :--- | :---: |
| **R-1 写集边界** | `git diff master..HEAD --name-only` 恰 21 文件（2 openspec + 3 scripts + 7 main + 6 test + 1 handoff）；负向 diff 实测 0 文件：`db/migration`、`pom.xml`、`frontend/`（`openapi.yaml` 位于 frontend/ 下同被覆盖）；`ls migration` 最高 V42，V43 未动用；7 笔提交全部单行 ASCII 无 body | ✅ PASS |
| **R-2 删除双清（C-11）** | `deleteFile` 软删后联动 `vectorStore.deleteByDocumentId` + `chunkMapper.deletePhysicallyByDocumentId`；缺依赖时在任何写入之前抛 `IllegalStateException`（无半清窗口）；`deletePhysicallyByDocumentId` 系 master 既有方法（`DocumentVectorChunkMapper.java:57`）；`KnowledgeAdminVectorSupport` 三方法逐行等价搬移（含 `@SuppressWarnings` 与 fail-open→关语义）；单测断言 `updateById(isDeleted)` + 双清各一次、缺文件零交互；两个 IT 兼容构造调用方均不触 `deleteFile`，failsafe 118 绿背书 | ✅ PASS |
| **R-3 DataScope 收敛（B-4/B-5）** | `assertCanReadContract`(creator+owner)/`assertCanReadTask`(creator+assignee+assigner) 委托既有 `canReadResource`（该文件零改动）；null 实体/无身份/越权一律 12002（`requireCurrentUser` `BusinessRecordAccessServiceImpl.java:119` 实证）；三入口断言先于附属查询，订单随所属合同、合同缺失 12002 fail-closed；`getByTaskId` 列表+分页双闸；7 个执行器（3 详情 + 4 结构化）全部前置 `requireLoggedInRole`；`AiQueryToolSupport` 纯静态逐行等价；专项测试非空洞——12002 错误码逐一 `assertEquals(ErrorCode.PERMISSION_DENIED.getCode())`、跨属主路径 verify 断言触发、正路径断言返回 VO | ✅ PASS |
| **R-4 merge-gate 同源（G-6）** | `[checkstyle]`/`[spotless]` 位于 unit 后 pmd 前，`MERGE_GATE_CMD_CHECKSTYLE`/`SPOTLESS` 覆盖钩子与 CI static-analysis 映射头注齐备，8 门→10 门；独立实跑自测输出“106 条断言全绿”（含场景 13 具名 PASS/FAIL 指名/覆盖 marker 透传） | ✅ PASS |
| **R-5 全量实测** | ① 靶向：Tests run 119, Failures 0, Errors 0, BUILD SUCCESS；② `spotbugs:check` `pmd:check`：BugInstance size is 0、PMD 零违规、BUILD SUCCESS（NCSS 零超限）；③ `merge-gate.sh`：10/10 PASS，GATE_EXIT=0（unit/checkstyle/spotbugs/spotless/pmd/baseline/frontend-unit/hook/bijection/pmd-baseline），`typed-router.d.ts` 已 checkout 还原；④ `check-test-baseline.sh` 独立复跑 EXIT=0：surefire 267 报告/1671/0/0/0、failsafe 31 报告/118/0/0/7 skip；⑤ 执行方 failsafe 一手 XML（target/，Oct 18 22:11–22:16）实测 31 文件/118 tests/7 skipped/零失败零错误，与“7 个真实外网用例 skipped”吻合 | ✅ PASS |
| **R-6 台账与物料** | `test-baseline.txt`：1647→1671（+24）、reports 266→267、source-revision=23670bf（分支内真实实现提交）无 -dirty，文件 md5 944c56ad… 与 tasks.md 断言一致；+24 精确对账 = AccessTest+7 / ContractTest+3 / OrderItemTest 新 4 / TaskCommentTest+5 / KnowledgeAdminTest+2 / AiQueryTest+3，reports +1 = 新测试类；`tasks.md` 10/10 勾选且每项带 6 空格缩进实测依据；handoff 改动、流水、md5、红线自检齐备 | ✅ PASS |

---

## 阻断项与 MINOR 记录

无阻断项。MINOR 2 条（均不碍合并）：

1. **handoff §1 记录过期**：写“共 6 笔、最终 HEAD = 172798d”，实际分支 7 笔、tip = cc3491f——第 7 笔正是把 handoff/tasks.md 证据随卡入库的 docs 提交，属自指性时滞的记录性偏差。
2. **执行方 verify 日志 md5（6e56946f…）无留存文件可对**：work/ 现存 _hp-verify*.log 实为 harden-password 卡留痕。但实质证据链完整——failsafe 一手 XML 在 target/ 且计数吻合、本轮独立实测全绿覆盖同一结论。建议后续执行卡把日志落 work/ 并在 handoff 写明文件名。

---

## handoff §8 四定夺点复核意见

1. **fail-closed 边界**：三处“缺数据即拒绝”（任务缺失 12002 不泄露存在性、订单所属合同缺失 12002、兼容构造 deleteFile ISE）均符合安全优先取向，误伤面仅限兼容测试构造与数据不完整行，认可维持。补充观察：合同详情缺失走既有 CONTRACT_NOT_EXISTS 而任务评论统一 12002，两入口语义不同但各有依据（前者保留原语义、后者防存在性探测），不构成矛盾。
2. **getContractById 未挂断言**：实测全仓调用方仅 `AiEntityResolver:154` 与 `OrderActionExecutor:89`（均在 AI 侧、上游已有登录与工具守卫），公共 API 行为未变、卡规格未点名，认可本卡不收口；将来若暴露给新调用方再评估挂闸。
3. **invoice_info 非受管表**：实测 `ResourceTypeConstant.MANAGED_TABLES` 确无 invoice_info，切面不注入范围条件——handoff 披露属实。本卡只加身份守卫（严格收窄、无扩权），建议另立卡将 invoice_info 纳入受管表或显式豁免登记。
4. **两协作类拆分等价性**：逐行 diff 审阅等价（VectorSupport 三方法原样搬移；AiQueryToolSupport setApprovalStatus(null) 在每次新建 DTO 上与“不设置”等价；懒初始化竞态仅产生等价实例且 final 字段 JMM 安全），119/119 + 全量 1671 + 静态门禁全绿背书，等价性成立。
