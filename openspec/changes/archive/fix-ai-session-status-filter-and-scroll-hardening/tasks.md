# 任务分解与留痕：AI 会话软删除状态过滤与滚动查询加固（fix-ai-session-status-filter-and-scroll-hardening）

## 0. 执行记录
- 执行分支：`feature/fix-ai-session-status-filter`
- 起点 commit：`master@cdb074d`
- 实施人员：子 agent
- 终审复验：主 agent
- 新增用例：2（`AiSessionServiceImplTest` 由 7 增至 9，定向 `Tests run: 9, Failures: 0, Errors: 0, Skipped: 0`）
- Surefire 全量总数：913 -> 915（`mvn -B -ntp test`，0 失败 0 错误 0 跳过，BUILD SUCCESS）
- 静态分析四门禁：`mvn -B -ntp pmd:check spotbugs:check checkstyle:check spotless:check` 全绿（0 违规，BUILD SUCCESS）
- 回归基线：`bash scripts/check-test-baseline.sh --update` 写入 surefire 164/915/0、failsafe 24/83/6；复校门禁退出码 0
- 受控写集实交：2 Java 文件 + `scripts/test-baseline.txt` + 本 `tasks.md`
- 实现说明：`scrollSessions` 仅追加一行 `.eq(AiSessionEntity::getStatus, 1)`，方法仍为紧凑单出口；单测以 `@BeforeAll` 初始化 MyBatis-Plus `TableInfo` 后捕获 `LambdaQueryWrapper`，断言 SQL 片段含 user_id/status/游标/LIMIT 且过滤值为 status=1。
- 未跑项（默认门禁外）：failsafe IT（`mvn verify`，需 Docker）、真实模型外部调用、生产环境验证。

## 1. 任务分解清单

### 阶段 1：分支检出与基线准备
- [x] 1.1 从 `master` 检出特性分支 `feature/fix-ai-session-status-filter`
- [x] 1.2 确认工作树状态纯净，无冲突未决改动

### 阶段 2：服务层查询过滤加固
- [x] 2.1 在 `AiSessionServiceImpl.scrollSessions` 中追加 `.eq(AiSessionEntity::getStatus, 1)` 过滤条件
- [x] 2.2 保持方法紧凑单出口，遵从 PMD `OnlyOneReturn` 规约

### 阶段 3：单元测试建设与验证
- [x] 3.1 在 `AiSessionServiceImplTest` 中补齐 `scrollSessions` 专属单元测试（覆盖仅查活跃会话与游标条件应用）
- [x] 3.2 运行定向单测 `mvn test -Dtest=AiSessionServiceImplTest` 确保 100% 通过

### 阶段 4：质量门禁与基线核验
- [x] 4.1 运行全量单测 `mvn -B -ntp test` 确保无回归失败
- [x] 4.2 运行静态分析 `mvn -B -ntp pmd:check spotbugs:check checkstyle:check spotless:check` 确保 0 违规
- [x] 4.3 经 `scripts/check-test-baseline.sh --update` 更新 `scripts/test-baseline.txt`，确保基线校验通过
- [x] 4.4 运行 `check-line-endings lf` 与 `check-write-set`，确保换行与写集完全合规

### 阶段 5：分支合并与终审准备
- [x] 5.1 特性分支提交代码
- [x] 5.2 切换回 `master` 执行真 `--no-ff` 合并
- [x] 5.3 严格停步，等待主 agent 亲跑终审与 owner 推送授权（绝对禁止 git push）
