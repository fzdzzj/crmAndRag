# 提案：知识库管理端点文件查询有界分页与排序增强（optimize-knowledge-file-pagination）

> 变更 ID：`optimize-knowledge-file-pagination` ｜ 能力域：`knowledge` / `server` ｜ 执行基线：`master@2427787`
> 依赖：前序 `add-knowledge-admin-api`（已合入 master）、`test-frontend-use-knowledge`（已合入 master）

---

## 1. Why（背景与痛点）

在完成知识库管理端点建设（7 个管理 API，任务组 1–6）与前端测试套件固化（卡 P-k）后，审查 `KnowledgeAdminService.listFiles` 发现以下后端性能与设计缺陷：

1. **无界全量扫描与内存压力**：
   当前 `KnowledgeAdminService.listFiles(Long kbId)` 直接调用 `uploadedFileMapper.selectList(qw)`。当单个知识库或全库可见文件数量较多时，无界 `SELECT *` 会将全部记录加载至 JVM 堆内存并在内存中进行 DTO 映射，极易导致大并发下的 GC 停顿与内存峰值；
2. **缺乏数据库端有界分页能力**：
   既有接口未提供 `pageNum` 与 `pageSize` 参数，调用方无法按需请求单页数据；
3. **测试覆盖缺口**：
   `KnowledgeAdminServiceTest` 目前覆盖了 `listBases`、`upload`、`retrievalTest` 等方法，但唯独缺失针对 `listFiles` 的专属单元测试。

---

## 2. What Changes（变更内容）

### 2.1 控制器与服务层参数增强（100% 向后兼容）
1. **`KnowledgeAdminController.listFiles`**：
   - 增加可选分页与排序参数：
     - `@RequestParam(value = "pageNum", required = false) Integer pageNum`
     - `@RequestParam(value = "pageSize", required = false) Integer pageSize`
     - `@RequestParam(value = "sortOrder", required = false, defaultValue = "desc") String sortOrder`
   - 返回类型保持 `Result<List<KnowledgeFileVO>>`，保证现有前端及 OpenAPI 契约 100% 零破坏兼容。
2. **`KnowledgeAdminService` 逻辑演进**：
   - 保留原单参签名 `listFiles(Long kbId)`，内部默认委托至多参重载 `listFiles(kbId, null, null, null)`，确保二进制与编译级兼容；
   - 增强重载签名 `public List<KnowledgeFileVO> listFiles(Long kbId, Integer pageNum, Integer pageSize, String sortOrder)`；
   - **分页逻辑**：
     - 若 `pageNum` 与 `pageSize` 均为空：保持既有全量 `selectList` 行为（全量查询向后兼容）；
     - 若传入 `pageNum` 或 `pageSize`：通过已有 `PageValidationUtils.validateAndFix(pageNum, pageSize)` 进行边界约束（限制 `1 <= pageSize <= 100`），调用 MyBatis-Plus 的 `uploadedFileMapper.selectPage(new Page<>(validNum, validSize), qw)` 实现数据库级有界分页并返回当页列表；
   - **排序逻辑**：
     - 校验 `sortOrder` 仅允许 `'asc'` 或 `'desc'`（默认 `'desc'`），安全下沉至 `qw.orderBy(true, "asc".equalsIgnoreCase(sortOrder), "create_time")`，彻底规避 SQL 注入风险。

### 2.2 单元测试覆盖
在 `KnowledgeAdminServiceTest.java` 中新增针对 `listFiles` 的完整测试用例套件：
- 缺省全量查询用例：断言调用 `selectList`，验证倒序排序；
- 有界分页查询用例：断言调用 `selectPage`，验证返回指定页码与大小的数据；
- 分页参数超限校验用例：传入 `pageSize = 200` 时，断言被 `PageValidationUtils` 拦截抛出 `BaseException`；
- 排序方向用例：验证 `asc` 升序与 `desc` 降序参数生效；
- 无可见知识库/无权限用例：断言安全返回空列表。

---

## 3. Impact & Boundary（影响与边界）

- **受控写集**：
  1. `src/main/java/com/slz/crm/server/controller/KnowledgeAdminController.java`
  2. `src/main/java/com/slz/crm/server/service/KnowledgeAdminService.java`
  3. `src/test/java/com/slz/crm/server/service/KnowledgeAdminServiceTest.java`
  4. `scripts/test-baseline.txt`（仅由基线脚本按真实增加用例写入）
  5. `openspec/changes/optimize-knowledge-file-pagination/`（提案三件套）
  6. `work/task-card-knowledge-file-pagination.md`（任务卡）
- **零破坏边界**：
  - 零 DDL 修改（无需新增数据库迁移，复用现有表结构与索引）；
  - 零新外部依赖（复用 MyBatis-Plus `Page` 与已有 `PageValidationUtils`）；
  - 零契约破坏（未传分页参数时行为与返回格式同原逻辑完全一致）；
  - 权限码保持 `900L`（`KNOWLEDGE_ADMIN_MANAGE`）。

---

## 4. 验收标准

1. **功能与单测**：
   - `KnowledgeAdminServiceTest` 新增 5+ 个单元测试，100% 通过；
   - 全量单测 `mvn -B -ntp test` 908 -> 913+ 用例全绿；
2. **代码卫生与门禁**：
   - 静态分析四项门禁（PMD、SpotBugs、Checkstyle、Spotless）0 违规通过；
   - `scripts/check-test-baseline.sh` 回归基线门禁通过；
   - 换行符严格保持 LF。
