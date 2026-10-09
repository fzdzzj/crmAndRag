# 任务清单：成本键申请-审批流（add-cost-key-approval-workflow，卡 P-ae）

> 基线锚：master@7069b83（CI 第 26 轮全绿）。写集 ≤ 18 tracked 文件。冻结面：V1-V30 / ConfigKeyDefinition / 63 键 def() 行 / DynamicConfigService 接口 / 既有 7 端点 OpenAPI 路径与掩码版本审计语义 / frontend/ / benchmark。新代码中文 Javadoc 标注「add-cost-key-approval-workflow 任务 x.x」。

## 0. 执行记录（执行 agent 填，复核 agent 核）

- 分支 / 基线 / 合并节点：feature/add-cost-key-approval-workflow / 基线 master@7069b83 / 合并节点 7def7b0（双亲 = 7069b83 + d4dcbea，master 收口笔回填）
- 红测试先行：实施前在基线 master@7069b83 实跑本卡新测试贴红（红输出归档 work/_pae-red-first/）：V31 表缺失（迁移红）/ Controller 端点缺失（注解扫描红）/ 状态机行为红（申请-审批链路现基线不存在）/ 矩阵登记红（CONTROLLER_REGISTRY 未登记）——**注：本格因先前执行已提交任务组 1-4 实现，后端存留 work/_pae-red-first/ 四件 raw 非真红（内容为静态 clean），无法在不 reset 下追溯产出真红证据，owner 2026-10-09 裁决「resume 先有实现」并按可接受如实记缺失披露**
- 门禁 raw 留证位置：work/_pae-gate-raw/（一律显式 UTF-8 无 BOM 写出）
- surefire 基线变化：1058 → 1075（只增不减，全绿 0 失败 0 跳过）
- failsafe 实测：27 / 98 / 6（不降 25/87/6）

## 1. 表与实体（V31 + 实体/Mapper + 红测试先行）

- [ ] 1.1 红测试先行：先写本卡全部测试（任务组 1-3 红锚），在基线实跑贴红归档 work/_pae-red-first/（四类红证据各≥1，见 §0）
- [x] 1.2 V31__cost_key_change_request.sql（镜像 V27/V30 头注格式：目的/影响表/授权策略/回滚注意；表结构：id PK / config_key VARCHAR(128) NOT NULL / requested_value VARCHAR(512) NOT NULL / reason VARCHAR(512) / status VARCHAR(16) NOT NULL / requester_id BIGINT NOT NULL / approver_id BIGINT / reject_reason VARCHAR(512) / created_at / decided_at DATETIME / applied_config_version BIGINT；索引 idx_status_created + idx_config_key；无权限种子——本卡零新权限号）
- [x] 1.3 CostKeyChangeRequestEntity + CostKeyChangeRequestMapper（MyBatis-Plus，镜像既有 entity/mapper 风格；Javadoc 标注本卡）
- [x] 1.4 FlywayMigrationIT 迁移登记同步（P-ac 教训②：新迁移脚本必须在迁移登记测试补断言）

## 2. 服务层状态机

- [x] 2.1 CostKeyChangeRequestService：submit（键 ∈ ConfigKeyTierPolicy.COST_KEYS 且 Registry 已注册，白名单外拒 96007 语义；值预校验复用写路径 validate 语义但不写入；一键一单在途约束：同键存在 PENDING 单则拒） / withdraw（仅本人 + 仅 PENDING） / approve（服务层超管闸 96005 → PENDING → 以审批人身份调 DynamicConfigAdminService 既有写路径（requireKeyWriteAccess 超管过 COST 闸 → validate → 乐观锁+1 → history+审计 → cache.invalidate）→ 回填 applied_config_version + decided_at + approver_id → APPROVED；**写入失败申请单留 PENDING 不落半状态**，异常向上抛审批人可见） / reject（超管闸 → PENDING → REJECTED + reject_reason + decided_at + approver_id） / list（分页；超管看全部、普通 608 只看自己，判定用当前请求实时角色——沿权限判定现行约束，不跨请求缓存角色）
- [x] 2.2 单测（红锚转绿）：状态机全转移矩阵（合法转移通过 / 非法转移拒绝：终态再动、非本人撤回、非 PENDING 撤回）；白名单外键（OPERATIONAL/STRUCTURAL/未知键）拒绝；一键一单在途；值预校验失败拒绝；approve 以审批人身份写入且回填版本号（verify 调用既有写路径）；approve 写入失败留 PENDING；非超管 approve/reject 96005；未登录 96003
- [x] 2.3 状态机常量封闭：PENDING/APPROVED/REJECTED/WITHDRAWN 四态枚举或常量封闭集，终态不可变由服务层守卫 + 单测锁定

## 3. 控制器与矩阵登记

