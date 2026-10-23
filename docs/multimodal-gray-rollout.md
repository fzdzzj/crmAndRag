# 真共享空间生产灰度授权路径登记（MULTIMODAL-GRAY-ROLLOUT-1.0）

> 卡：`multimodal-gray-and-eval-threshold` 任务组 1（2026-10-22 Owner 拍板：**只登记运行期灰度路径，不改代码**）。
> 性质：**纯文档**——零 `src/main` 改动、零迁移、零权限常量、**生产开关默认值保持 `false` 原样**。
> 关联锚点：`docs/rag-quality/production-gray-cost-v1.json`（成本基线）、`docs/rag-quality/image-threshold-calibration-v1.json`
> （异图判别阈值定标）、`docs/token-metering.md`（embedding 计量口径）。

## 1. 这张文档解决什么

`production-gray-real-run` 已经量出「打开开关后每张图的成本」，但**没有授权打开**：生产开关默认 `false`。本文件把「打开」这件事登记为
**运行期动态配置操作**（谁写、写哪几个键、按什么顺序、怎么回滚、看哪些指标），而不是代码改动——**本卡一行生产代码都不碰**。

## 2. 四个键与真实门控关系（读代码实测，勿凭键名想当然）

| 键 | 默认值 | 档位 | 作用 |
|---|---|---|---|
| `rag.retrieval.image-slice-ingest.enabled` | **false** | COST | 方案 B 总开关：PDF 内嵌图片 → VLM 描述 → 描述文本切片进既有 chunk/embed 链路 |
| `rag.retrieval.image-slice-ingest.kb-allowlist` | 空 | COST | **KB 灰度白名单**（逗号分隔 KB id；空 = 全库）。按 KB id 精确匹配，不做前缀/模糊 |
| `rag.retrieval.image-slice-ingest.max-images-per-document` | **5** | COST | 单文档图帽，范围 1~20（超出不送 VLM、不落盘）。**这就是生产帽** |
| `rag.retrieval.image-vector-ingest.enabled` | **false** | COST | 方案 A 真视觉向量开关：同一张图再经文本-图像联合 embedding 写独立 image 集合 |

**关键结构事实（`PdfImageSliceIngestService` 实测）**：真视觉向量路**嵌在方案 B 的门控之内**——
`buildSlices()` 先判 `enabled(knowledgeBaseId)`（= 方案 B 总开关 **且** KB 白名单命中），命中后才看
`imageVectorEnabled()`；**没有独立的 `image-vector-ingest.kb-allowlist` 键**。因此：

> 「灰度开真视觉」= 方案 B 总开关 true + 白名单限定 KB + 真视觉开关 true。
> 只写 `image-vector-ingest.enabled=true` 而方案 B 总开关仍 false 时，**链路完全不动**（零外呼、零落盘）。

## 3. 灰度打开步骤（超管运行期操作，按序执行）

1. **先定灰度范围**：把目标知识库 id 以逗号分隔写进 `rag.retrieval.image-slice-ingest.kb-allowlist`
   （例如 `12,15`）。**空值 = 全库生效**，灰度期不要留空。
2. **确认图帽**：`rag.retrieval.image-slice-ingest.max-images-per-document` = **5**（生产默认帽；如灰度期要压成本可临时调小，
   硬上限 20）。
3. **先开方案 B**：`rag.retrieval.image-slice-ingest.enabled = true`。此时只有白名单 KB 的 PDF 内嵌图会被描述入切片。
4. **再开真视觉**：`rag.retrieval.image-vector-ingest.enabled = true`。此后同一批图额外产生视觉向量进 image 集合
   （query-by-image 的检索面）。
5. **写入方式**：管理端动态配置（COST 档，**超管专写**；不支持 608 运营档写入）。四项都是运行期热生效，无需重启。
6. **观察窗口**：至少跑一个白名单 KB 的重建入库 + 若干次随图提问，再决定扩 KB。扩面 = 改白名单（第 1 步），不是改开关。

## 4. 成本基线引用（决策依据，不重复计算）

