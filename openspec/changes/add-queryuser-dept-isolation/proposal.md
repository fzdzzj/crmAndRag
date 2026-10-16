# 提案 · queryUser 数据权限部门隔离 (add-queryuser-dept-isolation)

> 档位：B 档（零 DB 迁移 / 零权限常量新增 / 零 LLM 外呼 / 零冻结契约触碰）
> 来源：`work/_paa-candidates-pool.md` §7 决策点 D3（L167）与优化清单第 2 项（L175）
> 基线：master@1c314b7（排队卡：待 unify-vector-ops-endpoints 闭环后派发）

## 1. 背景与目标

AI 工具 `queryUser` 当前允许查询全量用户，低权限销售可枚举全公司员工信息（PII 泄露面，§110 D3）。
本卡加**部门隔离纵深**：

1. `queryUser` 工具实现内按**当前操作用户 deptId** 过滤查询结果（仅返回同部门用户）；
2. 豁免语义：超管（roleId=0 拦截器直通角色）与既有数据权限语义中已豁免的角色保持可见全量
   ——以拦截器/切面既有豁免口径为准，不新造豁免规则；
3. 工具描述（tool description）同步更新，让 LLM 知晓结果范围受限，避免幻觉承诺。

## 2. 非目标

零迁移、零权限常量、零冻结契约；不改 `queryUser` 之外的 AI 工具；不动 PermissionsInterceptor。

## 3. 验收

- 单测：同部门过滤 / 跨部门不可见 / 豁免角色全量 / 空部门边界；基线只增；
  merge-gate 8/8；AI 工具描述一致性测试若存在则同步更新。
