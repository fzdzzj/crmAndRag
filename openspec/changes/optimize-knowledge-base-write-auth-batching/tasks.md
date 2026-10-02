# 任务分解与留痕：知识库管理端点写权限判定批量化消解 N+1（optimize-knowledge-base-write-auth-batching）

## 0. 执行记录
- 执行分支：`feature/optimize-knowledge-base-write-auth-batching`
- 起点 commit：`master@a018ecd`
- 实施人员：子 agent
- 终审复验：主 agent
- 新增用例：5（`KnowledgeBaseAuthorizationServiceTest` 批量写权限专属用例）
- Surefire 全量总数：920（基线起点 915 → 920，+5）
- 静态分析四门禁：`mvn -B -ntp pmd:check spotbugs:check checkstyle:check spotless:check` 全绿（0 违规）；其间 PMD `CognitiveComplexity` 命中一次（26 > 25），已在同文件内拆分私有方法消解
- 回归基线：`bash scripts/check-test-baseline.sh --update` 写入 `surefire.tests=920`，`bash scripts/check-test-baseline.sh` 复核「回归基线门禁通过」
- 受控写集实交：`KnowledgeBaseAuthorizationService.java` + `KnowledgeBaseAuthorizationServiceTest.java` + `scripts/test-baseline.txt` + 本 `tasks.md`
- 未跑项（默认门禁外）：failsafe IT（`mvn verify`，需 Docker）、真实模型外部调用、生产环境验证。

## 0.1 阻塞说明（须由主 agent / owner 拍板，未擅自扩大写集）
任务卡第二步（授权服务批量方法）已在受控写集内落地；但**任务卡第三步、第四步 2 项无法在受控写集内落地**，实测证据如下：

1. 卡片声称 `KnowledgeAdminService.listBases()` 逐行调用 `canWrite` 造成 N+1，**实测不成立**：`listBases()` 走 `KnowledgeAdminResponseMapper.toBaseVO(...)`，不做任何逐行 SQL；`git log -S"setCanWrite"` 与 `grep setCanWrite|getCanWrite` 全仓零命中，该 N+1 从未存在。
2. 卡片第三步与 `spec-delta` 规则 5 要求 `vo.setCanWrite(...)`；但 `KnowledgeBaseVO` 仅有 `id/name/displayName/visibility(String)/ownerUserId/createTime`，**无 `canWrite`、无 `description`**，且 `visibility` 为 `String` 而 `b.getVisibility()` 为枚举——按卡片原样写入**编译不通过**。
3. 补齐 `canWrite` 必须改 `KnowledgeBaseVO.java`（POJO）或前端契约 `frontend/openapi.yaml`，**均不在受控写集内**，且卡片明令「严禁修改任何实体类或 POJO」；`check-write-set` 会把越界文件判为 `ILLEGAL_FILE`。

处置：本次仅交付写集内**可独立复用且可验证**的批量判定能力；端点接入（2.3）与其单测（3.2）、以及 `--no-ff` 合并（5.2）**暂缓**，等待 owner 决定「扩展写集允许新增 `KnowledgeBaseVO.canWrite`」或「修订任务卡」。

## 1. 任务分解清单

### 阶段 1：分支检出与基线准备
- [x] 1.1 从 `master` 检出特性分支 `feature/optimize-knowledge-base-write-auth-batching`
- [x] 1.2 确认工作树状态纯净，无冲突未决改动

### 阶段 2：服务层批量写权限判定实现
- [x] 2.1 在 `KnowledgeBaseAuthorizationService` 中新增 `resolveWritableKnowledgeBaseIds(UserContext, List<KnowledgeBaseEntity>)` 方法
- [x] 2.2 严格实现超管与负责人内存短路、非本人库一次 `selectList` 批量 IN 查询（PMD 阈值下拆分为 `addActiveIds` / `addOwnedAndMemberIds` / `addMemberIds` 私有方法）
- [ ] 2.3 在 `KnowledgeAdminService.listBases()` 中接入批量判定，消解 N+1 查询（**阻塞**：VO 无 `canWrite` 字段，见 §0.1）

### 阶段 3：单元测试建设与验证
- [x] 3.1 在 `KnowledgeBaseAuthorizationServiceTest` 中补齐批量写权限测试（超管零 SQL、自有库零 SQL、协作库恰一次批量 IN、VIEWER/非成员排除、软删库排除、null/空入参容错）
- [ ] 3.2 在 `KnowledgeAdminServiceTest` 中补充 `listBases` 批量授权协作测试（**阻塞**：依赖 2.3，见 §0.1）
- [x] 3.3 运行定向单测 `KnowledgeBaseAuthorizationServiceTest`（12 用例，100% 通过）

### 阶段 4：质量门禁与基线核验
- [x] 4.1 运行全量单测 `mvn -B -ntp test`：Tests run: 920, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS
- [x] 4.2 运行静态分析 `mvn -B -ntp pmd:check spotbugs:check checkstyle:check spotless:check`：BUILD SUCCESS，0 违规
- [x] 4.3 经 `scripts/check-test-baseline.sh --update` 更新 `scripts/test-baseline.txt` 至 `surefire.tests=920`，复核门禁通过
- [x] 4.4 运行 `check-line-endings lf` 与 `check-write-set`，确保换行与写集完全合规

### 阶段 5：分支合并与终审准备
- [x] 5.1 特性分支提交代码
- [ ] 5.2 切换回 `master` 执行真 `--no-ff` 合并（**暂缓**：验收规则 5 未成立，见 §0.1，待 owner 拍板）
- [ ] 5.3 严格停步，等待主 agent 亲跑终审与 owner 推送授权（绝对禁止 git push）
