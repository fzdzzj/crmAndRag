package com.slz.crm.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Qdrant 配置；仅使用原生 gRPC 端口 6334。 */
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

  /** 业务实体向量集合名（add-customer-semantic-retrieval 任务 1.3；与 knowledge_chunk 同维度建制）。 */
  private String crmEntityCollection = "crm_entity";

  /**
   * 图片视觉向量集合名（multimodal-shared-space 任务 2.1）。
   *
   * <p>独立集合是硬要求：视觉 embedding 与文本 embedding 是两个向量空间，同集合混写会让 ANN 邻域失去语义， 也挡住了维度不同的可能（视觉模型维度可 ≠ 文本维度）。
   */
  private String imageCollection = "knowledge_image";

  /** 向量维度，必须与 embedding 模型一致。 */
  private int dimensions = 1024;

  /**
   * 图片视觉向量维度（multimodal-shared-space 任务 2.1）：必须与视觉 embedding 模型输出一致。
   *
   * <p>刻意与 {@link #dimensions} 分开：文本换 embedding 模型不该连带重建 image 集合，反之亦然。
   */
  private int imageDimensions = 1024;

  /** 启动时是否检查/创建集合。 */
  private boolean initializeOnStartup = true;

  /** RPC 超时时间（毫秒），默认 10 秒。 */
  private int timeoutMs = 10000;
}