来源：`docs/rag-quality/production-gray-cost-v1.md` / `.json`（真实公有领域 PDF 真跑，`PRODUCTION-GRAY-COST-1.0`）。

| 每张**真正入库**的图 | 实测值 |
|---|---|
| vision token | **≈ 1 371**（9 600 / 7；prompt 8 001 + completion 1 599 真实回流） |
| vision 外呼 | **1.29**（9 / 7，含被描述质量闸门剔除的 2 张**沉没成本**） |
| 视觉 embedding 调用 | **2.00**（摄取 1 + 查询 1） |
| 文本 embedding 调用 | **4.57**（32 / 7，含查询侧多路拆路） |
| 端到端耗时 | **≈ 9.8 s** |

> **预算口径三条**（照抄基线的结论，勿自行换算）：① 按「每张入库图」摊分，不按每份 PDF（页数/图数方差极大）；② 真实文档提取→入库有
> **22% 沉没**（9 提取 / 7 入库，vision 已花），预留预算要按**提取图数**；③ vision 是唯一有真实 token 的环节，两类 embedding 端点
> **token 不可得**（口径见 `docs/token-metering.md`），只能按调用次数计价。

## 5. 判别阈值（灰度期查询侧口径）

- 程序化套件 `ImageCrossModalSuite` 的 **0.90 保持不变**（其语料是程序化 6 图，改它会动 `image-baseline-v1` 可比性）。
- **真实语料**的判别阈值以定标锚为准：`docs/rag-quality/image-threshold-calibration-v1.json`
  （`threshold.recommendedThreshold`，由「同图下限 / 异图上限」区间中点定义；不可分时该文件明确写「不给硬阈值」）。
- 生产灰度实测提醒：真实语料异图余弦最高到 **0.9887**（`production-gray-cost-v1`），**0.90 在真实语料上会把异图判成同图**——
  所以真实语料闸门读定标锚，不读 0.90。

## 6. 回滚（可逆，写回即可）

1. `rag.retrieval.image-vector-ingest.enabled = false`（先关费用最高的真视觉路）；
2. 需要全停时再 `rag.retrieval.image-slice-ingest.enabled = false`（方案 B 停 = 完全回到升级前行为，文本摄取不受影响）；
3. 已落盘的图片资产/切片不自动清理：重跑该 KB 的**重建入库**会按既有清理链（软删 → 清向量 → 删对象 → 物理删行）收口；
4. 不需要也不允许改代码、改默认值或回滚迁移（本卡零迁移）。

## 7. 灰度期观察指标

| 指标 | 用途 |
|---|---|
| `ai.token.total{type="vision"}` | vision 真实 token（唯一可读的真 token 账） |
| `ai.token.record.calls{type="embedding"}` | embedding **调用次数**（token 不可得时的唯一真账） |
| `ai.token.usage.unavailable{type="embedding"}` | token 不可得声明条数（应与 embedding 调用次数同步增长；不同步说明端点行为变了） |
| `ai.token.record.failure` | 计量落库失败（>0 说明账单有缺口） |
| 摄取 WARN「描述质量闸门」/「图片提取失败」 | 沉没成本与失败率 |

## 8. 未解决项 / 待决策项

1. **生产开关是否真的打开**（Owner 决）：本文件只登记路径与成本，不构成打开授权；打开动作本身是运行期操作，需 Owner 按
   §4 的成本基线拍板。
2. **图帽 5 的预算含义**：真实文档动辄上百张内嵌位图（DOE 那份实测 107 张），帽 5 决定单文档 vision 上限 ≈ 5 × 1 371 token
   ≈ 6 855 token（不含沉没）；若灰度期发现单文档成本超预期，优先调**图帽**再考虑关开关。
3. **KB 白名单是唯一灰度粒度**（观察）：没有 per-document / per-filename 灰度；需要更细粒度须另立卡（本卡不改代码）。

## 9. 开前核对（Pre-flight，逐项打卡后再动开关）

