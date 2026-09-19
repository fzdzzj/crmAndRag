package com.slz.crm.platform.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 模型 Provider 配置（{@code platform.ai.model.*}，contracts-frozen.md §1）。
 *
 * <p>与 {@code spring.ai.dashscope.*} 的关系：dashscope 走 Spring AI 自动配置； compatible-mode/vllm
 * 走本节配置。{@code api-key}/{@code base-url} 留空时会回退到 {@code spring.ai.dashscope.api-key}/{@code
 * spring.ai.dashscope.base-url}， 让“一个通义 Key 同时服务原生协议与 compatible-mode SSE”成为默认行为。
 *
 * <p>敏感项（api-key）一律环境变量/.env 注入，禁止入库（任务 3）。
 */
@Component
@ConfigurationProperties(prefix = "platform.ai.model")
public class ModelProviderProperties {

  /** Provider 类型：dashscope（默认）| openai-compatible | vllm */
  private String provider = "dashscope";

  /** OpenAI 兼容端点 base-url；留空回退 spring.ai.dashscope.base-url */
  private String baseUrl = "";

  /** OpenAI 兼容端点 api-key；留空回退 spring.ai.dashscope.api-key */
  private String apiKey = "";

  /** 对话默认模型（可被 Prompt 内 options 覆盖） */
  private String chatModel = "qwen-plus";

  /** 视觉/OCR 默认模型（vision() 调用强制使用，避免误用纯文本模型） */
  private String visionModel = "qwen-vl-plus";

  /** 嵌入默认模型（知识库入库/检索共用） */
  private String embeddingModel = "text-embedding-v3";

  /** 单次调用超时（秒）；0 表示不限（不建议，容易拖死 SSE 线程） */
  private long timeoutSeconds = 60;

  public String getProvider() {
    return provider;
  }

  public void setProvider(String provider) {
    this.provider = provider;
  }

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(String apiKey) {
    this.apiKey = apiKey;
  }

  public String getChatModel() {
    return chatModel;
  }

  public void setChatModel(String chatModel) {
    this.chatModel = chatModel;
  }

  public String getVisionModel() {
    return visionModel;
  }

  public void setVisionModel(String visionModel) {
    this.visionModel = visionModel;
  }

  public String getEmbeddingModel() {
    return embeddingModel;
  }

  public void setEmbeddingModel(String embeddingModel) {
    this.embeddingModel = embeddingModel;
  }

  public long getTimeoutSeconds() {
    return timeoutSeconds;
  }

  public void setTimeoutSeconds(long timeoutSeconds) {
    this.timeoutSeconds = timeoutSeconds;
  }
}
