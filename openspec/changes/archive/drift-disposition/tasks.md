# Tasks — drift-disposition

> 执行契约见 `openspec/git-workflow.md`。当前基线：master surefire 615 全绿；本提案纯测试设施+文档，基线不动。
> 拍板口径（2026-09-14）：7 项全部登记豁免，不动表结构。硬约束：禁改迁移链 V1..V26、禁改任何实体类、全程 ¥0。

## 1. 已定夺漂移登记设施

- [x] 1.1 `src/test/java/com/slz/crm/integration/schema/` 新增 `KnownDriftRegistry`（常量类：7 项登记，每项含 表.列 / 差异描述 / 豁免理由 / 定夺日期 2026-09-14），中文 Javadoc 标注「drift-disposition 任务 1.1」；登记内容以 proposal.md 定夺表为准（W1-W5 类型不亲和 + I1 生成列 + I2 预留表）
- [x] 1.2 `SchemaDriftAuditor`/`SchemaDriftComparator` 接入登记：WARN/INFO 漂移先查 `KnownDriftRegistry`，命中 → 计入 KNOWN（已定夺，仅计数）；未命中 → 计入 NEW（待定夺，打印明细行）；中文注释标注「drift-disposition 任务 1.2」
- [x] 1.3 `SchemaDriftAuditIT` 输出补 KNOWN/NEW 计数行（门禁语义不变：CRITICAL fail；NEW 不失败但显式打印提醒——新漂移需定夺后登记或处置）
- [x] 1.4 单测：① 登记命中（W1 项落 KNOWN 不落 NEW）② 未登记漂移落 NEW ③ 防呆（登记项的表.列在实体与迁移链中不存在时报错，防止登记过期/写错）

## 2. 首轮定夺落地验证

- [x] 2.1 `mvn -B -ntp test-compile failsafe:integration-test "-Dit.test=SchemaDriftAuditIT"`（本地无 Docker 时按既有门控跳过则记录，有 Docker 则真跑）：输出 KNOWN=7 / NEW=0 / CRITICAL=0，门禁绿

## 3. 文档收尾

- [x] 3.1 `docs/schema-drift-audit.md`：§3/§4 各表加"已定夺（2026-09-14 豁免）"标注与豁免理由（以 proposal 定夺表为准），加 KNOWN 登记指针；§5 待授权清单移出 7 项，保留"新漂移走 NEW 定夺流程"说明
- [x] 3.2 `HANDOFF.md` 漂移章节更新（7 项定夺清零 + KNOWN/NEW 机制说明）；AGENTS.md 漂移相关行同步

## 4. 回归与收尾

- [x] 4.1 `mvn -B -ntp test` 全绿（615 基线调整至实测 **618**，新增 KnownDriftRegistryTest 3；已同步 ci.yml 三处：surefire 数字、check_baseline、错误提示行）
- [x] 4.2 git 收尾：分支 `feature/drift-disposition`，提交按任务组 `type(scope): 中文描述`（提案三件套随首个提交入库），亲验全绿 + `git status` 干净（已知未跟踪件勿提交勿删除）后 `--no-ff` 合入 master，汇报带 commit hash + KNOWN/NEW 计数 + surefire 实测计数
