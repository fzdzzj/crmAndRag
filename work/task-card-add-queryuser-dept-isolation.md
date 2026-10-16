# 任务卡 · add-queryuser-dept-isolation（queryUser 部门隔离，B 档，三卡序列 2/3）

> 目标：queryUser AI 工具按当前操作用户 deptId 过滤（仅同部门），豁免对齐拦截器既有口径，工具描述同步。
> 锚点：Grep `queryUser` 定位工具实现；豁免参照 PermissionsInterceptor 语义。

## 红线

- 零迁移 / 零权限常量 / 零冻结契约；不改其他 AI 工具与 PermissionsInterceptor；
- 预期零前端改动（typed-router 自然零 diff）。
- 验收：同部门/跨部门/豁免/无部门四向单测；基线只增；merge-gate 8/8。

## 交付纪律

- 分支 `feature/add-queryuser-dept-isolation`（自 master 检出，线性提交，完工树净）；
- 完工停步等 Owner：不 merge、不 push；tasks 勾选 + 6 空格依据行；handoff 真实披露。
