# 变更提案：calibrate-review-intensity —— 复核强度分级校准

复核档位（按本提案 D1 自 declare）：**C 档**（纯 docs + 非门禁脚本，无产品代码 / 迁移 / 权限 / 真跑）

## 一、现状与问题

1. 复核不分档：任何卡都全量复跑门禁 + 双树核对，同一门禁最多跑三遍（执行自跑 → 复核复跑 → 合并后 CI）。
2. 纯文档卡与 schema 变更同走 13 站全链，owner 亲派与回报阅读成本集中在低风险变更上。
3. 机械核对（git 干净 / diff 形状 / tasks 全勾 / 基线不动）是零判断力核对，却由执行自报、指导抽查、复核复看三方人肉重复。
4. 门禁本身不冗余：三段 CI、PMD/SpotBugs 台账、权限覆盖、schema 漂移门禁全部保留（自动运行、历史上真抓过问题：57 裸奔端点、冻结绕过、CRITICAL 漂移）。本卡只调**人工复核强度**，不碰门禁。

## 二、建议方案

**建议 D1 · 三档判据（按变更内容判，卡 proposal 头部自 declare）**
- A 档 ＝ 含任一：DB 迁移脚本、权限语义、真跑（RAG_BENCHMARK_REAL=1）、冻结契约改动、合并 / push 本身。
- B 档 ＝ 常规产品代码 + 测试，无 A 档要素。
- C 档 ＝ 纯文档、openspec 归档批、logbook、非门禁脚本。
- 备选：按 diff 行数分档 —— 否，行数与风险不同构（一行迁移脚本 ＞ 五百行文档）。

**建议 D2 · B 档复核减配**
- 复核 agent ＝ 证据审计（命令 + 哈希留痕真实性核验）+ surefire 定向复跑 + 机械脚本双树核对；Docker IT / 真跑不再复跑，合并后 CI 兜底。
- 备选：B 档维持全量 —— 否，减配名存实亡，回到三遍跑。

**建议 D3 · C 档不派复核**
- 指导 agent 单遍逐格核验 + 机械脚本出表替代复核 agent；勾选哈希留痕规则不豁免。
- 备选：C 档保留只读复核 —— 否，读报告正是要替代的人肉环节。

**建议 D4 · 机械核对脚本（新增，非门禁）**
- `scripts/tests/review-mechanical-check.sh`：两树 git 状态 / HEAD 位置、分支 vs master diff 文件清单、tasks.md 未勾项计数、基线文件 vs master 漂移；恒 exit 0，只报告不判定。
- 接入复核提示词与指导抽查 SOP；不进 CI、不接 merge-gate.sh。
- 备选：并入 merge-gate.sh —— 否，门禁与工具职责混淆，且改 merge-gate 属 A 档动作。

## 三、影响面、排期与边界

- 改动面：`docs/review-intensity-policy.md`（新）、`scripts/tests/review-mechanical-check.sh`（新）、AGENTS.md「复核档位」小节（≤3 行指针）、本卡目录；无 Java 产品代码。
- 排期：执行排在 b2-suite-recalibration 闭合之后（顺序纪律，不并行派发）；拍板可先落。
- 生效语义：合入后新卡按档位走；在途卡按旧规则。本卡自身按 C 档试点（指导单遍核验）。
- 不动：三段 CI 门禁、各台账与只许下调纪律、权限覆盖 / 漂移门禁、证据链原则、owner 亲派纪律。
