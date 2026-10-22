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
 *
 * <p><b>token 不可得的诚实口径</b>（multimodal-gray-and-eval-threshold 任务 3.1）：DashScope 文本 embedding
 * 与文本-图像联合 embedding 两端点实测<b>不回流 usage</b>（{@code ModelProviderImpl#embed} 拿到 null metadata、{@code
 * ImageEmbeddingSupport#parseResponse} 取不到 input_tokens），因此调用方上报的 token 三列为 {@code null}。落库表三列是
 * {@code NOT NULL DEFAULT 0} （{@code V5__governance.sql}，不为计量口径新增迁移），所以库里读到的 0
 * <b>表示"未知"而不是"零成本"</b>；这一区分由两个指标承担：
 *
 * <ul>
 *   <li>{@code ai.token.record.calls}：每次计量上报 +1（<b>调用次数已计</b>，与 token 是否可得无关）；
 *   <li>{@code ai.token.usage.unavailable}：token 三列全为空时 +1（<b>token 不可得声明</b>，按 model/type 打标）。
 * </ul>
 *
 * <p>不伪造 token（不按字符数估算、不补 0 到某一档），也不改冻结契约 {@code TokenUsageRecord}/{@code ModelCallResult} （{@code
 * contracts-frozen.md} §71 明示 9 字段不改签名）。
 */
@Component
public class TokenUsageRecorderImpl implements TokenUsageRecorder {

  private static final Logger LOG = LoggerFactory.getLogger(TokenUsageRecorderImpl.class);

  /** 调用次数计量名（token 不可得时也逐次 +1；口径见类注释）。 */
  static final String CALLS_METRIC = "ai.token.record.calls";

  /** token 不可得声明计量名（三列 token 全为空的计量记录 +1）。 */
  static final String USAGE_UNAVAILABLE_METRIC = "ai.token.usage.unavailable";

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
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 计量落库容错：ORM+provider+meter多源，失败计数不阻断调用链
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
      // 调用次数与 token 可得性分开计：embedding 端点不回流 token，次数仍是真账
      tokenCounter(CALLS_METRIC, record).increment();
      if (!usageAvailable(record)) {
        meterRegistry
            .counter(
                USAGE_UNAVAILABLE_METRIC,
                "model",
                blankToUnknown(record.model()),
                "type",
                record.type() == null ? "unknown" : record.type().wireName())
            .increment();
      }
    } catch (Exception exception) {
      meterRegistry
          .counter(
              "ai.token.record.failure",
              "type",
              record.type() == null ? "unknown" : record.type().wireName())
          .increment();
      LOG.warn("Token计量落库失败: model={}, type={}", record.model(), record.type(), exception);
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

  /**
   * token 是否可得（三列至少一列非空）。
   *
   * <p>不用 {@code >>> 0} 之类的兜底判据：全空就是"端点没给"，与"给了 0"是两件事，必须靠这里区分。
   *
   * @param record 计量记录
   * @return true 表示至少拿到一项 token
   */
  private boolean usageAvailable(TokenUsageRecord record) {
    return record.promptTokens() != null
        || record.completionTokens() != null
        || record.totalTokens() != null;
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
