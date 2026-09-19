# 前端 CI 自动化说明

## 工作流一览

| 工作流 | 触发时机 | 内容 |
|--------|---------|------|
| `ci.yml` | push 到 main、指向 main 的 PR | lint / type-check / build |
| `e2e-official.yml` | 手动触发（workflow_dispatch） | MySQL + 后端构建 + Playwright E2E，全程官方 Runner |

本地等价命令：

```bash
pnpm install --frozen-lockfile
pnpm gen:api
pnpm lint:check
pnpm type-check:check
pnpm build
pnpm exec playwright test e2e/role-permission.spec.ts
```

## 首次配置步骤（在 GitHub 上做一次）

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
