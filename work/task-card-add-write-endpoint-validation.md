# 任务卡 · add-write-endpoint-validation（写端点校验收口，A 档，本批三卡 1/3）

> 目标：34 controller 写端点全量 @Valid + DTO 注解对齐 openapi required，零语义破坏。
> 锚点：v2 池 J-1 条目（34/2 实测）；统一 400 handler 与 validation starter 已就绪。

## 红线

- 契约 required 对表是唯一注解依据（未标 required 零约束）；零迁移/零权限常量/零前端；
  不删服务层既有校验；>80 文件体量超限即停报 Owner 拆批。
- 验收：写集清单化逐 controller 核对；每 controller ≥1 反向 400 用例；基线只增；merge-gate 8/8。

## 交付纪律

- 分支 `feature/add-write-endpoint-validation`（自 master 检出，线性提交，完工树净）；
- 完工停步等 Owner：不 merge、不 push；A 档独立复核（全量门禁 + 写集逐位）。
