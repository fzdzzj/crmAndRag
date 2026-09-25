# 提案：图片上下文缓存按会话释放

## Why

AiChatImageContextCache 用 sessionId 做外层 Map，每个会话最多 8 条，条目里可以有图片向量。evictSession 没有任何调用方。会话归档只把状态改成 0 并取消待确认操作，不清这个缓存。进程一直活着时，不再访问的会话仍占着向量副本。

**背景**：
- 这不是 JVM 参数问题。缓存没有会话数上限，也没有跨会话的过期清理。
- 单会话 8 条上限已经存在，不要为了变快改掉命中语义。

**当前状态**：put 只淘汰同一会话里的过期条目和超额条目。别的会话不参与。

**期望状态**：会话归档时清掉该会话缓存。put 时同时丢掉已经全部过期的其他会话。缓存条目数有总上限。命中内容、TTL 和单会话 8 条上限不变。

## What Changes

- 归档会话时调用 evictSession。
- put 时清理过期会话，并限制外层 Map 的会话数。
- 不改图片理解结果、不改向量内容、不调堆参数。

## Impact

### 受影响的规范
- spec/changes/update-image-context-session-bound/specs/image-context-cache/spec-delta.md - 新增会话释放与总上限

### 受影响的代码
- src/main/java/com/slz/crm/server/ai/AiChatImageContextCache.java - 过期会话清理和总上限
- src/main/java/com/slz/crm/server/service/impl/AiSessionServiceImpl.java - 归档时释放缓存

### 用户影响
- 归档后的会话不再复用内存里的图片摘要和向量。未归档且未过期的命中不变。

### API 变更
- 无。

### 需要迁移
- [ ] 数据库迁移
- [ ] API 版本提升
- [ ] 用户沟通
- [ ] 文档更新

## 时间线评估

小。只加离线单测，不调用视觉模型。

## 风险

- 归档事务里多一次内存删除：失败不能回滚数据库，所以释放放在事务成功之后，避免归档失败却先清缓存。
- 总上限淘汰仍在使用的会话：上限要大于单机会话常态，并只淘汰最久未访问的会话。测试要锁住未过期命中不被误删。
