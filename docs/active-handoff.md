# 主 Agent 轮换交接活看板 (Active Main Agent Handoff)

> **更新时间**：2026-10-23（retrieval-and-contract-hardening 三合一卡：近重复机制决策 + 图片开关实证 + 契约权限生成化，**A 档**）
> **维护契约**：**本文件必须采用整体覆盖更新 (Rolling Overwrite)，严禁无限追加历史流水账**！
> **适用对象**：**新打开主 Agent 对话窗口时第一必读入口**。读完即可立即进入指导工作状态。

---

## 1. 现状快照 (Current State Snapshot)

- **当前主干版本**：`master` = **`e5a4645`（M1 合并）+ `acc7142`（基线重锚）+ 本归档笔**，= origin/master。分支 `feature/retrieval-and-contract-hardening` **永久保留**（基点 `5bb8b05` → 4 笔：`50b7603` 立项 / `95e6dbe` 三组交付 / `d71d7f0` C3 口径修复 / `1bf7881` C5 判定修复）
- **测试基线四元组**：`source-revision=e5a4645`（**树净，非 `-dirty`**）+ Surefire **报告 332 / 用例 2077 / F0 / E0 / S0** + Failsafe **报告 44 / 用例 145 / F0 / E0 / S13** + `CI=#175`（Run 39965760454，**6/6 全绿**）。本卡前 328/2059 + 43/144 → 332/2077 + 44/145（+4 报告 +18 例 +1 IT）
- **本卡规模**：**21 文件 / +16596 行**（`git diff --stat 5bb8b05..e5a4645`）；merge 树 == 分支 tip 树 == **`850376e79a5835061e1d51fdb0b7263f66282aa1`**（`git show -s --format=%T` 逐字节相等）。**注意**：树等价只免"合并前再验"，**不能免基线重锚**——本卡仍在干净合并树上跑了 `mvn clean verify`（BUILD SUCCESS）+ `--update`
- **三组结论（同一分支内按组判定，互不连坐）**：
  - **G1 近重复机制 → UNDECIDABLE-BY-DATA**：C1=0.90 / C2=0.02 **关节不可达、两侧可达**——`floor=0.98` 在**现有 2% 泄漏上限内**把保留率从 **58.3% 抬到 67.5%**（L=1.81%，+9.2pp）；够到 90% 保留需把泄漏放宽到 **3.26%**（`floor=0.90`）。零外呼（复用已入仓 540 对余弦 + 仓库内 3 份 PDF），网格 M1 200 / M2 25 / M3 325，帕累托 8/13/12
  - **G2 图片四开关 → 七条断言全绿**：A1/A2/A2b/A3/F1/F2/N1 在同一上下文同一 bean 内完成（`assertSame` 6 处），**实证"运行期热生效、无需重启"与"写回 false 即停"**，零真外呼
  - **G3 契约权限维 → P1/P2/P4 绿**：279 个 operation（SECURED 259 + OPEN 20），`x-required-permission{code,name}` / `x-permission-status: OPEN` 由 `@RequirePermission` 自动生成；契约 `4825A786…`、customizer `DAD0AC6D…`；`src/main` 精确 2 文件
- **本卡两轮复核（A 档，Owner 亲派）+ 两次指导侧退回**：首轮四判据 PASS 但被退回两条（C3 基线把**余弦中位数**单调性误当**逐 kind 保留数**单调性；customizer 哈希在 pmd 缩短注释后未重取）；第二次修复又由指导侧主动推翻结论（`verdict()` 把"只满足一半"窄化为 `C1∧C2`，使 **NO-GO 覆盖了本应 UNDECIDABLE 的情形**，且结案措辞"已到顶"与 `floor=0.98` 的 +9.2pp 直接矛盾）。两处缺陷**根因都是判据文字本身写得会自相矛盾**，已在 `proposal.md` §10 记 v1.1/v1.2/v1.3
- **先前遗留已解除**：本地 `pnpm build` 的 esbuild 临时文件 `Access denied`（master 同红，环境级）→ 按裁定移交 CI，**#175 的 `Frontend Test / Lint / Type-check / Build Budget` 实跑绿**，G3 无需回退
- **工作树拓扑**：观察树 `D:\code\crmAndRag`（logbook + `work/` 未跟踪）；权威树 `D:\code\crmAndRag-merge-add-knowledge-admin-api`（分支 / 门禁 / 合并 / push 唯一法定树）

