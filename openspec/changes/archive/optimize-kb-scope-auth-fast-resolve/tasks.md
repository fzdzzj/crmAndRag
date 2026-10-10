# 任务分解与留痕：知识库指定 scope 授权快速收敛（optimize-kb-scope-auth-fast-resolve）

## 0. 执行记录
- 执行分支：`feature/optimize-kb-scope-auth-fast-resolve`
- 起点 commit：`master@b8c2116`
- 实施人员：子 agent
- 终审复验：复核子 agent（主 agent 只裁决）
- 实现路径选择：**路径 1（QueryWrapper 复刻）**。`selectActiveKnowledgeBaseIdsByUserId`（注解 `@Select`，`KnowledgeBaseMemberMapper.java:23-32`）过滤条件逐条登记：① `m.is_deleted=0`（成员行存活）与 JOIN 半边 `kb.is_deleted=0`（目标库存活）均由 `KnowledgeBaseMemberEntity`/`KnowledgeBaseEntity` 的 `@TableLogic` 在 QueryWrapper selectList 上自动追加承接（两实体注解实测在位）；② `m.user_id=#{userId}` 显式 `.eq` 复刻；③ 成员命中 id 并入知识库定向查询的 OR 分支，配合该查询自身自动追加的 `kb.is_deleted=0` 完整复刻 JOIN 的库存活语义；④ `ORDER BY m.id` 仅顺序项，改 requested 序已获卡面授权（三处消费方零顺序依赖）。无复刻歧义且写集最小（不加 mapper 文件）。
- 新增用例：10 条（定向收敛 7 + 空/null scope 与 null 用户全量回归 3），`KnowledgeBaseAuthorizationServiceTest` 12→22。
- Surefire 全量总数：938（928+10，只增不减）。
- 受控写集实交：`KnowledgeBaseAuthorizationService.java` + `KnowledgeBaseAuthorizationServiceTest.java`（P-n 既有文件，实际路径 `src/test/java/com/slz/crm/unit/knowledge/auth/`，卡面写集路径缺 `unit/` 段，以仓库实际为准）+ `scripts/test-baseline.txt` + 本 `tasks.md`（4 文件；路径 1 无 mapper 文件）。
- 未跑项（默认门禁外）：failsafe IT（`mvn verify`，需 Docker）、真实模型外部调用、生产环境验证。
- 实测记录（2026-10-02，子 agent 自跑）：红（未改主代码）`mvn -B -ntp -Dtest=KnowledgeBaseAuthorizationServiceTest test` = `Tests run: 22, Failures: 7, Errors: 0`（`TooManyActualInvocations: Wanted 1 time / But was 2 times` ×4、非数字双调用 `But was 4 times`、多值序 `expected: <[31, 33]> but was: <[33, 31]>`、超管 SQL 片段 `超管定向必须是 id IN 存在性查询 ==> expected: <true> but was: <false>`；空/null scope 与 null 用户 3 条回归在旧形状保持绿）；绿 `Tests run: 22, Failures: 0, Errors: 0, Skipped: 0` BUILD SUCCESS。全量 `mvn -B -ntp test` = 938/0/0/0 BUILD SUCCESS。四静态门禁 `pmd:check spotbugs:check checkstyle:check spotless:check` = BUILD SUCCESS（pmd 首轮红 2 条 `OnlyOneReturn`，按仓内单出口风格重构后归零）。基线 `--update` 真实写回 surefire.tests 928→938（reports 165 不变、failsafe 24/83/6 不变），写后 check RC=0。门禁自测 101 + 35 = 136 条全绿。

## 1. 任务分解清单

### 阶段 1：分支检出与语义复刻取证
- [x] 1.1 从 `master@b8c2116` 检出特性分支，确认工作树纯净。实测：`git checkout -b feature/optimize-kb-scope-auth-fast-resolve master` 落在 b8c2116，tracked 零改动（仅既有 untracked 物料）
- [x] 1.2 读 `selectActiveKnowledgeBaseIdsByUserId` 的 SQL 定义（注解/XML），逐条登记过滤条件，判定路径 1（QueryWrapper 复刻）或路径 2（mapper 重载）并登记理由。实测：见 §0 实现路径选择；两实体 `@TableLogic` 在位是路径 1 成立的关键前提

