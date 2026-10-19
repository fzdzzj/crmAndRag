# 任务卡 · fix-minor-debt-family（待办池 MINOR 家族合卡，A 档）

- 基点：master@370c7d2（surefire 270/1697、failsafe 31/118/7、门禁 10/10）
- 分支：`feature/fix-minor-debt-family`（权威树检出）
- 来源：§120 两项 + §121 三项 + §129 M-1（详见 proposal.md）

## 目标（六点，目标约束非微操）
1. **M-1 错误码收敛**：src/main 全部 `new BaseException("...")` 改枚举构造（BaseException 位于 `common/exiception/`，注意目录拼写即此）；映射表（原文案→ErrorCode→理由）落 handoff；测试改码断言；error-code-map.ts 镜像按需同步。
2. 摄取 BLOCK 错误码收口 **96004**（ContentSecurityService:73 / DocumentIngestionService:176 链）。
3. 摄取扫描内存优化：readAllBytes 全量缓冲 → 尺寸上限或分块（BLOCK 语义不变）。
4. assign 补目标用户存在+在职校验（CustomerPoolServiceImpl:133，置于 :221 配额行锁之前）。
5. release 置空显式落库（CustomerPoolServiceImpl:102，UpdateWrapper set null，casToPrivate 同型）。
6. TokenBudget 无锁断言：盘点 AiChatResumeSupport:181→TokenBudgetService.check 链，确认则修，过时则记录弃置。

## 红线
零迁移 / 零新依赖 / 零新增错误码常量（默认；无码可用者 handoff 单列待裁决，禁擅自加码）/ 不改冻结契约 / 基线只增不减。

## 验收
定向全绿 + clean verify 全绿 + 单次基线 --update + merge-gate 10/10 + handoff 含 M-1 映射表与逐项证据。**停步**：未 merge 未 push，待 A 档独立复核。
