# 变更提案 · retrieval-and-contract-hardening（三合一：近重复机制决策 + 图片开关实证 + 契约权限生成化）

> 档位：**A 档**（取三组最高：含**冻结契约改动**）。按 `docs/review-intensity-policy.md` §二，A > B，故本卡整体 A 档——执行自跑全量门禁、指导侧全量逐格核验、复核 agent **全量**（门禁复跑 + 哈希真实性 + 双树核对 + 证据审计）。**三合一省的是派发轮次，不是复核强度。**
> 本卡把三张原本独立的卡合成一条分支、一个执行者、一次合并。三组各有独立的预注册判据与写入面，**互不越界、互不抵赖**（见 §5 停步裁决）。

## 0. 统一主题：把「只有代码阅读/文档措辞支撑的断言」变成可执行证据

三条缺口同源——**都是"看起来已经解决了，其实只有人读过一遍"**：

| 组 | 断言现状 | 谁来证明 |
| --- | --- | --- |
| **G1 近重复机制** | 上一卡判定树只命中 F4（三带样本 < 30），于是"权重维持现值"。但真正的问题被门槛挡住了：**41.7% 的同源变体被当异图丢弃** | 离线复算（零外呼）：540 对余弦已在锚里，3 份 PDF 在仓库里 |
| **G2 图片开关** | 灰度执行单写着"只开 A 不动链路""运行期热生效，无需重启""改回 false 即停"——三句都只有读代码支撑 | 一次零真外呼的 IT：同上下文翻键，看摄取侧行为真的变没变 |
| **G3 契约权限维** | `frontend/openapi.yaml` 11028 行逐字钉死，但**权限要求这一维完全不在契约里**（`x-` 扩展数 = 0） | 从 `@RequirePermission` 自动生成 + 与权威扫描器集合相等 |

三者都**不新增功能**，都是"把软断言变硬"。这是它们合成一张卡的内在理由，不是为了省事硬拼。

## 1. G1 · 近重复机制决策（零外呼离线判据）

### 1.1 现状锚点（实测，非转述）

`ImageSimilarityGate`（`src/main/java/com/slz/crm/knowledge/retrieval/ImageSimilarityGate.java`）：`DEFAULT_THRESHOLD=0.9944`（L31）、`NEAR_DUPLICATE_FLOOR=0.9887`（L34）、权重 `0.75/0.6/0.5`（L37/40/43）、带切分 `Δ=0.0057` 三等分（L87-101）、`tierOf`（L110-121）：`>=threshold` 正常召回 / `[floor,threshold)` 降权 / `<floor` **过滤丢弃**。

v2 锚 `docs/rag-quality/image-threshold-calibration-v2.json` 的 `pairs` 共 **540** 对（键 `leftKey/rightKey/stratum/kind/cosine`）：same-image 144 / near-duplicate 120 / same-class 100 / cross-class 176。生产视图落带 `207 / 3 / 1 / 6 / 323`。

120 对同源变体的去向：**63 对（52.5%）判同图**、**7 对（5.8%）进三档降权带**、**50 对（41.7%）判异图丢弃**；负样本 276 对里有 **3 对（1.09%）**落进带内。

> 一句话：三档降权这条走廊只接住 5.8%，而"腰斩"（41.7%）发生在走廊外面。上一卡的 F1 用"below-floor 占比 > 0.5"当替身，41.7% 险险没到 50%，于是机制缺陷被记成了"样本不足"。

### 1.2 预注册判据 C1–C6（跑前写死，跑后禁改）

`retained` = 判 `SAME_IMAGE` 或 `NEAR_DUPLICATE`（未被丢弃）；`dropped` = 判 `CROSS_IMAGE`。

