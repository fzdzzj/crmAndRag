# 任务卡 · fix-pool-claim-race（公海认领/分派竞态 CAS 修复，B 档，本批三卡 2/3）

> 目标：认领/分派条件更新收口并发竞态，50 上限原子化——零迁移优先。
> 锚点：CustomerPoolServiceImpl L98 无条件 updateById（v2 池 I-2 全锚点）；§108 公海池闭环先例。

## 红线

- 零迁移（V43 不占，禁加 version 列）/ 零权限常量 / 零前端；不动公海业务语义与对外接口签名；
  单测禁真并发计时测试（mock CAS 失败路径）。
- 验收：既有用例全绿 + 失败路径新用例；基线只增；merge-gate 8/8。

## 交付纪律

- 分支 `feature/fix-pool-claim-race`（自 master 检出，线性提交，完工树净）；
- 完工停步等 Owner：不 merge、不 push；B 档独立复核（门禁 exit 0 主证 + 写集 diff 审查）。
