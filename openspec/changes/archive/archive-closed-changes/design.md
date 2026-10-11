# 设计 — archive-closed-changes

**无代码设计**：纯目录移动（3 × git mv，rename 记录），无行为变更、无配置变更、无契约面。

- 写集形态：3 目录 rename（约 15 文件的 R 记录）+ 本卡三件套新增。
- 风险与对策：旧路径引用断裂 → 前置 grep 实测唯一命中为 P-ai HANDOFF 自引用（随目录移动）；撞名 → 前置 ls archive/ 实测。
- 门禁：`bash scripts/merge-gate.sh` 全 PASS（surefire 不变 1089；四静态不受文档移动影响，仍复跑兜底）。
- Git 序：feature/archive-closed-changes 单笔 → 停步回报 → owner 授权后 `--no-ff` 合入 master、push。
