# 任务卡 · retrieval-and-contract-hardening（三合一）

> 档位 **A**（三组取最高：含冻结契约改动）｜ 分支 `feature/retrieval-and-contract-hardening` ｜ 树：`D:\code\crmAndRag-merge-add-knowledge-admin-api`（从 `master` 检出）
> 三件套：`openspec/changes/retrieval-and-contract-hardening/{proposal.md,tasks.md}` ｜ 提示词：`work/prompt-retrieval-and-contract-hardening.md`

## 一句话目标

把三条"只有读代码/文档措辞支撑"的断言变硬：**G1** 近重复机制该不该换（零外呼离线判据）｜ **G2** 图片四开关的门控关系与运行期热生效（零真外呼实证）｜ **G3** 端点权限要求生成进 OpenAPI 契约（A 档）。

## 三组一览

| 组 | 主判据（跑前写死） | 写入面 | 停步即回退 |
| --- | --- | --- | --- |
| **G1** | `R_keep ≥ 0.90`（基线 0.583）、`L ≤ 0.02`（基线 0.0109）、逐 kind 单调、外呼 **=0**、全网格披露（floor 200 点 / dHash 0..24） | 5 个测试类 + 1 脚本 + 决策锚 JSON + 决策文档 | 键对不上 / 语料不可复现 |
| **G2** | A1/A2/A2b/A3/F1/F2/N1 七条（A3 必须 `== 图数`，0 类必须 `== 0`，且真证到"未重启"） | 1 个 IT + runbook §12 | F1/F2 或 A1/A2/A2b 不成立 |
| **G3** | P1 零例外、P2 集合相等（两路径独立）、P3 前端四项绿、P4 `src/main` 精确 2 文件 | customizer + `OpenApiConfig` + 重导出契约 + parity 测试 + spec-delta + 解冻登记 | 覆盖后复校不一致 / P2 无法解释 / 前端红 |

**执行顺序固定**：G0 → G1 → G2 → G3 → G4（G3 最后：它要重导出契约 + 跑前端构建）。

## 写入面并集（白名单外一律回退）

```
# G1
src/test/java/com/slz/crm/quality/PerceptualHashSupport.java
src/test/java/com/slz/crm/quality/NearDuplicateMechanismAnalyzer.java
src/test/java/com/slz/crm/quality/NearDuplicateMechanismAnalyzerTest.java
src/test/java/com/slz/crm/quality/NearDuplicateMechanismDecisionTest.java
src/test/java/com/slz/crm/quality/NearDuplicateMechanismDecisionGuardTest.java
scripts/rag-quality/near-dup-mechanism-report.sh
docs/rag-quality/near-dup-mechanism-decision-v1.json          （生成物）
docs/rag-quality/near-dup-mechanism-decision-v1.md
# G2
src/test/java/com/slz/crm/knowledge/document/ImageVectorSwitchHotFlipIT.java
docs/multimodal-gray-rollout.md                                （只追加 §12）
# G3
src/main/java/com/slz/crm/server/config/OpenApiPermissionCustomizer.java
src/main/java/com/slz/crm/server/config/OpenApiConfig.java
frontend/openapi.yaml                                          （只许 --update 覆盖）
src/test/java/com/slz/crm/contract/OpenApiPermissionContractParityTest.java
src/test/java/com/slz/crm/contract/OpenApiContractExportTest.java （仅追加一条）
openspec/changes/retrieval-and-contract-hardening/specs/api-contract/spec-delta.md
spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md   （追加一行）
# 卡
openspec/changes/retrieval-and-contract-hardening/**
work/task-card-retrieval-and-contract-hardening.md
```

## 全局硬边界

- 三组都**零真外呼**（不出现 `DASHSCOPE`/`System.getenv`/http 客户端，不读 `.env`，不跑 `RAG_*_REAL=1`）
- `pom.xml` 零 diff（零新依赖）、迁移零 diff（零迁移）、`scripts/test-baseline.txt` 不许改
- **不打开任何生产开关、不改任何默认值、不改权限语义**（`RequirePermission`/`PermissionOperates`/拦截器/`OpenEndpointRegistry`/`PermissionCoverageScanner` 全零 diff）
- `docs/permission-matrix-audit.md`（历史快照）、`docs/dynamic-config-keys.md`、`frontend/typed-router.d.ts`、`frontend/src/**`（除 `gen:api` 产物）零 diff
- 判据数字（0.90 / 0.02 / 200 点 / 0..24 / `== 图数` / P1 零例外）**一个都不许改**

## 停步裁决（三合一的关键：不连坐）

- **G1** NO-GO 是**合法结论**（不是故障）：交付帕累托前沿 + 结案声明。
- **G2** 若 F1/F2 或 A1/A2/A2b 不成立 ⇒ 回退 G2 产物、实证写 `work/evidence-retrieval-and-contract-hardening-g2-stop.md`，**禁止改 `src/main` 变绿**，G1/G3 继续。
- **G3** 若不收敛 ⇒ 回退 G3 全部产物（含契约与前端生成物），G1/G2 继续。
- 任一停步组的产品必须回到 `master` 状态，分支上不许留半成品红测试。

## 门禁

```
mvn -B -ntp test
mvn -B -ntp test -Dtest=ImageVectorSwitchHotFlipIT          # G2（降级真库则 verify -Dit.test=...）
bash scripts/rag-quality/near-dup-mechanism-report.sh       # G1
bash scripts/check-openapi-consistency.sh                   # G3 主门禁
cd frontend && pnpm gen:api && pnpm lint:check && pnpm type-check:check && pnpm test && pnpm build
bash scripts/merge-gate.sh
bash scripts/check-test-baseline.sh                         # 只读
```

## 回报（≤300 字）

① 三组各自 交付/停步 ② G1 `verdict` + 帕累托一句话 ③ G2 七条断言结论（是否真证到未重启）④ G3 P1–P4 + operation 计数 ⑤ 门禁结果 ⑥ 未解决/需决策项
