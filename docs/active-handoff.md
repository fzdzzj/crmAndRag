# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-24（evidence-to-operations 三合一卡：交换率重定价 + 前端权限派生 + 开关开启通道，**B 档**）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master` = **`3e777e8`（M1 合并）+ `15010dc`（基线重锚）+ 本归档笔**，= origin/master。分支 `feature/evidence-to-operations` **永久保留**（基点 `3edc492` → **7 笔**：`04adddf` 立项 / `8f2021e` G1 / `e4434da` G2 / `40769a3` G3 / `f6881ee` 基线 / `5924c9a` 审计文字修正 / `e8c0a47` 行尾稳定性修复）
- **测试基线四元组**：`source-revision=3e777e8`（**树净，非 `-dirty`**）+ Surefire **报告 337 / 用例 2092 / F0 / E0 / S0** + Failsafe **报告 44 / 用例 145 / F0 / E0 / S13** + `CI=#177`（Run 40003108877，**6/6 全绿**）。上一卡 332/2077/0 + 44/145/13 → 337/2092/0 + 44/145/13（+5 报告 +15 例，恰为 5 个新测试类）
- **本卡规模**：**34 文件 / +7769 −88**；merge 树 == 分支 tip 树 == **`42f5bc16c63c99ac59980b7022ef40758cacf532`**。**注意**：树等价只免「合并前再验」，**不免基线重锚**——本卡仍在干净合并树上跑了 `mvn clean verify` + `--update`
- **三组结论（按组判定，互不连坐）**：
  - **G1 交换率 → `EXCHANGE-RATE`**：把「泄漏率」按 `stratum` 拆成**有害半边**（same-class `S_same`，分母 100＝同文档对）与**无害半边**（cross-class `C_cross`，分母 176）。基线 `floor=0.9887`：`R=70/120=0.5833`、`S_same=2/100=0.02`、`C_cross=1/176=0.0057`；**免费档不存在**（`f<0.9887` 的 178 个点上 `S_same≥0.03`，`firstImprovingFloor=0.988` 已先压掉 1 张同文档图）。价目表（ΔR 相对基线 / 多压掉的同文档图）：`0.982 → +8.33pp / +1`（**全表最高效，ΔR/ΔS=8.33**）、`0.98 → +9.17pp / +2`、`0.975 → +13.3pp / +4`、`0.95 → +25pp / +6`、`0.90 → +35.8pp / +6`（`R=94.17%`）
  - **G1 结构性的那一条**：cross-class 余弦最高 **0.98873594**（即基线那 1 对）、次高直接 **0.73** ⇒ **`C_cross` 在基线档及以下 178/200 个点上恒为 1/176**（`f>0.98873594` 的 22 点为 0）⇒ **降 floor 的全部边际代价都落在同文档抑制上，跨文档噪声不再新增**
  - **G2 前端权限派生 → P1–P4 全绿**：生成物 `endpointPermission.gen.ts` **279 键**（`x-required-permission` 259 + `x-permission-status: OPEN` 20，**`operationId` 实测 0 个 ⇒ 键必须是 `METHOD /path`**）；**只出 type**（`app` 桶 gzip 余量仅 3960 B）；运行期小表 **57 项** + `Permission.vue` 增 `endpoint`（fail-closed）；站点 **65 条 / 11 文件** 全部替换，仅留 **1 处**手抄例外（`sale/list.page.vue` 的 `SALES_VIEW_SALE_OPPORTUNITY_ONLY_MY`，契约 0 候选）；反向自证：改错一个权限名 → `pnpm type-check:check` 必红（TS2322 留痕）
  - **G3 开启通道 → Q1–Q4 全绿**：`scripts/rag-quality/image-vector-ingest-preflight.sh`（`preflight`/`watch`，退出码 `0`=GO / `1`=NO-GO / `2`=输入错误，原因码含结构性陷阱 `A_WITHOUT_B`）+ 自测 **7 案** + 副本变异自证 + `MultimodalRolloutKeysDocConsistencyTest`（§1/§9.1 四键默认值与注册表对齐，改值必红）；执行单 `docs/multimodal-gray-rollout.md` 追加 **§13**（+30/−0，§1–§12 逐字未动）
