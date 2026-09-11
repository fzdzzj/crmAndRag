package com.slz.crm.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Qdrant 配置；仅使用原生 gRPC 端口 6334。
 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.qdrant")
public class QdrantProperties {
    /** Qdrant 主机。 */
    private String host = "localhost";

    /** Qdrant gRPC 端口。 */
    private int port = 6334;

    /** 是否启用 TLS。 */
    private boolean useTls = false;

    /** 云服务 API Key；自建可为空。 */
    private String apiKey = "";

    /** 向量集合名。 */
    private String collection = "knowledge_chunk";

    /** 向量维度，必须与 embedding 模型一致。 */
    private int dimensions = 1024;

    /** 启动时是否检查/创建集合。 */
    private boolean initializeOnStartup = true;
}
