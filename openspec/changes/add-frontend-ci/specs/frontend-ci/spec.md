# Spec — frontend-ci

## 变更点
- 根仓库 .github/workflows/ci.yml 新增 job：frontend-quality（或 frontend-lint-type-check）
- Job 配置：
  runs-on: ubuntu-latest
  steps 包含：
    - checkout@v4
    - pnpm/action-setup@v4 (version: 10)
    - actions/setup-node@v4 (node-version: 22, cache: pnpm)
    - run: pnpm install --frozen-lockfile   (with working-directory: frontend)
    - run: pnpm lint:check
    - run: pnpm type-check:check
- 使用 defaults.run.working-directory: frontend 或每 step working-directory
- 触发继承根 on: （push master/feature/** , pr）
- 不包含 build、gen:api、playwright/e2e（禁止默认跑）
- Java build-and-gate job 内容 100% 逐字保留（包括所有注释中的 653、13 及历史说明）

## 版本来源（不可臆造）
- Node: 22
- pnpm: 10
  来自 frontend/.github/workflows/ci.yml 原文
- package.json 无 engines 字段，scripts 确认 lint:check / type-check:check 存在

## 验证要求
- 本地：pnpm checks 成功（或记录失败原因作为 HANDOFF）
- mvn -B -ntp test 报告 Tests run: ... 653
- CI yaml 变更后 grep 确认 "653" "13" 仍为原文上下文
- git log 显示 feature/add-frontend-ci + --no-ff merge

## 边界
- 不新增 frontend 内 workflow 改动
- 不影响现有 e2e-official.yml
- 不改任何 Java / pom / 测试类
- frontend/ 保持 untracked 状态（不 git add frontend/ ）