- **本卡复核（B 档，Owner 亲派）= PASS**（32 项清单，含 G1 从 540 对原始数据**独立全量重算** 200/200 点零偏差、R 与上一卡锚逐点相等、分母 100/176 正确）；复核提两条**非阻断**文字缺陷（Javadoc「53 项」实为 57；「`C_cross` 全网格恒 1/176」措辞过宽），已在 `5924c9a` 修正
- **合并期抓到的真缺陷（本卡最重要的一课）**：合并树首次 `clean verify` **红**在 G1 决策测试，报文「首个差异在第 1 行」且期望/实际都打印 `{`＝不可见差异。根因：**Jackson 默认缩进器用 `System.lineSeparator()`（Windows 写 CRLF），而 `.gitattributes` 的 `*.json text eol=lf` 让仓库存 LF** ⇒「干净 checkout 读 LF」与「本机跑过写模式写 CRLF」给出相反结论。已在 `serialize()` 钉死 LF（`e8c0a47`）。**执行端 verify 绿、复核端 verify 也绿，只有「重新物化后的合并树首跑」能抓到它**
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **权限域三收口**：`PermissionModule` 号段单一事实源 + 授权页 UX + 权限字面量类型生成（`permission.gen.ts` 漂移即红）
2. **契约自带权限维（全链闭环）**：后端 `@RequirePermission` → OpenAPI `x-required-permission{code,name}` / `x-permission-status: OPEN`（279 operation，与 `PermissionCoverageScanner` **集合相等**，`OpenApiPermissionContractParityTest` 守）→ 前端 `endpointPermission.gen.ts`（type-only）→ `uiEndpointPermissions.ts` 小表（编译期 `satisfies` 钉死）→ `Permission.vue` 的 `endpoint` prop（未登记 **fail-closed**）
3. **近重复交换率有价目表（未改任何常量）**：`[NEAR_DUPLICATE_FLOOR=0.9887, threshold=0.9944)` 三档降权 `×0.75/×0.6/×0.5`；**机制决策 = UNDECIDABLE-BY-DATA**（`near-dup-mechanism-decision-v1.md`）+ **交换率 = EXCHANGE-RATE**（`near-dup-exchange-rate-v1.md`，锚唯一写入口 `NearDuplicateExchangeRateDecisionTest`，守卫 `…GuardTest`）；**档位待 Owner 拍板**，`ImageSimilarityGate` 全常量**零改动**
4. **图片四开关门控关系与热生效已实证**：`image-slice-ingest.enabled` + `kb-allowlist` 门控在前、`image-vector-ingest.enabled` 在后，只开后者链路完全不动；四键运行期热生效、写回即停（`ImageVectorSwitchHotFlipIT` 七条断言）
5. **开关开启通道已工具化**：`image-vector-ingest-preflight.sh` 把 §9 人工核对单变成可判定脚本（GO/NO-GO + 原因码），与 §10 观测面、§11 回滚、§12 热生效实证、§13 工具化说明成套
6. **图像路独立相似度硬闸门**（独立键 0.9944 + 三档 tier + 下限 0.9887 + 重排后降权）
7. 其余已闭环能力面（多模态真共享空间 / v6.0 主套件真跑 / PDF 图文评测 / 五路语义 hook / 公海 CAS / 写端点校验 / 向量运维收口 / 线索漏斗 V42 / 超管 token 视图等）沿用上一版，不再复述

---

