# 增量契约规范：项目文件列表鉴权读批量化（batch-project-file-list-auth-reads）

## 1. 行为契约增量规范

### 契约 1：批量过滤判定矩阵逐字等价（Verdict Matrix Equivalence）
- **GIVEN** 一组项目文件行与当前用户；
- **WHEN** 列表链路（`queryPage` 分页后行集 / `listBy*` 全量行集）走批量过滤入口；
- **THEN** 可读子集与逐行单入口 `canReadProjectFile` 判定**逐行一致**且保持原顺序：超管（roleId=1）整组直通；用户 null/冻结/离职整组空；非超管按 activity(225 权限→creator/参与人) → opportunity(204→owner/creator/approver) → contract(orderId 反查→215→owner/creator) → 无维度且无合同→仅上传人本人 → reservedRead（恒 false）顺序判定，任一维度命中即读。

### 契约 2：权限判定保持逐行实时（No Request-Scoped Permission Snapshot）
- **GIVEN** 判定期间另一连接改派用户角色并提交；
- **WHEN** 批量过滤执行；
- **THEN** 每行的权限链判定仍取**该行判定时刻**的当前角色（`hasPermission` 逐行实时调用，次数不因批量化减少）；角色改派交错回归（`runRoleReassignInterleavingRegression`）必须原样绿。不引入任何请求级/跨行权限快照。

### 契约 3：SQL 批量化范围受限（Bounded Batching Scope）
- **GIVEN** 一次列表请求 N 行；
- **WHEN** 批量入口执行；
- **THEN** 仅三类读被批量化：`sys_user` 读 N→1、维度实体/订单项读 N×维度→去重后 `selectBatchIds` ≤4 次、参与人查询 N→1 次 IN；`auth/permission`（权限链联查）次数不减。已知取舍：单行路径维度权限 false 时不查实体，批量路径无条件预取（多读但预取数据不参与无权限行判定，判定等价）。

### 契约 4：对外契约零变化（Frozen External Contract）
- **GIVEN** 任一列表端点调用；
- **WHEN** 批量化上线后调用；
- **THEN** `/query` 的 total=库内条件总数（records 可不足额/为空）、四 list 端点 List 形状、Page 字段、records 顺序、下载令牌签发与下载时独立复核边界，全部与批量化前逐字一致；单行入口 `canReadProjectFile` 行为零变化（下载复核路径不碰）。
