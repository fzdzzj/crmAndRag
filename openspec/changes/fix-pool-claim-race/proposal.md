# 提案 · 公海认领/分派并发竞态修复 (fix-pool-claim-race)

> 档位：B 档（服务层正确性改造 + 单测；优先零迁移方案）
> 来源：`work/_paa-candidates-pool-v2.md` §2b I-2；2026-10-17 Owner 拍板本批三卡之二
> 基线：master@17008e2 起算，实际派发以届时 master 实测为准（队列位：D4 → D6 → J-1 → **本卡** → 暗线①）

## 1. 背景与目标

`CustomerPoolServiceImpl` L98 认领链路无条件 `updateById`（I-2 实测锚点）：并发认领互覆盖（后写赢，
前者无感知）、50 私海上限 check-then-act 可被并发突破；主管分派同竞态面。本卡以 **CAS 条件更新**
收口（§108 公海池特性闭环后的正确性新证据，非特性重报）：

1. 认领/分派改为条件 UPDATE（WHERE 公海态谓词），affected=0 → 抛「已被认领/状态已变」业务错误（复用
   既有错误码语义，handoff 留痕）；
2. 50 上限在事务内与 CAS 原子化（方案执行侧按 customer/customer_pool_log 实测表结构设计，handoff
   留痕取舍——**优先零迁移**：条件更新/锁行方案，禁为乐观锁擅加 version 列占用 V43）；
3. 并发语义单测：mock CAS 失败路径（affected=0 / 上限临界）断言错误码与零副作用，禁不可靠的真并发
   计时测试。

## 2. 非目标

零迁移（V43 不占）/ 零权限常量 / 零前端（错误提示走既有通道）；不动公海池业务语义（回池/扫描/配额
规则本身）；不改 CustomerPoolService 对外接口签名。

## 3. 验收

- 单测：CAS 成功路径行为不变（既有用例全绿）+ 失败路径新用例（认领冲突/上限突破拦截）；
- 基线只增（实测为准）；merge-gate 8/8（Docker 9/9）；typed-router 零 diff。
