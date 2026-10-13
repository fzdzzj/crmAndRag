package com.slz.crm.knowledge.config;

import com.slz.crm.knowledge.vector.CrmEntityVectorStore;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.knowledge.vector.QdrantVectorStore;
import com.slz.crm.platform.resilience.DependencyResilienceExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 业务实体向量库装配（add-customer-semantic-retrieval 任务 1.3）。
 *
 * <p>collection 建制对齐 knowledge_chunk 既有机制：qdrant 态启动即 ensure（派生 properties 的
 * collection=crmEntityCollection，维度同 embedding 模型）；in-memory 态独立实例与知识库内存库零共享。 底层实例由本类直接构造（非
 * bean），不进入 CrmVectorStore 候选集。
 */
@Configuration
public class CrmEntityVectorStoreConfiguration {

  /** 生产默认实现：派生 QdrantProperties 指向 crm_entity 集合，启动 ensure（失败不阻断，读写时重试）。 */
  @Bean
  @ConditionalOnProperty(
      name = "rag.vector-store.provider",
      havingValue = "qdrant",
      matchIfMissing = true)
  public CrmEntityVectorStore crmEntityVectorStore(
      QdrantProperties properties, DependencyResilienceExecutor resilience) {
    QdrantProperties entityProperties = new QdrantProperties();
    entityProperties.setHost(properties.getHost());
    entityProperties.setPort(properties.getPort());
    entityProperties.setUseTls(properties.isUseTls());
    entityProperties.setApiKey(properties.getApiKey());
    entityProperties.setCollection(properties.getCrmEntityCollection());
    entityProperties.setDimensions(properties.getDimensions());
    entityProperties.setInitializeOnStartup(properties.isInitializeOnStartup());
    entityProperties.setTimeoutMs(properties.getTimeoutMs());
    QdrantVectorStore backing = new QdrantVectorStore(entityProperties, resilience);
    backing.initialize();
    return new CrmEntityVectorStore(backing);
  }

  /** 本地或测试回退实现：独立内存实例，与知识库内存库隔离。 */
  @Bean
  @ConditionalOnProperty(name = "rag.vector-store.provider", havingValue = "in-memory")
  public CrmEntityVectorStore crmEntityVectorStoreInMemory() {
    return new CrmEntityVectorStore(new InMemoryVectorStore());
  }
}
