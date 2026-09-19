# Development Guide

## Package Manager

**Use `pnpm` for all package management operations.**

This is a frontend project. After making code changes, you do NOT need to run `pnpm run dev` or `pnpm run serve` — the dev server handles hot reloading automatically.

## Commands

```bash
# Start development server
pnpm dev

# Build for production
pnpm build

# Preview production build
pnpm preview

# Type checking (full)
pnpm type-check

# Read-only project check（与 pre-commit 钩子同口径，改完代码先跑这条）
pnpm type-check:check

# 单文件快速自查（比全量快，但绕过 tsconfig 的 include/paths，不替代上面两条）
pnpm exec vue-tsc --noEmit src/path/to/File.vue

# Lint and fix code (includes type-check)
pnpm lint

# Format code with Prettier
pnpm format

# Run both lint and format
pnpm check

# Generate API clients from OpenAPI specification
pnpm gen:api
```

## Environment Configuration

The app uses environment variables (`BASE_PATH`, `BASE_API`) configured in Vite. These are defined at build time and accessible via `import.meta.env`.

API proxy is configured for development via Vite's proxy feature (see `vite.config.ts`).

The project includes a service tester interface at `/dev/tester` for API testing.
