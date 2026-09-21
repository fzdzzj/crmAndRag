# 规范增量：harness-gates

> 变更：`operationalize-harness-gates` ｜ 日期：2026-09-21
> 需求↔发现映射（来自本轮 `better-harness` 评审）：R1←`static-analysis-gates-disabled`+`ci-gates-no-trigger`(声明侧) ｜ R2←`commit-hook-inactive` ｜ R3←`frontend-tests-ungated` ｜ R4←`ci-gates-no-trigger`(执行侧) ｜ R5←`merge-checklist-stale-targets`

## ADDED Requirements

### Requirement: R1 门禁声明必须有生效触发点与读者
任何被表述为"必须通过 / 失败即阻止合入 / 有阈值"的检查，MUST 同时满足三件事，否则该表述不得存在：① 指明**真实触发点**（会自动执行的位置，或一条本地必跑命令）；② 每个阈值变量或数字 MUST 有可 grep 到的**唯一读者**（构建配置或判定脚本），禁止注入后无人读取；③ 被临时关闭的门禁 MUST 在被跟踪文档登记「项 + 关闭理由 + 复测命令 + 到期触发条件」，禁止只存在于 commit message。

#### Scenario: 装饰性阈值被判违规
- **WHEN** CI 注入了某个阈值环境变量，而全仓（排除历史交接记录与工作副本快照）grep 不到任何读取它的构建配置或脚本
- **THEN** 该检查视为未生效，必须补齐读者或删除该阈值声明，不得以"以后会用"保留

#### Scenario: 临时豁免只写在提交信息里
- **WHEN** 某门禁插件被配置为跳过，唯一记录是引入提交的正文
- **THEN** 不满足本要求；必须在被跟踪文档登记豁免四要素，并在到期触发条件命中时复测

#### Scenario: 无触发通道时的声明改写
- **WHEN** 门禁定义所在平台的触发通道在本仓不可用（如无远端）
- **THEN** 声明必须改写为指向本地必跑命令，不得保留"失败即阻止合入"的无条件表述

### Requirement: R2 提交期钩子两侧共存且可实证
`core.hooksPath` 的生效目录 MUST 同时承载前端提交检查与仓内既有工具钩子，二者 MUST NOT 互相顶掉。注册 MUST 由唯一安装器（`frontend/scripts/setup-hooks.mjs`）幂等完成，被转发目标不存在时 MUST 静默跳过而非报错。仓库文档 MUST 给出可运行的判别式，且该门禁的启用结论 MUST 以一次"坏改动被实际阻断"的实测为凭，不以配置值存在为凭。

#### Scenario: 判别式与文档期望一致
- **WHEN** 执行文档声明的门禁启用判别式
- **THEN** 生效 hooks 目录（由 `git config --get core.hooksPath` 或其缺省位置确定）内存在 `pre-commit`，且它能被追踪到实际转发调用 `frontend/.githooks/pre-commit`；同目录内既有工具钩子仍可执行。仅"配置值看起来对"或"钩子文件存在"均不满足本要求

#### Scenario: 配置存在但未真正阻断
- **WHEN** 仅有钩子文件或配置项存在，没有一次被阻断的提交实测记录
- **THEN** 不得声称提交期门禁已生效；MUST 补一次"故意提交被 `precommit:check` 拒绝的改动 → 提交非零退出 → 还原改动"的实测（禁止 `--no-verify`）

#### Scenario: 被第三方工具改写后不可分辨
- **WHEN** 生效钩子目录被其他工具重写，导致前端检查不再执行
- **THEN** 从工作副本外观必须可判别（安装器输出实际值 + 文档给出复验命令），且重新注册为幂等操作，不产生第二套竞争的安装路径

### Requirement: R3 被跟踪的前端单测必须属于自动执行的门禁
`frontend/` 下被版本控制跟踪的单元/组件级用例 MUST 可由一条在仓库文档中声明的命令执行，且 MUST 被至少一个会自动执行的检查覆盖。端到端轨道 MUST NOT 被静默纳入默认执行路径（其依赖真实后端与外部凭据，成本口径另受 `openspec/git-workflow.md` 成本闸门约束）。

#### Scenario: 用例存在但无入口
- **WHEN** 存在被跟踪的 `*.test.ts`，但 `frontend/package.json` 无对应脚本、且没有任何自动检查调用它
- **THEN** 视为未受门禁覆盖；MUST 复用文档已声明的命令口径补最小入口并接入既有检查，不新造并行命令

