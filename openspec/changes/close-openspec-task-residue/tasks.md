# 任务清单：close-openspec-task-residue（卡 P-ab）

## 0. 执行记录（执行 agent 填，复核 agent 核）

- 分支 / 基线 / 合并节点：feature/close-openspec-task-residue / 基线 master@191d56f / 合并节点 ________（双亲 = 191d56f + feature 顶端）
- 19 格编辑落位：16 补勾 + 2 维持未勾注记 + 1 不动（run-baseline-ladder 6.2 自带定夺）
- 证据 raw 留证位置：work/_pab-evidence-raw/；门禁 raw 留证位置：work/_pab-gate-raw/
- 未跑项明列：________

## 1. 逐格核验与编辑

- [ ] 1.1 建分支 feature/close-openspec-task-residue（基线 master@191d56f）
- [ ] 1.2 任务卡 §3 证据命令逐条复跑并 raw 归档 work/_pab-evidence-raw/（任一不符立即停步）
- [ ] 1.3 按任务卡 §2 载荷逐位点应用 18 处编辑（16 勾 + 2 注记；每处先验 OLD 恰 1 次再替换；UTF-8 无 BOM、纯 LF）
- [ ] 1.4 逐文件 git diff 核对恰预期 + 全区勾数统计（7 个被改文件 `- [ ]` 计数 = 0/0/0/0/0/1/1；openspec/changes/ 其余未勾仅剩 run-baseline-ladder 6.2 与本卡在途格）

## 2. 门禁（merge 前全绿留 raw，work/_pab-gate-raw/）

- [ ] 2.1 mvn -B -ntp test（DASHSCOPE_API_KEY 置空）：Tests run: 1019, Failures: 0, Errors: 0, Skipped: 0（逐字不变；数字偏离立即停步回报，禁自行修复）
- [ ] 2.2 四静态 0 违规：checkstyle / spotbugs / pmd:check / spotless:check
- [ ] 2.3 守卫：check-dirty CLEAN；check-line-endings lf 10 文件全 OK；check-write-set 191d56f 恰 10 文件
- [ ] 2.4 bash scripts/check-test-baseline.sh 通过且台账零变动（不 --update）
- [ ] 2.5 三套自测（agent-helper / check-test-baseline / merge-gate）独立 raw 归档
- [ ] 2.6 bash scripts/merge-gate.sh 全绿（含 [frontend-unit]；node_modules 在位不得跳）

## 3. 合并与停步

- [ ] 3.1 笔 1 docs(openspec) 提交：7 历史卡文件回补 + 本卡三件套（tasks.md 此刻 1.x-3.x 未勾如实入库）
- [ ] 3.2 笔 2 docs(openspec) 提交：tasks.md 1.x/2.x/3.1-3.2 勾选 + §0 执行记录填齐
- [ ] 3.3 切 master → merge --no-ff（分支保留）→ git status 双确认 CLEAN
- [ ] 3.4 master 收口笔（合并后允许的一笔）：§0 回填合并节点 hash + 3.3 勾选
- [ ] 3.5 严格停步回报（绝对禁止 git push）
      预注册（本卡 spec-delta 契约 5）：本格与 §4 复核区为终态未勾，回补载体为 owner 指定的后续 master 前向提交，带此注记的未勾格不构成悬空。

## 4. 复核（复核 agent，只读；按 spec-delta 契约 5，本区勾选由后续回补笔处理，本卡内维持未勾）

- [ ] 4.1 拓扑：合并节点双亲 = 191d56f + feature 顶端；真 --no-ff（≠ feature 顶端、diff 非空）；分支保留
- [ ] 4.2 写集恰 10 文件（7 历史卡 tasks.md + 3 本卡三件套）逐文件比对；src/ 与 frontend/ diff 为空；archive/run-baseline-ladder 不在写集；work/ 仍 untracked
- [ ] 4.3 18 处编辑逐字与任务卡 §2 载荷一致；16 处留痕与 2 处注记逐字核验；每处留痕中的证据命令独立复跑逐条吻合；历史行原文与组状态注记外记录零改动
- [ ] 4.4 门禁 raw 复核：1019 逐字 + 四静态 0 + 守卫 CLEAN + 台账零变动
- [ ] 4.5 §0 执行记录填齐（合并节点 hash、19 格落位统计、raw 位置）且收口笔仅改本卡 tasks.md
