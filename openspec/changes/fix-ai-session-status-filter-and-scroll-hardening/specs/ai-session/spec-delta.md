# 增量契约规范：AI 会话状态过滤（fix-ai-session-status-filter-and-scroll-hardening）

## 1. 行为契约增量规范

### 契约 1：活跃会话列表滚动查询（Active Session Scroll）
- **GIVEN** 当前用户下存在状态为活跃（`status = 1`）与已归档软删（`status = 0`）的多条会话；
- **WHEN** 调用 `aiSessionService.scrollSessions(userId, cursorTime, cursorId, limit)`；
- **THEN** 查询语句必须包含 `status = 1` 过滤条件，返回的列表中仅含有活跃会话，已归档会话被彻底排除。

### 契约 2：游标分页延续性（Cursor Continuity）
- **GIVEN** 传入有效的 `cursorTime` 与 `cursorId`；
- **WHEN** 调用 `aiSessionService.scrollSessions(userId, cursorTime, cursorId, limit)`；
- **THEN** 必须同时满足 `(updated_time < cursorTime) OR (updated_time = cursorTime AND id < cursorId)` 以及 `status = 1`，按最后活跃时间与 ID 双重倒序排列并受 `LIMIT` 约束。
