# eval-port-parity v1 · 评测端口生产化误差条（卡 eval-port-production-parity）

- 判定: ERROR-BAR-PARTIAL-QUANTIFIED —— 稀疏腿差异 + 度量三口径已量化；语料腿与默认伪向量仍未量化
- CI 端口: **未改** —— RagQualityRegressionTest 一字不动，真装置只进独立测试类（proposal D6；无 Docker 即跳过）
- 稀疏腿: 真 MySQL 8.0.36 + 真 Flyway（V22 ngram）跑生产同一条 `fulltextSearch`，72 查询逐条对照 bigram 近似
  - 真 ngram 命中数 / bigram 命中数 / top-5 集合重叠总数见锚 `sparseDifferential`
  - 差异只报数字（集合重叠、名次位移、分数 min/p50/max），定性措辞一律不落锚（守卫词表断言）
- 度量腿: 每用例并列 `rawFusedScore`（归一化前融合分）/ `rank`（同集合名次）/ `margin`（与相邻候选差）
  - 自证: 生产 reranker 三次调用拆开恒等式 `port == V + Z - wv` 且 `V == wv·N(raw)`，容差 1e-12
- 归一化面: min-max 仍是集合内相对量；锚只保留归一化前值，不改生产实现
- 输入腿: 真嵌入通道 opt-in（`RAG_BENCHMARK_REAL=1` + key），预算 ¥5 封顶、第一次调用前硬检查
  - 计划调用 = 查询侧 embed（≥72，改写拆路后按干跑实测）+ fixture 全量切片；单价 text-embedding-v3「文本输入」0.5 元/百万 tokens
  - 执行状态与调用台账见锚 `binary.ledger`；未执行即记 UNDETERMINED，不用近似顶替
- 语料腿: 15 份固定 fixture，本卡不碰；`residualDeviation` 逐条列仍 off-production 的腿与被排除结论
- 伪向量基线指标: recall@5=0.8438 / mrr=0.7884（fixture 语料 + 伪向量；禁外推）
- 细节锚: docs/rag-quality/eval-port-parity-v1.json（写模式重建，默认逐字节复算；纯 LF）
