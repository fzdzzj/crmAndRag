# 任务卡 · evidence-to-operations（B 档 · 三合一）

> 本卡是执行端唯一执行单。细则以 `openspec/changes/evidence-to-operations/{proposal.md,tasks.md}` 为准；本卡只写「怎么开工、写到哪、怎么停」。

## 1. 一句话目标

把上一卡留下的三条证据各推进一步：**G1** 把近重复「泄漏率」拆成「同文档抑制（有害）」与「跨文档降权（无害）」，给出可拍板的价目表与免费档；**G2** 让前端按钮权限从 `frontend/openapi.yaml` 的 `x-required-permission` 派生，取消手抄权限名；**G3** 把 `docs/multimodal-gray-rollout.md` §9 的人工核对单变成可判定脚本 + 自测，并防文档漂移。

## 2. 工作树与分支

- **权威树**（唯一法定工作树）：`D:\code\crmAndRag-merge-add-knowledge-admin-api`
- 开工前：`git rev-parse --abbrev-ref HEAD` 必须是 `master`，`git status --porcelain` 必须为空（除本卡新增的 `openspec/changes/evidence-to-operations/` 与 `work/` 下本卡文件）。
- 切分支：`git checkout -b feature/evidence-to-operations`
- **分支永久保留**；合并与 `git push` 由主 Agent 在 Owner 授权后执行，执行端**不得 push、不得 merge 回 master、不得改写历史**。
- 观察树 `D:\code\crmAndRag` 只读参考；**禁止**在观察树提交或改 `src/`。

## 3. 必读（按顺序，读完再动手）

1. `openspec/changes/evidence-to-operations/proposal.md`（尤其 §1.2 E1–E6、§2.3 P1–P4、§3.3 Q1–Q4、§4 硬边界、§5 停步裁决）
2. `openspec/changes/evidence-to-operations/tasks.md`（30 条任务，逐条打勾 + 6 空格「补勾依据」）
3. `openspec/changes/archive/retrieval-and-contract-hardening/proposal.md` §1（上一卡的判据与结论，G1 的对照面）
4. `docs/rag-quality/near-dup-mechanism-decision-v1.md` + `.json` 的 `criteria` / `baseline` / `pareto`（G1 的输入锚）
5. `src/test/java/com/slz/crm/unit/permission/PermissionTypeCatalogGenTest.java`（G2 生成器的**范式**：默认校验、`-D…update=true` 才重写）
6. `frontend/src/components/common/Permission.vue` + `frontend/src/types/permission.ts`（G2 现状）
7. `docs/multimodal-gray-rollout.md` §1 / §9 / §12（G3 现状，§13 只追加）
8. `src/main/java/com/slz/crm/platform/config/{DynamicConfigKeyRegistry.java, ConfigKeyTierPolicy.java}`（G3 真值源）
9. `scripts/rag-quality/near-dup-mechanism-report.sh`（脚本范式）+ `scripts/check-test-baseline.sh`（基线门禁语义）

## 4. 写入面（白名单，超出即整卡作废）

**G1 允许新增**
- `src/test/java/com/slz/crm/quality/NearDuplicateExchangeRateAnalyzer.java`
- `src/test/java/com/slz/crm/quality/NearDuplicateExchangeRateDecisionTest.java`
- `src/test/java/com/slz/crm/quality/NearDuplicateExchangeRateDecisionGuardTest.java`
- `docs/rag-quality/near-dup-exchange-rate-v1.json`
- `docs/rag-quality/near-dup-exchange-rate-v1.md`
- `scripts/rag-quality/near-dup-exchange-rate-report.sh`

**G2 允许新增**
- `frontend/src/types/endpointPermission.gen.ts`
- `frontend/src/constants/uiEndpointPermissions.ts`
- `frontend/src/utils/endpointPermission.ts`
- `frontend/src/utils/__tests__/endpointPermission.test.ts`
- `src/test/java/com/slz/crm/unit/permission/EndpointPermissionCatalogGenTest.java`
- `src/test/java/com/slz/crm/unit/permission/PermissionSiteAuditTest.java`

**G2 允许修改**
- `frontend/src/components/common/Permission.vue`
- 11 个站点：`frontend/src/pages/(dashboard)/{contact/task,contact/activity,client/pool,client/list,client/contacts,sale/list,sale/lead,sale/contract,privilege/user,privilege/role}.page.vue`、`frontend/src/components/finance/FinanceTable.vue`
- `frontend/docs/test-checkpoints.md`（只追加检查点）

**G3 允许**
- 新增 `scripts/rag-quality/image-vector-ingest-preflight.sh`
- 新增 `scripts/tests/image-vector-ingest-preflight-selftest.sh`
- 新增 `src/test/java/com/slz/crm/platform/config/MultimodalRolloutKeysDocConsistencyTest.java`
- 修改 `docs/multimodal-gray-rollout.md`（**只追加 §13**；Q3 发现文档本身不一致时可修 §9.1，见 §7）

**全卡允许**
- `docs/active-handoff.md`（结论引用）
- `work/` 下的证据与日志文件（不进提交的临时产物请自删）

