# 任务清单 · retrieval-and-contract-hardening（三合一）

> 档位 **A**（取三组最高：含冻结契约改动）。**零真外呼、零新依赖、零迁移、不改任何默认值、不打开任何生产开关。**
> 分组：**G0** 立项共用 → **G1** 近重复机制决策 → **G2** 图片开关实证 → **G3** 契约权限生成化 → **G4** 全量门禁与照判。
> 勾选规则：勾选行**下一行**必须有 6 空格缩进「补勾依据」+ 可独立复跑的命令/哈希。**禁止无证据补勾、禁止以推断冒充实测。**
> 判据（C1–C6 / A1–N1 / P1–P4）见 `proposal.md` §1.2 / §2.2 / §3.3，**跑前写死，跑后禁改**。
> 停步裁决见 `proposal.md` §5：任一组触发停步 ⇒ 该组产物整体回退到 master 状态，其余组继续做完。

## 0. G0 · 立项与共用侦察（零外呼）

- [ ] 0.1 三份现状锚点各复述一遍并给行号/数值：`ImageSimilarityGate` 五常量（L31/34/37/40/43）；`PdfImageSliceIngestService` 四处（L39/64/136/152-157）；`frontend/openapi.yaml` 行数 11028 与 `x-` 计数 0
- [ ] 0.2 确认开工基线：`git status --porcelain` 干净、`git log -1 --oneline` 记录、分支 `feature/retrieval-and-contract-hardening` 从 `master` 检出
- [ ] 0.3 把三组判据的数值原样登记成代码常量或文案（C1=0.90 / C2=0.02 / floor 200 点步 0.0005 / dHash 0..24；A3 == 图数；P1 0 例外），供后续守卫与复核逐字比对
- [ ] 0.4 记录 G3 需要的 operation 基数：导出契约里 path×method 总数（命令 + 数值），供 P1 报数与复核抽查

## 1. G1 · 近重复机制决策（零外呼离线）

### 1.1 口径冻结

- [ ] 1.1.1 复核锚身份并与本卡重生成语料比对：`corpus.bases=24`、`images=144`、`pairs=540`、`imageEmbeddingCalls=288`、`strata` 四类计数（144/120/100/176），不等即 fail-fast
- [ ] 1.1.2 `NearDuplicateMechanismAnalyzer` 写入 `C1_MIN_RETENTION=0.90`、`C2_MAX_LEAKAGE=0.02`、`FLOOR_GRID={0.90 + 0.0005*k, k=0..199}`、`DHASH_D=0..24` 常量，注释注明"来自 proposal §1.2，禁改"
- [ ] 1.1.3 复算基线并写入锚：`R_keep = 70/120 = 0.583`、`L = 3/276 = 0.0109`、逐 kind 保留数（供 G1 照判比对）

### 1.2 感知哈希纯函数

- [ ] 1.2.1 新增 `PerceptualHashSupport`：`BufferedImage -> long`（9×8、BT.601 定点整数灰度、面积平均缩放、相邻差分 64 bit）；禁止 I/O、禁止网络类型
- [ ] 1.2.2 提供 `hamming(long,long)` / `distanceRatio(...)`
- [ ] 1.2.3 `PerceptualHashSupportTest`：同图两次距离 0；1 bit 翻转距离 1；2× 放大距离 0；纯色/梯度图符合预期

### 1.3 机制评估

- [ ] 1.3.1 `NearDuplicateMechanismAnalyzer` 输入 = 540 对（`leftKey/rightKey/stratum/kind/cosine`）+ `Map<String,BufferedImage>`（键与锚同命名空间）+ 判据常量
- [ ] 1.3.2 M1：threshold 固定 0.9944，`floor` 200 点逐点算 `R_keep/L/逐 kind R_keep`（测试侧自实现 `tierOf` 同语义比较，禁调 `src/main` 私有方法）
- [ ] 1.3.3 M2：`D` 0..24 逐点；同源 120 / 负样本 276 / same-image 144 分别统计
- [ ] 1.3.4 M3：`(D_low,D_high)` 全组合（`D_low<=D_high`）逐点；规则写死：`dHash<=D_low ⇒ 同图`；否则 `dHash<=D_high || cosine>=0.9887 ⇒ 近似带`；否则丢弃
- [ ] 1.3.5 键对齐 fail-fast：任一对键在图集合缺失 ⇒ `IllegalStateException` 报出键名（禁止静默跳过/兜底图）
- [ ] 1.3.6 `NearDuplicateMechanismAnalyzerTest`：合成 6 图 3 对，手算期望逐项断言；另加"少一张图必须抛错"用例

### 1.4 决策锚与守卫

