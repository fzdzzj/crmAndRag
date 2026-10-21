# 交接 · fix-backlog-batch4（收尾小卡 ⑬ sales_stage_approval 附件残留 + ⑭ 错误码总数断言生成化，B 档）

> 权威树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`｜分支：`feature/fix-backlog-batch4`（自 `master@71a274b` 检出）
> 规格：`openspec/changes/fix-backlog-batch4/{proposal.md,tasks.md}`｜档位：**B**（门禁退出码 0 + 主 Agent 审 diff）
> **停步状态：未 merge、未 push、未删分支**（提交哈希链见下）

## 1. 交付摘要

| 任务 | 改动 | 证据 |
| --- | --- | --- |
| ⑬ 审批域附件补齐 | `ChildHostAttachmentCleaner` 公司/联系人两入口补 `sales_stage_approval` 域附件清理；`SalesStageApprovalMapper` 构造注入；类注释同步 | 单测 5/5、IT 2/2、全量 `verify` 绿 |
| ⑭ 错误码总数生成化 | `error-code-map.test.ts` 删硬编码 `100`，改 Node fs 读取两枚举源文件正则计数 | Vitest 70/70、生成值实测 100 |

红线核对：零迁移 / 零新依赖 / 零新错误码（两枚举源文件零 diff）/ `ForeignKeyConfig` 零 diff / `error-code-map.ts` 生产镜像零 diff / `frontend/` 仅 `error-code-map.test.ts` 一处测试工程改动 / 基线只增不减。

## 2. ⑬ 闭包口径论证

### 2.1 为什么审批行是「已有 opportunities 集」的下一层

- 关联图实测（`ForeignKeyConfig` 149-161 行）：`SALES_OPPORTUNITY_RELATIONS` 含 `SalesStageApprovalEntity.opportunityId`（`sales_stage_approval.opportunity_id`）；而 `SALES_STAGE_APPROVAL_RELATIONS` 为空（同文件 163-170 行注释：附件边已按多态语义移除、子图为空）。
- 因此宿主路径只有两种、且都是**两级**：
  - 公司 → 商机（一级，`company_id` / 联系人名下 `contact_id`）→ 审批行（二级）；
  - 联系人 → 商机（一级，`contact_id`）→ 审批行（二级）。
- 两入口在改动前**已经**算出了 `opportunities` 一级集（公司入口 = 公司直挂商机 ∪ 联系人名下商机；联系人入口 = 联系人名下商机）。审批行只需 `selectIds(salesStageApprovalMapper, "opportunity_id", opportunities)` 一次反查即得完全闭包——**没有新增深度、没有新增递归**，这是本卡能把改动压在 20 行内的根据。
- 审批行自身子图为空，故「二级展开」对审批域同样等价于递归闭包；不存在第三层。

### 2.2 model_name 口径

审批附件的判别值 = `ModelName.APPROVAL_ATTACHMENT`（值 `approval_attachment`），与先例 `SalesOpportunityServiceImpl#deleteStageApprovalAttachments`（211 行）一致。**不是** `ModelName.SALES_STAGE_APPROVAL`——后者是「审批单」这个业务对象的模型名，附件上传时落的是 `approval_attachment`。误用后者会删 0 行且断言不报错（静默无效），是本卡最容易踩的坑。

### 2.3 商机入口为什么不重复接线

- `SalesOpportunityServiceImpl#delete` 在 `deleteCascade` 之前已先例调用 `deleteStageApprovalAttachments(opportunityId)`（`selectAllApprovalsByOpportunityId` → `listEntitiesByAndIds(APPROVAL_ATTACHMENT)` → `removeByIds`）。
- Cleaner 的商机入口若再接一遍，同一次商机物理删会跑第二轮 select + 删除 IO（含磁盘删），语义完全冗余；且先例是「先列出过滤后的行再删 id」的更严格形态，Cleaner 的 `removeByAndIds` 直接按 `(and_id, model_name)` 删——两条路径并存会让「谁负责审批域」的归属再次模糊，正是 batch3 遗留登记要消除的模糊。
- 因此商机入口**只**保留活动/任务两类，类注释明确写「商机入口不重复接线（该先例在商机删除路径已覆盖，再删是冗余第二轮 IO）」。回归网：单测 `cleanBeforeOpportunityPhysicalDelete_doesNotTouchApprovalDomain` 断言 `salesStageApprovalMapper` 零交互 + 无 `APPROVAL_ATTACHMENT` 删除调用。

