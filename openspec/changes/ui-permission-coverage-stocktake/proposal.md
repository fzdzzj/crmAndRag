# proposal · ui-permission-coverage-stocktake（前端权限小表覆盖率 + 文档存量核对，储备 6+7 合并）

> 卡 30。**零外呼 / 零迁移 / 零契约改动 / `src/main` 零 diff**。储备 6（前端小表维护）与储备 7（文档存量）合并成一张卡：
> 两条腿都以「**核对并留痕**」为主，改动只有一处（补登记），且改动面由实测决定。

## §1 问题陈述（为什么值得单独一张卡）

### 腿 A：小表的真实风险不是「写错」，是「漏登」

`frontend/src/constants/uiEndpointPermissions.ts`（**98 行 / 57 条登记**）已经有很强的**编译期**护栏：键必须是生成物 `EndpointKey`、值必须满足 `ContractPermission<K>`（权限名与契约 `x-required-permission.name` 全等，或 OPEN 端点只能 `null`）。写错键或写错名，`pnpm type-check` 直接红。

但表类型是 `Partial<{ [K in EndpointKey]: ... }>`（`uiEndpointPermissions.ts:22-26`）⇒ **漏登是合法的**。而运行期 `permissionOfEndpoint` 对未登记端点 **fail-closed**（`utils/endpointPermission.ts:34`；`Permission.vue:29` 是其唯一生产调用点）⇒ **漏登的后果是「功能静默消失」**：按钮/入口不报错、不告警，就是不出现。

编译期护栏守住了「登记得对不对」，**没有守住「该登的登了没有」**。这就是储备 6 的全部含义。

### 腿 B：板子指向的存量文件，两个在观察树而不在权威树

