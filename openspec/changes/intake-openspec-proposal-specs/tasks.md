# 任务清单：intake-openspec-proposal-specs

## 0. 执行记录（执行 agent 填，复核 agent 核）

- 分支 / 基线 / 合并节点：
- 28 文件 add 前 sha256 留证位置：
- 门禁 raw 留证位置：
- 未跑项明列：

## 1. 落盘与入库

- [x] 1.1 建分支 feature/intake-openspec-proposal-specs（基线 master@bacd842）
- [x] 1.2 28 个历史文件 add 前 sha256 留证（work/_pz-intake-hashes.txt）→ 笔 1 chore(openspec) 提交（任务卡 §2.1 清单逐文件，清单外零容忍）
- [x] 1.3 权威树重写 docs/backend-optimization-candidates.md（任务卡附录 A 文稿逐字落盘，纯 LF）
- [ ] 1.4 笔 2 docs(openspec) 提交：候选池 + 本卡三件套（tasks.md 勾选反映执行完成实况）
- [ ] 1.5 git status 终态：openspec/changes/ 下零 untracked spec 文件；work/ 与其他既有 untracked 项零触碰

## 2. 门禁（merge 前全绿留 raw）

- [ ] 2.1 mvn -B -ntp test：Tests run: 1019, Failures: 0, Errors: 0, Skipped: 0（逐字不变；DASHSCOPE_API_KEY=""）
- [ ] 2.2 四静态 0 违规：checkstyle / spotbugs / pmd:check / spotless:check
- [ ] 2.3 三守卫：check-dirty CLEAN；check-line-endings lf（31 个新入库/新建文件全 LF）；写集核验 = git diff --name-only bacd842..HEAD 与 32 文件清单逐文件相等 + git diff bacd842..HEAD -- src/ frontend/ 为空
- [ ] 2.4 测试基线台账零变动（不 --update）
- [ ] 2.5 三套自测（agent-helper / check-test-baseline / merge-gate）独立 raw 归档（P-y 复核建议落地）

## 3. 合并与停步

- [ ] 3.1 切 master → merge --no-ff（分支保留）→ git status 双确认 CLEAN → 停步回报禁 push
- [ ] 3.2 §0 执行记录填齐（合并节点 hash、门禁数字、拓扑）

## 4. 复核（复核 agent，只读）

- [ ] 4.1 拓扑：合并节点双亲 = bacd842 + feature 顶端；真 --no-ff（≠ feature 顶端、diff 为空）；分支保留
- [ ] 4.2 写集恰 32 文件（28 历史 + 1 候选池 + 3 本卡三件套）逐文件比对；src/ 与 frontend/ diff 为空；work/ 仍 untracked
- [ ] 4.3 28 历史文件与 sha256 留证逐文件一致（纯 add 零改动）
- [ ] 4.4 候选池与附录 A 文稿逐字一致（§1 方法论原文 + §2 归档表 + §3 挂账 + §4 待取证方向）
- [ ] 4.5 门禁 raw 复核：1019 逐字 + 四静态 0 + 守卫 CLEAN + 台账零变动
- [ ] 4.6 openspec/changes/ 下零 untracked spec 文件（git status 终态核验）