---

## 2. 核心架构与现行能力面

1. **权限域双收口**：`PermissionModule` 号段单一事实源 + 授权页 UX + 权限字面量类型生成（`permission.gen.ts` 漂移即红）
2. **契约自带权限维（本卡新增）**：OpenAPI 每个 operation 必带 `x-required-permission{code,name}` 或 `x-permission-status: OPEN`，由注解生成、与 `PermissionCoverageScanner`+`OpenEndpointRegistry` **集合相等**（`OpenApiPermissionContractParityTest` 守）；消费侧（前端按契约派生按钮权限）尚未做
3. **图片四开关门控关系与热生效已实证（本卡新增）**：`image-slice-ingest.enabled`+`kb-allowlist` 门控在前、`image-vector-ingest.enabled` 在后，只开后者链路完全不动；四键均运行期热生效、写回即停（`ImageVectorSwitchHotFlipIT` 七条断言）
4. **近重复三档降权（机制结论已出，未改任何常量）**：`[floor=0.9887, 阈值=0.9944)` 三等分 → `×0.75/×0.6/×0.5`；**机制决策 = UNDECIDABLE-BY-DATA**（详见 `docs/rag-quality/near-dup-mechanism-decision-v1.md`）；`ImageSimilarityGate` 全常量**零改动**
5. **图像路独立相似度硬闸门**（独立键 0.9944 + 三档 tier + 下限 0.9887 + 重排后降权）
6. **超管按用户查看 AI token 消耗**（`/ai/token-usage/by-user`，权限 810 只授 role_id=1）
7. 其余已闭环能力面（多模态真共享空间 / v6.0 主套件真跑 / PDF 图文评测 / 五路语义 hook / 公海 CAS / 写端点校验 / 向量运维收口 / 线索漏斗 V42 等）沿用上一版看板，不再复述

---

## 3. 当前在途与候选队列（retrieval-and-contract-hardening 闭环刷新）

- **在途待合入卡**：**0 张**；**已立卡待派发**：**0 张**；**待办池**：**0 条**
- **长期储备（按优先级）**：
  1. **定「保留率-泄漏率」交换率**（G1 的直接后续，**唯一有精确菜单的一项**）：数据侧已备 `M1` 前沿 8 点（`67.5%@1.81%` ↔ `94.2%@3.26%`），缺的是**查询-相关性真值或真实查询日志**（用于判断"9 对负样本被降权而非过滤"的实际伤害）。动 `NEAR_DUPLICATE_FLOOR` 前必须先有这份真值，且属新卡（会改生产常量）
  2. **前端按契约派生按钮级权限**（G3 的自然下一步）：用 `x-required-permission` 替代 TS 里手抄的权限常量，消除漂移温床
  3. **生产开关运行期打开（Owner 决，非代码）**：`rag.retrieval.image-vector-ingest.enabled` 默认 `false`；执行单 `docs/multimodal-gray-rollout.md`（§9 开前核对 / §10 开后可观测 / §11 回滚 / **§12 本卡新增的热生效实证**）
  4. 文档类存量：全域权限清单（`docs/permission-matrix-audit.md` 属**历史快照，遵裁决不改写**）；在线考试子域 `work/_online-exam-backlog.md`；候选总账 `work/_paa-candidates-pool-v2.md`
- **下批推荐**：储备 1 或 2，或由 Owner 另指定主题

---

## 4. 主 Agent 核心执行契约与协作红线