板 §3 第 7 条列的文档存量里，`work/_online-exam-backlog.md`（6592 B）与 `work/_paa-candidates-pool-v2.md`（70691 B）**在权威树 `Test-Path` = false，在观察树 `D:\code\crmAndRag\work\` 实存且未跟踪**（2026-10-25 双树实测）。板子是给下一位读者用的，**不标明「观察树 work/」限定**就会让读者在权威树里空手而归——这比不写更糟。

> **勘误（立项侦察之误，M1 前订正）**：本条初稿只测了权威树，据此写成「实测都不存在」；执行阶段双树复测推翻该结论（锚 md:17-20、task-card §7 已按实测记录）。判据 R0–R5 与决策 D1–D4 不受影响。

## §2 侦察已得事实（行号实测于 `2d77460`，执行者只需复核）

- **登记面**：`uiEndpointPermissions.ts` 98 行、**57 条**（`grep "^\s*'[A-Z]+ /"` 计数）；编译期护栏见 `:8-26`。
- **契约面**：`types/endpointPermission.gen.ts` 294 行，`EndpointPermissionMap`（`{code: N, name: 'X'}` 或 `{status: 'OPEN'}`，见 `:11-17`）。
- **使用面**：`permissionOfEndpoint` 全仓**唯一生产调用点** = `components/common/Permission.vue:29`（`props.endpoint`）⇒ **使用面 = 模板里 `<Permission endpoint="…">` 的字面量集合**（含 `:endpoint` 动态绑定，须单独处置）。
- **运行期语义**：`lookupPermission`（`:18`）/ `permissionOfEndpoint`（`:34`）；未登记 fail-closed + 每键 `console.warn` 一次，既有测试 `endpointPermission.test.ts:15-22` 已覆盖。
- **门禁通道**：CI `frontend-quality` job 的表名即「Frontend Test / Lint / Type-check / Build Budget」，`ci.yml:246` 注释列明后续步骤含 **Vitest**，`frontend/package.json` 有 `"test": "vitest run"` ⇒ **新增 vitest 用例即进门禁，无需改 CI YAML**。
- **存量面**：板 §3 第 7 条共 3 项——2 项 work 文件（`_online-exam-backlog.md`、`_paa-candidates-pool-v2.md`）**权威树不在、观察树实存未跟踪**（见 §1 腿 B 勘误），1 项是 `docs/permission-matrix-audit.md`（历史快照，**遵裁决不改写**）。

## §3 判据（R0–R5，执行与复核均以此为准）

- **R0 零外呼 + 边界**：不改 `src/main`、不改 `frontend/openapi.yaml`、不改契约、不改 CI YAML、不改既有断言；新增测试只读源码。
- **R1 三集合对账（腿 A 核心）**：实测并落锚三个集合——`used`（模板中 `<Permission>` 的 `endpoint` 字面量）/ `registered`（57 条）/ `contract`（生成物全量键）。三档差集逐一落锚：
  - `used ∖ registered` = **静默 fail-closed 缺口** ⇒ 必须补齐（值只许取契约 `name` 或 OPEN 的 `null`，**不得自拟**）；
  - `registered ∖ contract` = 按编译期护栏应恒空 ⇒ 仍要运行期断言证明（不靠推理）；
  - `registered ∖ used` = 僵尸登记 ⇒ **只标注不删**（删除会改变历史权限判断，属另卡）。
- **R2 门禁（腿 A 交付物）**：新增 vitest 用例断言 `used ⊆ registered`，并在 `pnpm vitest run` 下真跑绿。**动态绑定 `:endpoint`** 无法静态解析时，必须走**显式白名单**（文件:行号 + 理由），**不许静默跳过**；白名单每项都要在锚里登记。
- **R3 存量核对（腿 B）**：板 §3 第 7 条逐项落 `(路径, 在/不在, 处置)`；**不在**的必须给出处置（订正板子 / 归档留痕 / 补建），并在交付里写明「板子由主 agent 在 M2 订正」。`docs/permission-matrix-audit.md` 保持**零改动**。
- **R4 verdict 三选一（只许这三个字符串）**：`COVERAGE-CLOSED`（无缺口，未改任何登记）/ `GAPS-FIXED`（有缺口并已补齐，须逐个列出补了什么）/ `INCONCLUSIVE`（附缺哪条证据，例如动态绑定无法解析且白名单不成立）。**禁**第四种，**禁**夹带阈值主张或重构建议。
- **R5 增量最小**：`uiEndpointPermissions.ts` 的改动**只许追加缺失条目**（或按 R2 白名单机制调整），**不得**重排、重命名、删除既有条目；diff 行数要与缺口数对齐（补 N 条 ≤ N 行量级）。

## §4 交付物

- 新 vitest 用例：`frontend/src/utils/__tests__/endpointPermissionCoverage.test.ts`（读源码 + 三集合断言）
- 结论锚：`docs/ui-permission-coverage-v1.json`（**LF**，写模式开关，默认逐字节复算）+ `.md`（≤40 行，含三集合计数、缺口清单、存量核对表）
- 仅在实测有缺口时改 `frontend/src/constants/uiEndpointPermissions.ts`（追加式）

## §5 成本

**0 次真实外呼**；0 迁移、0 契约、0 权限、0 新依赖（vitest 已在 devDependencies）、0 CI YAML 改动；`pnpm vitest run` 期望 ≤30 s。

## §6 裁决假设（执行者可推翻，但必须停手上报）

- **D1** 使用面从**模板字面量**提取（`<Permission endpoint="X"` / `:endpoint="'X'"`），不试图静态解析运行时拼接；解析不了的进白名单并留痕。
- **D2** 门禁做成 vitest 而**不是**改 CI YAML：`frontend-quality` job 已跑 Vitest，新增用例即生效。
- **D3** 僵尸登记（`registered ∖ used`）**不删**：本卡只登记成清单，删除属另卡（会改变历史权限判断）。
- **D4** 板子订正（§3 第 7 条）由**主 agent 在 M2** 完成；执行者只交付核对表，**不改** `docs/active-handoff.md`（避免与主 agent 的板子写权冲突）。

## §7 与储备 6/7 的关系

- 储备 6 的原话是「前端小表随站点增长维护」——本卡把它从「人肉记得加」变成**门禁**（不登就红），这是唯一可持续的形态。
- 储备 7 的原话是「文档类存量」——本卡只做**核对与订正**，不做内容重写；`docs/permission-matrix-audit.md` 永久维持历史快照身份。

## §8 判据修订记（M1 前代笔，2026-10-25）

- **R4 的 `GAPS-FIXED` 分支不可达**：验收用例按「复算时的三个差集」机械推导 verdict，若本卡曾有缺口并补齐，补齐后 `used∖registered` 恒回空 ⇒ 只会写出 `COVERAGE-CLOSED`。本卡表零改动、未触发，属**我的口径缺陷**，留记录不改生成器（要同时定「补齐了什么」的落盘形态，另卡处理）。
- **§1 腿 B 与 §2 存量面 已勘误**：初稿「两文件实测都不存在」只对权威树成立，详见 §1 勘误段。
- 以上两条**不动** R0–R5、D1–D4 的判据语义，也不动任何测量值：锚 `docs/ui-permission-coverage-v1.json`（sha256 前 8 位 `01f8ef36`）与 `.md` 本卡零改动。
