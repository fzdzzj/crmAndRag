package com.slz.crm.server.ai.port;

import com.slz.crm.platform.contract.SourceReference;
import java.util.List;

/**
 * C↔B 知识库检索集成缝。
 *
 * <p>该接口是 Lane C 本地端口，不进入冻结契约包；Lane B 提供生产实现， C 只消费检索结果和冻结的 {@link SourceReference}。
 */
public interface KnowledgeRetrievalPort {

  /**
   * 执行一轮知识库检索。
   *
   * @param query 已由短问规则改写后的检索查询
   * @return 面向模型注入的上下文与来源；零命中返回空结果，不在此层硬兜底
   */
  RetrievalResult retrieve(RetrievalQuery query);

  /** 检索输入；图片向量允许为空，图片理解与 KB 检索保持解耦。 */
  record RetrievalQuery(
      String query,
      Long userId,
      List<String> kbScope,
      int topK,
      float[] imageVector,
      String intentCategory) {

    public RetrievalQuery {
      kbScope = kbScope == null ? List.of() : List.copyOf(kbScope);
    }
  }

  /** 检索输出；{@code hitCount} 由 B 按授权过滤后的真实结果数填写。 */
  record RetrievalResult(String context, List<SourceReference> sources, int hitCount) {

    public RetrievalResult {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static RetrievalResult empty() {
      return new RetrievalResult("", List.of(), 0);
    }
  }
}
