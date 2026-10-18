# 任务卡 · harden-single-replica-contract 单副本部署契约硬化

## 1. 任务背景与目标
- **对应卡**：`openspec/changes/harden-single-replica-contract/`（`proposal.md` + `tasks.md`）
- **变更档位**：B 档（无 DB 迁移、无权限变更、无真外呼、无冻结契约修改）
- **核心目标**：硬化部署编排与环境约束（锁定 `replicas: 1`），扩容 `SingleReplicaArchitectureAssumptionGuardTest` 至全量 8 大进程内状态组件与定时调度，加固并测试 `NumberGenerator`。

## 2. 权威上下文与输入
- **必读规格**：`openspec/changes/harden-single-replica-contract/{proposal.md,tasks.md}`
- **代码参考**：`SingleReplicaArchitectureAssumptionGuardTest.java`、`ProductionConfigurationGuard.java`、`NumberGenerator.java`、`deploy/docker-compose.yml`

## 3. 边界与硬约束
- **代码范围**：`deploy/`、`src/main/resources/`、`src/main/java/.../untils/NumberGenerator.java`、`src/test/...`；
- **红线禁区**：
  1. 零 DB 迁移（`src/main/resources/db/migration/` 零修改，下一可用号仍为 V43）；
  2. 零新增 Maven 依赖（`pom.xml` 零修改，严禁引入 Redis 或 ShedLock）；
  3. 零破坏性接口变更（前端与 OpenAPI 契约 100% 保持零改动）；
  4. 严禁触碰 `frontend/typed-router.d.ts`；
  5. 提交信息必须全部使用 `-m "xxx"` 内联纯 ASCII 简短语句。

## 4. 验收门禁要求
- **构建测试**：目标测试真跑全绿，`mvn -B -ntp clean verify` 全量通过；
- **基线约定**：`scripts/test-baseline.txt` 经 `--update` 单次写入（在 262/1628 基础上只增不减，无 `-dirty`）；
- **聚合门禁**：`bash scripts/merge-gate.sh` 8/8 全 PASS，还原 `typed-router.d.ts` 噪声；
- **交付产物**：输出 `work/handoff-harden-single-replica-contract.md` 并停步待复核。
