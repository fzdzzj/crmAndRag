# Tasks — optimize-knowledge-file-pagination

> 执行契约见 `openspec/git-workflow.md`。执行基线：`master@2427787`。
> 约束：受控写集严格限定在 4 个核心文件；禁止修改 DDL；保持 100% 向后兼容；代码与文档纯 LF。

---

## 0. 执行记录（执行 agent 填写）
- 特性分支：`feature/optimize-knowledge-file-pagination`
- 新增用例预期：>= 5 用例
- 最终汇报项：新增用例数、Surefire 全量总数（908 -> 913+）、静态分析结果（0 violations）、`check-test-baseline.sh` 结果。
- 实际新增用例：5（`KnowledgeAdminServiceTest` 由 11 增至 16，定向 `Tests run: 16, Failures: 0, Errors: 0, Skipped: 0`）
- Surefire 全量总数：908 -> 913（`mvn -B -ntp test`，0 失败 0 错误 0 跳过，BUILD SUCCESS）
- 静态分析四门禁：PMD / SpotBugs / Checkstyle / Spotless 全绿（0 违规，BUILD SUCCESS）
- `check-test-baseline.sh`：`--update` 与复校均退出码 0；门禁自测 `check-test-baseline-selftest.sh` 101 断言 PASSED
- 契约漂移处置：新增 3 个 query 参数触发 `OpenApiContractExportTest` 漂移，经 owner 授权后以 `bash scripts/check-openapi-consistency.sh --update` 同步 `frontend/openapi.yaml`（写集 +1，已获授权）
- 受控写集实交：3 Java 源文件 + `scripts/test-baseline.txt` + 本 `tasks.md` + `frontend/openapi.yaml`（授权项）
- 实现说明：服务层 `listFiles` 采用单出口紧凑写法（`result` 变量 + 末尾唯一 `return`），以满足 PMD `OnlyOneReturn` 与 `NcssCount`（类 <=150 / 方法 <=75）双规则；功能语义与任务卡 §3.2 完全一致。

---

## 1. 控制器层参数增强
- [x] 1.1 在 `KnowledgeAdminController.listFiles` 添加可选参数 `pageNum`、`pageSize`、`sortOrder`（默认 `"desc"`）
- [x] 1.2 将参数透传至 `knowledgeAdminService.listFiles`

---

## 2. 服务层逻辑演进与安全排序
- [x] 2.1 在 `KnowledgeAdminService` 保留原 `listFiles(Long kbId)` 签名，并委托至多参方法
- [x] 2.2 实现重载方法 `listFiles(Long kbId, Integer pageNum, Integer pageSize, String sortOrder)`
- [x] 2.3 分页分支：
  - 若 `pageNum` 与 `pageSize` 均为空：走既有 `uploadedFileMapper.selectList(qw)` 全量查询
  - 若传入分页参数：使用 `PageValidationUtils.validateAndFix(pageNum, pageSize)` 校验并调用 `uploadedFileMapper.selectPage(...)`
- [x] 2.4 排序安全：
  - 仅允许 `"asc"` 或 `"desc"`（忽略大小写，默认 `"desc"`），防范非法排序参数

---

## 3. 单元测试套件开发（KnowledgeAdminServiceTest）
- [x] 3.1 编写缺省全量查询用例：断言调用 `selectList`，验证返回列表与默认倒序
- [x] 3.2 编写有界分页查询用例：断言调用 `selectPage`，验证分页参数与条数
- [x] 3.3 编写分页参数越界防御用例：验证 `pageSize > 100` 时抛出 `BaseException`（`PARAM_FORMAT_ERROR`）
- [x] 3.4 编写排序方向用例：验证 `asc` 升序与 `desc` 降序参数生效
- [x] 3.5 编写未授权/无可见知识库用例：断言短路返回空列表

---

## 4. 全量质量门禁与基线写入
- [x] 4.1 运行定向单测：`mvn test -Dtest=KnowledgeAdminServiceTest`，确证新增用例全绿
- [x] 4.2 运行全量单测：`mvn -B -ntp test`，确保 913+ 用例全绿
- [x] 4.3 静态分析四门禁：`mvn -B -ntp pmd:check spotbugs:check checkstyle:check spotless:check`
- [x] 4.4 更新基线台账：`bash scripts/check-test-baseline.sh --update`
- [x] 4.5 运行门禁自测试套件：`bash scripts/tests/check-test-baseline-selftest.sh`
- [x] 4.6 换行符与写集核验：`scripts/agent-helper.sh check-line-endings lf` 与 `check-write-set`