> **边界（reserve-sweep-1 S4）**：本节只是把「打开前必须先看什么」写成可执行清单。**本卡不打开任何开关、不改任何默认值**；
> 打开动作属 **Owner 运行期决定**（依据 §4 成本基线与 §8 待决策项），不构成打开授权。

### 9.1 键当前值怎么读（全部只读，不写）

1. **管理端单键**：`GET /platform/config/items/rag.retrieval.image-vector-ingest.enabled`
   （方法级 `@RequirePermission(PLATFORM_DYNAMIC_CONFIG_MANAGE, 608)`，超管直通；返回 `ConfigItemView` 的
   `value` / `source` / `version`）。
2. **管理端按命名空间批量**：`GET /platform/config/items?namespace=rag.retrieval`（一次看全四键，含静态默认展示）。
3. **DB 直查（只读）**：

   ```sql
   SELECT config_key, config_value, value_type, version, is_deleted, update_time
   FROM dynamic_config_item
   WHERE config_key IN (
     'rag.retrieval.image-slice-ingest.enabled',
     'rag.retrieval.image-slice-ingest.kb-allowlist',
     'rag.retrieval.image-slice-ingest.max-images-per-document',
     'rag.retrieval.image-vector-ingest.enabled');
   ```

   表结构见 `src/main/resources/db/migration/V6__dynamic_config.sql`：`is_deleted=1` 表示该键没有覆盖行，
   **实际生效值回落静态默认**（`DynamicConfigKeyRegistry`：`image-slice-ingest.enabled=false`、
   `image-vector-ingest.enabled=false`、`kb-allowlist=""`、`max-images-per-document=5`）。
4. **判定**：四键都查不到覆盖行（或 `is_deleted=1`）/ `config_value` 为 `false`/空/`5` ⇒ 「现在是关的」，可以进入
   §3 灰度步骤；任一为 `true` 先按 §11 回滚，再谈打开。

### 9.2 灰度面：摄取侧 vs 检索侧

| 键 | 作用面 | 打开后立刻产生的外呼/写入 | 可逆性 |
|---|---|---|---|
| `image-slice-ingest.enabled` | **摄取侧**（只在重建入库时动作，已入库文档不会自动重跑） | 提取内嵌图 → VLM 描述（vision token）→ 描述文本切片 | 改回 `false` 即停新增 |
| `image-slice-ingest.kb-allowlist` | 摄取侧范围 | 只影响命中 KB；空 = 全库 | 改回即收窄/恢复 |
| `image-slice-ingest.max-images-per-document` | 摄取侧成本帽 | 单文档 vision 上限（默认 5，硬上限 20） | 改回即恢复 |
| `image-vector-ingest.enabled` | **摄取侧写入 + 检索侧读取**（`knowledge_image` 集合的 query-by-image 面） | 每张入库图 1 次视觉 embedding + 1 个 image 集合点 | 改回 `false` 停新增；**历史点不自删**（见 §11） |

**结构性提醒（§2 已实测）**：真视觉路嵌在方案 B 门控之内，**没有独立的 `image-vector-ingest.kb-allowlist`**。
因此「只开 `image-vector-ingest.enabled=true` 而 B 总开关仍 false」时链路**完全不动**（零外呼、零落盘），
核对时不要把它当作「已灰度」。

### 9.3 成本基线链接（打开前必读）

- `docs/rag-quality/production-gray-cost-v1.md` / `.json`：每张**真正入库**的图 ≈ **1 371 vision token**、
  **1.29** 次 vision 外呼（含 22% 沉没）、**2.00** 次视觉 embedding 调用、**4.57** 次文本 embedding 调用、**≈9.8 s** 端到端。
- 预算口径：按「每张入库图」摊分、按**提取图数**预留（见 §4 三条口径），不按 PDF 页数。

## 10. 开后可观测（打开后看什么、在哪看）

