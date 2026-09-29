package com.slz.crm.platform.model;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 卡 G 档2：把 {@code platform.ai.model.connect-timeout-seconds} / {@code timeout-seconds} 补到容器 {@code
 * RestClient.Builder} 上，覆盖 dashscope 原生路（{@code DashScopeChatAutoConfiguration} 等经 {@code
 * ObjectProvider<RestClient.Builder>} 取容器 builder，取不到才回退自建实例）。
 *
 * <p>生效顺序有保证：Boot 的 {@code RestClientBuilderConfigurer.configure()} 先装默认 requestFactory、再跑全部 {@code
 * RestClientCustomizer}（3.5.5 字节码实测），故本类设的超时不回退。
 *
 * <p>影响面：离线扫描全仓 7 个含 {@code RestClient.Builder} 引用的 jar，非测试资产只有 Boot 自身定义与 dashscope 各自动配置；本仓
 * {@code src/main/java} 无其它消费点。
 */
@Component
public class AiModelRestClientCustomizer implements RestClientCustomizer {

  private final ModelProviderProperties properties;

  public AiModelRestClientCustomizer(ModelProviderProperties properties) {
    this.properties = properties;
  }

  @Override
  public void customize(RestClient.Builder builder) {
    builder.requestFactory(
        CompatibleModeSupport.buildRequestFactory(
            properties.getConnectTimeoutSeconds(), properties.getTimeoutSeconds()));
  }
}