| 编号 | 判据 | 门槛 | 基线 |
| --- | --- | --- | --- |
| **C1** | 同源**保留率** `R_keep` | **≥ 0.90** | **0.583**（70/120） |
| **C2** | 负样本**泄漏率** `L` = retained/276 | **≤ 0.02** | **0.0109**（3/276） |
| **C3** | 逐 kind 保留率单调 `crop-12 ≤ crop-04 ≤ scale-070 ≤ scale-085`（`jpeg-q80` 允许与 `scale-085` 互换） | 必须成立 | **基线不满足**：crop-12=0 ≤ crop-04=9 ≤ scale-070=22，但 22 > scale-085=21（锚 `baseline.outcome.satisfiesC3=false`） |
| **C4** | 外呼次数 | **= 0** | 0 |
| **C5** | 决策 ∈ {GO, NO-GO, UNDECIDABLE-BY-DATA} | 三选一，禁造第四种 | — |
| **C6** | **全网格披露**：floor 0.90→0.9995 步长 0.0005（**200 点**）、dHash `D` 0..24、混合 `(D_low,D_high)` 全组合，逐点入锚 | 必须全量 | — |

**C5 规则（v1.2 消歧，按序短路四支）**：
① 有点同时满足 `C1∧C2∧C3` ⇒ **GO**，只交「重构规格」（参数/组合顺序/降级行为/新增可观测点/后续卡档位），**本卡不实施**；
② 有点满足 `C1∧C2`、仅 `C3` 缺 ⇒ **UNDECIDABLE-BY-DATA**（机制可用但不单调）；
③ 有点满足 `C1`（不论 `C2`）或有点满足 `C2`（不论 `C1`），且两者不可同时满足 ⇒ **UNDECIDABLE-BY-DATA** + 须给出「关节不可达 + 两侧可达」的量化前沿；
④ 连 `C1` 与 `C2` 都无任何点可达 ⇒ **NO-GO**（此时「无可换机制」才为真）+ 帕累托前沿 + 「近重复三档机制结案声明」。
消歧只朝更保守方向：不新增任何通往 GO 的路径；`C1=0.90` / `C2=0.02` / `C3` 规则与网格参数**一字未改**。

### 1.3 三条候选机制

| 机制 | 做法 | 理由 |
| --- | --- | --- |
| **M1** floor 帕累托扫描 | threshold 固定 0.9944，仅下调 floor（C6 网格） | 零成本改现值语义；量化"带宽要宽到多少才够，代价多大" |
| **M2** dHash-64 感知哈希 | 复用 corpus 已产出的 `BufferedImage`（**禁止二次栅格化**），9×8 灰度差分 64 bit，扫 `D` | 裁剪/缩放鲁棒，完全离线、零依赖、零外呼 |
| **M3** 混合 | `dHash<=D_low` ⇒ 同图；否则 `dHash<=D_high \|\| cosine>=floor` ⇒ 近似带；否则丢弃 | 余弦管语义、dHash 管像素同源，最可能同时满足 C1 与 C2 |

不测：换 embedding（需外呼，且是换底座）、OCR 文本兜底（无字图不适用）、学权重（**无查询-相关性真值**，正是不可识别项）。

### 1.4 G1 写入面（allowlist）

```
src/test/java/com/slz/crm/quality/PerceptualHashSupport.java              （新增）
src/test/java/com/slz/crm/quality/NearDuplicateMechanismAnalyzer.java     （新增）
src/test/java/com/slz/crm/quality/NearDuplicateMechanismAnalyzerTest.java （新增）
src/test/java/com/slz/crm/quality/NearDuplicateMechanismDecisionTest.java （新增，锚唯一写入口）
src/test/java/com/slz/crm/quality/NearDuplicateMechanismDecisionGuardTest.java（新增）
scripts/rag-quality/near-dup-mechanism-report.sh                          （新增）
docs/rag-quality/near-dup-mechanism-decision-v1.json                      （生成物）
docs/rag-quality/near-dup-mechanism-decision-v1.md                        （新增）
```

**G1 禁止**：`src/main/**` 零 diff（含 `ImageSimilarityGate` 五常量与 Javadoc 语义）；`NearDuplicateCorpus.java` 零 diff（变体定义不许动，否则两卡数字不可比）；`pom.xml` 零 diff；外呼 0 次（不出现 `DASHSCOPE`/`System.getenv`/http 客户端，不读 `.env`，不跑 `RAG_*_REAL=1`）。

**G1 自证两条**：① 144 个 `same-image` 对在本卡重算下必须仍判"同图"（dHash 距离 0 且余弦回读一致）——防"重生成语料 ≠ 当初真跑语料"；② 文档里不许出现手算数字，每个数标"来自 `bash scripts/rag-quality/near-dup-mechanism-report.sh` 的哪张表"。