1. **角色边界**：主 Agent 负责侦察、架构、写卡、复核裁决、M1/M2 收口；**不写业务代码**
2. **硬红线**：严禁未经 Owner 授权 `git push` / 改写历史 / 删分支 / 生产外呼 / 改库结构；**禁改已合入迁移脚本**
3. **任务卡派发**：`work/README.md` 目标约束型短卡；提示词写死写集、修复上限 1 次、回报 ≤300 字、细节留文件；执行与复核子 Agent **一律 Owner 亲派**
4. **复核强度分档**（`docs/review-intensity-policy.md`）：C 档审 diff；B 档门禁 0 + 审 diff；A 档（迁移/权限/真跑/**契约**）独立复核四判据
5. **合并闭环纪律**：`merge --no-ff` 保留分支 → **树哈希逐字节判等**（相等只免"合并前再验"）→ **干净树上 `mvn clean verify` + `--update` 重锚** → push → CI 6/6 → M2 归档
6. **"跑不了"结论一律挂 CI 条件**：本地装不下的构建/真跑不得直接"接受"，须由 CI 对应 job 定案（本卡 esbuild 即范例）
7. **映射/生成物铁律**：分类逻辑先证数据侧实际形态；生成物必须由**默认跑的校验测试**守住
8. **证据纪律**：补勾依据每个数字必须能独立复算；基线 `source-revision` 必须无 `-dirty`；**预注册门槛必须锋利**（代理比例当替身会让真结论漏网）
9. **数据质量先于结论**：「不可能事件」先怀疑生成管线（底色/alpha/尺寸），再谈统计
10. **量纲白名单（本卡新增）**：任何"单调性/分布"结论必须写明**量纲 + 口径 + 来源表**——C3 事故就是"余弦中位数"被当成"逐 kind 保留数"
11. **判据即合同（本卡新增）**：写判据时若两支逻辑可重叠、或出现"或反之"这类在本方向无意义的词，必须当场消歧并附一个可执行断言；事后消歧只能朝**更保守**方向（本卡 C5 从"无机制可用"退回"关节不可达"，即为此原则）
12. **环境陷阱**：中文 UTF-8 文件的**逐行计数不要信 PowerShell `Get-Content/Select-String`**（行分隔会被按 GBK 解码吞掉），要用 `read` 工具按物理行核；bash 一律走 `D:\git\Git\bin\bash.exe -lc`

---

## 5. 权威索引指路 (Where to Look)

- 接口与冻结契约：`spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md`（本卡新增 2026-10-23 解冻登记：仅新增 `x-` 扩展）
- 数据库迁移：`ls src/main/resources/db/migration`（实测量高 **V43**，下一可用 **V44**）
- 历史裁决归档：`docs/archive/logbook-historical-202609-202610.md`（§8~§85）；活动日志 `docs/main-agent-logbook.md`（**最新 §156**）
- 历史任务卡：`work/archive/closed-cards/`
- 回归基线：`bash scripts/check-test-baseline.sh`（台账 `scripts/test-baseline.txt`，只经 `--update` 写）
- **本卡三组产出**：① 机制决策锚 `docs/rag-quality/near-dup-mechanism-decision-v1.json`（唯一写入口 `NearDuplicateMechanismDecisionTest#...-Dnear.dup.decision.write=true`，复算 `bash scripts/rag-quality/near-dup-mechanism-report.sh`）+ 决策文档 `near-dup-mechanism-decision-v1.md` ② `ImageVectorSwitchHotFlipIT` + `docs/multimodal-gray-rollout.md` §12 ③ `OpenApiPermissionCustomizer` + `OpenApiPermissionContractParityTest` + `spec-delta.md`
- 契约/体积门禁：`scripts/check-openapi-consistency.sh`、`frontend/bundle-budget.json` + `frontend/scripts/check-bundle-budget.mjs`
- 计量与超管视图：`docs/token-metering.md`

---

## 6. 主 Agent 轮换交接操作指引 (Handing Over Protocol)

- **结束会话前必须做**：① 有新卡合入或基线变动则更新 §1/§2/§3；② 任务卡归档到 `work/archive/closed-cards/`；③ 严禁追加流水账，全文 ≤120 行整体覆盖。
- **新主 Agent 第一动作**：① 读 `AGENTS.md` + 本文件；② `git status --short` 核实两棵树；③ 立即响应需求。
