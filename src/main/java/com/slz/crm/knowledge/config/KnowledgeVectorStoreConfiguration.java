package com.slz.crm.knowledge.config;

import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.knowledge.vector.QdrantVectorStore;
import com.slz.crm.platform.resilience.DependencyResilienceExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** 向量库装配：qdrant 默认，in-memory 仅本地/测试回退。 */
@Configuration
public class KnowledgeVectorStoreConfiguration {

  /**
   * 生产默认实现；wire-dependency-circuit-breaker 任务 4：注入共享的依赖韧性执行器（依赖名 vector-qdrant）。
   *
   * <p>{@code @Primary}（add-customer-semantic-retrieval 任务 1.3 连带修复）：业务实体向量库（CrmEntityVectorStore）
   * 合入后 {@link com.slz.crm.platform.contract.CrmVectorStore} 类型出现双候选，既有知识库消费方
   * （DerivedQuestionService / DocumentIngestionService / KnowledgeRetrievalServiceImpl
   * 等）按类型注入必须继续命中知识库主集合。
   */
  @Bean
  @Primary
  @ConditionalOnProperty(
      name = "rag.vector-store.provider",
      havingValue = "qdrant",
      matchIfMissing = true)
  public QdrantVectorStore qdrantVectorStore(
      QdrantProperties properties, DependencyResilienceExecutor resilience) {
    return new QdrantVectorStore(properties, resilience);
  }

  /** 本地或测试回退实现（primary 语义同 qdrant 态：知识库主集合优先于业务实体第二集合）。 */
  @Bean
  @Primary
  @ConditionalOnProperty(name = "rag.vector-store.provider", havingValue = "in-memory")
  public InMemoryVectorStore inMemoryVectorStore() {
    return new InMemoryVectorStore();
  }
}
