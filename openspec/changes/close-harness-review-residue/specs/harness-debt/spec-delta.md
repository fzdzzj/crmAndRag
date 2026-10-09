# Spec Delta：harness 评审残留治理收口（close-harness-review-residue，卡 P-af）

> 基线：master@b47593c（P-ae 已合入）。本卡为工程治理卡，收口 harness 2026-09-30 评审 6 项发现中的 #1 残留 / #2 / #5 三项（#3 已由 §70.4 消解、#4 已由 owner 的 AGENTS.md 更新消解、#6 openspec/spec 双轨归属为 owner 拍板项不在本卡）。

## 契约 1：IDE/agent 资产目录忽略封闭

`.gitignore` 既有各段逐字零改动，仅既有 `# ---- IDE ----` 段追加 `.trae/`、`.trae-tmp/`、`.workbuddy/` 三行；`work/*` 白名单框架（`!work/task-card-*.md` / `!work/handoff-*.md` / `!work/README.md`）逐字保留。上述目录出现于仓库根时，`git status` 不得将其计为未跟踪噪声（`git check-ignore` 逐目录实测命中）。

## 契约 2：ci.yml comment-only

头注修正仅限 `#` 注释行：`name: CI` / `on:` / `jobs:` / steps 逐字节不动（diff 自检全部变更行为注释行）。头注口径必须与远端实测一致（执行时 `gh run list` 为准，不照抄立项文案数字）；最早两次 failure（36229004762 / 36522264015）保留为史实不删；「合并前的真实验收边界仍在本地：`bash scripts/merge-gate.sh`」表述保留；悬空的 `docs/main-agent-execution.md §18` 引用改指 `docs/main-agent-logbook.md §18`。

## 契约 3：AGENTS.md 单行事实修正

仅 L67 主协作树角色行修正为执行文档双轨现实：主树承载 `docs/main-agent-logbook.md`（实时执行日志，未跟踪）与 `work/`；`docs/main-agent-execution.md` 收敛为 master 上的方法论手册。该行其余语义（严禁直接修改 src/ 或执行代码合并、`frontend/typed-router.d.ts` 严禁碰触）逐字保留；全文件仅此一行改动，零规则语义变化。

## 契约 4：work/ 三分治理与零删除铁律

- **保留原位**：白名单三类（task-card-* / handoff-* / README.md）+ 被 master 已合入文本（openspec/**/tasks.md §0、docs/、scripts/、AGENTS.md）grep `work/` 命中且在主树存在的路径 + `work/_stale-openspec-drafts/`（§70.4 attic）；
- **归档不删**：其余未引用废料 Move-Item 移入 `work/_archive-P-af/` 同名子路径，manifest 全表落 `work/_archive-P-af/_MANIFEST.md`，移动完全可逆；
- **删除类单列授权**：`work/AiChatStreamErrorRecovery.java.bak`、`work/AiChatStreamFinalizer.java.bak` 与 `.qoder/worktrees/` 14 目录本卡一律不删，落证清单停步请求 owner 授权；授权后由 owner 指定主体执行删除并回填本卡 §0；
- **移动可逆性守卫**：主树 work/ 递归文件计数移动前后一致（1796）；主树 tracked 文件零 M 零 D；引用命中路径逐条原位复核（引用零断链）。

## 契约 5：测试基线零变化（硬验收）

本卡零测试文件改动，不适用红测试先行，以现状实测清单（任务组 1）替代前置取证。surefire 1075/1075 全绿 0 失败 0 跳过、failsafe 27 类 98 例 6 跳零变化；`test-baseline.txt` 零更新（`check-test-baseline.sh` 不带 `--update` 必须通过）；benchmark（RagRealRetrievalBenchmarkIT / 金标 fixtures / SUITE_VERSION）零触碰；零真实外呼（跑测试前 DASHSCOPE_API_KEY 置空字符串）。

## 契约 6：写集、合并与回补纪律

- 写集恰 7 tracked 文件：`.gitignore` / `.github/workflows/ci.yml` / `AGENTS.md` / 本卡三件套 / `docs/harness-summary-2026-09-30.md`（随卡入库，其 untracked 自述措辞同步更新，其余内容逐字保留）；frontend/、src/、pom.xml、scripts/、db/migration 零触碰；check-write-set 锚 b47593c；
- 提交按任务组分笔中文信息；merge --no-ff 合入 master、分支保留、**绝对禁止 git push**；
- 7.4 与 §8 复核区未勾格为终态未勾，回补载体为 owner 指定的后续 master 前向提交（沿 P-ac/P-ae 契约惯例），不构成悬空。