## 2. G2 · 图片四开关「门控关系 + 运行期热生效」实证

### 2.1 现状锚点（读代码实测）

| 事实 | 依据 |
| --- | --- |
| 真视觉键常量 | `PdfImageSliceIngestService.java` L64 `IMAGE_VECTOR_ENABLED_KEY = "rag.retrieval.image-vector-ingest.enabled"` |
| 视觉向量在方案 B 门控**之后** | 同 L136 `imageVectorEnabled() ? ImageVisualVectorSupport.begin() : null` |
| **每次调用都重读**（非启动期读） | 同 L152-157 → `config.get(..., Boolean.class, Boolean.FALSE)` |
| 方案 B 门控 = 总开关 **且** KB 白名单命中；**无**独立 `image-vector-ingest.kb-allowlist` | `docs/multimodal-gray-rollout.md` §2/§9.2 |
| 配置层写后即读新值（但只到配置层） | `DynamicConfigCacheTest` L25、`DynamicConfigAdminServiceTest` L314/L342 |
| 可复用骨架（真库 + mock 视觉向量 + 配置矩阵） | `ImageVectorSpaceIT`（Testcontainers MySQL、`assumeTrue` Docker 守卫、L266 写键） |

**缺口**：没有任何测试在**同一上下文、同一 bean 实例**上做「关 → 翻 → 开 → 再翻回」并观察**摄取侧**行为。L152-157 的"每次重读"是静态阅读结论。

### 2.2 预注册断言（跑前写死）

| 编号 | 场景 | 必须观察到 | 证的是哪句 |
| --- | --- | --- | --- |
| **A1** | B=false + 白名单空 + A=true | `embedImage` **0**、staged **0**、image 集合 **0** 点 | §2「只开 A 而 B 仍 false ⇒ 链路完全不动」 |
| **A2** | B=true + 白名单不命中 + A=true | `embedImage` **0** | §2/§9.2「白名单是唯一灰度粒度」 |
| **A2b** | B=true + 命中 + A=false | 描述切片 > 0 **但** `embedImage` **0** | §2「两个开关各管一段」 |
| **A3** | B=true + 命中 + A=true | `embedImage` **== 图数 N**、image 集合点 == N | §3 第 4 步 |
| **F1** | 同上下文同 bean：关→跑(0)→**运行期**写 true→再跑 | 第二轮 **> 0**（不重启） | §3 第 5 步「运行期热生效」 |
| **F2** | 紧接写回 false→再跑 | 第三轮 **回到 0** | §6/§11「改回 false 即停新增」 |
| **N1** | 反向自检 | A3 计数 **严格大于** A1 计数 | 防"全 0 假绿" |

**补充约束**：全过程写在一个测试方法内（或 `@TestInstance(PER_CLASS)` 同一实例）+ 关键 bean `assertSame`（跨实例会隐式重建上下文，那样"热生效"等于没测）；零真外呼（`ModelProvider` 计数替身）；**配置写入必须走生产同一个服务 bean**（管理端服务优先），不许直接改 mock map。

### 2.3 G2 写入面

```
src/test/java/com/slz/crm/knowledge/document/ImageVectorSwitchHotFlipIT.java  （新增）
docs/multimodal-gray-rollout.md        （只追加 §12「热生效与门控关系实证」，§1–§11 一字不动）
```

**G2 禁止**：`src/main/**` 零 diff（含 `DynamicConfigKeyRegistry`、`ConfigKeyTierPolicy`）；不改任何默认值、**不打开任何生产开关**；`docs/dynamic-config-keys.md` 零 diff；`pom.xml` 零 diff。

**技术路线**：优先 H2 + mock 持久化（先例 `DynamicConfigSchemaH2Test`），**不依赖 Docker**；仅当实测构造不出真实 `buildSlices` 路径时才降级 `ImageVectorSpaceIT` 样式真库，并在回报里说明原因。

## 3. G3 · 端点权限要求「生成化」进 OpenAPI 契约（A 档动的就是它）

### 3.1 现状锚点