| 观测面 | 具体锚点 | 判读 |
|---|---|---|
| vision 真实 token | 指标 `ai.token.total{type="vision"}` | 增长 ⇒ 描述链路在跑；不增但开关为 true ⇒ 方案 B 门控/白名单未命中 |
| embedding 调用次数 | 指标 `ai.token.record.calls{type="embedding"}` | 增量 ≈ 文本 embedding + **视觉 embedding（= 入库图数 × 1）**；只有文本增量 ⇒ 真视觉路没写 |
| embed token 不可得声明 | `ai.token.usage.unavailable{type="embedding"}` | 应与调用次数同步增长，不同步 ⇒ 端点行为变了（计量口径见 `docs/token-metering.md`） |
| 计量落库失败 | `ai.token.record.failure` | >0 ⇒ 账单有缺口，先修计量再扩面 |
| 摄取日志 | 「描述质量闸门」/「图片提取失败」WARN（`PdfImageSliceIngestService`） | 沉没成本与失败率；提取失败不计入入库图 |
| 落盘证据 | Qdrant 集合 **`knowledge_image`**（`qdrant.image-collection` 默认值，`QdrantProperties:35`）点数 | 点数增长 ⇒ 视觉向量已写；vision token 涨而点数不涨 ⇒ 写入失败 |
| 检索侧标记 | 命中元数据 `imageSimilarityTier` / `imageSimilarityBand`（`ImageSimilarityGate`） | `NEAR_DUPLICATE` + `NEAR_HIGH/MID/LOW` 出现 ⇒ 近似带在真实查询中被填充（`docs/rag-quality/image-near-duplicate-bands-v1.md`） |

**观察窗口**：至少一个白名单 KB 的**重建入库** + 若干次随图提问，再决定扩 KB（§3 第 6 步）。
扩面只改白名单，不改开关；扩面之前先复核上面第 1、2、6 三行。

## 11. 回滚（可逆：写回 `false`，但已入库的图片向量要单独处置）

1. **先关费用最高的真视觉路**：`rag.retrieval.image-vector-ingest.enabled = false`（改回后零新增视觉 embedding 外呼、零新增 image 集合点）。
2. **需要全停时再关方案 B**：`rag.retrieval.image-slice-ingest.enabled = false`（= 完全回到升级前行为；文本摄取不受影响，§6）。
3. **已入库图片向量的处置（开关不管历史数据）**：

   - 开关只控制**后续写入**；已写入 `knowledge_image` 集合的点**不会**因改回 `false` 而自动消失。
   - **判断依据**：① 集合 `knowledge_image` 的点数是否 >0；② 该点是否属于本次灰度白名单 KB（按 KB id 过滤）；
     ③ 是否还要保留 query-by-image 能力（保留则不动，彻底回收才清理）。
   - **处置路径**：对该 KB 重跑**重建入库**，走既有清理链（软删 → 清向量 → 删对象 → 物理删行，§6.3）；
     **禁止**直接 drop 整个 `knowledge_image` 集合——它会连带清掉其他 KB/其他批次合法写入的图片向量。
   - 清理前先确认 KB 范围（`image-slice-ingest.kb-allowlist` 的灰度清单）与集合名，并在变更单里写清影响面。

4. **回滚验收**：再读四键确认 `false`/空/`5`（§9.1 只读查询）；观察 embedding 调用次数回落到基线；
   `knowledge_image` 点数不再增长（清理场景下应下降）。
5. **禁止**：改代码、改默认值、回滚迁移（本卡零迁移）；回滚是运行期写值，不是发版。

## 12. 热生效与门控关系实证（retrieval-and-contract-hardening G2，零真外呼）

> 本节只追加**事实**：本卡**未打开任何开关**、未改任何默认值。§9 的 pre-flight 清单与 Owner 运行期授权流程**一字不变**，
> 本节只是把 §2 / §3 第 5 步 / §6 第 1 条 / §11 第 1 条的措辞变成了可复跑的测试。

### 12.1 断言表（`src/test/java/com/slz/crm/knowledge/document/ImageVectorSwitchHotFlipIT.java`）

