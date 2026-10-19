# 任务清单 · fix-minor-debt-family

> 勾选纪律：每项完成后在勾选行下一行留 6 空格 + 「补勾依据（日期 实测命令与结论）」证据行；未完成不勾。

## 1. M-1 BaseException(String) 全仓收敛
- [x] 1.1 盘点 src/main 全部 `new BaseException("` 单参字符串调用点，产出全量清单（file:line + 文案 + 方法）
      补勾依据（2026-10-19 实测）：卡面 grep 口径命中 24 处字面量；同法盘点出同一缺陷的另 71 处（`ErrorCode.X.getMessage()` 56 处、`MessageConstant.Y` 13 处、`String.format(ErrorCode.X.getMessage(), n)` 2 处），合计 **95 处**全量清单见 handoff §1.1/§1.2/§1.3/§1.4。返场轮次收敛口径：**95/95 全部收敛**（原 4 处待裁决已拍板映射 50002）。
- [x] 1.2 制定逐点 ErrorCode 映射表（复用既有码；无合适码者单列待裁决），落 handoff
      补勾依据（2026-10-19 实测）：映射表落 `work/handoff-fix-minor-debt-family.md` §1.2（字面量逐条 原文案→码→理由，含补漏的 `PaymentRecordServiceImpl:70`）＋§1.3（MessageConstant 13 映射：9 处原映射 + 返场轮次按拍板映射为 `PARAM_FORMAT_ERROR`(50002) 的原 4 处待裁决）＋§1.4（`.getMessage()` 56 处恒等映射全量行号）。**零新增错误码常量**。
- [x] 1.3 按映射表改枚举构造，行为面 code=0→真实码；src/test 同步改码断言
      补勾依据（2026-10-19 实测）：`mvn -B -ntp -o compile` EXIT=0；复扫 src/main **零违规**（首轮剩 4 处待裁决，返场轮次已映射 50002）；`BaseExceptionConvergenceTest` 6/6 绿；`UserPasswordUpdateTest` 增 `PARAM_FORMAT_ERROR`(50002) 码断言后 4/4 绿；`BusinessActivityRelationSupportTest` 4/4 绿（4 处映射点码断言）。
- [x] 1.4 客户端可见端点错误码核对 `frontend/src/.../error-code-map.ts` 镜像，按需同步（§127 先例）
      补勾依据（2026-10-19 实测）：本次引入的 10003/12002/13001/31001/31002/50001/50002/50007/50008/90001/91003/92008/92013/92014/92015/92018/92026/96004 在镜像中全部已存在，**镜像零改动**；`ErrorCodeMapParityTest` 3/3 绿（clean verify 内）。
- [x] 1.5 M-1 专项测试：每类映射至少 1 例断言 code 透传
      补勾依据（2026-10-19 实测）：新增 `BaseExceptionConvergenceTest`（返场后 6 例：源码门禁严格枚举形态 + 2 例负向自证 + 构造器契约 + 码族抽样 10003/92026/50002/91003/90001）；`BusinessActivityRelationSupportTest` 4 例断言 50002 + 原文案；`CustomerPoolServiceTest`/`UserPasswordUpdateTest`/`DocumentIngestionServiceTest` 补码断言；全量 surefire 1714/0/0（返场后见 §7.1）。

## 2. 摄取 BLOCK 错误码收口 96004
- [x] 2.1 确认 ContentRiskBlockedException 当前错误码与 96004 对应常量名
      补勾依据（2026-10-19 实测）：改造前该类为裸 `RuntimeException`，**无 code 字段**（即卡面所说「确认当前码」的答案＝无码，落兜底 code=0）；96004 常量＝`platform/contract/PlatformErrorCode.CONTENT_RISK(96004)`（既有、已在前端镜像）。
- [x] 2.2 收口至 96004 并补断言（摄取 BLOCK 路径错误码 == 96004）
      补勾依据（2026-10-19 实测）：`ContentRiskBlockedException` 增 `code = PlatformErrorCode.CONTENT_RISK`；`GlobalExceptionHandler` 增 `@ExceptionHandler` 回 96004 通用文案；新增 `GlobalExceptionHandlerContentRiskTest` 2/2 绿，`DocumentIngestionServiceTest` BLOCK 用例加 96004 断言后 12/12 绿。

