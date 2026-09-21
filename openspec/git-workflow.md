# Git 工作流 — 检索链路优化提案执行契约（交接 agent 必读）

> 适用：`openspec/changes/` 下剩余提案（2–5）的执行 agent。本文自包含，与本机环境（旧版 git + PowerShell）已对齐。
> 当前基线：master @ `e24663a`（提案 1 已 `--no-ff` 合入）；**回归基线阈值只存放在 `scripts/test-baseline.txt`，裁决由 `bash scripts/check-test-baseline.sh` 完成（本地与 CI 同一条命令）**——本文与该文件之外的任何地方都不复制 surefire/failsafe 数字作验收口径，历史口径一律以该文件与 `git log -p -- scripts/test-baseline.txt` 为准；仓库**无 remote，禁止 push**（推送需用户显式授权）。

## 1. 分支模型

- 每个提案一个分支：`git checkout -b feature/<change-id>`，基于最新 master。
- 例外——提案 3 与提案 2 并行时：提案 3 分支基于提案 2 分支创建（叠罗汉），合入顺序 2 → 3。串行执行则都基于 master。
- **本机 git 版本过老，没有 `git switch`**：建分支用 `checkout -b`，切回用 `checkout master`。
- 若提交误落在 master（分支忘了建）：`git reset --soft HEAD~1`（只移指针不动文件）→ `checkout -b <branch>` → 重新提交。

## 2. 提交粒度与信息

- **提交 = tasks.md 的任务组**（一个 `## N.` 节）→ 1–2 个提交；每个提交独立可编译。
- 信息格式沿用仓库惯例 `type(scope): 中文描述`；已用 type：feat / test / chore / docs / fix。
- **tasks.md 勾选与代码同提交**：完成某任务组，勾掉对应 checkbox，一并 `git add` 进该组提交（记录随代码走，合并后无需补勾）。
- 执行中若修订 proposal/tasks 内容，随所属任务组提交，不单独开 docs 提交。

## 3. 合并回 master（缺一不合）

1. 亲验：`bash scripts/merge-gate.sh` 全绿。这一条命令就是合并前必跑序列，不要拆开来手跑后就当作跑过：
   它依次裁决 surefire 单元与契约、`spotbugs:check`（High 级）、回归基线、提交期 `pre-commit` 转发器是否在位、
   SpotBugs 豁免台账双射。需要把 failsafe 集成测试也纳入时加 `--with-verify`（本机 Docker 在线时会经
   Testcontainers 拉镜像，故默认不跑）。
   输出即合入证据：把各子门禁的实测数字抄进汇报，不许引用上一轮数字。
2. 亲验：`git status` 干净（仅允许剩两个已知未跟踪文件，见 §6）。
3. 合并：
   ```
   git checkout master
   git merge --no-ff feature/<change-id> -m "Merge branch 'feature/<change-id>'：提案N/5 <一句话摘要>"
   ```
4. `--no-ff` 保留 change 边界气泡（沿用提案 1 `e24663a` 模式）。

## 4. CI 基线 bump（凡新增测试的提案必做）

- 基线数字的**唯一存放处是 `scripts/test-baseline.txt`**，本文与 `.github/workflows/ci.yml` 都不复制它，
  也不存在"同步改 CI 里几处数字"这回事——CI 的阶段3 只是调 `bash scripts/check-test-baseline.sh` 做裁决。
- 更新方式只有一种：跑一次真实构建，然后带 `--update` 让它自己写入。
  ```bash
  mvn -B -ntp clean verify
  bash scripts/check-test-baseline.sh --update
  ```
  **禁止手改该文件的数字**（`scripts/check-test-baseline.sh:13` 与 `scripts/test-baseline.txt` 第 1 行都写着这条，
  对任何提案生效）；当前运行含 Failures/Errors 时 `--update` 会直接拒绝写入。
- 分支收尾提交信息口径：`chore(ci): 回归基线由一次真实运行重新写入——锁住提案X新增Y测试（实测见 --update 输出）`；
  failsafe 侧仅在**新增 IT 且本地实测**后才会上调（无 Docker 时哪些跳哪些红，见 `docs/migration-runbook.md` §6.2）。
- 基线 JSON（`baseline-after-*.json`）**入库**——它们是后续提案"不回退"验收的锚点。
- **新增 `src/main/resources/db/migration/` 脚本必须在同一变更内更新 `FlywayMigrationIT` 的 `EXPECTED_VERSIONS`**（该类是迁移链全集门禁；版本表漏登记 = 新脚本对门禁完全失明，CI 下限口径也拦不住）。

## 5. 成本闸门（停下来向用户要授权，不许自作主张）

- 真实模型外发调用（`RAG_BENCHMARK_REAL=1` 基线首跑 / 重嵌入 reingest / LLM 压缩试跑）：执行前必须获用户确认。
- 提案 2 的 5.1 依赖提案 1 的 4.1 基线首跑（`baseline-v1.json` 实测数字）——若未完成，做到该任务前停下向用户要授权。

## 6. 本机环境坑（已踩过，勿重复）

- PowerShell **不支持 `&&`**：链命令用 `;` 或分步执行。
- git 无 `switch`（见 §1）。
- 未跟踪遗留 `_rag优化交接.md`、`_vlm_transcribe.py`：属 rag 学习工作区（`c:\Users\fzdzzj\Desktop\rag`）的暂存物，**勿提交、勿删除**。
- `.env` / `.qoder/` 已在 .gitignore，勿动。

## 7. 通用收尾自检（每个提案合并前逐条过）

1. tasks.md 除显式"待授权"项外全勾。
2. `bash scripts/merge-gate.sh` 全绿（回归基线由其中的 `[baseline]` 子门禁裁决），把实测计数写进汇报；
   数字本身只在 `scripts/test-baseline.txt`，本文不复制，也不去 CI YAML 里找。
3. `git log --oneline --graph`：提交按任务组整齐、merge 带 `--no-ff`。
4. 汇报写明：实测测试数、baseline JSON 路径、遗留未勾项及原因（HANDOFF 教训：报完成要亲验 commit hash + status 干净）。
