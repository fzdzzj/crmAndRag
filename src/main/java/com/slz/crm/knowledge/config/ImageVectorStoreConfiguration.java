package com.slz.crm.knowledge.config;

import com.slz.crm.knowledge.document.ImageVectorCleanupRegistry;
import com.slz.crm.knowledge.vector.ImageVectorStore;
import com.slz.crm.knowledge.vector.InMemoryVectorStore;
import com.slz.crm.knowledge.vector.QdrantVectorStore;
import com.slz.crm.platform.resilience.DependencyResilienceExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 图片视觉向量库装配（multimodal-shared-space 任务 2.1）。
 *
 * <p>collection 建制对齐 knowledge_chunk / crm_entity 既有机制：qdrant 态启动即 ensure（派生 properties 的 {@code
 * collection=imageCollection}、{@code dimensions=imageDimensions}）；in-memory 态独立实例，与知识库内存库、业务实体内存库
 * 三方零共享。底层实例由本类直接构造（非 bean），不进入 CrmVectorStore 候选集。
 *
 * <p>装配即把实例绑定到 {@link ImageVectorCleanupRegistry}：文档删除/重建的物理双清链在 knowledge/document 侧是静态工具方法， 绑定后
 * image 集合的清理无需改动写集之外的调用点（详见该钩子的类注释）。
 */
@Configuration
public class ImageVectorStoreConfiguration {

  /** 生产默认实现：派生 QdrantProperties 指向 image 集合（独立维度），启动 ensure（失败不阻断，读写时重试）。 */
  @Bean
  @ConditionalOnProperty(
      name = "rag.vector-store.provider",
      havingValue = "qdrant",
      matchIfMissing = true)
  public ImageVectorStore imageVectorStore(
      QdrantProperties properties, DependencyResilienceExecutor resilience) {
    QdrantProperties imageProperties = new QdrantProperties();
    imageProperties.setHost(properties.getHost());
    imageProperties.setPort(properties.getPort());
    imageProperties.setUseTls(properties.isUseTls());
    imageProperties.setApiKey(properties.getApiKey());
    imageProperties.setCollection(properties.getImageCollection());
    imageProperties.setDimensions(properties.getImageDimensions());
    imageProperties.setInitializeOnStartup(properties.isInitializeOnStartup());
    imageProperties.setTimeoutMs(properties.getTimeoutMs());
    QdrantVectorStore backing = new QdrantVectorStore(imageProperties, resilience);
    backing.initialize();
    ImageVectorStore store = new ImageVectorStore(backing);
    ImageVectorCleanupRegistry.bind(store);
    return store;
  }

  /** 本地或测试回退实现：独立内存实例，与文本库/业务实体库隔离。 */
  @Bean
  @ConditionalOnProperty(name = "rag.vector-store.provider", havingValue = "in-memory")
  public ImageVectorStore imageVectorStoreInMemory() {
    ImageVectorStore store = new ImageVectorStore(new InMemoryVectorStore());
    ImageVectorCleanupRegistry.bind(store);
    return store;
  }
}