## 3. 大文件摄取扫描内存优化
- [x] 3.1 readAllBytes 全量缓冲改为尺寸上限前置校验或分块评估（语义等价：BLOCK 仍落库前拒绝）
      补勾依据（2026-10-19 实测）：`DocumentIngestionService` 增 `MAX_DOCUMENT_BYTES=100MB`（对齐 `spring.servlet.multipart.max-file-size`）+ `assertWithinDeclaredSize`（读流前拒）+ `readAllBytesBounded`（`readNBytes(max+1)` 有界读），顺序仍为「尺寸 → 安全评估 → 存储 → 落库」，超限以既有 `PARAM_OUT_OF_RANGE`(50007) 拒绝；旧无界 `readAllBytes` 已删。
- [x] 3.2 边界测试：超限尺寸被拒 / 正常尺寸不受影响
      补勾依据（2026-10-19 实测）：新增 `DocumentIngestionSizeLimitTest` 4/4 绿——声明超限（流一次未被读 + 零 store/零 insert）、有界读取恰好等于上限放行/超 1 字节拒绝、正常中文正文逐字节不变、上限 100MB 对齐断言。

## 4. assign 目标用户存在性校验
- [x] 4.1 配额行锁前补目标用户存在+在职校验（复用既有判存/状态错误码）
      补勾依据（2026-10-19 实测）：`CustomerPoolServiceImpl.assign` 在 `assertQuotaNotExceeded` 之前 `selectById` + `CustomerPoolTargetUserGuard.assertAssignable`（抽类原因：内联会把类推过 PMD 75/150 门限，见 handoff §4）；码复用 `ID_NOT_EXISTS`(92008)/`USER_IS_FROZEN`(12006)/`USER_IS_QUIT`(12007)/`USER_STATUS_EXCEPTION`(12003)，零新增。
- [x] 4.2 靶向测试：不存在/离职目标用户 assign 被拒，合法用户不受影响
      补勾依据（2026-10-19 实测）：`CustomerPoolServiceTest` 16/16 绿——不存在（断言码 + `userMapper.selectList` 零调用证明未进配额行锁）、冻结/离职/状态异常各 1 例、happy path 增 `InOrder` 断言「先查目标用户再 FOR UPDATE」。

## 5. release 置空显式落库
- [x] 5.1 release 路径 setOwnerId(null) 等置空改显式 UpdateWrapper set null 真落库（casToPrivate 同型）
      补勾依据（2026-10-19 实测）：`CustomerPoolServiceImpl.release` 由 `updateById(实体)` 改为 `LambdaUpdateWrapper.set(ownerId,null).set(poolReleaseReason,..).set(poolReleaseTime,now).set(poolStatus,公海).eq(id).eq(isDeleted,false)` + `update(null, wrapper)`，与 `casToPrivate` 同型。
- [x] 5.2 靶向测试：release 后 DB 行 ownerId/reason 字段真被清空
      补勾依据（2026-10-19 实测）：`CustomerPoolServiceTest.release_byOwner_updatesAndLogsRelease` 重写为断言 SET 子句含 `pool_status/owner_id/pool_release_time/pool_release_reason` 且**绑定参数含 null**（即 owner_id 置空真进 SQL，NOT_NULL 策略不再吞掉）、`updateById` 零调用、RELEASE 日志 reason 正确；16/16 绿。

## 6. TokenBudget 无锁断言盘点处置
- [x] 6.1 盘点确认无锁共享默认值点；确认则修（volatile/原子化）并补并发安全测试；过时则 handoff 记录后弃置
      补勾依据（2026-10-19 实测）：盘点确认**存在**——`TokenBudgetProperties` 为 `@Component @ConfigurationProperties` 单例（无 `@RefreshScope`），`enabled`/`dailyLimit`/`monthlyLimit` 原为普通可变字段，被全部请求线程经 `TokenBudgetService.check` 共读、配置绑定/刷新在线程外写；非 volatile 下可见性无保证且 `long` 允许撕裂（JLS 17.7）。**已修**：三字段加 `volatile` + 类级并发说明；新增 `TokenBudgetPropertiesConcurrencyTest` 3/3 绿（volatile 结构断言 + 灰度默认值 false/100000/1000000 + 跨线程可见性）。

