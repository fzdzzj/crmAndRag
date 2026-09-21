# Tasks — tighten-pmd-violations

> 执行契约见 `openspec/git-workflow.md`（限路径直落 master、不 push）与 proposal.md 的分片定义。
> 硬约束：surefire 计数不变（锁 `scripts/test-baseline.txt`）；台账只下调；每分片收尾 = pmd:check 实测 → --update → pom 照抄 → merge-gate 全绿 → 提交。
> 每完成一步立刻勾选并在行尾补实测证据。

## 1. 分片 A：CommentSize（282 → ≈0；尺子校准 + 4 处注释精简）

- [x] 1.1 `pmd-rules.xml` 的 `CommentSize` 增配 `maxLineLength=120`（默认 6 荒谬；Q5 假设已登记 proposal.md） ｜实测：1329→1047（-282，其中 278 为行宽校准消除）
- [x] 1.2 精简 4 处超 20 行注释块到 ≤20 行（语义保真）：`RequirePermission.java:9-40` / `ContextBuilder.java:15-37` / `AssistantChatRequest.java:5-30` / `UserContext.java:5-28` ｜实测：CommentSize=0；spotless apply 后仍 0，复测 1047 不变
- [x] 1.3 分片收尾：pmd:check 实测新条数 → `pmd-baseline-check.sh --update` → pom 同步 → `merge-gate.sh` 全绿 → 限路径提交 ｜实测：1047/179 写入台账，pom 469 行=1047，merge-gate 8 子门禁全 PASS（[unit] surefire 计数不变）

## 2. 分片 B：OnlyOneReturn（732，按模块分批，每批独立收尾）

- [x] 2.1 盘点 732 处按模块分布，登记批次表（common/pojo/quality/knowledge/platform/server） ｜实测：target/pmd.xml 统计——common 34 / pojo 22 / quality 3 / knowledge 134 / platform 71 / server 468，合计 732，与台账 OneReturn 一致
- [x] 2.2 common 批（34）：合并多 return 为单一出口（行为等价），pmd:check → --update → pom 同步 → merge-gate → 提交 ｜实测：1047→1013（-34，本模块 OnlyOneReturn 清零 0，其他规则无新增）；台账 1013/172、pom 469 行=1013；merge-gate 8 子门禁全 PASS（[unit] surefire 724 不变）
- [ ] 2.3 pojo 批（22）｜实测：
- [ ] 2.4 quality 批（3）｜实测：
- [ ] 2.5 knowledge 批（134）｜实测：
- [ ] 2.6 platform 批（71）｜实测：
- [ ] 2.7 server 批（468）｜实测：

## 3. 分片 C：AvoidCatchingGenericException（134，逐例；前置 Q6 拍板）

- [ ] 3.1 盘点 134 处（位置/try 内容/抛出源/是否顶层兜底），产出处置表 ｜实测：
- [ ] 3.2 **停下**：处置表 + 三候选方案上 owner 拍板（Q6），未拍板不动代码 ｜实测：
- [ ] 3.3 按拍板执行：安全收窄 / @SuppressWarnings 豁免+理由注释 / 保留登记 ｜实测：

## 4. 总收尾

- [ ] 4.1 runbook §6.7 与 HANDOFF.md 同步最终基线与分片结论 ｜实测：
- [ ] 4.2 proposal.md 验收逐条复核（命令+输出摘录） ｜实测：
