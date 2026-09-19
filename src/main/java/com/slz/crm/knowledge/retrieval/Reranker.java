package com.slz.crm.knowledge.retrieval;

import java.util.List;

/**
 * 检索重排器抽象（方案08升级，complete-hybrid-retrieval-and-rerank 任务 4.1）。
 *
 * <p>知识库检索内部组件（非平台冻结契约）：对融合后的候选做精排。 实现契约：入参候选全集 MUST 原样返回（只重排/重打分，不增删候选）； 实现内部失败时不得向调用方抛错（LLM
 * 实现自行回退默认链）。
 */
public interface Reranker {

  /**
   * 对候选精排。
   *
   * @param query 检索查询（与召回同源，未二次改写）
   * @param candidates 融合后的候选（非 null）
   * @return 重排后的候选（同全集，顺序/分数按实现语义更新）
   */
  List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates);
}
