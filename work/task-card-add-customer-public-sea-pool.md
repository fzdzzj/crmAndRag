# 任务卡 add-customer-public-sea-pool：客户公海池与全生命周期流转机制

## 1. 任务背景与目标
- **对应卡**：`openspec/changes/add-customer-public-sea-pool/`（`proposal.md` + `tasks.md`）
- **变更档位**：A 档（含 Flyway `V41` 迁移脚本与库表结构变更；依据政策强制 A 档）
- **核心目标**：建立客户公海池模型与流转机制（私海保有量配额校验、业务员自主认领、主动退回、主管分派、超 30 天无跟进系统自动回收、商业活动跟进联动、流转历史日志、前端公海池视图集成），复用 113/116/117 既有权限常量。

## 2. 权威上下文与输入
- **必读规格**：`openspec/changes/add-customer-public-sea-pool/{proposal.md,tasks.md}`
- **先例参考**：`FlywayMigrationIT.java`（V41 登记）、`BusinessRecordAccessServiceImpl.java`（客户访问控制）、`CustomerCompanySupport.java`（客户通用逻辑）、`BusinessActivityServiceImpl.java`（跟进联动）

## 3. 边界与硬约束
- **代码范围**：`src/main/resources/db/migration/V41__customer_public_sea_pool.sql`、`CustomerCompanyEntity`、`CustomerPool*`（Entity/Mapper/Service/Controller/Scheduler）、`BusinessActivityServiceImpl`、前端 `client/` 及相关测试。
- **红线禁区**：
  1. 库结构迁移唯一真相源为 `V41__customer_public_sea_pool.sql`，严禁改动已合入的 V1~V40 历史脚本；
  2. 零新权限常量（严格复用既有 113/116/117，严禁改动 `PermissionOperates.java`）；
  3. 公海流转（认领/退回/分派）必须事务后异步同步向量，保证 Qdrant 权限锚与所有者一致；
  4. 分支一律从权威树 master 检出 `feature/add-customer-public-sea-pool`，完工停步，严禁未经 owner 授权执行 merge 或 push。

## 4. 验收门禁要求
- **构建与迁移**：Docker 本地 MySQL 跑通 `FlywayMigrationIT`（V1~V41 全通过）+ `SchemaDriftAuditIT`（0 CRITICAL 0 NEW）；
- **全量门禁**：`mvn -B -ntp verify`（surefire 只增不减，基线 1415；failsafe 116 绿）；
- **静态与前端**：四静态 0 违规 + 前端三检 179 pass + `bash scripts/merge-gate.sh` 8/8 全 PASS；
- **契约与基线**：OpenAPI 契约一致性校验通过，树净状态通过 `check-test-baseline.sh --update` 更新基线。

## 5. 产出与交接
- 产出交接快照：`work/handoff-add-customer-public-sea-pool.md`。