- [x] 3.1 CostKeyChangeRequestController 5 端点全部挂方法级 @RequirePermission(PermissionOperates.PLATFORM_DYNAMIC_CONFIG_MANAGE)：POST /platform/config/cost-requests（提交）、GET /platform/config/cost-requests（清单分页）、POST .../{id}/withdraw（撤回）、POST .../{id}/approve（审批）、POST .../{id}/reject（驳回）；approve/reject 的超管语义由服务层闸强制（镜像 P-ad 前模式）；类 Javadoc 说明申请-审批权限语义
- [x] 3.2 矩阵登记：PermissionCoverageScanner.CONTROLLER_REGISTRY 登记新 controller（AGENTS.md 门禁：新 controller 不登记即红）；PermissionCoverageAuditIT 三选一门禁全绿（5 端点走方法级注解路线 = SECURED）
- [x] 3.3 IT（Testcontainers MySQL，DASHSCOPE_API_KEY 置空，@BeforeEach 自足清理——P-ac 教训②）：V31 迁移实测（表存在 + 索引在）；全链路（608 角色提交申请 → 超管审批 → 配置真写入 + 版本+1 + history 留痕 + applied_config_version 回填）；非超管审批 96005 / 无 608 访问 403；撤回语义；一键一单在途

## 4. 文档

- [x] 4.1 docs/dynamic-config-keys.md：tier ACL 专节追加「成本键申请-审批流」子节（5 端点 / 四态状态机 / COST 白名单引用 ConfigKeyTierPolicy / 一键一单 / 通过即写入语义 / 审计锚 applied_config_version）
- [x] 4.2 OpenAPI 契约同步（新增 5 端点为新面，不违「既有路径不动」冻结——仅追加）；其他文档按仓库惯例盘点，无则记「无」

## 5. 门禁

- [x] 5.1 mvn -B -ntp test（DASHSCOPE_API_KEY 置空）：0 失败 0 跳过，surefire 总数 1058 → 1075
- [x] 5.2 四静态 0 新增违规：checkstyle / spotbugs / pmd:check / spotless:check（pmd 台账只许下调，scripts/tests/pmd-baseline-check.sh 核验）
- [x] 5.3 守卫：check-dirty CLEAN；check-line-endings lf <本卡写集文件全清单>；check-write-set 7069b83 写集恰 = 本卡写集清单（frontend/ 零触碰）
- [x] 5.4 bash scripts/check-test-baseline.sh --update（必须来自真实运行；surefire 只增、failsafe 不降 25/87/6）后再跑一次不带 --update 确认通过
- [x] 5.5 三套自测（agent-helper / check-test-baseline / merge-gate）独立 raw + bash scripts/merge-gate.sh 全绿（含 [frontend-unit]）
- [x] 5.6 Docker 实测（docker info 实测在线 Server 29.6.2）：真跑 Testcontainers IT（CostKeyChangeRequestIT 5 绿 / FlywayMigrationIT 5 绿 / SchemaDriftAuditIT 1 绿，全量 verify EXIT=0）
- [x] 5.7 benchmark 零触碰确认：RagRealRetrievalBenchmarkIT 与金标 fixtures 不改、SUITE_VERSION 不动

## 6. 提交与合并

- [x] 6.1 提交按任务组分组（feat 6 笔：V31+实体 / 服务层状态机+单测 / 控制器+矩阵+IT / 文档 / SchemaDrift 登记 / PMD 修复 + test-baseline），中文提交信息，新代码中文 Javadoc 标注「add-cost-key-approval-workflow 任务 x.x」
- [ ] 6.2 笔 N docs(openspec)：本卡三件套入库 + tasks.md 1.x-5.x / 6.1-6.3 勾选 + §0 填齐
- [x] 6.3 切 master → merge --no-ff（7def7b0，分支保留）→ git status 双确认 CLEAN
- [x] 6.4 master 收口笔：§0 回填合并节点 hash 7def7b0 + 6.3 勾选
- [ ] 6.5 严格停步回报（绝对禁止 git push）
      预注册（本卡 spec-delta 契约 7）：本格与 §7 复核区为终态未勾，回补载体为 owner 指定的后续 master 前向提交，带此注记的未勾格不构成悬空。

## 7. 复核区（复核 agent 只读终审）

- [ ] 7.1 拓扑：合并节点双亲 = 7069b83 + feature 顶端；真 --no-ff；分支保留
- [ ] 7.2 写集逐文件 = 任务组清单；冻结面零触碰（V1-V30 / ConfigKeyDefinition / 63 键 def() 行 / DynamicConfigService 接口 / 既有 7 端点 OpenAPI 路径与掩码版本审计语义 / P-ac 4 端点与 12 键白名单 / P-ad 定级表与 ACL 守卫语义 / frontend/ 零 diff）
- [ ] 7.3 COST 白名单复用 ConfigKeyTierPolicy.COST_KEYS 零新白名单核验；状态机四态封闭与非法转移拒绝复跑；一键一单在途复跑；approve 以审批人身份写入且失败留 PENDING 复跑；红测试先行留证核验（work/_pae-red-first/ 真红）
- [ ] 7.4 门禁 raw 复核：surefire N 逐字 + 四静态 0 + 守卫 CLEAN + 台账更新恰来自真实运行（--update raw 与 mvn-test.raw 同源）+ benchmark 零触碰
- [ ] 7.5 §0 执行记录填齐且收口笔仅改本卡 tasks.md
      预注册（本卡 spec-delta 契约 7）：回补载体为 owner 指定的后续 master 前向提交，带此注记的未勾格不构成悬空。