### 2.4 注入改动范围

`ChildHostAttachmentCleaner` 构造器 +1 参数（`SalesStageApprovalMapper`）。三处调用方（`CustomerCompanyServiceImpl:59`、`CustomerContactServiceImpl:67`、`SalesOpportunityServiceImpl:51`）均为 `@Autowired` 字段注入，Spring 装配零改动；`ChildHostAttachmentCleanerTest` 用 `@InjectMocks` 覆盖构造器。

## 3. ⑭ 生成化前后对照

| | 前（batch3） | 后（本卡） |
| --- | --- | --- |
| 断言来源 | `const BACKEND_ERROR_CODE_NAMES = 100`（手写） | 读 `ErrorCode.java` + `PlatformErrorCode.java` 正则计数 |
| 后端加码时 | 必须手改，漏改则 Vitest 假红（前端段独立哨兵失效） | 自动跟随，零手工 |
| 实测值 | 100 | 90（ErrorCode）+ 10（PlatformErrorCode）= **100** |

计数口径（两枚举同构）：**两个空格缩进 + 常量名 + `(` + 码值数字**，正则 `/^ {2}([A-Z][A-Z0-9_]*)\(\s*(\d+)/gm`。

- **`\s*` 不是宽松，是必需**：`ErrorCode.BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS`（254-255 行）是 `NAME(` 换行后才写码值的形态。严格同行匹配会得 99，与反射口径差 1 → 假红。见偏差 D2。
- **与 `ErrorCodeMapParityTest` 的口径一致性**：该 Maven 测试的 `backendEntries()` 用 `ErrorCode.values()` + `PlatformErrorCode.values()` 求并集（=100），再与前端 `ApiErrorCode` 名单做 `TreeSet` 双向等值 + 逐项码值/文案/messageKey 比对。本卡前端计数是同一集合的**源码文本**视图：两枚举无多行枚举声明、无动态生成常量，因此文本计数 = 反射计数（实测均 100）。差异只在触发面：Maven 侧管「名单/码值/文案」精确对齐，前端侧管「前端段独立跑 pnpm 时总数不许漂」。
- **不删断言的理由**：`frontend-quality` CI job 独立跑 `pnpm`，不跑 Maven；删了总数断言，前端段就完全没有「后端加码而未镜像」的哨兵。生成化是唯一既保留哨兵又灭掉手工常量的方案。
- **双环境路径论证**：`const TEST_FILE_DIR = dirname(fileURLToPath(import.meta.url))` → `resolve(TEST_FILE_DIR, '../../..')` 得仓根（`frontend/src/constants` 上三级 = 仓库根），再 `resolve` 到 `src/main/java/com/slz/crm/{common/enumeration/ErrorCode.java, platform/contract/PlatformErrorCode.java}`。不依赖 `process.cwd()`、不使用字符串拼接分隔符；Windows 本地与 CI Linux 由 `node:path` 归一。Vitest 本地实测绿；路径写错时 `readFileSync` 会直接抛错而非静默返回 0。

## 4. 测试与门禁证据（2026-10-21，权威树实测）

- 定向单测：`mvn -B -ntp test -Dtest=ChildHostAttachmentCleanerTest` → **Tests run: 5, Failures: 0, Errors: 0, Skipped: 0**
- 定向 IT：`mvn -B -ntp failsafe:integration-test failsafe:verify -Dit.test=CustomerCompanyPhysicalDeleteAttachmentCascadeIT` → **Tests run: 2, Failures: 0, Errors: 0**（公司入口 / 联系人入口各一组：审批附件行清零 + 磁盘文件删除 + 同 `and_id` 跨模型对照存活）
- 前端：`pnpm exec vitest run src/constants/error-code-map.test.ts` → **Test Files 1 passed / Tests 70 passed**；生成计数复算 90 + 10 = 100
- Docker：`docker info` → `29.6.2`（在线）
- 全量：`mvn -B -ntp clean verify` → **BUILD SUCCESS**；surefire `Tests run: 1894, Failures: 0, Errors: 0, Skipped: 0`；failsafe `Tests run: 124, Failures: 0, Errors: 0, Skipped: 7`；checkstyle `0 violations`；spotless check / pmd check 通过
- 基线：`bash scripts/check-test-baseline.sh --update` 后复跑 `bash scripts/check-test-baseline.sh` → **EXIT=0**，`surefire reports 304 / tests 1894 / skipped 0` + `failsafe reports 34 / tests 124 / skipped 7`（只增不减）
- 门禁：`bash scripts/merge-gate.sh` → **EXIT_GATE=0，10/10 PASS**：`[unit] [checkstyle] [spotbugs] [spotless] [pmd] [baseline] [frontend-unit] [hook] [bijection] [pmd-baseline]`
- 证据日志：`work/logs/fix-backlog-batch4-verify.log`、`work/logs/fix-backlog-batch4-merge-gate.log`（忽略区，不入库）

