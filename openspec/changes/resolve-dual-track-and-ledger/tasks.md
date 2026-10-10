# 任务清单：openspec/spec 双轨归属定夺与台账清账（resolve-dual-track-and-ledger，卡 P-ag）

> 基线锚：master@fd4ad62（CI 第 28 轮全绿，Run 38011805147）。**零删除铁律**：归档一律 git mv（openspec CLI 既有惯例优先，实测确认），不删任何文件。**零测试文件改动**：surefire 1075 / failsafe 27 类 98 例 6 跳零变化为硬验收，test-baseline.txt 不 `--update`。**双工作面纪律**：tracked 改动全在权威树 feature 分支；主树 d:\code\crmAndRag 仅允许 work/ 内 untracked 移动与只读落证，禁止主树一切 git 写操作。

## 0. 执行记录（执行 agent 填，复核 agent 核）

- 分支 / 基线：feature/resolve-dual-track-and-ledger / 基线 master@fd4ad62 / 合并节点 a720f5f（双亲 = fd4ad62 + 2e04197，master 收口笔回填）
- 前置实测（替代红测试先行的说明）：本卡零测试文件改动，不适用红测试先行；以任务组 1 现状实测清单为前置取证（work/_pag-gate-raw/group1-evidence.txt）
- 27 格回补落位统计：P-ab 7 / P-ac 7 / P-ad 6 / P-ae 7，共 27 格逐格核验证据补勾完成，optimize-project-file-list-auth-reuse 6.1 维持未勾核验在位（work/_pag-gate-raw/group3-evidence.txt）
- 归档落位统计：26 个变更目录全部 git mv 移入 archive/；移动前 changes/ 26 目录、archive/ 28 目录，移动后 changes/ 仅剩本卡（1 目录）、archive/ 54 目录；全仓 13 文件文字引用同步更新，旧路径 git grep 零命中
- 门禁 raw 留证位置：work/_pag-gate-raw/（一律显式 UTF-8 无 BOM 写出）
- surefire 基线变化：1075 → 1075，零变化
- failsafe 台账零变化：27 / 98 / 6（本卡本地不重跑——merge-gate 默认序列 with_verify=0，[it] 段 fail-closed 明列；以 CI 远端下一轮全绿为背书）

## 1. 前置现状落证（无红测试先行，本组即前置取证）

- [x] 1.1 分支自证：从权威树 master@fd4ad62 检出 feature/resolve-dual-track-and-ledger；任何写操作前原样粘贴 `git -C <权威树> rev-parse --abbrev-ref HEAD` 与 `git -C <权威树> log --oneline -2` 自证（本机 git <2.23，无 branch --show-current / switch / restore）
- [x] 1.2 归档口径实测：changes/ 下 26 目录逐一核对「已合入 master（目录 tracked 在 master）且无悬空格」——未勾格扫描结果落证 raw（预期仅 optimize-project-file-list-auth-reuse 6.1 一处授权门控格，其 P-ab 日期化注记在位）；归档后 changes/ 应仅剩本卡
- [x] 1.3 归档惯例确认：实测 openspec 归档既有惯例（openspec CLI 是否有 archive 命令 / git-workflow.md / project.md 是否定义流程），按惯例执行；无明文惯例则 git mv
- [x] 1.4 引用影响面落证：git grep 逐目录实测引用 `openspec/changes/<待归档id>` 的文件清单 raw 落证（主 agent 立项时初测 13 文件，以执行时实测为准）；归档动作若含本卡三件套复制入库（主树 untracked 原件 → feature 分支），按 P-af 笔 1 惯例执行

## 2. 双轨归属定夺落地

- [x] 2.1 AGENTS.md「在途变更规格」段：「与 `spec/changes/` 的分工**待 owner 确认**，改检索链路前两个目录都先看」改为定论——spec/changes = 平台总纲与冻结契约轨（add-crm-rag-fusion-platform 长期活文档，契约改动走受控解冻；其余 13 目录历史规格资产只读保留不再新加）；openspec/changes = 逐卡变更唯一新卡轨（完工归 archive/）；检索链路辨权：改契约先查 spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md，功能/修复改动经 openspec 卡走；全文件仅此一处改动
- [x] 2.2 openspec 侧规则文件（以 1.3 实测为准，project.md 或 git-workflow.md）补双轨辨权一节（与 2.1 同口径）；diff 自检仅该节新增

## 3. 预注册勾格回补（27 格，P-ab spec-delta 契约 5 的回补载体）

