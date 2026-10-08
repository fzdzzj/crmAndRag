# 提案：wire-llm-call-timeout（卡 P-s）

## 为什么
`platform.ai.model.timeout-seconds` 配置存在但从未被消费（死配置，全仓零消费点）；非流式 LLM 调用（RAG 增强链路六消费方：衍生问题生成 / 上下文压缩 / 查询改写 / HyDE / 多查询改写 / 重排）无任何超时——LLM 挂住将占住检索线程与 ForkJoinPool.commonPool（Hyde/Compressor 两处裸跑公共池）。流式主路（AI chat）超时/隔离/取消/有界重试四件套已齐备，本卡补齐非流式路的最后缺口。

## 改什么
`ModelProviderImpl.chat/vision/embed` 接线 `timeout-seconds`（复用既有配置键，不新增配置面）：超时快速失败、`cancel` 中断底层调用；实现路径（JDK 有界等待包裹 vs Spring AI 原生超时能力）由执行侧实测选定并登记。

## 不改什么
流式路（已健全）、六消费方及其降级逻辑、线程池治理与 commonPool 归属、熔断接入、任何 OpenAPI 契约、DDL、frontend。

## 费用红线
零真实模型调用：单测纯 Mockito，IT 以 `DASHSCOPE_API_KEY` 置空跑，零 API 费用增量；超时语义为快速失败，不引入任何重试放大。
