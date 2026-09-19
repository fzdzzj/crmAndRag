package com.slz.crm.knowledge.retrieval;

/**
 * 上下文压缩器抽象（方案10，add-context-compression-and-enrichment 任务 2.1）。
 *
 * <p>实现语义契约（rag-context 规范）：
 *
 * <ul>
 *   <li>未超预算时 MUST 原文逐字返回，不做任何改写；
 *   <li>压缩后 MUST 保留全部 {@code [n]} 编号标记且不改写编号——上下文编号与 sources 列表下标的一一对应是 citationPrecision
 *       的判定依据（引用编号完整性）；
 *   <li>超预算且无法在保留全部编号的前提下压到预算内时，MUST 保留全部编号段 （完整性优先于预算），并压缩到可达的最小长度。
 * </ul>
 */
public interface Compressor {

  /**
   * 把带编号上下文压缩到 token 预算内。
   *
   * @param context 带编号上下文（{@code [n] ...} 段落结构）
   * @param tokenBudget token 预算上限（&gt; 0）
   * @return 压缩后上下文（未超预算时为原文逐字）
   */
  String compress(String context, int tokenBudget);
}