## 5. 偏差清单

| 编号 | 偏差 | 处置 |
| --- | --- | --- |
| D1 | `tasks.md` 1.2 写「Cleaner 三入口**既有**用例补审批域 verify」，实测仓库内**没有** `ChildHostAttachmentCleaner` 的任何单测（`grep cleanBefore` 命中仅 `SalesOpportunityServiceTest:178` 一处 mock 调用） | 新建 `src/test/java/com/slz/crm/unit/service/ChildHostAttachmentCleanerTest.java`（5 用例：公司入口 / 联系人入口 / 商机入口不接线 / 审批集为空 / 空入参），非改写既有用例 |
| D2 | 计数口径在提案基础上多容忍 `(` 与码值之间的换行（`\s*`） | 提案只写「`(` + 数字」；实测严格同行会少数 `BUSINESS_ACTIVITY_CONTACT_NOT_EXISTS`，得 99 与反射口径 100 背离。属口径澄清，不改语义 |
| D3 | 运行 Vitest 会触发 `unplugin-vue-router` 重写 `frontend/typed-router.d.ts`（新增 `salesLeadList` 路由声明 + 行尾改写） | 该文件是他人改动/生成物，**未纳入提交**；两次运行后均 `git checkout -- frontend/typed-router.d.ts` 还原。merge-gate 的 `[frontend-unit]` 也自带同款提示「生成物噪声，不要提交」 |
| D4 | 全量 `verify` 首轮 PMD 红：`CommentSize` 类注释 24 行 > `maxLines=20` | 压缩类注释至 18 行（论证移入本 handoff），复跑 `pmd:check` 与全量 `verify` 均绿；未改任何规则/阈值 |
| D5 | 基线数字变化：surefire reports 303→**304**（新增 1 个单测类报告）、tests 1889→**1894**（+5）、failsafe reports 34→34、tests 123→**124**（+1，联系人入口新用例） | 只增不减，符合红线；`--update` 来自一次真实 `clean verify` 运行 |
| D6 | 首次 IT 定向验证用 `failsafe:integration-test` 直跑（非完整 `verify`） | 仅作早期反馈；最终结论一律取 `mvn -B -ntp clean verify` 那一轮 |
| D7 | 提交分三笔（fix → test(baseline) → docs(card)），handoff 落观察树不入库 | 与任务卡「分笔对齐任务组」一致；openspec 卡物料随分支提交 |

## 6. 待裁决项

1. **商机入口的防御性清理**：当前严格按提案不加（依赖 `SalesOpportunityServiceImpl#delete` 先例）。若将来出现其它路径直接 `deleteCascade(SalesOpportunityEntity.class, ...)`（如批量清理、运维脚本），审批域会再次漏清。建议登记为观察项，或在 `ForeignKeyDeleteUtil` 入口统一加一层「级联前审计」——后者属新卡范围。
2. **联系人删除链路的重摄取**：本卡 IT 真跑通了 `CustomerContactServiceImpl#deleteByIds`（含 `reingestAfterCommit`）的全链路，未观察到副作用；是否需要为「联系人物理删 + 附件清理」补一条独立 IT（现挂在公司 IT 类里）由复核裁量。
3. **`frontend/typed-router.d.ts` 的生成物归属**：每次跑 Vitest 都会重写，长期靠 `git checkout` 收尾。建议纳入 `.gitignore` 或改为 CI 生成（非本卡范围，且属他人改动区）。
