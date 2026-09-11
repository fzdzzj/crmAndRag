package com.slz.crm.knowledge.config;

import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.knowledge.vector.QdrantVectorStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 向量库装配：qdrant 默认，in-memory 仅本地/测试回退。
 */
@Configuration
public class KnowledgeVectorStoreConfiguration {
    /** 生产默认实现。 */
    @Bean
    @ConditionalOnProperty(name = "rag.vector-store.provider", havingValue = "qdrant", matchIfMissing = true)
    public QdrantVectorStore qdrantVectorStore(QdrantProperties properties) {
        return new QdrantVectorStore(properties);
    }

    /** 本地或测试回退实现。 */
    @Bean
    @ConditionalOnProperty(name = "rag.vector-store.provider", havingValue = "in-memory")
    public InMemoryVectorStore inMemoryVectorStore() {
        return new InMemoryVectorStore();
    }
}
