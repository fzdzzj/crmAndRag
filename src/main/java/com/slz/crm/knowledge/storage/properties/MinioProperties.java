package com.slz.crm.knowledge.storage.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** MinIO 配置，前缀 knowledge.minio。 */
@Data
@Component
@ConfigurationProperties(prefix = "knowledge.minio")
public class MinioProperties {
  /** MinIO endpoint。 */
  private String endpoint = "http://localhost:9000";

  /** Access key。 */
  private String accessKey = "";

  /** Secret key。 */
  private String secretKey = "";

  /** 原始文件桶。 */
  private String bucket = "knowledge-files";
}