- [ ] 1.4.1 `NearDuplicateMechanismDecisionTest`：复用 `NearDuplicateCorpus` 取 24 base + 120 变体的 `BufferedImage` 与键，按 `leftKey/rightKey` 与已提交锚连接（**禁止二次栅格化**）
- [ ] 1.4.2 语料复现自证：144 个 `same-image` 对本卡重算必须仍判"同图"（dHash 距离 0 + 余弦回读一致），否则 fail-fast
- [ ] 1.4.3 写模式：`-Dnear.dup.decision.write=true` 才写 `docs/rag-quality/near-dup-mechanism-decision-v1.json`（唯一写入口）；默认模式只断言不写
- [ ] 1.4.4 锚字段齐全：`criteria`（C1–C6 原样回显）、`corpus`（含锚文件 SHA-256）、`mechanisms[]`（每机制**全网格**）、`pareto`、`verdict`+`verdictReason`、`externalCalls: 0`、`generatedAt`、`elapsedMillis`
- [ ] 1.4.5 打印固定表格到 stdout：基线表 / M1 200 行 / M2 25 行 / M3 帕累托 / 逐判据照判
- [ ] 1.4.6 `NearDuplicateMechanismDecisionGuardTest`（默认跑）：floor 网格 ≥200 点且步长 0.0005、`D` 0..24 无缺、`externalCalls==0`、`verdict` 与 C 规则重算一致、`criteria` 与 proposal §1.2 逐字相同、锚内 `ImageSimilarityGate` 五常量回显等于现值

### 1.5 复算脚本与决策文档

- [ ] 1.5.1 `scripts/rag-quality/near-dup-mechanism-report.sh`：跑写模式 + `tee target/near-dup-mechanism-report.txt`；脚本退出码即判定
- [ ] 1.5.2 `docs/rag-quality/near-dup-mechanism-decision-v1.md`：§1 基线（标表号）§2 三机制全表 §3 帕累托前沿 §4 C1–C6 照判 §5 结论与后续卡边界 §6 数据质量与未覆盖类 §7 复算命令
- [ ] 1.5.3 文档零手算数字；节点数与锚不一致即缺陷
- [ ] 1.5.4 GO ⇒ 写「机制重构规格」（推荐机制/参数/组合顺序/降级行为/新增可观测点/后续卡档位），声明未实施；NO-GO ⇒ 写「近重复三档机制结案声明」+ 帕累托 + 翻案所需数据

### 1.6 G1 照判

- [ ] 1.6.1 照 proposal §1.2 原文填判定表（C1–C6 一行一条，结果列只许"满足/不满足"）
- [ ] 1.6.2 给出 `verdict` 与推导链（哪条机制、哪组参数、为何不选其它）
- [ ] 1.6.3 逐 kind 保留率表 + 单调性结论（C3）；不单调须区分机制缺陷 vs 变换语义差异
- [ ] 1.6.4 帕累托前沿表：每档 `L` 下 `R_keep` 最大值（M1/M2/M3 各一列）+ "把 `R_keep` 从 0.583 提到 0.90 的最小泄漏代价"

### 1.7 G1 硬边界自证

- [ ] 1.7.1 `git diff --stat master -- src/main` 为空；`git diff master -- src/main/java/com/slz/crm/knowledge/retrieval/ImageSimilarityGate.java` 为空
- [ ] 1.7.2 `git diff master -- src/test/java/com/slz/crm/quality/NearDuplicateCorpus.java` 为空
- [ ] 1.7.3 外呼证据：G1 新增文件 grep 无 `DASHSCOPE|System.getenv|RestTemplate|WebClient|HttpClient|HttpURLConnection|Socket`；`NearDuplicateCorpusTest` 零外呼守卫仍绿
- [ ] 1.7.4 `pom.xml`、迁移、`frontend/**`、`scripts/test-baseline.txt` 零 diff

## 2. G2 · 图片四开关实证（零真外呼）

### 2.1 锚点核实

- [ ] 2.1.1 复述四键与门控顺序并给行号：`IMAGE_VECTOR_ENABLED_KEY`（L64）、视觉向量启用点（L136）、每调用重读（L152-157）、方案 B 门控在前（`buildSlices`）
- [ ] 2.1.2 抄录执行单三条待证措辞原文：`docs/multimodal-gray-rollout.md` §2、§3 第 5 步、§6 第 1 条/§11 第 1 条（不改写）
- [ ] 2.1.3 判定技术路线（H2 首选 / 真库降级）并记录依据；记录所选 PDF fixture 的图数 `N`

### 2.2 骨架与替身

