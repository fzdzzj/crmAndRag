# CLAUDE.md

规则正文已收敛到 [AGENTS.md](AGENTS.md)，本文件只做指路，不再单独维护约束，避免两份文件漂移。

- 全部约束、命令、钩子策略、提交与 PR 规范：见 [AGENTS.md](AGENTS.md)。
- 其中 Critical Rules（Tailwind only、PascalCase、API 走 hooks、日期格式 `yyyy-MM-dd HH:mm:ss`、读取文件不忽略首行、往 `docs/test-checkpoints.md` 追加检查点）在 [AGENTS.md 的 Critical Rules 一节](AGENTS.md#critical-rules)。
- 类型检查统一用 `pnpm type-check:check`，不要手敲 `npx vue-tsc`（`package.json` 里 `type-check:app:check` / `type-check:config:check` 已经带好 tsconfig 参数）。
- 文档索引也在 [AGENTS.md](AGENTS.md#documentation)。

Claude Code 专属的自动化配置建议仍留在 [docs/claude-automation-recommendations.md](docs/claude-automation-recommendations.md)；它与规则冲突时，以 [AGENTS.md](AGENTS.md) 为准。
