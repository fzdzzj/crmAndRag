# 前端 CI 自动化说明

## 工作流一览

前端质量门禁现在**只有一个权威定义处**：仓根的 `.github/workflows/ci.yml` 里的 `frontend-quality` job（`working-directory: frontend`），步骤为 gen:api → lint:check → type-check:check → **vitest 单元轨** → build → gzip 体积预算。

| 位置 | 状态 | 说明 |
|------|------|------|
| `.github/workflows/ci.yml` 的 `frontend-quality` | 权威定义 | 本仓 `git remote -v` 为空、`master` 无 upstream，**该 job 今天不会被自动触发**；合并前由 `bash scripts/merge-gate.sh` 在本地跑等价序列 |
| `frontend/.github/workflows/ci.yml.prev-host-unread` | 已归档（`operationalize-harness-gates` 组 4.3） | 归档原因：宿主不读取嵌套目录下的 `.github/workflows`，且其触发分支写的是 `main`，与本仓默认分支 `master` 不符 |
| `frontend/.github/workflows/e2e-official.yml.prev-host-unread` | 已归档（同上） | 同上；另依赖私有后端仓检出与 `BACKEND_REPO_TOKEN` |

> 归档文件内容未作改动，保留作历史与恢复参考。**不要按下面"首次配置步骤"去 GitHub 配置那两个文件**——它们已不被读取；那一节保留是为说明归档件的原始意图。

本地等价命令：

```bash
pnpm install --frozen-lockfile
pnpm gen:api
pnpm lint:check
pnpm type-check:check
pnpm test                       # Vitest 单元/组件轨，无需浏览器与后端
pnpm build
pnpm exec playwright test e2e/role-permission.spec.ts
```

## 首次配置步骤（在 GitHub 上做一次）

> 适用对象是已归档的 `*.prev-host-unread`，仅当本仓被镜像到托管平台并决定恢复这两条流水线时才用得上。

1. **给前端仓库配置后端访问令牌**

   `e2e-official.yml` 需要检出私有仓库 `2024-shiliuzi/crm-back`：

   - 生成一个 PAT（Fine-grained 或 classic，勾选 `repo` / `Contents: Read`）；
   - 前端仓库 → Settings → Secrets and variables → Actions → New repository secret；
   - 名称填 `BACKEND_REPO_TOKEN`，值粘贴 PAT。

2. **推送代码到 main**

   提交内容至少包含：

   - `.github/workflows/ci.yml`
   - `.github/workflows/e2e-official.yml`
   - `e2e/ci/seed.sql`
   - `e2e/role-permission.spec.ts`、`playwright.config.ts`、`e2e/env.ts`

3. **手动触发 E2E**

   Actions → `E2E Official` → Run workflow。

   工作流会：

   - 启动 MySQL 8 服务（`crm_dev` 库）
   - 检出后端仓库并执行 `script.sql` + `init_data.sql` + `e2e/ci/seed.sql`
   - Maven 构建并启动后端（Java 21）
   - 启动前端 dev server，运行 `e2e/role-permission.spec.ts`
   - 失败时上传 `test-results/` 和 `backend.log`

## 种子账号

CI 数据库固定使用以下账号（密码都是 `123456`）：

| 邮箱 | 角色 |
|------|------|
| `admin1@slz.com` | Admin |
| `lihua@example.com` | 销售员（除权限管理外全权限） |
| `zhangming@example.com` | 销售经理 |

## 常见问题

- **backend checkout 失败**：`BACKEND_REPO_TOKEN` 未配置或 PAT 权限不足（需要 `repo` 读取权限）。
- **MySQL 连不上**：服务容器健康检查没通过，通常等 10-30 秒会自动重试。
- **后端启动失败**：失败产物里有 `crm-back/backend.log`，下载查看。
- **E2E 失败**：`test-results/` 里有截图和 trace。

## 注意事项

- `.env` / `e2e/.env` 不要提交（已 gitignore）。
- 新增依赖后必须提交 `pnpm-lock.yaml`，否则 `--frozen-lockfile` 失败。
- `e2e-official.yml` 检出的是后端 `master` 分支；如需指定分支改 `ref` 即可。