七条断言写**在同一个测试方法内**（`switchGateAndHotFlipAssertions`），A→B→F 顺序执行，全程只有一个
`PdfImageSliceIngestService` / 一个 `DynamicConfigService` / 一个 `DynamicConfigCache` 实例。fixture PDF 内嵌 **N = 3** 张图。

| 编号 | 场景（四键取值） | 必须观察到 | 实测 |
| --- | --- | --- | --- |
| **A1** | B=**false** + 白名单空 + A=true | `embedImage` **0**、vision **0**、切片 **0**、image 集合 **0** 点 | 0 / 0 / 0 / 0 |
| **A2** | B=true + 白名单**不命中** + A=true | `embedImage` **0**、image 集合 **0** 点 | 0 / 0 |
| **A2b** | B=true + 命中 + A=**false** | 描述切片 **= N**、`embedImage` **0**、image 集合 **0** 点 | 3 / 0 / 0 |
| **A3** | B=true + 命中 + A=true | `embedImage` **= N**、image 集合点 **= N** | 3 / 3 |
| **F1** | 同实例：A=false 跑一轮（0）→ **运行期**写 true → 再跑 | 第二轮 `embedImage` **> 0**（= N） | 0 → 3 |
| **F2** | 紧接写回 false → 再跑 | `embedImage` **回到 0** | 0 |
| **N1** | 反向自检 | A3 计数 **严格大于** A1 计数 | 3 > 0 |

四键取值口径：A = `rag.retrieval.image-vector-ingest.enabled`（真视觉路），B = `rag.retrieval.image-slice-ingest.enabled`（方案 B 描述路），
白名单 = `rag.retrieval.image-slice-ingest.kb-allowlist`，图数帽 = `rag.retrieval.image-slice-ingest.max-images-per-document`。

### 12.2 「未重启」是怎么证的

1. **实例同一性**：`assertSame` 覆盖 service / config / cache 三个关键实例（首尾两次取引用比对），测试结束打印三个
   `System.identityHashCode`，日志形如 `[G2] 未重启证据：service@... config@... cache@...（7 轮共用，首尾 assertSame 通过）`。
2. **没有可重启的东西**：本 IT 直接装配生产类（无 Spring 上下文），**不存在**上下文重建路径；每轮唯一变化的是
   `DynamicConfigItemMapper` 的 **mock 行表**里那三行的值。
3. **配置只能经生产服务写入**：每轮翻键都走 `DynamicConfigAdminService#updateValue`（越权闸 → 类型校验 → 版本乐见锁 → 写历史 → 写后失效），
   测试**不直接改任何 map**；写后由真实 `DynamicConfigCache` 失效 + 真实 `DynamicConfigServiceImpl` 回读。
4. **零真外呼**：`ModelProvider` 是计数替身（`vision` 返回定长描述、`embedImage` 返回定长向量），`embedImage` 的调用次数就是断言对象；
   本类不出现真实模型客户端、不读环境变量、不读 `.env`，也不需要 Docker/H2（配置表持久化用 mock 行表，H2 兼容性由
   `DynamicConfigSchemaH2Test` 单独覆盖）。

### 12.3 复跑

```bash
mvn -B -ntp test -Dtest=ImageVectorSwitchHotFlipIT
```

预期：`Tests run: 1, Failures: 0`，日志里有一行 `[G2] 未重启证据`。

### 12.4 失败怎么读

| 红的是哪条 | 说明什么 | 怎么处理 |
| --- | --- | --- |
| A1 / A2 / A2b | **门控关系**变了：`buildSlices` 里的开关判断顺序或 KB 白名单语义被改（§2 的结构性断言为假） | 停手，按 proposal §5 写 G2 停步实证；**禁止改 `src/main` 让它变绿** |
| A3 | 视觉向量路没逐图落暂存（`embedImage` 不等于图数） | 先查是不是提取/描述闸门先失败（日志有 WARN），再确认开关是否真为 true |
| F1 / F2 | **热生效**破了：配置值写后没被下一次调用读到（缓存没失效或调用点改成启动期读） | 同上停步；这是 §3 第 5 步与 §11 第 1 条的实证前提 |
| N1 | 全 0 假绿：所有轮都没外呼 | 检查 fixture PDF 是否真含内嵌图（`IMAGE_COUNT` 与 `PdfImageExtractor` 抽取数） |

