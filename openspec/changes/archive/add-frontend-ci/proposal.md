# Proposal — add-frontend-ci

## 变更目标
在根 .github/workflows/ci.yml 中增加独立的 frontend job，仅负责前端质量门禁（lint + type-check），不影响现有 Java 门禁。
- 独立 job，使用 working-directory: frontend
- 步骤：pnpm install --frozen-lockfile ; pnpm lint:check ; pnpm type-check:check
- Node 版本与 pnpm 版本从 frontend/package.json + frontend/.github/workflows/ci.yml 确定（22 / 10），不臆造
- Java job 的三处数字（surefire 653、failsafe 13 及相关基线描述）与合入前逐字相同，严禁改动
- 确认 frontend/ 已在 master（dir 存在，当前分支 master）
- 本地执行验证：cd frontend; pnpm lint:check; pnpm type-check:check 以及 mvn -B -ntp test 仍 653
- 分支 feature/add-frontend-ci；merge --no-ff；不 push
- 禁止：默认跑 Playwright、DashScope、改 Vue 页面、改 surefire/failsafe 数字

## 范围边界
- 只改根 .github/workflows/ci.yml （增加 job）
- 不改 frontend/ 任何 Vue/代码/页面
- 不改 pom.xml 或 Java 测试计数
- 不改其他 workflow
- 验证命令严格本地执行并确认数字

## 验收
- CI 中出现独立 frontend job
- 报告 job 名、Node 版本、Java 基线未改证据（grep 653/13 原文）
- 本地验证通过，git merge --no-ff 产生 hash
- 仅 frontend workspace 存在于 master（未跟踪文件不提交本变更）