#### Scenario: 单元轨失败被自动拦截
- **WHEN** 前端单元测试断言失败
- **THEN** 承载它的检查步骤非零退出并指出具体用例，无需人工偶然发现

#### Scenario: e2e 不被默认执行
- **WHEN** 默认门禁序列运行
- **THEN** Playwright/e2e 轨道不被执行，且这一排除是显式声明的（不是因缺配置而静默跳过）

### Requirement: R4 合并前本地聚合门禁必须自带断言与自测
当真实验收边界落在本地时，"合并前必须通过"的序列 MUST 收敛为一条仓库自有聚合命令，MUST 具备：具名失败出口（指出哪一子门禁失败）、幂等只读语义（不改数据、不访问外网）、以及可运行的自测。写不出可信自测的聚合门禁 MUST NOT 落地。

#### Scenario: 子门禁失败时聚合命令必须变红
- **WHEN** 任一子门禁（单测全绿、基线裁决、按需集成测试）失败
- **THEN** 聚合命令非零退出并指名失败的子门禁；自测脚本覆盖此场景

#### Scenario: 全绿时聚合命令零退出
- **WHEN** 所有子门禁通过
- **THEN** 聚合命令零退出，且输出可作为合入证据被记录（含实测数字与口径）

#### Scenario: 缺自测的门禁脚本不许存在
- **WHEN** 新增聚合门禁脚本但 `scripts/tests/` 下无对应自测
- **THEN** 该任务降级为仅修正声明，不得留下无自测的门禁 owner

## MODIFIED Requirements

### Requirement: R5 测试基线数字的唯一权威源
（原口径：`openspec/project.md` 与 `openspec/git-workflow.md` 规定"测试基线数字一律以 `.github/workflows/ci.yml` 为准"。现该文件已不含任何硬编码阈值，故权威源改述如下。本需求与 `docs/migration-runbook.md` §72 既有口径同义，不重复其正文。）

回归基线阈值 MUST 只存放在 `scripts/test-baseline.txt`，且 MUST 只允许由 `scripts/check-test-baseline.sh --update` 从一次真实运行写入；该文件第 1 行与判定脚本 `:13` 的"不要手改"约束对任何提案生效。裁决口径四项：失败与错误必须为 0、测试总数不得少于基线（防丢测试）、跳过数不得高于基线（防悄悄跳过）。活治理文档（`HANDOFF.md`、`openspec/project.md`、`openspec/git-workflow.md`、根 `AGENTS.md`、`docs/**`）受三条约束：① 权威指针 MUST 指向真正持有该值的位置，不得指向已不再持有它的文件；② 若文档内确实要出现数字，MUST 同处标注测量日期并声明其不作验收口径、以权威文件及其变更历史为准；③ MUST NOT 把数字写成等式或下限断言。本地与 CI MUST 是同一条判定命令，以便在无 CI 触发通道时结论可本地复现。

#### Scenario: 文档与权威文件冲突
- **WHEN** 任一被跟踪文档声称基线以 CI YAML 为准，而 CI YAML 已不含阈值
- **THEN** 该文档必须改述指向 `scripts/test-baseline.txt`；执行者 MUST NOT 因文档指引而去手改阈值文件

#### Scenario: 带日期且声明不作口径的快照引用被允许
- **WHEN** 某运维文档为说明历史口径而保留一组数字，同处标注了测量日期、并声明"以权威文件及其变更历史为准、本文不复制数字作验收口径"
- **THEN** 满足本要求，不得被当作违规数字清除（防止把"引用式改写"误做成"删光数字"）

#### Scenario: 收尾清单指向已消失的编辑目标
- **WHEN** 合并收尾清单要求修改一个在当前 CI 定义中不存在的步骤或字段
- **THEN** 该条目必须删除或改写为一次带 `--update` 的真实运行流程；照清单执行时每一步都必须能落到当前存在的文件或命令上

#### Scenario: 基线只增不减
- **WHEN** 一次变更后测试总数低于基线
- **THEN** 裁决失败（防止测试被静默删除）；若为有意变更，MUST 由真实运行重新写入基线并在提交信息说明，不许直接编辑数字

#### Scenario: 不增减用例的提案不得改动基线
- **WHEN** 某提案声明不新增/删除 Java 测试用例
- **THEN** 合入前 `scripts/test-baseline.txt` 的计数必须与其一致；发生变化即视为该提案越界，须回退或补正范围说明
