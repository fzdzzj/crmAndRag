# 增量契约规范：项目文件列表行级鉴权同请求复用（optimize-project-file-list-auth-reuse）

## 1. 行为契约增量规范

### 契约 1：列表路径行级判定恰一次（Single Verdict Per Row）
- **GIVEN** 任一项目文件列表读取路径（`queryPage` / `listByActivityId` / `listByOrderId` / `listByContractId` / `listByOpportunityId`）扫描到 N 行候选记录；
- **WHEN** 组装列表响应；
- **THEN** `attachmentAccessService.canReadProjectFile` 对每个候选行在同一请求内恰好调用 1 次；不可读行被过滤且不签发下载令牌，可读行直接签发。

### 契约 2：分页口径保持（Total Semantics Preserved）
- **GIVEN** `queryPage` 数据库条件总数为 T、其中当前用户可读 k 行；
- **WHEN** 返回 `Page<ProjectFileVO>`；
- **THEN** `records` 仅含 k 行可读记录，`total` 仍为数据库条件总数 T（含不可读行）；全无权时 `records` 允许为空而 `total` 非零。

### 契约 3：下载端复核不放松（Download-time Recheck Unchanged）
- **GIVEN** 任一已签发的项目文件下载令牌；
- **WHEN** 持令牌请求 `PublicAttachmentController` 下载端点；
- **THEN** 下载端仍逐次独立调用 `canReadProjectFile` 复核，权限被改派/回收后令牌立即失效；列表内判定复用严格限于单请求，不得构成跨请求的角色/权限缓存。