## 13. 开启通道工具化（evidence-to-operations G3，只追加本节）

> 本节只把 §9 的人工核对单变成**可判定脚本 + 自测 + 文档防漂移守卫**。本卡**不打开任何开关、不改任何默认值、
> 不改注册表与档位策略**（`DynamicConfigKeyRegistry` / `ConfigKeyTierPolicy` 零 diff，由守卫测试逐键比对）；
> §1–§12 逐字不动，打开动作仍是 **Owner 运行期决定**（§9 边界不变）。

### 13.1 预检/守望脚本

```bash
# preflight：读四键快照，给出 GO/NO-GO 与原因码
bash scripts/rag-quality/image-vector-ingest-preflight.sh preflight --snapshot <TSV 或扁平 JSON 文件>

# 或直连管理端只读接口（GET /platform/config/items?namespace=rag.retrieval，需要 608/超管 token）
bash scripts/rag-quality/image-vector-ingest-preflight.sh preflight --base-url http://<host> --token <JWT>

# watch：按间隔重复判定（--times 0 = 无限轮询，Ctrl-C 结束）
bash scripts/rag-quality/image-vector-ingest-preflight.sh watch --snapshot F --interval 30 --times 5
```

- 四键快照（TSV：`key<TAB>value`，`#` 注释；或扁平 JSON `{"key":"value"}`）覆盖 §2 的四个键
  （A=`image-vector-ingest.enabled`、B=`image-slice-ingest.enabled`、白名单、图帽）；逐键回显 + 判定。
- **退出码语义**：`0`=GO；`1`=NO-GO（**合法结论，不是故障**）；`2`=输入错误（缺键/值不合法/图帽越界 1~20/接口不可达）。
- **原因码**（按序短路）：`GO`（B=true + 白名单非空 + A=false，= §3 第 1~3 步已就位，下一步才是第 4 步开 A）/
  `A_WITHOUT_B`（A=true 而 B=false ⇒ **链路完全不动（§2/§12 已实证）**，不要当作已灰度）/ `B_OFF`（B=false，
  四键全关即原始态）/ `NO_ALLOWLIST`（B=true 但白名单空 = 全库生效，违反 §3 第 1 步灰度约定）/
  `ALREADY_ON`（A=true，重复打开无意义，变更走 §11）/ `BAD_INPUT`。
- 与 §9/§10/§11 的对应：`GO`/`NO_ALLOWLIST`/`B_OFF` 三态对应 §9.1 的「判定」列（开前核对）；
  `ALREADY_ON` 对应 §9.1 第 4 条「任一为 true 先按 §11 回滚」；`A_WITHOUT_B` 是 §2 结构事实（§12 七断言的 A1 案）的命令行复述；
  开启后的观测仍走 §10（脚本不观测指标，只判通道状态）。

### 13.2 自测与防漂移

- `bash scripts/tests/image-vector-ingest-preflight-selftest.sh`：六 fixture 用例（GO / A_WITHOUT_B / B_OFF /
  NO_ALLOWLIST / ALREADY_ON / 缺键 exit 2）+ watch 单轮一致性，逐案断言退出码与原因码（2026-10-23 实测 7/7 绿；
  变异自证：把脚本 `A_WITHOUT_B` 改坏后自测红，改回绿——自测真的在看输出）。
- `mvn -B -ntp test -Dtest=MultimodalRolloutKeysDocConsistencyTest`（surefire 默认集内）：把本文件 §2 四键表的
  键名/默认值/档位与 §9.1 手抄默认行，逐键对 `DynamicConfigKeyRegistry` 静态默认 + `ConfigKeyTierPolicy`（COST）比对；
  注册表或文档任一侧漂移即红（Q3 反向自证已留痕：手改 §9.1 `image-vector-ingest.enabled` 为 `true` → 测试红 → 还原绿）。

