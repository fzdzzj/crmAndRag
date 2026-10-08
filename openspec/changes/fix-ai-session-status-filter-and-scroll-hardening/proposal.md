# 提案：AI 会话软删除状态过滤与滚动查询加固（fix-ai-session-status-filter-and-scroll-hardening）

## 1. 变更背景与核心痛点

- **现状取证**：
  1. `AiChatController` 在 `DELETE /sessions/{id}` 端点明确声明为「归档会话（软删 status=0），并级联取消其 PENDING 待确认操作」；
  2. 实体类 `AiSessionEntity` 中定义字段 `@Column(comment = "1=活跃 0=归档") private Integer status;`，`createSession` 时初始化赋值 `status = 1`；
  3. 然而，在 `AiSessionServiceImpl.scrollSessions` 中构造 MyBatis-Plus `LambdaQueryWrapper` 时，仅按 `userId` 进行过滤与 `(updatedTime, id)` 游标倒序，**遗漏了对 `status = 1` 的过滤条件**；
  4. 导致已被用户归档软删除（`status = 0`）的会话，仍然持续出现在 `GET /sessions` 返回的会话列表中；前端展示该会话后，用户若点击并尝试发送消息，会触发下游 `AiChatResumeSupport.rejectIfArchived` 拦截报错，造成明显的前后端状态断裂；
  5. `AiSessionServiceImplTest` 当前仅覆盖了归档后图片上下文缓存释放与事务同步，完全缺失对 `scrollSessions` 状态过滤与游标分页的服务层单测。

## 2. 变更方案与契约设计

1. **服务层查询过滤加固**：
   - 在 `AiSessionServiceImpl.scrollSessions` 中明确追加 `.eq(AiSessionEntity::getStatus, 1)`；
   - 确保返回给调用方的滚动会话列表仅包含活跃（`status = 1`）的会话，已归档会话自动剔除。
2. **专属单元测试补齐**：
   - 在 `AiSessionServiceImplTest` 中新增对 `scrollSessions` 的专属用例：
     - `scrollSessions_activeOnly_excludesArchivedSessions`：断言构造的 QueryWrapper 包含 `status = 1`，过滤归档会话；
     - `scrollSessions_withCursor_appliesCursorCondition`：断言带有 `cursorTime` 与 `cursorId` 时的复合游标分页逻辑。
3. **向后兼容性**：
   - 不改变 Controller 签名、不改变 API 出参契约结构（`AiSessionVO` 字段保持不变），100% 向后兼容。

## 3. 受控写集（Strict Write Set）

严格限定在以下 4 个文件：
1. `src/main/java/com/slz/crm/server/service/impl/AiSessionServiceImpl.java`
2. `src/test/java/com/slz/crm/unit/service/AiSessionServiceImplTest.java`
3. `scripts/test-baseline.txt`
4. `openspec/changes/fix-ai-session-status-filter-and-scroll-hardening/tasks.md`

## 4. 禁止事项与边界

- 严禁碰触任何数据库迁移脚本（禁止任何 DDL）；
- 严禁修改实体类 `AiSessionEntity.java`；
- 严禁修改控制器接口签名；
- 严禁引入任何新的依赖；
- 绝对禁止执行 `git push`（推前必须停步等待 owner 显式授权）。
