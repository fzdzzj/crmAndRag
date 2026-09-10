package com.slz.crm.knowledge.storage;

import com.slz.crm.knowledge.storage.properties.MinioProperties;
import io.minio.MinioClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 文件存储装配：MinIO 默认启用，可通过 knowledge.storage.provider=in-memory 回退。
 */
@Configuration
public class FileStorageConfiguration {
    /** 创建 MinIO 客户端；不在这里连接服务，连接错误延迟到实际访问。 */
    @Bean
    @ConditionalOnMissingBean(MinioClient.class)
    public MinioClient minioClient(MinioProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    /** 默认使用 MinIO。 */
    @Bean
    @ConditionalOnProperty(name = "knowledge.storage.provider", havingValue = "minio", matchIfMissing = true)
    public FileStorageService minioFileStorageService(MinioClient minioClient, MinioProperties properties) {
        return new MinioFileStorageService(minioClient, properties);
    }

    /** 本地或测试可用 in-memory 覆盖，禁止生产使用。 */
    @Bean
    @ConditionalOnProperty(name = "knowledge.storage.provider", havingValue = "in-memory")
    public FileStorageService inMemoryFileStorageService() {
        return new InMemoryFileStorageService();
    }
}