- `frontend/openapi.yaml`：**11028 行**、`x-` 扩展 **0** 个；由 `OpenApiContractExportTest`（`@ActiveProfiles({"test","dev"})` + MockMvc 打 `/v3/api-docs.yaml`）逐字钉死，门禁脚本 `scripts/check-openapi-consistency.sh`。
- 权限事实只在两处，且都在代码/测试内部：方法级 `@RequirePermission(PermissionOperates.X)`（`RequirePermission.java` L22-27，`@Target(METHOD)`）与测试侧 `PermissionCoverageScanner` + `OpenEndpointRegistry`（SECURED / INTENTIONAL_OPEN / PENDING_DECISION，PENDING 已清零）。
- `OpenApiConfig` 现有两个 customizer（`multipartFileParameterCustomizer` L53-72、`GlobalOpenApiCustomizer`），均不碰 `extensions`。

**「生成化」的歧义先钉死**：生成**权限要求**（`x-` 扩展），不是生成权限 DTO——`RoleANDPermissionDTO`/`PermissionGroupedVO` 那些本来就是 springdoc 从 VO 自动生成的，不存在手工维护问题。

### 3.2 契约增量（格式冻结，合入即不可改形状）

```yaml
x-required-permission: { code: 608, name: PLATFORM_DYNAMIC_CONFIG_MANAGE }   # 有方法级注解
x-permission-status: OPEN                                                    # 无注解（= INTENTIONAL_OPEN）
```

| 约定 | 内容 |
| --- | --- |
| 互斥 | 同一 operation 不得同时出现；不得两者都缺 |
| `code` | **数字**（`PermissionOperates#getCode()` 的 `Long` 原样） |
| `name` | 枚举常量名，全大写 |
| 扩展性 | 后续只许**新增** `x-` 键，不得改 (a)(b) 形状 |
| 不表达 | 不表达"哪个角色有权限"、不表达 COST 档位、不表达灰度白名单 |
| 为什么不塞 `security` | `securitySchemes` 表达**认证**（JWT），本系统权限是**方法级业务鉴权**，混用会误导 SDK 生成器 |

### 3.3 预注册验收判据 P1–P4

| 编号 | 判据 | 门槛 |
| --- | --- | --- |
| **P1** | 每个 operation 恰有 (a)(b) 之一 | **0 例外**（不许"允许 N 个漏"） |
| **P2** | 契约解析出的 `SECURED(路径\|方法→code)` / `OPEN(路径\|方法)` 与扫描器+登记表结果 **集合相等** | 任一差异即红；两条路径必须**独立** |
| **P3** | 前端零破坏：`pnpm gen:api` 后生成物 diff 为空或仅注释级；`lint:check`/`type-check:check`/`test`/`build` 全绿 | 任一红即停步 |
| **P4** | `src/main` 改动面 = `OpenApiConfig.java`（改）+ 1 个新 customizer 类 | 精确 2 文件 |

### 3.4 G3 写入面

```
src/main/java/com/slz/crm/server/config/OpenApiPermissionCustomizer.java   （新增）
src/main/java/com/slz/crm/server/config/OpenApiConfig.java                 （改：注册 bean）
frontend/openapi.yaml                                                      （生成物，只许 --update 覆盖，禁手改）
src/test/java/com/slz/crm/contract/OpenApiPermissionContractParityTest.java（新增，P1/P2）
src/test/java/com/slz/crm/contract/OpenApiContractExportTest.java          （仅追加一条点状断言）
openspec/changes/retrieval-and-contract-hardening/specs/api-contract/spec-delta.md（新增）
spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md               （追加变更日志一行）
```

**G3 禁止**：`RequirePermission.java` / `PermissionOperates.java` / `PermissionsInterceptor` / `OpenEndpointRegistry.java` / `PermissionCoverageScanner.java` 全部零 diff（**零权限语义变更**）；`docs/permission-matrix-audit.md` 零 diff（历史快照禁改）；`frontend/src/**` 不许手改（除 `gen:api` 自动产物）；`frontend/typed-router.d.ts` 零 diff。

**顺序不可乱**：`--update` → `pnpm gen:api` → 复跑 `check-openapi-consistency.sh` → `mvn test` → 前端四项。

## 4. 全局硬边界（三组并集，违反即整卡作废）

