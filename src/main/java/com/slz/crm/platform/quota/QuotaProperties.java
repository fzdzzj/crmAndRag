package com.slz.crm.platform.quota;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 四层请求配额默认阈值。 */
@Data
@Component
@ConfigurationProperties(prefix = "platform.quota")
public class QuotaProperties {

  /** 单用户每分钟请求上限。 */
  private long userPerMinute = 30;

  /** 单 IP 每分钟请求上限。 */
  private long ipPerMinute = 60;

  /** 单知识库每分钟请求上限。 */
  private long knowledgeBasePerMinute = 120;

  /** 全局每分钟请求上限。 */
  private long globalPerMinute = 600;
}
