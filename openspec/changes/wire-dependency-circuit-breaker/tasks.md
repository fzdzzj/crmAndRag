# tasks：wire-dependency-circuit-breaker（卡 P-u）

> 写集红线：仅任务卡 §0 登记的 ≤12 tracked 文件（含 NCSS 对策协作者例外）。零真实模型调用（`DASHSCOPE_API_KEY` 置空），全部数字自跑实测，禁止照抄卡面预期值。行为红线：零自动重试、Qdrant 内建重试零改动、P-s 超时语义保持、`DependencyUnavailableException` 不泄漏出门面。

## 1. 阶段一：开工准备
- [x] 1.1 从 `master@af99e41` 检出 `feature/wire-dependency-circuit-breaker`，登记工作树纯净（仅受保护未跟踪项）
- [x] 1.2 通读 `work/task-card-P-u.md` 与本三件套，确认写集、三项 owner 拍板与不接线面

## 2. 阶段二：骨架补全（executeNoRetry）
- [x] 2.1 `DependencyResilienceExecutor` 新增 `executeNoRetry(String, Callable)`：OPEN 检查 → 单次执行 → 成功清零 / 失败计数并抛 `CALL_FAILED`，无退避无重试；既有 `execute` 行为零变化（私有核心抽取时既有 4 测试保持绿）
- [x] 2.2 单测：单次失败即 `CALL_FAILED`（attempts==1）、失败计数计入熔断阈值（连续失败开闸）、OPEN 拒绝、成功清零；中文 Javadoc 标注「wire-dependency-circuit-breaker 任务 2」

## 3. 阶段三：模型三路接线
- [x] 3.1 红测试先行（未改主代码基线实录）：chat/embed/vision 失败使 `model-*` 依赖 `dependency.call{result=failure}` 计数 +1
- [x] 3.2 `ModelProviderImpl` 四处 `callWithTimeout` 接线点外包 `executeNoRetry`（依赖名 `model-chat`/`model-embed`/`model-vision`），catch `DependencyUnavailableException` 转回 `IllegalStateException`（消息语义保持、cause 透传）；NCSS 超阈则拆协作者（类名与理由登记 §0）
- [x] 3.3 契约锁定测试：OPEN 状态下三门面抛出类型为既有异常类型，`DependencyUnavailableException` 不出现在门面签名外

## 4. 阶段四：向量库与对象存储接线
- [x] 4.1 `QdrantVectorStore` 三操作（`upsertAll`/`search`/`deleteByDocumentId`）最外层接 `executeNoRetry("vector-qdrant", ...)`，内建 `executeWithRetry` 零改动，转回 `IllegalStateException` 既有消息格式；构造 +executor 参数，`KnowledgeVectorStoreConfiguration` 装配同步；既有测试构造签名同步
- [x] 4.2 `MinioFileStorageService` 三方法（`store`/`open`/`delete`）接 `executeNoRetry("storage-minio", ...)`，转回 `StorageException` 既有消息格式；构造 +executor，`FileStorageConfiguration` 同步；`exists()` 不接
- [x] 4.3 中文 Javadoc 标注「wire-dependency-circuit-breaker 任务 3/4」

## 5. 阶段五：门禁验证
- [x] 5.1 全量 `mvn -B -ntp test` 只增不减（956 基线）
- [x] 5.2 四静态门禁（pmd / spotbugs / checkstyle / spotless）0 违规
- [x] 5.3 `check-test-baseline.sh`（新增测试则 `--update` 真实写回）
- [x] 5.4 三守卫（`check-line-endings lf` / `check-write-set af99e41` / `check-dirty`）+ 三套门禁自测（agent-helper 35 / check-test-baseline 101 / merge-gate 87）全绿

## 6. 阶段六：提交与合并
- [x] 6.1 本文件勾选与执行留痕（NCSS 对策、红测试输出位置、门禁数字、git 拓扑、未跑项）
- [x] 6.2 3-4 笔提交按任务组（骨架 / 模型 / 向量库+存储 / chore 基线+docs）→ 切回 master 真 `--no-ff` 合并（禁 push，分支保留）→ 严格停步回报

## 0. 执行记录（子 agent 填写）

