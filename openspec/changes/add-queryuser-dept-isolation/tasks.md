# 任务清单 · queryUser 数据权限部门隔离 (add-queryuser-dept-isolation)

<!-- id: add-queryuser-dept-isolation -->
<!-- verify: bash scripts/merge-gate.sh -->

## 任务组 1 · 工具隔离实现

- [x] 1.1 定位 `queryUser` AI 工具实现（Grep `queryUser` 于 `src/main/java`），在查询处按当前操作用户 deptId 过滤结果（仅同部门）；豁免角色对齐拦截器既有口径（超管直通等），handoff 留痕豁免清单
      - 实现：`AiExtendedQueryToolExecutors.executeQueryUser` 经 `BaseUnit.getCurrentRole()` 取当前操作用户，非豁免按 deptId 过滤结果（`filterQueryUserByDept`）；豁免 roleId=1 超管全量。豁免清单与依据见 `work/handoff-add-queryuser-dept-isolation.md` §2。
- [x] 1.2 工具描述同步更新（结果范围受限说明），防止 LLM 幻觉承诺全量
      - `AiToolRegistry` queryUser 描述追加「结果范围受部门数据隔离限制，仅返回当前操作用户所属部门内的用户」；AiEval cases.json 仅断言工具名与参数、不涉描述字样，无需同步。

## 任务组 2 · 测试与门禁

- [x] 2.1 单测：同部门可见 / 跨部门不可见 / 豁免角色全量 / 无部门用户边界；`mvn -B -ntp test` 基线只增后 `--update`；merge-gate 8/8（Docker 在线 9/9）；typed-router 零改动（预期无前端改动）；产出 `work/handoff-add-queryuser-dept-isolation.md`（真实披露）
      - 四向单测挂 `AiQueryToolExecutorsTest`（27 run 全绿，新增 4 例）；基线 surefire.tests 1480→1484（+4）经 `scripts/check-test-baseline.sh --update` 写库；merge-gate `--with-verify` 9/9 全绿；typed-router 零 diff（vitest 行尾噪声已 `git checkout --` 还原）。
