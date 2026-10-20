# 提案 · fix-backlog-batch3（待办池清空卡：⑤精确码 + ⑨批量上限 + ⑩循环收口 + ⑪附件孤儿债，B 档）

## 为什么（待办池 ⑤⑨⑩⑪，2026-10-21 权威树实读）

- **⑤ 「已存在」无精确码**：`Result.error(String)` 默认 `code=0`（Result.java:32-37）；`SQLExceptionHandler` duplicate-key 三出口（组合文案/单列文案/兜底「数据已存在，无法重复添加」）全走它 → 唯一约束冲突这类可预期业务冲突对外是无语义的 0 码，与参数校验 50002（GlobalExceptionHandler:102-110 MethodArgumentNotValidException → PARAM_FORMAT_ERROR）在产品侧近义却不可区分。前端 `apiClient.ts:45` 响应拦截器只判 `code !== 1` → 改码零前端影响。
- **⑨ 批量 IN 长列表无上限**：16 个批量端点把 `List<Long>` 全量塞进 `IN`（清单见 tasks 2.2），既无空集闸也无 row 上限，超长请求直接生成巨型 SQL。
- **⑩ afterCommit 型循环**：`CustomerCompanyServiceImpl:303-309` `batchLogicalDeleteByIds` 仍 `for (id) logicalDeleteById(id)`（每 id 一趟级联 + 一次 afterCommit 注册），且无 PARAM_EMPTY 闸；批量基建 `ForeignKeyDeleteUtil.logicalDeleteByCascadeBatch`（:110-120）已存在未接线。
- **⑪ 附件孤儿债（两点）**：
  - **A 跨模型误删**：`ApprovalAttachmentServiceImpl.removeByIds`（:132-149）select 按 `id IN + model_name` 过滤，但 :148 `deleteBatchIds(ids)` 删**原始 ids**——ids 混入他域附件 id 时，被过滤掉的行仍被删（跨模型误删实锤；正确形态参照同文件 removeSourceAttachmentsByDistinctIds:311-312 只删过滤后行）。
  - **B 物理级联孤儿**：`approval_attachment` 不在 ForeignKeyConfig 任何关联图（死边卡移除后子图为空，config :163-170 注释明示）；三处物理级联删调用方绕开 canonical 附件清理，子宿主附件成孤儿（行+磁盘文件均残留）：① `CustomerCompanyServiceImpl.deleteByIds:259`（级联删 business_activity/contact_task 等）；② `CustomerContactServiceImpl` 物理删 :261（级联删 contact_task）；③ `SalesOpportunityServiceImpl.delete:164`（级联删 business_activity/contact_task——死边卡只补了 APPROVAL_ATTACHMENT 域）。
  - **勘误**：待办池表述中 assist_request 域实勘无洞——它是逻辑删域且删除走 `deleteByRecords` 已含 `removeByAndIds(ASSIST_REQUEST)`（AssistListQueryCoordinator:107）。

## 做什么

1. **⑤ 精确码**：`ErrorCode` 新增 `DATA_ALREADY_EXISTS(92029, "数据已存在", "error.data.already.exists")`（92xxx 业务逻辑错误段，下一可用号；92028 已用；i18n properties 文件不存在，messageKey 仅枚举字段零同步负担）；`SQLExceptionHandler` duplicate-key 三出口改 `Result.error(ErrorCode.DATA_ALREADY_EXISTS, 原文案)`——**文案逐字节不变，code 0→92029（行为变化拍板项）**；FK/缺省值路径不动。
2. **⑨ 统一上限闸**：`common/untils` 新增 `BatchIdsGuard`（静态：null/空集 → PARAM_EMPTY；distinct 后 >500 → PARAM_OUT_OF_RANGE 带上限文案；500 为 Guard 常量并注明选型依据）；16 端点统一接入（每处一行，@RequestBody/@RequestParam 两形态都覆盖）。**不做服务端自动分批**——批量删除分批会产生多事务部分提交中间态，超限硬拒让调用方自行分批重试。
3. **⑩ 批量单趟化**：`batchLogicalDeleteByIds` 改 `foreignKeyDeleteUtil.logicalDeleteByCascadeBatch(CustomerCompanyEntity.class, idList)` + PARAM_EMPTY 前置闸；afterCommit 向量清理保持逐公司语义（`customerIngestionService` 无批量接口，idList.forEach 注册）。
4. **⑪ 附件债**：A = `removeByIds:148` 改删过滤后 entities 的 id；B = 三调用方在 `deleteCascade` **之前**先查子宿主 id 集再 `removeByAndIds(ids, 域)`（形态与死边卡 `deleteStageApprovalAttachments` 一脉相承，不动 ForeignKeyConfig 关联图）。**行为变化拍板项**：物理级联删从「留孤儿附件」变为「连带清理附件（行+磁盘）」。

## 红线

零数据库迁移 / 零新依赖 / 除 ⑤ DATA_ALREADY_EXISTS(92029) 外零新增错误码 / 不动 ForeignKeyConfig 关联图 / 不改冻结契约 / frontend/ 零 diff（⑤ 错误码不在 openapi 契约面；若 springdoc 镜像出现 diff 走 `check-openapi-consistency.sh --update` 唯一合法入口并留证）/ 基线只增不减 / 严禁 git push、merge、reset/rebase/amend、删分支。

## 档位

**B 档**：门禁退出码 0 + 主 Agent 审 diff 裁决（无迁移、无权限矩阵变化、无冻结契约改动；⑤ 前端零影响有实证、⑪ 有死边卡形态 C 先例）。

## 验收口径

定向 + 全量 `mvn -B -ntp clean verify`（Docker 实测）+ 树净单次 `check-test-baseline.sh --update` + `merge-gate.sh` 10/10；handoff 含 ⑤ 前端影响面实证（apiClient 判定逻辑）与既有测试断言同步清单、⑨ 16 端点接入对照表、⑩ 单趟化前后对照、⑪ 跨模型误删用例与孤儿债 IT（Testcontainers：物理级联删后附件行清零 + 跨模型同 and_id 对照存活）。停步：未 merge 未 push。