- [ ] 2.2.1 新增 `ImageVectorSwitchHotFlipIT`，全程在**一个测试方法**内（或 `@TestInstance(PER_CLASS)` 同一实例）覆盖 A→F 序列
- [ ] 2.2.2 `ModelProvider.embedImage` 用计数替身返回定长向量；配置写入走**生产同一个服务 bean**（管理端服务优先）
- [ ] 2.2.3 "未重启"证明：关键 bean `assertSame` 或单方法顺序执行 + 注释说明，并在补勾依据里指向行号
- [ ] 2.2.4 零真外呼自证：测试类 grep 无 `DASHSCOPE|System.getenv|RestTemplate|WebClient|HttpClient|HttpURLConnection|Socket`

### 2.3 七条断言

- [ ] 2.3.1 A1：B=false + 白名单空 + A=true ⇒ `embedImage` **0**、staged **0**、image 集合 **0** 点
- [ ] 2.3.2 A2：B=true + 白名单不命中 + A=true ⇒ `embedImage` **0**
- [ ] 2.3.3 A2b：B=true + 命中 + A=false ⇒ 描述切片 **> 0** 且 `embedImage` **0**
- [ ] 2.3.4 A3：B=true + 命中 + A=true ⇒ `embedImage` **== N**、image 集合点 **== N**
- [ ] 2.3.5 F1：同上下文**运行期**写 true ⇒ 下一轮 `embedImage` **> 0**
- [ ] 2.3.6 F2：写回 false ⇒ 下一轮 `embedImage` **回到 0**
- [ ] 2.3.7 N1：A3 计数 **严格大于** A1 计数
- [ ] 2.3.8 每轮之间清空替身暂存/计数（只作用于测试替身，不碰生产缓存语义）

### 2.4 执行单补章

- [ ] 2.4.1 `docs/multimodal-gray-rollout.md` 追加 §12「热生效与门控关系实证」：断言表 + IT 类名 + 复跑命令 + 失败怎么读
- [ ] 2.4.2 §12 明确写：本卡**未打开任何开关**，§9 pre-flight 与 Owner 运行期授权不变
- [ ] 2.4.3 `git diff master -- docs/multimodal-gray-rollout.md` 中 §1–§11 **零改动**（只新增 §12）

### 2.5 G2 硬边界

- [ ] 2.5.1 `git diff --stat master -- src/main` 为空；`DynamicConfigKeyRegistry.java`、`ConfigKeyTierPolicy.java` 零 diff
- [ ] 2.5.2 `docs/dynamic-config-keys.md`、`pom.xml`、迁移、`frontend/**` 零 diff
- [ ] 2.5.3 若触发停步：回退 G2 产物并把实证写 `work/evidence-retrieval-and-contract-hardening-g2-stop.md`（未跟踪），**禁止改 `src/main` 变绿**

## 3. G3 · 契约权限生成化（A 档改动面）

### 3.1 增量冻结

- [ ] 3.1.1 复述现状：`grep -c "^ *x-" frontend/openapi.yaml` = **0**、`wc -l` = 11028、`OpenApiConfig` 两个 customizer 行号
- [ ] 3.1.2 把 proposal §3.2 的 (a)(b) 两段 YAML 与语义约定表**原样**抄进 `specs/api-contract/spec-delta.md`（含互斥/只增不改/不表达什么）
- [ ] 3.1.3 记录 operation 基数（= G0 4 的数值），供 P1 与复核

### 3.2 自定义器

- [ ] 3.2.1 新增 `OpenApiPermissionCustomizer implements OperationCustomizer`：有 `@RequirePermission` ⇒ `x-required-permission {code,name}`；无 ⇒ `x-permission-status: OPEN`
- [ ] 3.2.2 只读方法级注解；不扫 classpath、不 import 测试源码、不留可变全局状态；`code` 用 `getCode()` 的 `Long` 原样
- [ ] 3.2.3 在 `OpenApiConfig` 注册 bean（与既有 multipart customizer 并列），不动既有两个 customizer 逻辑
- [ ] 3.2.4 `git diff --stat master -- src/main` 恰好 2 文件（1 增 1 改），出表留痕

### 3.3 重导出与前端同步（顺序不可乱）

- [ ] 3.3.1 `bash scripts/check-openapi-consistency.sh --update`（唯一覆盖入口）
- [ ] 3.3.2 `cd frontend && pnpm gen:api`；记录 `git status --porcelain frontend/` 与 `git diff --stat frontend/`
- [ ] 3.3.3 复跑 `bash scripts/check-openapi-consistency.sh` 必须绿
- [ ] 3.3.4 前端四项 `pnpm lint:check` / `type-check:check` / `test` / `build` 全绿（对应 P3）

### 3.4 守卫测试 P1/P2

