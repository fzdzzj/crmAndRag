package com.slz.crm.server.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** AI 助手业务配置（application.yml 中 crm.ai.*） */
@Data
@Component
@ConfigurationProperties(prefix = "crm.ai")
public class AiProperties {
  /** 传给 LLM 的历史轮数（滑动窗口） */
  private Integer maxHistoryRounds = 10;

  /** 确认卡片超时时间（分钟） */
  private Integer pendingExpireMinutes = 30;

  /** 每用户每分钟 AI 请求上限 */
  private Integer rateLimitPerMinute = 10;

  /** 实体解析查询页大小（合同/商机候选列表全量返回，数据量不大） */
  private Integer entityQueryPageSize = 1000;

  /** LLM 调用超时（秒） */
  private Integer llmTimeoutSeconds = 60;

  /** 零输出连接型错误的同模型重试基础退避；第 n 次重试延迟 base * 2^n。 */
  private Long connectionRetryBaseDelayMillis = 500L;

  /** 工具调用失败修复重试上限（有限次修复） */
  private Integer maxFixRounds = 2;

  /** 降级备用模型（主模型失败时切换，空则不降级） */
  private String fallbackModel = "qwen-turbo";

  /** 主备模型均失败时的静态兜底文案（不吐异常栈） */
  private String staticFallbackMessage = "AI 服务暂时不可用，请稍后再试";

  /** SSE 心跳开关（长生成或工具调用期间保持代理链路连接） */
  private Boolean heartbeatEnabled = true;

  /** SSE 心跳周期（秒） */
  private Integer heartbeatIntervalSeconds = 15;

  /** SSE 心跳调度线程池大小；避免单个慢连接阻塞其他会话 */
  private Integer heartbeatPoolSize = 4;

  /** SSE 服务端超时（秒）；0 或负数时使用默认值，避免连接永久占用 */
  private Integer sseTimeoutSeconds = 300;

  /** 线程池配置 */
  private ThreadPool threadPool = new ThreadPool();

  @Data
  public static class ThreadPool {
    private PoolConfig chat = new PoolConfig(4, 8, 50);
    private PoolConfig title = new PoolConfig(2, 4, 100);
    private PoolConfig audit = new PoolConfig(2, 4, 200);
  }

  @Data
  public static class PoolConfig {
    private int coreSize;
    private int maxSize;
    private int queueCapacity;

    public PoolConfig() {}

    public PoolConfig(int coreSize, int maxSize, int queueCapacity) {

      this.coreSize = coreSize;

      this.maxSize = maxSize;

      this.queueCapacity = queueCapacity;
    }
  }
}
