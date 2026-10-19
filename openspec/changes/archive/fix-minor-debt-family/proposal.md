# 提案 · fix-minor-debt-family（待办池 MINOR 家族合卡）

## 为什么
近五卡（§120/§121/§127/§129）复核累积的 MINOR 待办共 6 项，均"小而真"：错误码语义混乱（code=null 降级 0）、公海半边状态未真落库、摄取链路内存与码制瑕疵。单项不值得开卡，合卡一次清账。

## 做什么（六点合一）
1. **M-1 BaseException(String) 全仓收敛**（主模块，§127 F-1 + §129 M-1 同坑）：src/main 全部 `new BaseException("...")` 单参字符串构造调用点（实测分布于 UserPasswordSupport:47、JWTInterceptor:67、InvoiceInfoServiceImpl:70-105、PaymentRecordServiceImpl:68-81 等，全量清单执行时盘点）改为 `BaseException(ErrorCode.X[, 原文案])` 枚举构造；行为面从 code=0（null 降级）变为真实错误码；src/test 字符串断言同步改码断言；客户端可见端点的错误码核对前端 `error-code-map.ts` 镜像（§127 92028 先例同型）。
2. **摄取 BLOCK 错误码收口 96004**（§121 MINOR-2）：`ContentRiskBlockedException`（ContentSecurityService:73-88 抛出、DocumentIngestionService:176-228 摄取链）错误码统一至 96004 对应 ErrorCode 常量。
3. **大文件摄取扫描内存优化**（§121 MINOR-3）：`DocumentIngestionService` readAllBytes 全量缓冲后再评估 → 改为尺寸上限前置校验或分块评估，语义等价（BLOCK 仍在任何落库前拒绝）。
4. **assign 目标用户存在性校验**（§120-1）：`CustomerPoolServiceImpl.assign`（:133-166）在配额行锁（:221-245）之前补目标用户存在+在职校验，消除"行锁退化为 gap 锁"与分派给幽灵用户的隐患。
5. **release 置空显式落库**（§120-2，fix-pool-release-owner-clear）：`CustomerPoolServiceImpl.release`（:102-130）setOwnerId(null) 等置空操作在 MP NOT_NULL 策略下为空操作从未落库 → 显式 UpdateWrapper set null（对照 casToPrivate 半边 §120 先例）。
6. **TokenBudget 无锁断言**（§121 MINOR-1）：挂载链 AiChatResumeSupport.rejectIfThrottled(:181-192)→TokenBudgetService.check(:205-231)；执行时盘点确认无锁共享默认值的具体点，确认则修（volatile/原子化），描述过时则 handoff 记录后弃置。

## 红线
零数据库迁移 / 零新依赖 / **零新增错误码常量**（复用既有 ErrorCode；确无合适码的调用点须在 handoff 逐条列出映射与理由供裁决，严禁擅自加码）/ 不改冻结契约 / 单副本无状态契约不受影响 / 基线只增不减。

## 档位
**A 档**：M-1 属错误码语义全仓收敛，多端点客户端可见行为面从 code=0 变真实码 + 前端镜像联动，广于 §127 单点先例；含公海状态落库行为修正。

## 验收口径
定向测试全绿（含 M-1 码断言迁移）+ `mvn -B -ntp clean verify` 全绿 + 单次 `check-test-baseline.sh --update` + `merge-gate.sh` 10/10 + handoff 含 M-1 逐调用点映射表与六项证据。严格停步：未 merge 未 push，待 A 档独立复核。
