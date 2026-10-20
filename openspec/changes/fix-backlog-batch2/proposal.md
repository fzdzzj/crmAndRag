# 提案 · fix-backlog-batch2（待办池第二批：SQLExceptionHandler 收口 + DTO 校验 + Reflector 阈值，B 档）

## 为什么（待办池 ⑦⑧⑫，2026-10-20 实读）
- **⑦** `SQLExceptionHandler`（server/handler）：:48 `indexName.split("\\.")[1]` 无守卫，裸索引名直接数组越界；:50 `split.length > 0` 恒真致 :53-56 死代码、分支结构错乱；`databaseFieldMap` 列名/索引名键空间共用（A-1 收敛并入后混居）；`doesn't have a default value` 裸列名路径误走索引名方法致字段名丢失（「：数据」）。
- **⑧** 关联维护 5 个 DTO 的 id 字段均无 `@NotNull`（BusinessActivityDTO/BusinessActivityUserDTO/BusinessActivityContactDTO/AddActivityUserRequestDTO/AddActivityContactRequestDTO），多 null id 深入到 list-vs-set 比对才拦截。
- **⑫** `RuleSelfRagReflector` 仍读编译期 `CitationAligner.KEEP_MIN`，与已动态化的对齐器阈值分叉。

## 做什么
1. **⑦ 四子项**：① :48 越界守卫（parts 判长，裸名走回落链）；② 死代码清理与分支重构（组合索引命中→组合文案；未命中→getFieldNameByIndex；恒真条件删除）；③ 键空间结构化隔离——索引名 7 键迁出至独立 map（yml 新键段 + @Value 新字段 + 全仓 grep databaseFieldMap 消费面确认列名路径不破）；④ SQL 裸列名路径改走 getNameByIndex 列名直查（**行为改善拍板项**：文案「数据」→ 实际字段名）。
2. **⑧** 5 DTO id 字段挂 `@NotNull`（controller 已 100% @Valid，注解即生效；**行为变化拍板项**：多 null id 从 50002 深层拦截提前到 400 校验拒绝，前端提示形态不变）。
3. **⑫** Reflector 阈值注入：经 `CitationThresholdsResolver`（同包）统一注入，复用上卡范式（Supplier/宿主传入，核心零 Spring 不破）；默认行为零漂移。

## 红线
零数据库迁移 / 零新依赖 / 零新增错误码 / 不改冻结契约 / 不动前端（frontend/ 零 diff，错误码与对外文案语义不变——④ 为明确拍板的行为改善例外）/ 基线只增不减 / 严禁 git push、merge、reset/rebase/amend、删分支。

## 档位
**B 档**：门禁退出码 0 + 主 Agent 审 diff 裁决。

## 验收口径
定向 + 全量 verify + 单次基线 --update + merge-gate 10/10；handoff 含 ⑦ 四子项前后对照（越界用例/分支覆盖表/消费面清单/文案对照）、⑧ 行为变化论证、⑫ 注入路径图与零漂移论证。停步：未 merge 未 push。
