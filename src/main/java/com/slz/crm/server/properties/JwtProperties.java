package com.slz.crm.server.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "slz.jwt")
public class JwtProperties {
  private String secretKey;
  private long ttl;
  private String tokenName;
}
