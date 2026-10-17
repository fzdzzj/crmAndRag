# 提案 · 写端点参数校验收口 (add-write-endpoint-validation)

> 档位：A 档（大写集：34 controller 域；复核按 A 档全量）
> 来源：`work/_paa-candidates-pool-v2.md` §2b J-1（第一轮 D 面关联）；2026-10-17 Owner 拍板本批三卡之首
> 基线：master@17008e2 起算，实际派发以届时 master 实测为准（队列位：D4 → D6 → **本卡** → I-2 → 暗线①）

## 1. 背景与目标

34 个 controller 仅 2 个写端点挂 `@Valid`；validation starter 与统一 400 handler（ErrorCode.PARAM_FORMAT_ERROR
通道）均已就绪——「刹车造好了没装车」（J-1 实测锚点见 v2 池条目）。本卡全局收口：

1. 写端点（POST/PUT/DELETE 的 `@RequestBody`）controller 参数补 `@Valid`；
2. DTO 字段按 **openapi.yaml 契约 required 定义**补 jakarta.validation 注解（@NotNull/@NotBlank/@Size 等）——
   **契约未标 required 的字段一律不加非空约束**（零语义破坏的安全口径）；
3. 反向单测：每 controller 至少 1 条非法请求断言 400 + 全局 handler 接管（可参数化/基类复用）。

## 2. 非目标

零迁移 / 零权限常量 / 零冻结契约（openapi.yaml 只读对照，不改契约本身）；不删既有服务层手动校验
（注解为增量防御）；不动读端点（GET）与既有单测语义。

## 3. 验收

- 全量写端点 @Valid 覆盖（写集清单化，逐 controller 核对）；基线只增；merge-gate 8/8（Docker 9/9）；
  typed-router 零 diff（零前端）；若执行中发现体量超限（>80 文件），回报 Owner 拆 A/B 批，禁擅自裁剪。