## 3. 当前在途与候选队列（evidence-to-operations 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**；**待办池**：**0 条**
- **长期储备（按优先级）**：
  1. **拍板近重复档位（Owner 决，非代码）**：价目表已备（`docs/rag-quality/near-dup-exchange-rate-v1.md`）——`0.982` 最高效（+8.33pp 换 1 张同文档图）、`0.98` 便宜（+9.17pp 换 2 张）、`0.90` 换满（94.17%，代价 +6 张）。**免费档不存在**；`S_same` 仍是**有害量上界**（A2 未测共现）。动 `NEAR_DUPLICATE_FLOOR` **必须新卡**（会改生产常量）
  2. **查询-相关性真值语料**：把 `S_same` 的上界换成真值（真实查询 + 相关性标注），同时给「泄漏是否真伤害检索」一个直接度量；这是唯一能消掉 A2 假设的路
  3. **生产开关运行期打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`；先跑 `bash scripts/rag-quality/image-vector-ingest-preflight.sh preflight --snapshot <四键快照>`，执行单 `docs/multimodal-gray-rollout.md` §9/§10/§11/§12/§13
  4. **前端小表随站点增长维护**：`uiEndpointPermissions.ts` 手写 57 项但编译期受契约约束；新增按钮走同一条派生链（生成物自动，小表手补）
  5. 文档类存量：全域权限清单（`docs/permission-matrix-audit.md` 属**历史快照，遵裁决不改写**）；在线考试子域 `work/_online-exam-backlog.md`；候选总账 `work/_paa-candidates-pool-v2.md`
- **下批推荐**：储备 2（真值语料）或 1（Owner 拍板后开新卡改档），或由 Owner 另指定主题

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责侦察、架构、写卡、复核裁决、M1/M2 收口；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权 `git push` / 改写历史 / 删分支 / 生产外呼 / 改库结构；**禁改已合入迁移脚本**
3. **任务卡派发**：`work/README.md` 目标约束型短卡；提示词写死写集、修复上限 1 次、回报 ≤300 字、细节留文件；执行与复核子 Agent **一律 Owner 亲派**
4. **复核强度分档**（`docs/review-intensity-policy.md`）：C 档审 diff；B 档门禁 0 + 审 diff；A 档（迁移/权限/真跑/**契约**）独立复核四判据
5. **合并闭环纪律**：`merge --no-ff` 保留分支 → **树哈希逐字节判等**（相等只免「合并前再验」）→ **干净树上 `mvn clean verify` + `--update` 重锚** → push → CI 6/6 → M2 归档
6. **「跑不了」结论一律挂 CI 条件**：本地装不下的构建/真跑不得直接"接受"，须由 CI 对应 job 定案（本卡 `Build Budget` 即范例，实跑绿故 G2 未回退）
7. **映射/生成物铁律**：分类逻辑先证数据侧实际形态；生成物必须由**默认跑的校验测试**守住
8. **证据纪律**：补勾依据每个数字必须能独立复算；基线 `source-revision` 必须无 `-dirty`；**预注册门槛必须锋利**
9. **数据质量先于结论**：「不可能事件」先怀疑生成管线，再谈统计
10. **量纲白名单**：任何"单调性/分布"结论必须写明**量纲 + 口径 + 来源表**
11. **判据即合同**：判据两支可重叠、或出现"或反之"这类本方向无意义的词，当场消歧并附可执行断言；事后消歧只能朝**更保守**方向
12. **行尾即判据（本卡新增）**：会做**逐字节比对**的锚/生成物，序列化必须钉死 LF 并与 `.gitattributes` 的 `text eol=lf` 一致；否则「干净 checkout」与「刚跑过写模式」结论相反（Jackson 默认缩进器用 `System.lineSeparator()`，Windows 写 CRLF）
13. **复核 PASS ≠ 可交付（本卡新增）**：`clean verify` 必须在**重新物化后的合并树**上跑（checkout 会重写文件）；分支已绿、复核已绿都不能替代这一步
14. **环境陷阱**：中文 UTF-8 文件的**逐行计数不要信 PowerShell `Get-Content/Select-String`**，用 `read` 工具按物理行核；含中文的 `sed/grep` pattern 进命令行易 127，改 ASCII；bash 一律走 `D:\git\Git\bin\bash.exe -lc`

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`
- 数据库迁移：`ls src/main/resources/db/migration`（实测最高 **V43**，下一可用 **V44**）
- 历史裁决归档：`docs/archive/logbook-historical-202609-202610.md`；活动日志 `docs/main-agent-logbook.md`（**最新 §157**）
- 历史任务卡：`work/archive/closed-cards/`；本卡卡面 `openspec/changes/archive/evidence-to-operations/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- **G1**：交换率锚 `docs/rag-quality/near-dup-exchange-rate-v1.json` + md（复算 `bash scripts/rag-quality/near-dup-exchange-rate-report.sh`）；上游 `near-dup-mechanism-decision-v1.json` / `image-threshold-calibration-v2.json`（SHA-256 `82d24204…`）
- **G2**：`frontend/src/types/endpointPermission.gen.ts`（写入口 `EndpointPermissionCatalogGenTest`，`-Dpermission.endpoints.update=true`）+ `frontend/src/constants/uiEndpointPermissions.ts` + 站点审计 `PermissionSiteAuditTest`
- **G3**：`scripts/rag-quality/image-vector-ingest-preflight.sh` + `scripts/tests/image-vector-ingest-preflight-selftest.sh` + `MultimodalRolloutKeysDocConsistencyTest` + 执行单 §13
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json` + `frontend/scripts/check-bundle-budget.mjs`
- 计量与超管视图：`docs/token-metering.md`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