- [ ] 3.4.1 新增 `OpenApiPermissionContractParityTest`：`@SpringBootTest` + `@AutoConfigureMockMvc` + `@ActiveProfiles({"test","dev"})`，MockMvc 取 `/v3/api-docs.yaml`，SnakeYAML 解析
- [ ] 3.4.2 P1：遍历 `paths` × method operation，断言 (a)(b) 恰有其一；输出 operation 总数与 SECURED/OPEN 计数
- [ ] 3.4.3 P2：从契约构造 `SECURED:(path|method)->code` 与 `OPEN:set(path|method)`，与 `PermissionCoverageScanner`+`OpenEndpointRegistry` 做**集合相等**；失败打印前 10 条差异
- [ ] 3.4.4 归一化只允许"去尾部 `/`"；其他归一化必须写进 spec-delta 并在回报声明
- [ ] 3.4.5 `OpenApiContractExportTest` **只追加**一条：`/permission/list` 的 `get` 带 `x-required-permission.code == 606`（不改既有三用例语义）

### 3.5 规格与解冻登记

- [ ] 3.5.1 `openspec/changes/retrieval-and-contract-hardening/specs/api-contract/spec-delta.md` 落盘（§3.2 原文 + 稳定性承诺 + 不表达什么）
- [ ] 3.5.2 `spec/changes/add-crm-rag-fusion-platform/contracts-frozen.md` 变更日志末尾追加一行（2026-10-23，注明"仅新增 `x-` 扩展，既有路径/schema/字段零改动；`@RequirePermission` 与拦截器语义零变更"）
- [ ] 3.5.3 `git diff master -- docs/permission-matrix-audit.md` 为空

### 3.6 G3 硬边界（P4）

- [ ] 3.6.1 `git diff --stat master -- src/main` = 2 文件；`RequirePermission.java`、`PermissionOperates.java` 零 diff
- [ ] 3.6.2 `PermissionsInterceptor`、`OpenEndpointRegistry.java`、`PermissionCoverageScanner.java` 零 diff
- [ ] 3.6.3 `frontend/typed-router.d.ts`、`frontend/src/**`、`docs/dynamic-config-keys.md` 零 diff（`gen:api` 自动产物除外）
- [ ] 3.6.4 `pom.xml` 零 diff（零新依赖）；迁移零 diff
- [ ] 3.6.5 若触发停步：回退 G3 全部产物（customizer + 契约 + 前端生成物 + spec-delta + 解冻登记），G1/G2 照常交付

## 4. G4 · 全量门禁与照判回报

### 4.1 门禁（A 档：全量 + 留痕）

- [ ] 4.1.1 `mvn -B -ntp test` 全绿，记录新增用例数与总计数
- [ ] 4.1.2 `bash scripts/check-openapi-consistency.sh` 绿（G3 主门禁；G3 停步则记为不适用）
- [ ] 4.1.3 `bash scripts/merge-gate.sh` 全绿（`[pmd]`/`[pmd-baseline]`/spotbugs 台账/权限覆盖/schema 漂移/契约一致性）
- [ ] 4.1.4 `bash scripts/check-test-baseline.sh` 结论如实记录（**不** `--update`）；`scripts/test-baseline.txt` 零 diff
- [ ] 4.1.5 G2 的 IT 实跑记录：`mvn -B -ntp test -Dtest=ImageVectorSwitchHotFlipIT`（降级真库则 `verify -Dit.test=...`），绿/跳过与原因

### 4.2 分组照判（三份出表，一份总判定）

- [ ] 4.2.1 G1：C1–C6 逐条 + `verdict` + 帕累托一句话
- [ ] 4.2.2 G2：A1/A2/A2b/A3/F1/F2/N1 逐条 绿/红 + 是否真证到"未重启"
- [ ] 4.2.3 G3：P1–P4 逐条 + operation 总数 + SECURED/OPEN 计数 + 前端生成物是否变化
- [ ] 4.2.4 总判定：交付/停步分组清单 + `git diff --stat master` 逐组分账（哪些文件属于哪组）

### 4.3 交付前自检

- [ ] 4.3.1 `git status --porcelain` 只含 proposal 三张写入面并集 + 本卡文件（其他一律回退）
- [ ] 4.3.2 每个勾选项的补勾依据都能被第三方独立复跑
- [ ] 4.3.3 没有为让结论好看而调网格/改门槛/删数据点（C6 自证：点数与步长可被守卫测试独立数出）
- [ ] 4.3.4 停步组产物已回退干净（`git status` 与 `git diff master` 中不出现其半成品）

## 5. 回报（≤300 字，细节留文件）

① 三组各自 交付/停步 ② G1 的 `verdict` 与帕累托一句话 ③ G2 七条断言结论（是否真证到未重启）④ G3 的 P1–P4 与 operation 计数 ⑤ 门禁结果（含本卡 IT 实跑状态）⑥ 未解决/需指导侧决策项。
