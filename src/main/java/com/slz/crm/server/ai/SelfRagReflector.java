package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.SourceReference;
import java.util.List;

/**
 * Self-RAG 生成侧反思器抽象（add-self-rag-reflection 任务 1.1）。
 *
 * <p>挂点：流式收尾 references 组装前；触发前提 retrieval.hasSources()。
 */
public interface SelfRagReflector {

  /** 尾注标注软降级文本（D2 拍板）：当有无据断言/引用被剥除时追加，硬拒答语义仍归 D16。 */
  String UNSUPPORTED_CLAIM_NOTE = "\n\n（未获知识库直接支持）";

  /**
   * 对答案及引用进行反思校验与过滤。
   *
   * @param answer 生成的答案文本（可为 null 或空）
   * @param sources 检索命中的来源片段列表
   * @param initialCitations 初始引用编号列表（1-based，为 null 时自答案中提取）
   * @return 反思结果（含过滤后的引用列表与软降级文本）
   */
  SelfRagResult reflect(
      String answer, List<SourceReference> sources, List<Integer> initialCitations);

  /**
   * 对答案及引用进行反思校验与过滤（从答案文本自动提取引用编号）。
   *
   * @param answer 生成的答案文本（可为 null 或空）
   * @param sources 检索命中的来源片段列表
   * @return 反思结果
   */
  default SelfRagResult reflect(String answer, List<SourceReference> sources) {
    return reflect(answer, sources, null);
  }
}
