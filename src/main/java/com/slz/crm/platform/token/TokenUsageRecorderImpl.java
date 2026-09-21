package com.slz.crm.platform.token;

import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.mapper.PlatformTokenUsageMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Token 计量落库与指标聚合实现。
 *
 * <p>计量失败禁止打断业务流；这里统一捕获异常、记录失败指标与告警日志。 token 数允许缺失，但记录本身不允许丢失。
 */
@Component
public class TokenUsageRecorderImpl implements TokenUsageRecorder {

  private static final Logger log = LoggerFactory.getLogger(TokenUsageRecorderImpl.class);

  private final ObjectProvider<PlatformTokenUsageMapper> mapperProvider;
  private final MeterRegistry meterRegistry;

  /**
   * 构造计量器。
   *
   * @param mapperProvider 计量 Mapper 提供器；测试或极端降级场景可为空容器
   * @param meterRegistry Micrometer 注册表
   */
  public TokenUsageRecorderImpl(
      ObjectProvider<PlatformTokenUsageMapper> mapperProvider, MeterRegistry meterRegistry) {
    this.mapperProvider = mapperProvider;
    this.meterRegistry = meterRegistry;
  }

  @Override
  public void record(TokenUsageRecord record) {
    Objects.requireNonNull(record, "TokenUsageRecord 不能为空");
    try {
      PlatformTokenUsageMapper mapper = mapperProvider.getIfAvailable();
      if (mapper == null) {
        throw new IllegalStateException("PlatformTokenUsageMapper 未装配");
      }
      mapper.insert(toEntity(record));
      tokenCounter("ai.token.prompt", record).increment(normalized(record.promptTokens()));
      tokenCounter("ai.token.completion", record).increment(normalized(record.completionTokens()));
      tokenCounter("ai.token.total", record).increment(normalized(totalTokens(record)));
    } catch (Exception exception) {
      meterRegistry
          .counter(
              "ai.token.record.failure",
              "type",
              record.type() == null ? "unknown" : record.type().wireName())
          .increment();
      log.warn("Token计量落库失败: model={}, type={}", record.model(), record.type(), exception);
    }
  }

  private PlatformTokenUsageEntity toEntity(TokenUsageRecord record) {
    PlatformTokenUsageEntity entity = new PlatformTokenUsageEntity();
    entity.setModel(blankToUnknown(record.model()));
    entity.setUserIdRef(blankToUnknown(record.userIdRef()));
    entity.setSessionId(record.sessionId());
    entity.setKnowledgeBaseId(record.knowledgeBaseId());
    entity.setUsageType(record.type());
    entity.setPromptTokens(nonNegative(record.promptTokens()));
    entity.setCompletionTokens(nonNegative(record.completionTokens()));
    entity.setTotalTokens(nonNegative(totalTokens(record)));
    entity.setSuccess(record.success());
    LocalDateTime now = LocalDateTime.now();
    entity.setCreateTime(now);
    entity.setUpdateTime(now);
    return entity;
  }

  private Long totalTokens(TokenUsageRecord record) {
    Long result = record.totalTokens();
    if (result == null) {
      long prompt = normalized(record.promptTokens());
      long completion = normalized(record.completionTokens());
      result = prompt + completion;
    }
    return result;
  }

  private long normalized(Long value) {
    return value == null || value < 0 ? 0 : value;
  }

  private Long nonNegative(Long value) {
    return normalized(value);
  }

  private String blankToUnknown(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private Counter tokenCounter(String name, TokenUsageRecord record) {
    return Counter.builder(name)
        .tag("model", blankToUnknown(record.model()))
        .tag("type", record.type() == null ? "unknown" : record.type().wireName())
        .tag("result", record.success() ? "success" : "failure")
        .register(meterRegistry);
  }
}
