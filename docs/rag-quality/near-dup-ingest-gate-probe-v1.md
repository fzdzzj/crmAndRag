# S0 闸门探针 · near-dup-ingest-gate-probe-v1

> 卡：`near-dup-ingest-cooccurrence` 任务组 B（R1）。锚：`near-dup-ingest-gate-probe-v1.json`（唯一写入口
> `NearDupIngestGateProbeIT`，opt-in `RAG_NEAR_DUP_INGEST_REAL=1`，写锚 `-Dnear.dup.ingest.gate.write=true`）。
> 基线：master `cdf3382`；视觉模型 `qwen3-vl-plus`（生产默认 `qwen-vl-plus` 在本 key 返回 model_not_found，灰卡同款覆盖）。

## 1. 一句话结论

**`gatePassRate = 6/6`，`verdict = PROCEED-S1`**——3 个 base 与 3 个最差变体（`crop-04`/`scale-070`/`crop-12`）全部
穿过真实 ingest 链路（VLM 描述 → 生产质量闸门 → 落盘 → 描述 embed + 视觉 embedImage），无一被闸门拒。

## 2. 逐图证据（6 图 × 3 次外呼 = 18 次，全部真计量点）

| # | assetKey | kind | gatePass | compact | VLM tokens(P/C/T) |
|---| --- | --- | --- | --- | --- |
| 1 | nist-sp800-101r1-p1-b1 | base | ✓ | 83 | 335/24/359 |
| 2 | nist-sp800-101r1-p1-b1@crop-04 | crop-04 | ✓ | 83 | 314/24/338 |
| 3 | nist-sp800-101r1-p16-b2 | base | ✓ | 248 | 636/165/801 |
| 4 | nist-sp800-101r1-p16-b2@scale-070 | scale-070 | ✓ | 232 | 430/157/587 |
| 5 | nist-sp800-101r1-p18-b3 | base | ✓ | 220 | 305/160/465 |
| 6 | nist-sp800-101r1-p18-b3@crop-12 | crop-12 | ✓ | 45 | 305/31/336 |

VLM 合计 2886 token；「（截图不清）」出现 0 次；影子分类与生产闸门逐图一致（锚 `failReason` 全 null）。

## 3. 对账

- `externalCalls = 18 = vision 6 + embedImage 6 + embed 6`：embedImage/embed 恰等于过闸门图数 6（写锚模式断言），
  无隐藏调用、无计量漏记；embedding token 端点不回流 usage，记 null（`docs/token-metering.md` §1，未按字符数估算）。
- R0 保真：6 份单图合成 PDF 逐份「抠出 1 图 + 宽高逐一对上」；守卫另覆盖单图 PDF / 21 图切两份（帽截断 20）/ 非 PDF
  字节经生产旁路返回空表且零外呼。
- 默认复跑（opt-in 无 write）：语料与 PDF 重建 + 生产提取器复验 + 派生字段重算，与磁盘锚**逐字节一致**（比对前剔除
  顶层 runtime 块——活值投影不参与字节恒等，fix1），零外呼。

## 4. 选取规则（冻结于锚 `selectionRule`）

`crop-04`/`scale-070` 各取带内第一对（锚 pairs 序）且变体过生产提取器 64px 下限；无合规带内实例时回落语料生成序
第一个合规变体；`crop-12` 无带内对，取 base 不与已选重合的合规变体。实测落点：crop-04 = 带内对
`p1-b1 ↔ p1-b1@crop-04`（cos 0.9934）；scale-070 带内对 `doe-p1-b2@scale-070` 短边 <64px 不可提取 → 回落
`p16-b2@scale-070`；crop-12 = `p18-b3@crop-12`。

## 5. 观察与移交 S1

1. **提取器下限是带内覆盖的第一道闸**：带内对 `doe-p1-b2 ↔ doe-p1-b2@scale-070`（cos 0.9890）的变体短边 <64px，
   真实 ingest 路径在提取期即丢弃它（S1 三态记 `EXTRACTION-DROPPED`，预注册 `INSUFFICIENT-COVERAGE` 语义的一部分，
   本卡不改语料、不修 `src/main`）。
2. 描述以英文/结构化 Markdown 为主（本次 6 图汉字数 0–130），`ConstraintQuerySplitter` 分隔词（并且/同时/后/且）
   命中风险低；S1 仍有拆分子查询预热与硬帽保护兜底。
3. `crop-12`（最重裁切）compact 45 字仍过 40 字闸门——闸门对退化变体的拒绝面比预期窄，S1 的 48 图入库率
   大概率接近满额；闸门连带丢图耦合（proposal §3.2）的实际杀伤面将由 S1 的 `GATE-REJECTED` 三态实测。

## 6. fix1 收卡（2026-10-25，S0-only）

- **NF-1 修复（冻结投影）**：原 `constants.visionModel` 落运行时活值（写锚时的 `qwen3-vl-plus`）⇒ 默认参数下 replay
  必不等、锚不可复认证。修后 `constants.visionModel` 只落编译期常量 `qwen-vl-plus`（可复算、不读属性），活值移入顶层
  `runtime` 块（`excludedFromReplay: ["visionModel"]` 白名单锁死，`overrideReason` 即上方 model_not_found 记载）；
  replay 整体剔除 `runtime` 后逐字节比对，写锚与 replay 共用同一份 `buildAnchor`。本锚 `runtime` 为手工迁移，
  未重跑任何外呼。
- **收卡裁定**：Owner 裁定 A′——S1 未授权（首轮 124 次外呼中止，重跑需 ~120 次将破 162 累计帽），S1 消融不跑、
  装置拆除；负结论预注册于 `near-dup-ingest-s1-not-run-v1.json`，骨架留 `d7abc37` 供后续卡取回。§5 的「移交 S1」
  表述随裁定作废（历史记录保留）。