**禁改**：`ImageSimilarityGate.java` 及任何生产常量、`frontend/openapi.yaml`、`target/openapi/openapi.yaml`（自动生成物仅由脚本重写）、`src/main/java/**`（除不存在的新文件）、`src/main/resources/db/migration/**`、`frontend/typed-router.d.ts`、`docs/permission-matrix-audit.md`、上一卡的三份锚与脚本、`scripts/merge-gate.sh`、`scripts/check-test-baseline.sh`。

## 5. 开工顺序与判据

顺序 **G0 → G1 → G2 → G3 → G4**，单笔提交按组切分，便于逐组回退：

| 提交 | 内容 |
|---|---|
| 1 | `feat(card): scaffold evidence-to-operations proposal and tasks`（立项，含本卡文件） |
| 2 | `feat(quality): split near-dup leakage into same-class suppression and cross-class down-weight`（G1） |
| 3 | `feat(frontend): derive button permissions from openapi endpoint permission extensions`（G2） |
| 4 | `feat(ops): make image-vector ingest activation checklist executable and drift-guarded`（G3） |
| 5 | `chore(baseline): re-anchor test baseline after evidence-to-operations`（全量门禁后由主 Agent 或按门禁要求执行） |

## 6. 门禁命令（Windows 环境，bash 走 Git 自带的 bash）

```text
D:\git\Git\bin\bash.exe -lc 'cd /d/code/crmAndRag-merge-add-knowledge-admin-api && mvn -B -ntp clean verify'
D:\git\Git\bin\bash.exe -lc 'cd /d/code/crmAndRag-merge-add-knowledge-admin-api && bash scripts/check-test-baseline.sh'
D:\git\Git\bin\bash.exe -lc 'cd /d/code/crmAndRag-merge-add-knowledge-admin-api && bash scripts/check-openapi-consistency.sh'
D:\git\Git\bin\bash.exe -lc 'cd /d/code/crmAndRag-merge-add-knowledge-admin-api/frontend && pnpm lint:check'
D:\git\Git\bin\bash.exe -lc 'cd /d/code/crmAndRag-merge-add-knowledge-admin-api/frontend && pnpm type-check:check'
D:\git\Git\bin\bash.exe -lc 'cd /d/code/crmAndRag-merge-add-knowledge-admin-api/frontend && pnpm exec vitest run'
```

- **基线门禁只在一次全量 `clean verify` 之后跑**；定向 `-Dtest=` 的残缺报告会被 `--update` 读进去，禁止在定向运行后跑基线。
- WSL 的 bash 在本机不可用（`ext4.vhdx` / `HCS` 报错），不要换别的 shell 绕。
- `pnpm build` 在本机可能因 esbuild 临时二进制 `Access denied` 失败（**master 同样**，环境所致）→ 体积判定移交 CI 的 `Frontend … Build Budget` job；**若该 job 在 CI 红，G2 停步**，不得删步骤绕过。

## 7. 停步与回退（三合一唯一安全阀）

- 每组独立判定；某组不达标 → **只回退该组写入面**（`git checkout -- <本组路径>` + 删本组新增文件），其余组照常完成。禁「一组红、整卡放弃」。
- G1 落 `UNDECIDABLE-BY-DATA` **不算失败**：照常交付价目表与上界声明，但 `active-handoff.md` 必须写「档位仍未定（Owner 拍板）」。
- G2 若 P2 反向自证无法留痕（环境问题）→ 降级为「正向 type-check 绿 + 生成物逐字节校验绿」，**如实上报，禁称反向已验证**。
- G3 若 Q3 发现 §9.1 手写默认值**与注册表本来就不一致** → 记为发现，允许在本组内修正 §9.1 并在报告单列该项；其余情形 §1–§12 逐字不动。
- 任一组停步，必须在报告里写清「停了哪组 / 原因码 / 其余组状态 / 已回退路径」。

## 8. 铁律

1. 不改生产常量（`threshold=0.9944`、`NEAR_DUPLICATE_FLOOR=0.9887`、`0.75|0.6|0.5`），不替 Owner 定交换率。
2. 不改冻结契约（`frontend/openapi.yaml` 零 diff）。
3. 零真外呼、零迁移、零新增第三方依赖。
4. 不打开任何开关、不改任何默认值（`DynamicConfigKeyRegistry` / `ConfigKeyTierPolicy` 零 diff）。
5. 分组回退只动本组路径，禁止顺手「修一下」别组文件。
6. 校验只在收尾跑一次全量；不要「验证到通过」的自循环，一轮不过就如实上报。

## 9. 回报格式（≤300 字）

```text
分支：feature/evidence-to-operations（N 笔提交，自 <sha>）
产出：<路径>(行数) 逐组一行
门禁：clean verify BUILD SUCCESS / surefire X/Y / failsafe A/B / baseline EXIT=0 / openapi-consistency EXIT=0 / 前端三门绿
G1：verdict=… 推荐档=…（S_same/C_cross 各一行关键数字）
G2：生成 key 数=… / 站点清零=… / 体积 app 桶=…
G3：自测 6/6 / 一致性测试绿 / §13 行数=…
未解决项：…（没有就写「无」）
待决策项：…（没有就写「无」）
```
