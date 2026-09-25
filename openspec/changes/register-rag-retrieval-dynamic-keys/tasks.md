# Tasks — register-rag-retrieval-dynamic-keys

> 全程 ¥0。先读 `AGENTS.md`、本案 proposal/spec、`DynamicConfigKeyRegistry` 现有 `def(...)` 写法与 `docs/dynamic-config-keys.md`。以权威 worktree `D:\code\crmAndRag-merge-add-knowledge-admin-api` 的 `master` 为准。不扩 `NAMESPACES`，不翻默认值，不改检索实现。真实模型 / 生产开关 / 迁移 / push 一律停下。

## 1. 登记 11 键

- [x] 1.1 在 `DynamicConfigKeyRegistry` 的 `rag.retrieval` 段按现有 `def(...)` 补齐 proposal 表中 11 键；默认值对齐代码读取点（query-rewrite true、fusion rrf/60、rerank default/0.60/0.40/4/3000/20、图文权重 0.70/0.30）。`fusion.mode` / `rerank.mode` 用 allowed 集合。不扩白名单，不登记 context/chunking/query 键。
- [x] 1.2 若文档默认与代码不一致，改 `docs/dynamic-config-keys.md` 去对齐代码，并注明这 11 键已注册、仅超管可写。不改代码内联默认。

## 2. 单测

- [x] 2.1 `DynamicConfigKeyRegistryTest`：11 键均 `definitionOf` 存在；至少覆盖 Boolean、枚举非法值拒绝、Double 越界拒绝、Integer/Long 下限拒绝。
- [x] 2.2 抽 1～2 个消费点（如 `RetrievalQueryRewriteService` / `RetrievalConfigResolver`）确认未改默认读取路径。

## 3. ¥0 验收

- [x] 3.1 定向跑注册表及相关单测，再按 `openspec/git-workflow.md` 用 `D:\git\Git\bin\bash.exe` 跑 `scripts/merge-gate.sh`；基线只许脚本从干净真实报告 `--update`。禁真实外呼。
- [x] 3.2 只纳入本案文件。回传 diff 与测试结果，**先不提交、不合并、不 push、不归档**。
