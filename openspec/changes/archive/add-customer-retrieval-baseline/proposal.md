# proposal — add-customer-retrieval-baseline

> customer 检索运营闭环：演示种子 + 生产回埋 + 真跑评测双面基线。

## 1. 背景

add-customer-semantic-retrieval（master a2f629c 合入）落地了 customer 语义检索全链：摄取（CustomerIngestionService）/回埋端点（POST /knowledge/customer/reingest，900 权限）/权限化检索（BusinessRetrievalService 四档 + TAGE/共享）/AiChat 双源集成。但存在三个运营缺口：

1. **库内无数据**：customer_company 仅 IT 种子 1-3 行，检索功能实际"空转"——无向量数据可检索，演示与评测均无数据面；
2. **零真跑回埋**：回埋端点从未带真实 DashScope embedding 执行过（D4 拍板 mock 验证，真跑留另授权节点）；
3. **零真跑评测**：现有 benchmark（RagRealRetrievalBenchmarkIT，SUITE 5.0 已锚 baseline-v6）只锚知识库面（KnowledgeRetrievalPort 链），customer 检索面（BusinessRetrievalService）零评测覆盖，合入后的知识库面回归也未经真跑确认。

## 2. owner 拍板记录（2026-10-13）

- **API 成本授权**：本次直接授权——DashScope embedding 真跑（回埋 + 评测）+ 评测链路真实外呼。成本先例：P-j 试点 149 tokens ≈ 0.0001 元；本卡量级 = 种子行数 × 每行 embedding + 两轮评测调用，预计分厘级，执行后按实测留痕。
- **D1 数据面**：先造种子再埋——造 50-200 行真实感演示客户数据（行业/规模/区域/备注丰富）后回埋，同时解决演示/开发/评测三面数据需求。
- **D2 评测锚定**：首跑不锚——customer 面评测首跑结果只记录留痕不锚 fixture，观察一轮真实数据质量后再决定锚入基线。
- **D3 评测入口形态**：独立 IT——新建 CustomerRetrievalBenchmarkIT（RAG_BENCHMARK_REAL 同款门控），不动已锚的知识库 SUITE 5.0 语义。

## 3. 范围（四个任务组）

- **任务组 1 · 演示种子**：Flyway V39（下一可用号以实测为准）种 50-200 行真实感客户：行业分布、规模字段、区域、备注语义丰富；owner 归属覆盖演示场景（分属不同部门/用户的客户，使权限四档效果可演示——归属人自己/上司/管理员可见、无档位同事不可见）。
- **任务组 2 · 生产回埋**：前置实测（库 COUNT / Qdrant 在线 / DASHSCOPE_API_KEY 有效）→ 本地部署环境真跑回埋（HTTP 调 900 端点或本地 boot 起服务，执行方式由执行侧定，留痕为凭）→ 留痕 ReingestResult(total/success/failure) + token 消耗 + 成本估算 → 幂等复跑一次验证（重复回埋不产生重复向量）。
- **任务组 3 · 真跑评测双面**：
  - customer 面：CustomerRetrievalBenchmarkIT 独立入口（RAG_BENCHMARK_REAL=1 + DASHSCOPE_API_KEY 双门控，Assumptions 跳过语义同款）+ 8-12 例 customer 检索用例（语义匹配：行业/规模/区域查询；词汇失配：同义不同词——对齐 §75 词汇失配结论；评测上下文用 ALL 档避免权限面混入质量评测）→ 首跑结果记录留痕不锚 fixture；
  - 知识库面回归：真跑 RagRealRetrievalBenchmarkIT 对照 baseline-v6（SUITE 5.0）逐例漂移，确认 add-customer-semantic-retrieval 合入后知识库面无回退。
- **任务组 4 · 门禁与收尾**：全量 test（surefire 只增不减）/四静态/三守卫/merge-gate；基线 --update 仅当用例数净增；§0 留痕（回埋实测数字、token/成本、评测首跑指标、知识库面漂移结论）。

## 4. 红线

- **授权范围限定**：真实外呼仅限本卡任务组 2/3 的回埋与评测链路；其余全部测试保持零外呼（DASHSCOPE_API_KEY 置空 + mock 桩），CI 门禁序列语义不变（真跑 IT 保持 opt-in 门控，默认跳过）。
- **已锚基线禁动**：知识库面 SUITE 5.0 / baseline-v6 fixture 零改动（D3 独立 IT）；既有迁移 V1-V38 禁改。
- **权限闸红线延续**：种子数据设计不得弱化权限演示语义（四档可见性在种子里必须真实呈现）；V39 种子不得破坏既有 IT 断言（COUNT 类断言若受影响，修测试而非改种子语义，§0 披露）。
- **SUITE 口径**：customer 评测用例集独立编号，不并入 63 例 fixture，SUITE_VERSION 不动。
- 新代码中文 Javadoc 标注「add-customer-retrieval-baseline 任务 x.x」；PMD 超阈拆 Support 先例；四静态与全量 test 禁并发顺序跑。

## 5. 裁决假设（owner 可否决）

1. 种子进 Flyway V39（正式数据而非 dev-only）——项目单环境部署，演示数据入库即生产数据；
2. 评测用例判卷复用 RagQualityEvaluator 规则面（recall/MRR），customer 用例的 expectedChunkIds 对应 crm_entity 向量 ID；
3. 回埋执行环境 = 本机 docker-compose / 本地 boot（"生产"语义 = 本地正式运行环境，非另有线上环境）；
4. 前端零改动；
5. 首轮观察期后的锚定决策（是否入 fixture 升版）留待后续卡。

## 6. 验收

- 任务组 1-4 全勾且每勾留痕；回埋实测（total/success/failure/token/成本）在案；两轮评测 raw 留痕（customer 首跑指标 + 知识库面漂移结论）；门禁全绿（全量 test 0 失败 + 四静态 0 + 三守卫 + merge-gate 8/8）；surefire 只增不减。
