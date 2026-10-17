# 任务清单 · 公海认领/分派并发竞态修复 (fix-pool-claim-race)

<!-- id: fix-pool-claim-race -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · CAS 改造

- [x] 1.1 实测认领/分派链路与 customer/customer_pool_log 表结构，设计零迁移 CAS 方案（条件 UPDATE 谓词 + 上限原子化），handoff 留痕方案取舍与 SQL 语义
      实测锚点复核：claim L84-104 / assign L139-174 同构（quota COUNT check-then-act → 读旧实体判公海 → L98/L163 无条件 updateById）；竞态三面与 v2 池 I-2 一致（并发互覆盖后写赢 / 同用户 N 并发全读 49 破 50 上限 / claim-release 交错把已回池客户判给旧 owner）。
      表结构实测（V41 只读）：customer_company 四列纯 additive + customer_pool_log 流转表；谓词命中既有 idx_owner 与 idx_customer_company_pool(pool_status,is_deleted)，零新增列/索引/表，V43 未占。
      方案定稿：认领/分派共用 casToPrivate 条件 UPDATE（WHERE id+pool_status=1+is_deleted=0，affected=0 抛错）；50 上限改 assertQuotaNotExceeded 内先锁 sys_user 行（SELECT...FOR UPDATE）再计数；否决持有行 FOR UPDATE（锁不住并发新认领行）与 UPDATE 内嵌计数子查询（RR 快照读不串行化）两备选。
      顺带实测发现：实体无 @TableField(updateStrategy)，MP 默认 NOT_NULL 策略下 updateById 置 null 为空操作——认领不清退回痕迹为既有潜伏缺陷，CAS 显式 set(null) 顺带修正；release 半边属回池红线不动仅披露。
      依据：`work/handoff-fix-pool-claim-race.md` §2/§3。
- [x] 1.2 实现条件更新 + affected=0 业务错误（错误码复用既有语义留痕）；50 上限并发收口
      CustomerPoolServiceImpl 单文件：claim/assign 的 updateById 块替换为 casToPrivate（LambdaUpdateWrapper 单出口 helper，PK 定位 affected∈{0,1}）；affected=0 → BaseException(PARAM_FORMAT_ERROR, "客户已被他人认领或状态已变更，无法认领/分派")——错误码复用 assertInPublicPool 既有 50002 语义，客户端机器码契约不变，消息区分竞态场景。
      assertQuotaNotExceeded 前置 userMapper.selectList(...eq(id).last("FOR UPDATE")) 锁用户行（锁序统一先 sys_user 后 customer_company，规避死锁）；对外接口签名零改动、release/回池/扫描/配额规则零触碰。
      依据：`work/handoff-fix-pool-claim-race.md` §3（SQL 语义与取舍全文）。

## 任务组 2 · 测试与门禁

- [x] 2.1 既有用例全绿 + CAS 失败路径新用例（认领冲突/上限临界，断言错误码与零副作用）；基线只增（实测为准）`--update`；merge-gate 8/8（Docker 在线 9/9）；typed-router 零 diff；产出 `work/handoff-fix-pool-claim-race.md`（真实披露）
      CustomerPoolServiceTest 12/12（9 既有断言形状适配 + 3 新增：claim/assign CAS affected=0 冲突断言 50002+消息+verifyNoInteractions(log)+never updateById；配额临界锁 wrapper 含 FOR UPDATE + InOrder 先锁后计数 + 满额零写库）；测试基建坑 @BeforeAll TableInfoHelper.initTableInfo 按 CustomerIngestionServiceTest 先例补（纯 Mockito JVM 无 UserEntity lambda 缓存）。
      全量 mvn test 1573/0/0（1570→1573，+3 恰为本卡新增）；check-test-baseline.sh --update 写入 1573（source-revision=8587689 净树 stamp），门禁复跑 EXIT=0。
      merge-gate --with-verify（Docker 29.6.2 实测在线）9/9 全绿 EXIT=0：[unit]1573 / [spotbugs]0 / [pmd]0 / [it]118/0/0/7 / [baseline] / [frontend-unit]197/197 / [hook] / [bijection]12↔12 / [pmd-baseline]0==0（日志 work/_fpcr-mergegate.log，HEAD=b56664d）；typed-router.d.ts 行尾噪声还原后零 diff。
      依据：`work/handoff-fix-pool-claim-race.md` §4/§5/§6（含基线笔重做与 reset --hard 自有分支披露）。
