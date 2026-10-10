# 任务清单：harness 评审残留治理收口（close-harness-review-residue，卡 P-af）

> 基线锚：master@b47593c（CI 第 27 轮全绿，Run 37944875404）。写集 7 tracked 文件（check-write-set 锚 b47593c）。**零删除铁律**：任何删除类动作一律停步请求 owner 授权，本卡执行面只做「归档不删」。**零测试文件改动**：surefire 1075 / failsafe 27 类 98 例 6 跳零变化为硬验收，test-baseline.txt 不 `--update`。**双工作面纪律**：tracked 改动全在权威树 feature 分支；主树 d:\code\crmAndRag 仅允许 work/ 内 untracked 移动与只读落证，禁止主树一切 git 写操作。

## 0. 执行记录（执行 agent 填，复核 agent 核）

- 分支 / 基线 / 合并节点：feature/close-harness-review-residue / 基线 master@b47593c / 合并节点 a7fd472
- 前置实测（替代红测试先行的说明）：本卡零测试文件改动，不适用红测试先行；以任务组 1 现状实测清单为前置取证，raw 归档权威树 work/_paf-first/
- work/ 归档摘要：归档 12 目录 + 493 顶层散文件（共 505 项）至 work/_archive-P-af/ ｜保留原位 4 目录（commit-msgs、mailbox、_pad-red-first、_stale-openspec-drafts）+ 57 顶层文件（11 项 master 文本引用命中 + 46 项白名单 task-card-*/handoff-*）｜递归计数移动前 1797（排除 task-card-P-af.md 为 1796）= 移动后 1797（排除 task-card-P-af.md 为 1796，且排除 _MANIFEST.md 为 1796）
- 删除授权清单（owner 2026-10-10 授权全部删除，主 agent 已执行）：work/AiChatStreamErrorRecovery.java.bak、work/AiChatStreamFinalizer.java.bak + .qoder/worktrees 14 目录（全名单见任务 5.2）——删除前自证 16 项在位与 src 正本 ×2 在位，删除后 Test-Path bak ×2 = False、worktrees 子目录 0/14，主树 tracked 零 M 零 D（证据见 docs/main-agent-logbook.md §71.3）
- 门禁 raw 留证位置：work/_paf-gate-raw/（一律显式 UTF-8 无 BOM 写出）
- surefire 基线变化：1075 → 1075（零变化，0 失败 0 跳过）
- failsafe 台账零变化：27 / 98 / 6（本卡本地未重跑——merge-gate 默认序列 with_verify=0，[it] 段 fail-closed 明列；以 P-ae 遗留报告核对通过 + CI 第 28 轮远端 failsafe 全绿为背书）

## 1. 前置现状落证（无红测试先行，本组即前置取证）

- [x] 1.1 分支自证：从权威树 master@b47593c 检出 feature/close-harness-review-residue；任何写操作前原样粘贴 `git -C <权威树> rev-parse --abbrev-ref HEAD` 与 `git -C <权威树> log --oneline -2` 自证（本机 git <2.23，无 branch --show-current / switch / restore）
- [x] 1.2 主树 work/ 全量清单落证：实测复核顶层 16 目录 + 551 文件、递归 1796 文件；产出三分初判表（保留原位 / 归档候选 / 删除类候选），删除类候选逐一核对 src/main 是否存在同名正本并备注
- [x] 1.3 引用核对 bright-line：对权威树 master 全量文本 grep `work/`（openspec/**/tasks.md、docs/、scripts/、AGENTS.md），凡命中且该路径在主树 work/ 存在一律保留原位；核对结果（命中清单）raw 留证
- [x] 1.4 .qoder/worktrees 14 目录清单落证：名称逐字、每目录大小、每目录 AGENTS.md 与根 AGENTS.md 漂移核对（fc / compare 哈希均可）、确认均不在 `git worktree list`
- [x] 1.5 ci.yml 头注现状摘录 + `gh run list --limit 20` 实测刷新口径（立项锚点 Run 37944875404 @ b47593c；以执行时实测为准，不得照抄立项文案数字）