- **分支与基线**：`feature/wire-dependency-circuit-breaker` 自 `master@af99e41` 检出（HEAD 起点即 af99e41）。检出时 tracked 零修改；工作树未跟踪项为前序 lane 遗留（`work/`、`docs/`、其它 lane 的 `openspec/changes/*`）与本卡三件套目录 `openspec/changes/wire-dependency-circuit-breaker/`（proposal/tasks/spec-delta 由 owner 侧物料产生，本笔只提交 tasks.md，其余两份保持未跟踪，不在声明写集内）。开工期用过的 `src/main/resources/pmd-rules.xml` 临时降阈探测已复原，探测改用副本 `work/_pu-pmd-probe/probe-rules.xml`（未跟踪），故 pmd-rules.xml 零改动。
- **executeNoRetry 实现要点（私有核心抽取方式）**：把 `execute` 里的三段治理动作抽成 `requireCircuit`（computeIfAbsent + OPEN 检查 + `dependency.circuit.rejected`）、`markSuccess`（清零 + success 计数）、`markFailure`（failure 计数 + 达阈值时 `dependency.circuit.opened` 并返回 opened）；`execute` 与 `executeNoRetry` 共用同一组私有核心，`execute` 的语句顺序与计数时序逐条不变（既有 4 条单测原样绿，实测 `Tests run: 8, Failures: 0`）。`executeNoRetry` 单次执行、零退避、零重试，失败一律 `CALL_FAILED`。
- **NCSS 对策（触发，已拆协作者）**：卡面记 `ModelProviderImpl` NCSS 143/150，**基线实测为 145**（`work/_pu-pmd-probe/run.sh` + 降阈副本实测，阈值读者为 `pmd-rules.xml` 的 `classReportLevel=150`），余量仅 5；四处 call 点就地包 try/catch 需 +12~16 语句必然越阈。**拆出 `src/main/java/com/slz/crm/platform/model/ModelCallGuard.java`**（@Component，`call(dependency, Supplier)` 内部 `executeNoRetry` + 异常转换），`ModelProviderImpl` 只替换 call 点并加 5 参 @Autowired 构造器；拆后实测 NCSS **149**（余量 1，遗留风险见下）。Qdrant（104→）与 MinIO 门面余量充足，就地接线未拆。
- **红测试输出位置**：`work/_pu-red.log` —— 未改主代码的 af99e41 基线实跑 `Tests run: 14, Failures: 9, Errors: 0`（Model 6 跑 5 红：chat/embed/vision/vision(options) 计数 6≠5 + 接线扫描 0 命中；MinIO 4 跑 3 红：store、open/delete 计数 + 接线扫描；Qdrant 4 跑 1 红：三操作接线扫描 `expected: <3> but was: <0>`）。
- **变异红（补证新 API 用例可失败）**：`work/_pu-mutation-red.log` —— 把 `executeNoRetry` 改成"透传不计数、不查 OPEN"这一处语义变异后，四套测试 `Tests run: 28, Failures: 11, Errors: 5`，覆盖三门面 + 骨架，含 `dependency.call{result=failure}` 的 MeterNotFound 与 OPEN 快速拒绝断言；随后从 `work/` 备份还原，复跑 `Tests run: 28, Failures: 0`。门面构造器新增 executor 参数，故指标类用例无法在未改主代码基线上编译，红证由"基线行为红（`work/_pu-red.log`）+ 单点变异红（本项）"两段共同承担。
- **门禁实测数字（全部本轮自跑）**：全量 `mvn -B -ntp test` = **977** 全绿（0 Failures / 0 Errors / 0 Skipped，基线 956 → +21，见 `work/_pu-fulltest2.log`；首轮曾 977 跑 1 红，因探测脚本把 `NcssProbe.class` 落进 `work/` 命中 `HarnessGovernanceGuardTest` 禁 .class 残留守卫，已把探测产物改落 `target/pmd-probe/` 并复跑全绿）。四静态门禁：checkstyle **0** violations、spotless **0**（876 files clean）、pmd **0**（`target/pmd.xml` violations=0，`PMD_BASELINE_OK` 实测 0 == 登记 0 == pom 阈值 0）、spotbugs **BugInstance size is 0**（`BIJECTION_OK`，12 豁免 ↔ 12 High）。基线 `--update` 写回：surefire **168/977/0**（原 166/956/0），failsafe **24/83/6** 不变。三守卫：`check-line-endings lf` 11 个 Java 文件全 `LINE_ENDINGS_OK` rc=0；`check-write-set af99e41`（13 文件声明集，合并前实测）通过；`check-dirty` **CLEAN**。三套门禁自测 = **35 + 101 + 87 全绿 rc=0**。
- **git 拓扑**：`af99e41 → feature/wire-dependency-circuit-breaker（4 笔：feat 骨架 executeNoRetry / perf 模型+ModelCallGuard / perf 向量库+对象存储 / chore 基线+tasks 留痕）→ master --no-ff 合并`；未 push，分支保留。
- **构造签名同步的落点**：卡面"既有测试构造签名同步"按**保留原签名便捷构造器**的方式满足——`QdrantVectorStore(QdrantProperties)` 与 `MinioFileStorageService(MinioClient, MinioProperties)` 各自委托到新增的 +executor 版（自带独立执行器，注册到 Micrometer 全局注册表），Spring 装配走 @Bean 方法注入的共享执行器。收益：`QdrantVectorStoreTest`（1 文件在写集内）、`QdrantTimeoutConfigIT`、`QdrantVectorStoreSearchRealIT`、`RepresentativeHotpathBenchmark` 共 8 处构造点零改动，写集不越 12+1；`ModelProviderImpl` 四参版同理保留，9 处测试/IT/基准构造点零改动。
- **未跑项与假设**：未跑 failsafe IT（`mvn verify` 段需本地 Docker 的 Testcontainers 系列、真外呼 IT 需 `RAG_BENCHMARK_REAL=1` opt-in），未做真实 DashScope/vLLM 与生产 Qdrant/MinIO 验证；`DASHSCOPE_API_KEY` 全程置空，零真实模型调用。假设一：`DASHSCOPE` 消费方对门面异常只依赖既有类型与消息（三门面契约锁已覆盖 chat/embed/vision/upsert/search/delete/store/open/delete 九条路的类型与消息零变化）。假设二：4 参/1 参/2 参便捷构造器的熔断状态按实例独立、指标进 Micrometer 全局注册表，对生产路径（@Autowired 共享执行器）无影响。
- **遗留**：`ModelProviderImpl` NCSS 149/150 仅剩 1 条余量，后续任何在该类新增语句的卡都必须先拆协作者（`ModelCallGuard` 已是第一个落点）；`work/_pu-*.log`、`work/_pu-pmd-probe/` 为本卡探测产物，未跟踪、不提交。