- [x] 3.1 逐格核验补勾：P-ab 3.4/3.5/4.1-4.5（7 格）、P-ac 全部未勾（7 格）、P-ad 全部未勾（6 格）、P-ae 全部未勾（7 格）——每格先独立核验证据（合并节点 `git log --format="%H %P" -1 <merge>`、分支在册 `git branch --list`、CI 轮次 gh run list、logbook 章节锚点），再补勾 + 勾选行下一行 6 空格留痕「补勾依据（2026-10-10 P-ag 逐格核验）：证据命令与哈希」
- [x] 3.2 断言语义冻结自检（P-ab 契约 6）：diff 仅有勾选框状态、留痕行、随勾选语义更新的组状态注记；历史行原文逐字保留
- [x] 3.3 optimize-project-file-list-auth-reuse 6.1 维持未勾（P-ab 契约 3 授权门控格）：确认 P-ab 日期化注记在位、不重复注记、不虚勾

## 4. 归档整理（26 目录）

- [x] 4.1 批量归档：按 1.3 确认的惯例将 26 个变更目录移入 archive/（git mv 或 openspec CLI archive）；移动前后 archive/ 目录数与 changes/ 目录数对账落证
- [x] 4.2 引用同步：1.4 清单中的文字引用逐文件更新为归档后路径（含待归档目录自身的内部交叉引用）；更新后 git grep 旧路径 `openspec/changes/<id>`（不含 archive 段）零命中
- [x] 4.3 归档后自检：changes/ 仅剩本卡；`git status` 无游离态；P-af 复核报告等历史文档中带 archive 路径的引用不受影响（只改未归档路径引用）

## 5. 入库与提交分组

- [ ] 5.1 笔 1 docs(openspec)：本卡三件套从主树复制入 feature 分支提交入库 + 任务组 1-4 勾选与 §0 填齐（承 intake-openspec-proposal-specs 契约 5）
- [ ] 5.2 笔 2 docs(agents): 双轨归属定夺（AGENTS.md + openspec 规则文件）；笔 3 docs(openspec): 27 格预注册回补（4 张历史卡 tasks.md）；笔 4 chore(openspec): 归档整理（26 目录移动 + 引用同步）；分笔中文提交各附一句 why，不使用 `git commit -- <pathspec>` 提交重命名/移动文件

## 6. 门禁与合并（收口）

- [x] 6.1 `mvn -B -ntp test`（DASHSCOPE_API_KEY 置空字符串）：1075/1075 全绿 0 失败 0 跳过；`bash scripts/check-test-baseline.sh` 不带 `--update` 必须通过（台账零更新）
- [x] 6.2 四静态 0 违规（checkstyle / spotbugs / pmd:check / spotless:check）+ 三守卫（check-dirty CLEAN / check-line-endings lf <本卡写集> / check-write-set fd4ad62 写集逐文件比对——移动型文件以 rename 记账）+ `bash scripts/merge-gate.sh` 全 PASS；Docker 前提 `docker info` 实测，不在线则 failsafe 段 fail-closed 明列不掩瞒
- [x] 6.3 切 master → merge --no-ff（合并节点回填 §0，分支保留）→ `git status` 双确认 CLEAN → master 收口笔（仅本卡 tasks.md §0 回填 + 6.3 勾选）
- [ ] 6.4 严格停步回报（绝对禁止 git push）：原样粘贴路径自证、git log --oneline --graph -12、git status --short、每笔 git show --stat、门禁 raw 结论行、27 格逐格留痕摘录、归档前后对账与引用同步清单；任何未实际执行的命令不得出现在回报中
      预注册注记：本格与复核区 7.1-7.5 的勾选不在本卡执行时点完成（停步回报为执行终态；复核 agent 只读不改文件），回补载体为 owner 指定的后续 master 前向提交（P-ab 契约 5）。

## 7. 复核区（复核 agent 只读终审）

- [ ] 7.1 拓扑：合并节点双亲 = fd4ad62 + feature 顶端；真 --no-ff；分支保留；收口笔仅改本卡 tasks.md
- [ ] 7.2 写集：AGENTS.md 仅一处 + openspec 规则文件仅一节 + 4 张历史卡 tasks.md 仅勾格/留痕 + 26 目录纯 rename + 13 文件仅引用路径替换；src/、frontend/、pom.xml、scripts/、db/migration 零 diff
- [ ] 7.3 回补核对：27 格逐格证据独立复跑（合并节点/分支/CI 轮次）；断言语义冻结抽验；optimize-project-file-list-auth-reuse 6.1 仍未勾且注记在位
- [ ] 7.4 归档核对：archive/ 目录数 = 原数 + 26；changes/ 仅剩本卡；旧路径引用零命中；归档 manifest 与实物一致（抽 3 项）
- [ ] 7.5 门禁 raw 复核：surefire 1075 逐字 + 台账零更新 + 四静态 0 + 守卫 CLEAN + merge-gate 逐 PASS 行
      预注册注记：同 6.4，回补载体为 owner 指定的后续 master 前向提交（P-ab 契约 5）。
