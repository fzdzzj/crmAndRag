# 任务卡 · unify-vector-ops-endpoints（向量运维端点统一收口，B 档，三卡序列 1/3）

> 目标：`POST /knowledge/admin/sync/{entity}` + `/all` 统一收口五实体散落 reingestAll；前端运维卡片。
> 锚点：Grep `reingestAll` 定位五处；KnowledgeAdminController 既有权限常量复用。

## 红线

- **成本红线：单测全 mock 向量服务，零真实重嵌入；首次真实调用 /sync/* 须 Owner 显式授权**；
- 零迁移 / 零权限常量 / 零冻结契约；禁碰 typed-router.d.ts（再生即还原，分支零 diff）；
- 不改各实体回埋服务实现，只做入口委托。
- 验收：枚举校验/委托断言/权限锚单测；基线 1472 只增；merge-gate 8/8（Docker 在线 9/9）；契约同步。

## 交付纪律

- 分支 `feature/unify-vector-ops-endpoints`（自 master 检出，线性提交，完工树净）；
- 完工停步等 Owner：不 merge、不 push；tasks 勾选 + 6 空格依据行；handoff 真实披露。