## 2. .gitignore 增段

- [x] 2.1 既有 `# ---- IDE ----` 段 `.qoder/` 之后追加三行：`.trae/`、`.trae-tmp/`、`.workbuddy/`（收敛 09-30 评审 Finding 1 残留）；`work/*` 白名单框架与其余各段逐字零改动
- [x] 2.2 生效实测：`git check-ignore -v` 三目录命中（.gitignore:19/20/21，raw work/_paf-first/2.2-gitignore-check.txt）；主树噪声收敛为主树同步新 master 后生效的效果（主树 detached @ b47593c 沿用旧 .gitignore 时 .trae/ 与 .workbuddy/ 仍 ？?），2026-10-10 push 后主树同步时复验（见 docs/main-agent-logbook.md §71.3）
      措辞修正（2026-10-10 回补笔）：原勾格「untracked 5 项 → 3 项」为超前表述，raw 仅含 check-ignore 实测，按亲验披露点修正为同步后复验口径。

## 3. ci.yml 头注口径修正（comment-only）

- [x] 3.1 重写 L4-L14 注释叙事为实测口径：远端已常态全绿（最近 Run 锚点 + 日期 + 6/6，以 1.5 实测为准）；最早两次 failure（36229004762@2026-09-26、36522264015@2026-09-29）保留为史实；`docs/main-agent-execution.md §18` 引用改指 `docs/main-agent-logbook.md §18`；「合并前真实验收边界仍在本地：bash scripts/merge-gate.sh」表述保留；合并序列引用 openspec/git-workflow.md §3 保留
- [x] 3.2 diff 自检：`git diff` 全部变更行均为 `#` 注释行；`name: CI` / `on:` / `jobs:` / steps 逐字节零变化

## 4. AGENTS.md 一行口径修正

- [x] 4.1 L67 主协作树角色行：将「承载 `docs/main-agent-execution.md`（权威流水账）与 `work/`（任务卡与交接快照）」替换为「承载 `docs/main-agent-logbook.md`（实时执行日志，未跟踪）与 `work/`（任务卡与交接快照）；`docs/main-agent-execution.md` 已收敛为 master 上的方法论手册」；该行其余文字（严禁直接修改 src/ 或执行代码合并、frontend/typed-router.d.ts 严禁碰触）逐字保留；全文件仅此一行改动

## 5. 主树 work/ 物理治理（untracked，归档不删）

- [x] 5.1 归档移动：建 `work/_archive-P-af/`，将 1.3 引用核对未命中且非白名单（task-card-*/handoff-*/README.md）且非 `_stale-openspec-drafts/` 的废料（commit-msgs/、logs/、mailbox/、probe~probe5/、_pad-*/、_v1-out~_v5/ 及未引用散文件，以 1.3 结果为准）Move-Item 移入 `work/_archive-P-af/` 同名子路径；manifest 全表（from→to）落 `work/_archive-P-af/_MANIFEST.md`，§0 落摘要；work/README.md 若存在则追加一行归档区说明
- [x] 5.2 删除授权清单落证（本卡不执行删除，停步回报单列请求 owner 授权）：work/AiChatStreamErrorRecovery.java.bak、work/AiChatStreamFinalizer.java.bak（生产类源码副本，harness 发现 #1 点名）+ .qoder/worktrees/ 下 14 目录：agent-general-purpose-0813cb7c / 0a6ff501 / 284d3ce6 / 5563cb2e / 6273a336 / 63212aca / 83455ce2 / 9cd4ac4a / 9ff07ea1 / a9c94f30 / edbc95dc / f43942fc / f8075820 / fcd35487（harness 发现 #5）；1.2 清单若发现其他删除类候选，逐项并入
- [x] 5.3 移动后自检：主树 work/ 递归文件计数 = 1796 不变（仅位置变化）；主树 `git status` tracked 区零 M 零 D；1.3 命中引用的路径逐条复核仍原位存在（引用零断链）

## 6. 入库与提交分组

