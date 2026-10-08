# 任务清单：close-candidate-pool-residue

## 0. 执行记录（执行 agent 填，复核 agent 核）

- 分支 / 基线 / 合并节点：feature/close-candidate-pool-residue / 基线 master@3c8a8b5 / 合并节点 ________（双亲 = 3c8a8b5 + feature 顶端）
- 候选池底稿三处 sha256 一致：主树底稿 work/_paa-candidates-pool.md = 权威树覆盖后 docs/backend-optimization-candidates.md = 提交后 git blob（________）
- 门禁 raw 留证位置：work/_paa-gate-raw/（mvn-test.raw / four-static.raw / check-dirty.raw / check-line-endings.raw / check-write-set.raw / check-test-baseline.raw / selftest-agent-helper.raw / selftest-check-test-baseline.raw / selftest-merge-gate.raw / merge-gate.raw）
- 未跑项明列：无（mvn test 1019 逐字；四静态 0；check-dirty CLEAN；check-line-endings lf 4/4；check-write-set 恰 4 文件；三套自测 + merge-gate 全过；台账零变动；未 push）

## 1. 落盘与入库

- [ ] 1.1 建分支 feature/close-candidate-pool-residue（基线 master@3c8a8b5）
- [ ] 1.2 以 work/_paa-candidates-pool.md（主 agent 预置候选池新版全文，纯 LF）整文件覆盖 docs/backend-optimization-candidates.md；覆盖后 sha256 与底稿一致（相对 3c8a8b5 恰改头部注记 + §4 两处，其余逐字节不变）
- [ ] 1.3 笔 1 docs(candidates) 提交：候选池收口 + 本卡三件套（tasks.md 此刻 1.x-3.x 未勾如实入库）
- [ ] 1.4 git status 终态：work/ 与其他既有 untracked 项零触碰

## 2. 门禁（merge 前全绿留 raw）

- [ ] 2.1 mvn -B -ntp test（DASHSCOPE_API_KEY 置空）：Tests run: 1019, Failures: 0, Errors: 0, Skipped: 0（逐字不变；数字偏离立即停步回报，禁自行修复）
- [ ] 2.2 四静态 0 违规：checkstyle / spotbugs / pmd:check / spotless:check
- [ ] 2.3 守卫：check-dirty CLEAN；check-line-endings lf 4/4；check-write-set 3c8a8b5 恰 4 文件
- [ ] 2.4 bash scripts/check-test-baseline.sh 通过且台账零变动（不 --update）
- [ ] 2.5 三套自测（agent-helper / check-test-baseline / merge-gate）独立 raw 归档
- [ ] 2.6 bash scripts/merge-gate.sh 全绿（含 [frontend-unit]；node_modules 在位不得跳）

## 3. 合并与停步

- [ ] 3.1 笔 2 docs(openspec) 提交：tasks.md 1.x/2.x/3.1-3.2 勾选 + §0 执行记录填齐（raw 位置、门禁数字、blob hash）
- [ ] 3.2 切 master → merge --no-ff（分支保留）→ git status 双确认 CLEAN
- [ ] 3.3 master 收口笔（合并后允许的一笔，承 P-z 教训）：§0 回填合并节点 hash + 3.2/3.3 勾选
- [ ] 3.4 严格停步回报（绝对禁止 git push）

## 4. 复核（复核 agent，只读）

- [ ] 4.1 拓扑：合并节点双亲 = 3c8a8b5 + feature 顶端；真 --no-ff（≠ feature 顶端、diff 为空）；分支保留
- [ ] 4.2 写集恰 4 文件（1 候选池 + 3 本卡三件套）逐文件比对；src/ 与 frontend/ diff 为空；work/ 仍 untracked
- [ ] 4.3 候选池提交内容与底稿三处 sha256 一致；相对 3c8a8b5 恰改两处（头部注记 + §4 三段证据结论，含命令与节点 hash）
- [ ] 4.4 门禁 raw 复核：1019 逐字 + 四静态 0 + 守卫 CLEAN + 台账零变动
- [ ] 4.5 §0 执行记录填齐（合并节点 hash、门禁数字、拓扑）且收口笔仅改本卡 tasks.md
