# Repository Guidelines

本文件是 `frontend/` 的唯一规则权威源（canonical）。`CLAUDE.md` 与 `AGENT.md` 只做指路，不再各自维护规则正文。

## Critical Rules

- **Tailwind CSS only** — 不在 Vue 组件里写 `<style>` 标签。
- **PascalCase** for component names and file names（不要 kebab-case）。
- **API calls through hooks** — 组件内不直接调 API，包一层放 `src/hooks/`。
- **Type-check after changes** — 改完跑 `pnpm type-check:check`（本仓所有类型检查一律走 pnpm，不要手敲 `npx vue-tsc`，见下方命令清单）。
- **Date format**: `yyyy-MM-dd HH:mm:ss`。
- **读取文件时不要忽略首行**。
- **记录测试检查点** — 每次新增/修改/删除功能时，在 `docs/test-checkpoints.md` 追加 Playwright 检查点（无需立即执行，累积后统一批量验证）。
- 新增依赖、改写历史、`git push` 等不可逆动作先向用户确认。

## Project Structure & Module Organization
`src/` contains the app: `pages/` holds route pages named `*.page.vue`, `components/` groups reusable UI by domain, `hooks/` wraps API access and shared state, `service/` contains business-facing service calls, and `utils/` holds pure helpers. Generated API clients live in `src/api/axios/`; treat them as generated output from `openapi.yaml`. Static files belong in `public/`, build output goes to `dist/`, and router types are generated into `typed-router.d.ts`.

## Build, Test, and Development Commands
Use `pnpm` for all package management.

- `pnpm dev`: start the Vite dev server.
- `pnpm build`: create a production build in `dist/`.
- `pnpm preview`: serve the production build locally.
- `pnpm type-check`: run `vue-tsc -b`.
- `pnpm type-check:check`: run read-only type-check without writing build info.
- `pnpm lint`: run ESLint on `src/` and then type-check.
- `pnpm lint:check`: run read-only ESLint checks without autofix.
- `pnpm format`: format `src/` with Prettier.
- `pnpm check`: run linting and formatting together.
- `pnpm precommit:check`: run the exact checks used by the pre-commit hook.
- `pnpm gen:api`: regenerate `src/api/axios/` from `openapi.yaml`.
- `pnpm exec vitest run src/utils/__tests__`: run the current utility tests.

## Git Hook Policy
本目录以子目录形式落在 `crmAndRag` 仓内，`frontend/` 自身没有独立 `.git`，所以钩子路径要相对**仓根**写。

- 钩子实体：`.githooks/pre-commit`，内部用脚本自身位置推导 `frontend/` 作为工作目录，不依赖 `git rev-parse --show-toplevel`。
- 安装（一次性，会写仓库本地 git 配置，执行前需用户同意）：`node scripts/setup-hooks.mjs`，等价于在仓根执行 `git config core.hooksPath frontend/.githooks`。`pnpm install` 会通过 `prepare` 脚本自动做同一件事。
- 校验：`git config --get core.hooksPath` 期望输出 `frontend/.githooks`（不是 `.githooks`）。
- 卸载：`git config --unset core.hooksPath`。
- 正常开发不要绕过钩子（`--no-verify`）。

## Commit Workflow
- 每个提交都应通过 pre-commit 钩子。
- The hook runs `pnpm precommit:check`
- `pnpm precommit:check` is read-only and runs:
  - `pnpm lint:check`
  - `pnpm type-check:check`
- If it fails, fix the issues and commit again
- If you want autofix before committing, run `pnpm lint` manually
- 钩子未安装时（`core.hooksPath` 为空），上面两项必须手动跑，不要假定 CI 会兜住：CI 的 `frontend-quality` 作业跑同样的脚本。

## Coding Style & Naming Conventions
This is a Vue 3 + TypeScript repo with strict TypeScript enabled. Prettier enforces single quotes; default formatting yields 2-space indentation. Prefer `@/` imports over deep relative paths. Use PascalCase for Vue component files and component names, for example `CreateSaleModal.vue`. Keep page files under `src/pages/` with the `*.page.vue` suffix so `unplugin-vue-router` can generate routes correctly. Do not call APIs directly from components; add or extend a domain hook in `src/hooks/`.

## Testing Guidelines
两条互不替代的轨道，`vite.config.ts` 的 `test.exclude` 已把 `e2e/**` 从 Vitest 里排除：

- **单元 / 组件级（Vitest）**：用例在 `src/utils/__tests__/` 与 `tests/`，命名 `*.test.ts`，尽量贴近被测代码。全量跑 `pnpm exec vitest run`，单目录跑 `pnpm exec vitest run src/utils/__tests__`。这类用例不需要浏览器。
- **端到端（Playwright）**：spec 在 `e2e/`（`playwright.config.ts` 的 `testDir`），会自起 `pnpm dev` 作为 webServer。跑 `pnpm exec playwright test`，单文件 `pnpm exec playwright test e2e/role-permission.spec.ts`。新增/改动功能时按 Critical Rules 往 `docs/test-checkpoints.md` 追加检查点。

开 PR 前先跑 `pnpm type-check` 与受影响的那条轨道。

## Commit & Pull Request Guidelines
Recent history follows Conventional Commits such as `feat:`, `fix:`, `docs:`, `build:`, and `chore:`; keep that format, even when the subject is written in Chinese. PRs should describe the user-visible change, list affected routes or APIs, and include screenshots for UI changes. If `openapi.yaml` changes, mention whether `pnpm gen:api` was run and include the generated client updates in the same PR.

## Documentation

| File | Description |
|------|-------------|
| [docs/development.md](docs/development.md) | 开发命令与环境配置 |
| [docs/architecture.md](docs/architecture.md) | 项目架构、目录结构与设计模式 |
| [docs/code-conventions.md](docs/code-conventions.md) | 代码风格与编码规范 |
| [docs/testing.md](docs/testing.md) | Playwright 测试指南与路由表 |
| [docs/test-checkpoints.md](docs/test-checkpoints.md) | 待验证的测试检查点（累积后统一执行） |
| [docs/ci.md](docs/ci.md) | 前端 CI 说明 |
| [docs/project.md](docs/project.md) | 项目概览 |
