# Tasks — add-self-rag-reflection

> 任务组按 D1 拍板裁剪：R+L 全量执行；仅 R 时任务组 2 整组跳过；含 F 时另增任务组（默认不启用）。
> 红测试先行：实施前先在基线实跑新增测试贴红，记录红测试输出后再实现转绿。
> 中文 Javadoc 标注「add-self-rag-reflection 任务 x.x」。

## §0 执行记录

- 分支自证：`feature/add-self-rag-reflection`（`git rev-parse --abbrev-ref HEAD` 实测在位）。
- 任务组 1 红测试先行：
  - 测试文件：`src/test/java/com/slz/crm/server/ai/RuleSelfRagReflectorTest.java`
  - 红测输出：
    ```
    [ERROR] /D:/code/crmAndRag-merge-add-knowledge-admin-api/src/test/java/com/slz/crm/server/ai/RuleSelfRagReflectorTest.java:[17,17] 找不到符号
      符号:   类 RuleSelfRagReflector
      位置: 类 com.slz.crm.server.ai.RuleSelfRagReflectorTest
    ```
  - 转绿输出：`Tests run: 5, Failures: 0, Errors: 0, Skipped: 0 -- in com.slz.crm.server.ai.RuleSelfRagReflectorTest` 全绿。
- 任务组 2 红测试先行：
  - 测试文件：`src/test/java/com/slz/crm/server/ai/LlmSelfRagReflectorTest.java`
  - 红测输出：
    ```
    [ERROR] /D:/code/crmAndRag-merge-add-knowledge-admin-api/src/test/java/com/slz/crm/server/ai/LlmSelfRagReflectorTest.java:[49,11] 找不到符号
      符号:   类 LlmSelfRagReflector
      位置: 类 com.slz.crm.server.ai.LlmSelfRagReflectorTest
    ```
  - 转绿输出：`Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.slz.crm.server.ai.LlmSelfRagReflectorTest` 全绿；`DefaultSelfRagServiceTest` 3 例全绿。

## 任务组 1 — 规则反思层（R 档）

- [x] 1.1 新建规则反思服务：引用编号越界 / 指向空块 / 引用与命中源不匹配的确定性校验与剥除（挂点：流式收尾 references 组装前；触发前提 `hasSources()`）
- [x] 1.2 单测：越界/空块/不匹配/全剥除后空引用边界（D16 语义不破坏）四类 case（fake，无外呼）
- [x] 1.3 红测试先行证据落 §0

## 任务组 2 — LLM 支持度自评（L 档，D1 含 L 时执行）

- [x] 2.1 新建自评服务：ModelProvider + ModelCallOptions + TokenUsageRecorder（type 复用语义最近枚举，同 LLM 压缩口径）；逐条引用「被支持/不被支持」判定 + 无据断言处置（按 D2 拍板）
- [x] 2.2 失败回退链：超时/模型不可用/空输出/解析失败 → 回退 R 档规则链（与 compressor/rerank 回退语义同构）
- [x] 2.3 单测：fake ModelProvider 的支持/不支持/超时回退/空输出回退四类 case（无外呼）

## 任务组 3 — 动态键与治理

- [ ] 3.1 `DynamicConfigKeyRegistry.NAMESPACES` 扩 `rag.generation`；登记 3 键（selfrag.mode / selfrag.llm.timeout-ms / selfrag.llm.max-claims），默认值/描述与 proposal §3 一致
- [ ] 3.2 `ConfigKeyTierPolicy` 定级（按 D1 拍板：mode 含 llm 语义的定级回填此处）+ census 测试更新（63 → N 全量）
- [ ] 3.3 `docs/dynamic-config-keys.md` 新增 `rag.generation.*` 节（键/类型/默认值/权限档位/语义与回退）+ COST 键审批流适用说明

## 任务组 4 — 处置表改判与规格收口

- [ ] 4.1 `openspec/project.md` 处置表 12 行改判：「不做（复杂度；等基线归因后再议）」→「做（生成侧反思）｜add-self-rag-reflection」
- [ ] 4.2 proposal.md §6 决策点回填拍板结果；tasks.md 勾选 + §0 执行记录收口

## 任务组 5 — 门禁与基线

- [ ] 5.1 `DASHSCOPE_API_KEY` 置空串跑 `mvn -B -ntp test`：全绿 0 失败，surefire 只增不减（`bash scripts/check-test-baseline.sh` 裁决；基线 bump 只许 `--update` 从本次真实运行写入）
- [ ] 5.2 四静态 0 违规（checkstyle/spotbugs/spotless/pmd）+ 三守卫 CLEAN（check-dirty / check-line-endings lf / check-write-set）+ `bash scripts/merge-gate.sh` 全 PASS
- [ ] 5.3 （D3 含真跑口径时）真跑前停步向 owner 请求 `RAG_BENCHMARK_REAL=1` 授权；未授权则记「真跑另授权」于 §0

## Git 操作（本提案专属执行序）

1. 权威树（`D:\code\crmAndRag-merge-add-knowledge-admin-api`）从 `master` 检出 `feature/add-self-rag-reflection`；任何写操作前 `git rev-parse --abbrev-ref HEAD` 自证分支。
2. 三件套由主树 untracked 原件落盘到权威树 feature 分支（proposal/design/tasks 随首个任务组提交）。
3. 分笔中文提交，按任务组对齐：`type(scope): 描述（卡 P-ah 任务组 N）`；不用 `git commit -- <pathspec>` 提交重命名文件。
4. 门禁全绿后停步回报，等 owner 授权后 `git merge --no-ff` 合入 master、保留分支；**严禁 push**（push 须 owner 显式授权）。
5. 触发停止条件（测试数偏差、冻结面被要求改动、真跑未授权）立即停步回报，原样粘贴证据。
