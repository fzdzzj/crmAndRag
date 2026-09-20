package com.slz.crm.platform.contract;

/**
 * 检索参数单一真相源（TASK-18 裁定）。
 *
 * <p>topK / minScore 的运行期缺省值全工程只在此处声明一次：{@code knowledge.retrieval} 生产检索实现、 {@code server.ai}
 * 助手检索编排与 {@code DynamicConfigKeyRegistry} 展示默认都引用本类， 与 {@code docs/dynamic-config-keys.md}
 * 表格及质量基准（TOP_K=5）保持三方一致； 一致性由 {@code RetrievalParamTruthSourceTest}（CI 阶段 1）门禁强制，任一侧漂移即红。
 *
 * <p>落点选 {@code platform.contract} 而非 {@code knowledge.retrieval}：{@code knowledge.retrieval} 已依赖
 * {@code server.ai.port}（端口/适配器方向），若常量放 knowledge 包再被 {@code server.ai} 反向引用会形成包环； 两个消费方都已依赖 {@code
 * platform.contract}，此处是中立叶子。
 *
 * <p>禁止新增第三个默认值。改动本类数值必须同步文档、Registry 与基准，并确认行为变更影响面（见 work/mailbox TASK-18 handoff）。
 */
public final class RetrievalDefaults {

  /** 混合检索最终进入上下文的片段数（真相源值 5，原 AiChat 孤例 4 判为漏配已收敛）。 */
  public static final int TOP_K = 5;

  /** 向量召回相似度下限（真相源值 0.20，运行值口径；Registry 展示默认曾孤例 0.0 已修正）。 */
  public static final double MIN_SCORE = 0.20D;

  private RetrievalDefaults() {}
}