1. `scripts/test-baseline.txt` **三组都不许改**（合并侧统一 `--update` 重锚）。
2. `pom.xml` 零 diff（**零新依赖**）；`src/main/resources/db/migration/**` 零 diff（零迁移）。
3. 外呼 0 次（三组均零真外呼；`RAG_*_REAL=1` 一律不碰）。
4. 写入面严格限于 §1.4 / §2.3 / §3.4 三张表的并集；表外任何路径若要动，**停手回报**。
5. 预注册判据（C1–C6 / A1–N1 / P1–P4）的数字**一个都不许改**；不改就过不了，那就照实写"不过"。
6. **不打开任何生产开关**、不改任何默认值、不改权限语义。

## 5. 组间顺序与停步裁决（三合一最容易出事的地方）

**执行顺序固定**：G0 立项与共用侦察 → **G1**（最便宜，纯离线）→ **G2**（实证）→ **G3**（契约，最后，因为它要重导出契约 + 前端构建）→ G4 全量门禁与三份照判。

**停步裁决（防止一组失败连坐另外两组）**：

| 触发 | 裁决 |
| --- | --- |
| G1 三机制全不满足 C1∧C2 | **不是故障**，是合法结论：按 C5 四支判（本卡数据命中第 ③ 支 ⇒ UNDECIDABLE-BY-DATA）并交付两侧前沿 + 定夺数据；仅当连 C1/C2 都无点可达时才落 NO-GO + 结案声明。产物照常交付 |
| G1 键对不上 / same-image 重算不为同图 | G1 **停步**：回退 G1 全部产物，发现写入回报 + `work/evidence-<card>-g1-stop.md`（未跟踪），G2/G3 照常 |
| G2 的 F1/F2 不成立 | G2 **停步**：回退 G2 产物（IT + runbook §12 未写则概不回退），实证写 `work/evidence-<card>-g2-stop.md`；**禁止改 `src/main` 让它变绿**（修复另立卡） |
| G2 的 A1/A2/A2b 不成立 | 同上停步：说明执行单 §2 结构性断言为假，属重大发现 |
| G3 契约覆盖后复校不一致 / P2 差异无法用"去尾部 `/`"解释 / 前端四项红且不能从契约侧修 | G3 **停步**：回退 G3 产物（customizer + 契约 + 前端生成物 + spec-delta + 解冻登记），G1/G2 照常交付 |
| 任一组触发停步 | 其余组**必须继续做完**，不得整体放弃；回报里把"哪组停步、哪组交付"分开写 |

> 一句话：**分支只有一条，但交付是按组判定的**。停步组的产物必须回退到 `master` 状态，不许留半成品红测试在分支上。

## 6. 交付物汇总

| 组 | 交付物 |
| --- | --- |
| G1 | 5 个测试类 + 1 个脚本 + 决策锚 JSON + 决策文档 MD |
| G2 | 1 个 IT + `docs/multimodal-gray-rollout.md` §12 |
| G3 | 1 个 customizer + `OpenApiConfig` 注册 + 重导出契约 + parity 测试 + `OpenApiContractExportTest` 追加断言 + spec-delta + `contracts-frozen.md` 一行 |
| 卡 | `openspec/changes/retrieval-and-contract-hardening/{proposal.md,tasks.md}` + `work/task-card-retrieval-and-contract-hardening.md` |

## 7. 非目标

1. 三组都不实施功能性改造：G1 只到规格（GO 也不改常量）、G2 只取证（不打开开关）、G3 只让契约携带事实（不做前端按钮级权限 UI）。
2. 不做真外呼真跑（成本与安全都不需要）。
3. 不动 `docs/permission-matrix-audit.md`、`docs/dynamic-config-keys.md`、`frontend/typed-router.d.ts`、`frontend/src/**`。
4. 不"顺手修"G2 可能暴露的 `src/main` 缺陷（另立卡，档位可能 A）。
5. 不扩语料、不加依赖、不扩迁移。

## 8. 风险与取舍