### 阶段 2：红测试先行
- [x] 2.1 在 `KnowledgeBaseAuthorizationServiceTest` 扩展定向单测：指定 scope 时定向 SQL 预算断言（`knowledgeBaseMapper.selectList` ×1 + `memberMapper.selectList` ×1、`never` 成员全量方法；旧形状合计 3 组必红）+ ArgumentCaptor 断言 SQL 片段含 `id IN`。实测：卡面"旧形状普通用户恒 3 次"按 SQL 组总计（kb.selectList×2 + 成员定制方法×1），次数断言以 `times(1)` 落红（`But was 2 times`），语义与卡面一致
- [x] 2.2 覆盖 owner/PUBLIC/成员命中与皆无、非数字容错、超管存在性收敛、多值交集、空/null scope 全量回归。实测：新增 10 条全名单见提交 diff（owner 命中/PUBLIC 命中/成员命中含 OR id IN/皆无/非数字混入+纯垃圾零查询/超管 id IN 且无 owner 条件/多值交集保 requested 序/空 scope/null scope 全量回归/null 用户零查询）
- [x] 2.3 未改主代码基线实跑贴红输出。实测：见 §0 红段（22 跑 7 败 0 错，3 条回归用例按设计保持绿）

### 阶段 3：实现定向分支
- [x] 3.1 `authorizedKnowledgeBaseIds` 内部定向分支（中文 Javadoc 标注「optimize-kb-scope-auth-fast-resolve 任务 3.1」）；空/null scope 原全量路径零改动。实测：`visibleKnowledgeBaseIds` 本体零改动（diff 可证）；红测试首版实现曾保留前置全量枚举被定向断言拦截（`But was 3 times`），修正为条件分支后转绿
- [x] 3.2 成员定向查询 active 语义与既有方法逐条等价；返回集合语义恒为 `requested ∩ visible`。实测：逐条等价登记见 §0 与实现 Javadoc；成员命中并入定向 KB 查询 OR 分支由测试 SQL 片段断言（`OR id IN`）锁定；非数字容错与解析为空返回空表均与原交集语义等价
- [x] 3.3 定向单测实跑转绿贴输出。实测：`Tests run: 22, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS

### 阶段 4：门禁与基线
- [x] 4.1 全量 `mvn -B -ntp test` 928→938 只增不减。实测：`Tests run: 938, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（最终代码形态复跑）
- [x] 4.2 四静态门禁 0 违规。实测：`mvn -B -ntp pmd:check spotbugs:check checkstyle:check spotless:check` BUILD SUCCESS（pmd 首轮红 2 条 `OnlyOneReturn`，重构单出口后归零）
- [x] 4.3 `bash scripts/check-test-baseline.sh --update` 真实写回复验。实测：写前 check RC=0（下界 928 ≤ 实测 938），`--update` 写入 `surefire.tests=938`（reports 165、failsafe 24/83/6 不变，`source-revision=b8c2116-dirty`），写后 check RC=0
- [x] 4.4 三守卫 `check-line-endings lf` / `check-write-set b8c2116` / `check-dirty` 全绿；136 条门禁自测全绿。实测：`check-line-endings lf` 对两 java 文件 + 基线文件 + 本 tasks.md 全 `LINE_ENDINGS_OK`；`check-write-set b8c2116` 与 `check-dirty` 于 3 笔提交后执行（两守卫依赖已提交状态）；自测 `check-test-baseline-selftest.sh` 101 + `agent-helper-selftest.sh` 35 = 136 全绿

### 阶段 5：提交、合并与停步
- [x] 5.1 补齐 §0 实测数字并勾选，3 笔提交：`perf(knowledge): 指定 scope 授权定向收敛，免全量可见库枚举（卡 P-q 阶段2/3）`、`docs(openspec): 登记 P-q 任务留痕并完成 tasks 勾选（卡 P-q）`、`chore(ci): 校准 surefire 回归基线（卡 P-q 阶段4）`
- [x] 5.2 切回 master 真 `--no-ff` 合并，分支保留不删
- [x] 5.3 严格停步：绝对禁止 `git push`，等待复核子 agent 回报与 owner 授权
