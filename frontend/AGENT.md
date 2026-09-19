# AGENT.md

指路文件，本身不含规则。

- `frontend/` 的唯一规则权威源是 [AGENTS.md](AGENTS.md)：Critical Rules、目录结构、pnpm 命令清单、Git Hook Policy、Commit Workflow、编码规范、测试轨道、PR 规范、文档索引都在那里。
- 本文件过去自称"提供与 AGENTS.md 相同的操作规则"，实际只是它的一个子集（缺 Project Structure、Coding Style、Testing、PR 四节）。照本文件执行会静默丢掉约束，所以那句话不再成立，已删除。
- 需要只读校验时直接跑：`pnpm lint:check` 与 `pnpm type-check:check`。

工具只认 `AGENT.md` 单一文件名时，请从本文件跳到 [AGENTS.md](AGENTS.md) 取全文。
