# 规范增量：frontend-workspace

## ADDED Requirements

### Requirement: 前端工作区作为纯拷贝引入
系统（仓库） SHALL 通过拷贝 D:\code\crm\font\crm-front 当前工作树到 frontend/ 目录引入前端 workspace。拷贝 MUST 排除 node_modules、dist、.git、.env、src/api/axios、test-results、playwright-report、.worktrees、.zcode、.trae、*.log、openapi-ts-error-*.log 。MUST 保留 src/、public/、e2e/、tests/、spec/（含未跟踪前端提案如 add-ai-assistant-frontend）、docs/、package.json、pnpm-lock.yaml、vite*、openapi.yaml、tsconfig*、.githooks/、AGENTS.md、.env.example 等。

#### Scenario: 拷贝后结构正确
- **GIVEN** 源 crm-front 工作树
- **WHEN** 执行拷贝到 frontend/
- **THEN** frontend/package.json 存在
- **AND** frontend/src/ 存在且非空
- **AND** frontend/spec/changes/add-ai-assistant-frontend/ 等存在
- **AND** frontend/node_modules/ 不存在
- **AND** frontend/dist/ 不存在
- **AND** frontend/.env 不存在
- **AND** frontend/src/api/axios/ 不存在

### Requirement: 根 .gitignore 精确更新
仓库根 .gitignore SHALL 增加 frontend/ 下的构建/本地产物 ignore 条目，但 MUST NOT 忽略整个 `frontend/` 目录。

#### Scenario: 忽略条目
- **GIVEN** 追加 frontend/node_modules/ 、frontend/dist/ 、frontend/src/api/axios/ 、frontend/.env 、frontend/.env.* 等
- **WHEN** git status / add
- **THEN** node_modules 等不被 git 跟踪
- **AND** frontend/package.json 可被跟踪
- **AND** 无 "frontend/" 整目录规则

### Requirement: 验证命令与约束
执行 MUST 先 git checkout master ; git checkout -b feature/add-frontend-workspace 。
验证 MUST 运行：
  $env:DASHSCOPE_API_KEY=''
  cd frontend; pnpm install; pnpm type-check:check
  mvn -B -ntp test
surefire 合计 MUST 仍为 653（ci.yml Java 三处数字不动）。
type-check 失败 SHALL 记录为 HANDOFF，不修复 Vue 业务代码。
禁止：改 Java、改 ci 数字、跑 54 条基准、实现知识库页、提交排除项、push。

#### Scenario: 验证通过基线
- **GIVEN** 拷贝完成、gitignore 更新
- **WHEN** 按指定命令验证
- **THEN** mvn test 输出显示 Tests run: 653
- **AND** frontend/package.json 存在
- **AND** git status 显示未提交 node_modules 等

### Requirement: 提案三件套随首提
openspec/changes/add-frontend-workspace/ 下的 proposal.md、tasks.md、specs/frontend-workspace/spec.md MUST 随首个 feature commit 入库。后续合并用 --no-ff 。

#### Scenario: 首提包含
- **WHEN** 首个提交在 feature/add-frontend-workspace
- **THEN** 提案三件套在 commit 中
- **AND** merge 后 --no-ff 保留边界

## Non-Goals (explicit in proposal)

- 不实现前端功能或知识库页。
- 不改后端 Java / Spring / Flyway。
- 不修改 ci.yml surefire/failsafe 数字。
- 不跑 RAG 基准。
- 不 push。
- 不使用 subtree/submodule。