## 7. 基线与物料
- [x] 7.1 定向测试全绿 + 全量 `mvn -B -ntp clean verify`（Docker 在线）
      补勾依据（2026-10-19 实测）：定向 `-Dtest` 18 类 103/0/0 绿；返场定向 2 类 10/0/0 绿；`mvn -B -ntp clean verify`（返场日志 `work/_reentry-verify.log`）surefire **1720/0/0**（报告 275）、failsafe **118/0/0/7**、pmd 0 violations、checkstyle/spotless/spotbugs 全绿、**BUILD SUCCESS**。
- [x] 7.2 树净单次 `check-test-baseline.sh --update` + 校验模式 EXIT=0 + 基线笔
      补勾依据（2026-10-19 实测）：Git Bash 一次性 `--update`（树净，源修订 `0d07514`）→ surefire 275/1720/0、failsafe 31/118/7，EXIT=0；校验模式「回归基线门禁通过」EXIT=0；基线笔 `a8d136f test(baseline): update clean baseline to 0d07514`；surefire 1697→1720（只增不减）。
- [x] 7.3 `merge-gate.sh` 10/10（typed-router 噪声 checkout 还原）
      补勾依据（2026-10-19 实测）：`bash scripts/merge-gate.sh`（返场日志 `work/_reentry-mergegate.log`，HEAD=`a8d136f`）**10/10 PASS**（unit 1720/checkstyle 0/spotbugs/spotless 1128/pmd 0/baseline 275-1720+31-118-7/frontend-unit 21 文件 197 例/hook/bijection 12↔12/pmd-baseline 0==0==0），末行「merge-gate 通过」；`frontend/typed-router.d.ts` 生成物已 `git checkout --` 还原，终态零改动。

## 8. 返场补丁（A 档复核 MAJOR-1 + 拍板项 + 文档 MINOR）
- [x] 8.1 收敛门禁收紧为严格枚举形态 + 两条负向自证用例
      补勾依据（2026-10-19 实测）：`BaseExceptionConvergenceTest` 改 `ErrorCode\.[A-Z0-9_]+(\s*,\s*.+)?` 全匹配，`.getMessage()`/空参/字面量/MessageConstant/String.format 一律违规；新增 2 例自证（合法绿 / 违规红），该类 6/6 绿。
- [x] 8.2 原 4 处待裁决按 Owner 拍板映射 50002 + 白名单清零 + 4 处码断言
      补勾依据（2026-10-19 实测）：`BusinessActivityRelationSupport:48/92/135/165` 改 `BaseException(ErrorCode.PARAM_FORMAT_ERROR, 原 MessageConstant)`；白名单删除（零豁免）；新增 `BusinessActivityRelationSupportTest` 4/4 绿（50002 + 原文案）。
- [x] 8.3 文档 MINOR：映射表补漏、CustomerPoolServiceIT 不实引用修正、口径统一 95/95
      补勾依据（2026-10-19 实测）：handoff §1.2 补 `PaymentRecordServiceImpl:70` 并删占位 #24；§5 如实标注「全仓无 CustomerPoolServiceIT、真库回环缺失、登记待办池」；§0/§1.1/§1.3/§8/§9 口径统一为 95/95 收敛（原 4 处已映射）。
- [x] 7.4 handoff + tasks 勾选 + 任务卡随 docs 笔提交；严格停步未 merge 未 push
      补勾依据（2026-10-19 实测）：handoff `work/handoff-fix-minor-debt-family.md`（六模块证据 + M-1 全量映射表 + 13 处 MessageConstant 映射（含返场拍板的原 4 处待裁决）+ 偏差清单）；任务卡与勾选同 docs 笔提交；分支 `feature/fix-minor-debt-family` 保留，**未 merge、未 push**。
