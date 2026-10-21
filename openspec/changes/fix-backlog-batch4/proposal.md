# 提案 · fix-backlog-batch4（收尾小卡：⑬ sales_stage_approval 附件残留 + ⑭ 错误码总数断言生成化，B 档）

## 为什么（2026-10-21 权威树 master@71a274b 实读）

- **⑬ sales_stage_approval 域附件残留**（batch3 遗留登记）：`ChildHostAttachmentCleaner` javadoc（:32-33）明示「不清理 sales_stage_approval 域附件（该域由 SalesOpportunityServiceImpl#deleteStageApprovalAttachments 先例负责）」——但该先例只覆盖**商机直删入口**（`SalesOpportunityServiceImpl.delete` 在 deleteCascade 前显式调用）；**公司与联系人两入口**的物理级联删会递归删到审批行（`SALES_OPPORTUNITY_RELATIONS` 含 `SalesStageApprovalEntity.opportunityId` 边），其 `model_name=approval_attachment` 附件成孤儿（行+磁盘残留）。
- **⑭ 错误码总数断言硬编码**（batch3 D1 连带暴露）：`frontend/src/constants/error-code-map.test.ts:20` `BACKEND_ERROR_CODE_NAMES = 100` 手写常量——每次后端加码都要手改，漏改则 Vitest 假红。JVM 侧 `ErrorCodeMapParityTest`（contract 包）虽已逐名双向比对，但 frontend-quality CI job 独立跑 pnpm 不跑 Maven，总数断言是**前端段独立哨兵**，只能生成化、不能删除。

## 做什么

1. **⑬ Cleaner 补审批域清理**：
   - `cleanBeforeCompanyPhysicalDelete` / `cleanBeforeContactPhysicalDelete` 两入口：利用**已算出的** `opportunities` 一级集，`selectIds(salesStageApprovalMapper, COL_OPPORTUNITY_ID, opportunities)` 取审批行 id → `approvalAttachmentService.removeByAndIds(approvalIds, ModelName.APPROVAL_ATTACHMENT)`（注意审批附件的 model_name 是 `APPROVAL_ATTACHMENT`，与先例 `deleteStageApprovalAttachments:211` 口径一致）。
   - 商机入口 `cleanBeforeOpportunityPhysicalDelete` **不加**（`SalesOpportunityServiceImpl.delete` 已有先例调用，重复清理冗余）；javadoc「不做什么」段同步改写。
   - 闭包深度核对结论：审批行挂商机下（二级），公司→商机（一级）→审批行（二级）、联系人→商机（一级）→审批行（二级），`opportunities` 集已现成，无新深度。
   - `SalesStageApprovalMapper` 注入 Cleaner（构造器 +1 参数，调用方零改动——Spring 装配）。
2. **⑭ 总数断言生成化**：test.ts 删硬编码常量，改 Node `fs` 读两枚举源文件（`src/main/java/com/slz/crm/common/enumeration/ErrorCode.java` + `platform/contract/PlatformErrorCode.java`，路径自 frontend/src/constants/ 回仓根，`import.meta.url` 相对推导保证本地 Windows 与 CI Linux 双环境可用），正则计数枚举常量条目（口径：两空格缩进 + 常量名 + `(` + 数字，两枚举同构）；断言 `Object.keys(ApiErrorCode)).toHaveLength(计算值)`。**行为变化拍板项：frontend/ 有一处测试文件 diff（test.ts），属本卡目的本身**；`error-code-map.ts` 生产镜像零 diff（本卡无新码）。

## 红线

零数据库迁移 / 零新依赖 / 零新错误码（不动 ErrorCode 与 PlatformErrorCode 枚举本身）/ 不动 ForeignKeyConfig 关联图 / `error-code-map.ts` 生产镜像零 diff / frontend/ 仅限 `error-code-map.test.ts` 一处测试工程改动 / 基线只增不减（当前 303/1889 + 34/123/7）/ 严禁 git push、merge、reset/rebase/amend、删分支。

## 档位

**B 档**：门禁退出码 0 + 主 Agent 审 diff 裁决（⑬ 与 batch3 ⑪ 同构已裁可 B 档承载；⑭ 纯测试工程）。

## 验收口径

定向 + 全量 `mvn -B -ntp clean verify`（Docker 实测）+ 树净单次 `check-test-baseline.sh --update` + `merge-gate.sh` 10/10（含 `[frontend-unit]`——⑭ 改动必须 Vitest 全绿）；handoff 含 ⑬ 两入口闭包口径论证与商机入口不重复接线论证、⑭ 生成化前后对照与双环境路径论证。停步：未 merge 未 push。