- [x] 6.1 笔 1 docs(openspec)：本卡三件套 + docs/harness-summary-2026-09-30.md 从主树复制入 feature 分支提交入库；后者文中 untracked 自述措辞（grep `untracked` 实测处数）同步更新为已入库，其余内容逐字保留；同笔完成 tasks.md 1.x-5.x 勾选与 §0 填齐
- [x] 6.2 笔 2-4 按对象分笔中文提交（各附一句 why）：`.gitignore`（chore(gitignore): IDE/agent 资产目录收敛）/ ci.yml（docs(ci): 头注口径对齐远端实测）/ AGENTS.md（docs(agents): 主协作树角色行双轨口径修正）；不使用 `git commit -- <pathspec>` 提交重命名文件

## 7. 门禁与合并（收口）

- [x] 7.1 `mvn -B -ntp test`（DASHSCOPE_API_KEY 置空字符串）：1075/1075 全绿 0 失败 0 跳过，总数与台账零偏差；`bash scripts/check-test-baseline.sh` 不带 `--update` 必须通过（台账零更新）
- [x] 7.2 四静态 0 违规（checkstyle / spotbugs / pmd:check / spotless:check）+ 三守卫（check-dirty CLEAN / check-line-endings lf <本卡 7 文件> / check-write-set b47593c 写集恰 = 7 文件清单、frontend/ 零 diff）+ `bash scripts/merge-gate.sh` 全 PASS；Docker 前提 `docker info` 实测，不在线则 failsafe 段 fail-closed 明列不掩瞒，禁止宣称「已验证」
- [x] 7.3 切 master → merge --no-ff（合并节点回填 §0，分支保留）→ `git status` 双确认 CLEAN → master 收口笔（仅本卡 tasks.md §0 回填 + 7.3 勾选）
- [x] 7.4 严格停步回报（绝对禁止 git push）：原样粘贴路径自证、git log --oneline --graph -10、git status --short、每笔 git show --stat、门禁 raw 结论行、归档前后递归计数、引用核对命中清单；结尾单列 5.2 删除授权请求
      补勾依据（2026-10-10 owner 转交执行子agent停步回报，主 agent 亲验逐项核对 PASS——拓扑/写集/diff/归档计数/门禁 raw 全吻合，见 docs/main-agent-logbook.md §71.1）；预注册回补按契约 6 于本笔完成。

## 8. 复核区（复核 agent 只读终审）

- [x] 8.1 拓扑：合并节点双亲 = b47593c + feature 顶端；真 --no-ff；分支保留；收口笔仅改本卡 tasks.md
- [x] 8.2 写集逐文件 = 7 文件清单；ci.yml diff 仅注释行（name/on/jobs 逐字节不动）；AGENTS.md 仅 L67 一行；.gitignore 仅 IDE 段 3 行；harness-summary 仅 untracked 自述措辞；frontend/、src/、pom.xml、scripts/、db/migration 零 diff
- [x] 8.3 work/ 治理核对：归档 manifest 与实物一致（抽 3 项核验）；1.3 引用核对 raw 复跑；白名单三类与 _stale-openspec-drafts/ 原位确认；.java.bak ×2 与 .qoder 14 目录仍在（未删除）；主树 tracked 零 M 零 D
- [x] 8.4 门禁 raw 复核：surefire 1075 逐字 + 台账零更新 + 四静态 0 + 守卫 CLEAN + merge-gate 逐 PASS 行；gh run list 口径与 ci.yml 头注数字一致
- [x] 8.5 §0 执行记录填齐、无悬空勾、预注册注记完整
      补勾依据（2026-10-10 owner 转交复核子agent只读终审报告：8.1-8.5 全 PASS、差异清单无差异，独立复跑 check-test-baseline 通过与 merge-gate 8 PASS，主 agent 交叉核验吻合，见 docs/main-agent-logbook.md §71.2）；8.3 为复核时点记录（当时 16 项未删，owner 同日授权后主 agent 已执行删除，见 §0 与 §71.3）；预注册回补按契约 6 于本笔完成。
