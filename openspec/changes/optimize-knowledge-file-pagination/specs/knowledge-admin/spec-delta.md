# Knowledge Admin Spec Delta: optimize-knowledge-file-pagination

## 1. Scope and Target
本规范定义知识库管理端点 `GET /knowledge/files` 及其服务层实现 `KnowledgeAdminService.listFiles` 的有界分页与安全排序行为契约增量。通过引入基于 MyBatis-Plus 的 `selectPage` 能力，在保持既有 API 契约 100% 向后兼容的同时，杜绝大规模数据下的无界内存加载隐患。

---

## 2. Requirements & Scenarios

### 2.1 缺省全量查询保持 100% 兼容
- **Requirement**: 当调用方未传递 `pageNum` 与 `pageSize` 参数时，系统 SHALL 保持既有行为，调用 `uploadedFileMapper.selectList(qw)`，按 `create_time` 倒序返回用户可见的全部文档列表。
- **Scenario: 缺省全量查询**:
  - **GIVEN** 用户拥有指定或全量可见知识库读取权限
  - **WHEN** 发起不带 `pageNum` 与 `pageSize` 的查询请求
  - **THEN** 调用 `uploadedFileMapper.selectList`，返回完整的 `List<KnowledgeFileVO>`。

### 2.2 有界分页与参数防御约束
- **Requirement**: 当调用方传递了 `pageNum` 或 `pageSize` 时，系统 SHALL 调用 `PageValidationUtils.validateAndFix(pageNum, pageSize)` 进行边界校验与修正，并调用 `uploadedFileMapper.selectPage(new Page<>(validNum, validSize), qw)`，仅将当页命中记录映射为 `KnowledgeFileVO` 返回。
- **Scenario: 合法单页查询**:
  - **GIVEN** 知识库中有 25 个文档，用户请求 `pageNum=2`, `pageSize=10`
  - **WHEN** 执行 `listFiles`
  - **THEN** 系统构造 `Page<>(2, 10)`，调用 `selectPage`，返回第 11 至 20 条记录。
- **Scenario: 页大小超限防御拦截**:
  - **GIVEN** 用户请求 `pageSize=101`（超过最大限制 100）
  - **WHEN** 执行 `listFiles`
  - **THEN** 系统立即抛出 `BaseException(ErrorCode.PARAM_FORMAT_ERROR)`，严禁进入数据库全量扫描。

### 2.3 安全排序规则
- **Requirement**: 系统 SHALL 支持 `sortOrder` 参数（可选，默认 `"desc"`）。仅允许区分大小写的 `"asc"` 或 `"desc"`，其余非法字符串 MUST 安全回落至 `"desc"`，排序字段固定限定在 `create_time`。
- **Scenario: 显式升序请求**:
  - **GIVEN** 用户传入 `sortOrder="asc"`
  - **WHEN** 执行 `listFiles`
  - **THEN** SQL 查询条件包含 `ORDER BY create_time ASC`。
- **Scenario: 非法排序参数安全回落**:
  - **GIVEN** 用户传入 `sortOrder="drop table"` 或其他非法参数
  - **WHEN** 执行 `listFiles`
  - **THEN** 系统安全回落为 `ORDER BY create_time DESC`，绝不拼接不受信 SQL。

---

## 3. Quality & Constraints

1. **零架构风险**：
   - 零 DDL 修改，不引入新表或修改表结构；
   - 零新增外部依赖，复用 MyBatis-Plus 与已有 `PageValidationUtils`；
   - 保持原有权限码 `900L`（`KNOWLEDGE_ADMIN_MANAGE`）不变。
2. **代码卫生与门禁**：
   - Surefire 全量单测通过（新增 5+ 用例，总数 913+）；
   - PMD / SpotBugs / Checkstyle / Spotless 四项门禁 0 违规；
   - 换行符严格保持 LF。
