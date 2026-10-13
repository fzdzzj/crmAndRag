package com.slz.crm.server.ai.port;

import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.UserContext;
import java.util.List;

/**
 * 业务数据语义检索集成缝（add-customer-semantic-retrieval 任务 1.1）。
 *
 * <p>该接口是 Lane C 本地端口（lane-local，对齐 {@link KnowledgeRetrievalPort} 先例），不进入冻结契约包； server 域提供生产实现（权限闸
 * + crm_entity 向量检索），AiChat 检索编排消费。
 *
 * <p>安全红线：无权业务数据绝不出现在检索结果——权限闸在端口实现内收口（与页面/SQL 数据权限 getHighestDataScopeLevel 同源），NONE 级直接空返回零外发。
 */
public interface BusinessRetrievalPort {

  /**
   * 执行一轮业务数据检索。
   *
   * @param query 检索查询（含用户身份快照与编号起点）
   * @return 面向模型注入的上下文与来源；零命中/无权限返回空结果，不在此层硬兜底
   */
  BusinessRetrievalResult retrieve(BusinessRetrievalQuery query);

  /**
   * 检索输入。
   *
   * @param query 检索查询文本（AiChat 侧短问改写后）
   * @param userContext 当前用户身份快照（userId/roleId/deptId 供权限闸解析数据范围级别）
   * @param topK 返回条数上限（必须 &gt; 0）
   * @param numberStart 业务片段 [n] 编号起点（1 起；接续知识库片段编号，保证上下文编号与合并后 sources 列表下标一一对应）
   */
  record BusinessRetrievalQuery(String query, UserContext userContext, int topK, int numberStart) {

    public BusinessRetrievalQuery {
      if (topK <= 0) {
        throw new IllegalArgumentException("BusinessRetrievalQuery.topK 必须大于 0");
      }
      if (numberStart < 1) {
        throw new IllegalArgumentException("BusinessRetrievalQuery.numberStart 必须 >= 1");
      }
    }
  }

  /** 检索输出；{@code hitCount} 为权限过滤后的真实命中数。 */
  record BusinessRetrievalResult(String context, List<SourceReference> sources, int hitCount) {

    public BusinessRetrievalResult {
      sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public static BusinessRetrievalResult empty() {
      return new BusinessRetrievalResult("", List.of(), 0);
    }
  }
}