| 风险 | 处置 |
| --- | --- |
| **三合一后爆炸半径变大**：G3 契约不收敛会拖住 G1/G2 | §5 停步裁决：G3 单独回退，G1/G2 照常交付；合并前最后一次 `git diff --stat master` 逐组分账 |
| **A 档全量复核一次覆盖三组**，复核成本高于三次 B 档减配 | 明确接受：省的是派发/澄清轮次，不是审查。复核提示词按"三组分别出表 + 一份总判定"组织 |
| 单次门禁时间长（契约导出 + 前端构建 + 全量 surefire） | 门禁顺序固定：`merge-gate.sh` 一次跑全；契约与前端在 G3 收尾各跑一次，不重复跑 |
| G1 的 0.90 / 0.02 是政策数（非实测推出） | 已在 §1.2 写明理由；**开工前可改，开工后改即违反 C6** |
| G2 测不出真实 `buildSlices` 路径 | 降级真库（`ImageVectorSpaceIT` 样式），回报说明原因，不算失败 |
| G3 的 `x-` 让契约涨约 570×2 行 | 接受：契约是生成物，逐字门禁把关；`openapi.yaml` 不参与前端打包 |
| G1 的 200 点网格慢 | 纯算术（≈11 万次比较）；若 > 5 s 用前缀和优化，**不得减点数** |

## 9. 验收（整卡 + 分组）

**整卡**：`mvn -B -ntp test` 全绿；`bash scripts/merge-gate.sh` 全绿；`bash scripts/check-openapi-consistency.sh` 绿；`bash scripts/check-test-baseline.sh` 结论如实记录（不 `--update`）；`scripts/test-baseline.txt` 与 `pom.xml` 零 diff；tasks.md 每项勾选行下一行有 6 空格「补勾依据」且可独立复跑。

**G1**：`bash scripts/rag-quality/near-dup-mechanism-report.sh` 成功且 `externalCalls=0`；C1–C6 逐条照判，`verdict` 与锚自洽；网格 200/25/全组合无缺；`src/main` 零 diff。

**G2**：七条断言全绿（或按 §5 停步）；runbook 只新增 §12；`src/main` 零 diff；测试类零外呼痕迹（grep 出表）。

**G3**：P1 零例外；P2 集合相等且两路径独立；P3 前端四项绿；P4 `src/main` 精确 2 文件；`contracts-frozen.md` 只多一行；`docs/permission-matrix-audit.md` 零 diff。

## 10. 修订记录

- v1.0（2026-10-23）：由三张独立卡（`near-dup-mechanism-decision` / `image-vector-switch-hotflip` / `openapi-permission-extension`）**三合一**而来。合并依据：三者同为"把软断言变硬"，且写入面无重叠。合并后档位取最高（A）；新增 §5 停步裁决，确保一组停步不连坐其余两组；判据、写入面、禁止清单逐条保留，未做任何放松。
- v1.1（2026-10-23）：修正 C3 基线栏。原写"4 项成立"系误移用 `docs/rag-quality/image-near-duplicate-bands-v2.md` §9 的**余弦中位数**单调性口径，而 C3 判的是**逐 kind 保留数**单调性（两个不同量）。实测基线不满足（scale-070 22 > scale-085 21）。**判据规则与门槛一字未改**，仅更正基线观测值。
- v1.2（2026-10-23）：消歧 C5 判定分支。原文三支逻辑重叠且"或反之"在本卡方向上无意义（系指导侧起草缺陷）。**消歧发生在看到数据之后**，故只朝更保守方向（不新增任何通往 GO 的路径），且 C1=0.90 / C2=0.02 / C3 规则与网格参数**一字未改**。据此本卡结论由 NO-GO 更正为 UNDECIDABLE-BY-DATA（关节不可达、两侧可达）。
- v1.3（2026-10-23）：记录 C5 第 ③/④ 支的**第二处文字消歧**（执行侧在第 2 次修复时提出，指导侧采信）。③「有点满足 C1（不论 C2）**或**有点满足 C2（不论 C1）⇒ UNDECIDABLE」与 ④「连 C1 都无任何点可达 ⇒ NO-GO」在"**仅 C2 可达**"情形下字面重叠；采信 ③ 的显式表述，**④ 的实际域收缩为"两侧皆不可达"**。该消歧对本卡数据**不改变结论**（C1 与 C2 两侧均可达 ⇒ ③ 必触发），已在 `verdict()` Javadoc、`NearDuplicateMechanismAnalyzerTest#verdictFollowsFourBranches`（含"仅 C2 可达"断言）与 tasks 1.6 留痕。**教训：判据要当合同用，两个读者必须读出同一个意思**——本卡 C3 与 C5 两处缺陷根因相同。
