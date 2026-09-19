# 提案：添加前端工作区（add-frontend-workspace）

> 变更 ID：`add-frontend-workspace` ｜ 能力域：`frontend` ｜ 序列：前端骨架引入
> 来源：D:\code\crm\font\crm-front 当前工作树（源 commit ~2a9e551 feat/usage-tour）。crmAndRag 此前无前端，此次为拷贝引入而非 subtree。

## Why

crmAndRag 项目为 CRM + RAG 融合后端，缺少前端部分。原 crm-front 已有 Vue3 + Vite + TS + pnpm 工作区，包含 src、e2e、spec（含 add-ai-assistant-frontend 等提案）、docs、package.json 等。需将前端 workspace 拷入本仓根 frontend/ 目录，作为后续前端开发的基座。

**已验证事实**：
- 源目录 D:\code\crm\font\crm-front 存在，当前工作树干净或有可接受未跟踪。
- 目标 frontend/ 尚不存在。
- 必须排除 node_modules / dist / .git / .env 等以避免提交大体积或敏感。
- 根 .gitignore 需显式列 frontend/ 下的 node_modules 等，禁止忽略整个 frontend/ 。
- 禁止改任何 Java 代码、禁止改 ci.yml surefire/failsafe 数字、禁止跑 54 条 RAG 基准、禁止实现知识库页。
- 验证需 pnpm type-check （可能失败记 HANDOFF）、mvn test 仍 653 绿。
- git 操作：checkout master → -b feature/add-frontend-workspace ；提案三件套随首提；--no-ff 合；不 push。

**当前假设**：源工作树内容即为要引入的基座（含 spec/changes 中的前端提案文件）；拷贝后前端可独立 pnpm 管理；不影响后端构建。

## What Changes

### 1. 拷贝前端工作树
- 目标：D:\code\crmAndRag\frontend/
- 使用 robocopy 拷贝当前工作树。
- 排除：node_modules、dist、.git、.env、src/api/axios、test-results、playwright-report、.worktrees、.zcode、.trae、*.log、openapi-ts-error-*.log
- 必须包含：src/、public/、e2e/、tests/、spec/（含 add-ai-assistant-frontend 等）、docs/、package.json、pnpm-lock.yaml、vite*、openapi.yaml、tsconfig*、.githooks/、AGENTS.md、.env.example 等。
- 拷贝后确认 frontend/package.json 存在，node_modules 不存在。

### 2. 更新根 .gitignore
- 追加：
  frontend/node_modules/
  frontend/dist/
  frontend/src/api/axios/
  frontend/.env
  frontend/.env.*
  frontend/*.log
  frontend/openapi-ts-error-*.log
  frontend/test-results/
  frontend/playwright-report/
  frontend/.worktrees/
  frontend/.zcode/
  frontend/.trae/
- 明确不加 "frontend/" 整目录 ignore。

### 3. 提案三件套
- 在 openspec/changes/add-frontend-workspace/ 下创建/更新 proposal.md、tasks.md、specs/frontend-workspace/spec.md
- 随首个 git commit 入库。

### 4. Git 分支与提交
- git checkout master
- git checkout -b feature/add-frontend-workspace
- 首提包含拷贝 + gitignore + 提案三件套
- 收尾：亲验 mvn test=653、git status 仅剩三个已知未跟踪件；--no-ff merge 到 master；不 push。

### 5. 验证
- $env:DASHSCOPE_API_KEY=''
- cd frontend; pnpm install; pnpm type-check:check   （失败记 HANDOFF，本单不修任何 Vue 业务代码）
- mvn -B -ntp test   确认合计仍 653（ci.yml Java 数字三处不动）

## Impact

- **新增**：frontend/ 整个目录（排除后的大小可控）；openspec/changes/add-frontend-workspace/ 三件套；根 .gitignore 条目。
- **修改**：仅 .gitignore
- **不改**：任何 Java 源码、pom、ci.yml（数字）、Flyway、RAG 基准、知识库实现、后端 controller/service。
- **不提交**：node_modules、.env、.git、dist、三个 _* 交接文件。
- **后续**：前端开发走单独提案（如 add-ai-assistant-frontend 已在 spec 中），本单只做 workspace 引入。

## 风险

- 拷贝后 pnpm install 可能因环境或 key 失败：已知，记为 type-check 失败原因，不阻塞。
- 源工作树有未跟踪前端提案：按任务要求必须带上 spec/ 。
- git detached：按指令先 checkout master。
- 根 gitignore 冲突：手动追加，避免全局 frontend/ 。

## Non-Goals

- 不实现任何前端页面/功能（包括知识库页）。
- 不改后端代码。
- 不跑 RAG 54 条基准。
- 不 bump ci.yml surefire/failsafe 数字。
- 不 push。
- 不修复 type-check 失败（本单）。
- 不做 subtree / submodule，只纯拷贝。

## 失败场景（停下汇报）

1. 拷贝后 frontend/package.json 不存在。
2. mvn test 后 surefire 不是 653。
3. 误提交了 node_modules 或 .env 。
4. 改了 Java 或 ci.yml 数字。
5. 跑了基准或实现了 kb 页。
