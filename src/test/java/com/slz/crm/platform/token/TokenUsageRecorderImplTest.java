package com.slz.crm.platform.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.mapper.PlatformTokenUsageMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

/** Token 计量器落库与失败不打断业务测试。 */
@ExtendWith(MockitoExtension.class)
class TokenUsageRecorderImplTest {

  @Mock private PlatformTokenUsageMapper mapper;

  @Mock private ObjectProvider<PlatformTokenUsageMapper> mapperProvider;

  private SimpleMeterRegistry registry;

  private TokenUsageRecorderImpl recorder;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    recorder = new TokenUsageRecorderImpl(mapperProvider, registry);
  }

  @Test
  void shouldPersistNormalizedUsageAndEmitMetrics() {
    when(mapperProvider.getIfAvailable()).thenReturn(mapper);
    TokenUsageRecord record =
        new TokenUsageRecord(
            "qwen-plus", "user:1", "session-1", 3L, TokenUsageType.INTENT, 12L, 8L, null, true);

    recorder.record(record);

    verify(mapper)
        .insert(
            org.mockito.ArgumentMatchers.argThat(
                entity -> {
                  assertThat(entity.getTotalTokens()).isEqualTo(20L);
                  assertThat(entity.getUsageType()).isEqualTo(TokenUsageType.INTENT);
                  assertThat(entity.isSuccess()).isTrue();
                  return true;
                }));
    assertThat(registry.get("ai.token.total").tag("type", "intent").counter().count())
        .isEqualTo(20.0);
  }

  @Test
  void shouldCountEmbeddingCallAndDeclareTokenUnavailable() {
    when(mapperProvider.getIfAvailable()).thenReturn(mapper);
    // embedding 端点不回流 usage 的真实形态：三列 token 全 null（ImageEmbeddingSupport / ModelProviderImpl.embed
    // 实测）
    TokenUsageRecord record =
        new TokenUsageRecord(
            "multimodal-embedding-v1",
            "user:system",
            null,
            null,
            TokenUsageType.EMBEDDING,
            null,
            null,
            null,
            true);

    recorder.record(record);

    // 调用次数已计（与 token 是否可得无关）
    assertThat(
            registry
                .get(TokenUsageRecorderImpl.CALLS_METRIC)
                .tag("type", "embedding")
                .counter()
                .count())
        .isEqualTo(1.0);
    // token 不可得显式声明，而不是静默记 0
    assertThat(
            registry
                .get(TokenUsageRecorderImpl.USAGE_UNAVAILABLE_METRIC)
                .tag("type", "embedding")
                .counter()
                .count())
        .isEqualTo(1.0);
    // 落库仍是 schema 的 NOT NULL 归一值 0（V5 迁移），语义由上面的指标承担
    verify(mapper)
        .insert(
            org.mockito.ArgumentMatchers.argThat(
                entity ->
                    entity.getPromptTokens() == 0L
                        && entity.getCompletionTokens() == 0L
                        && entity.getTotalTokens() == 0L));
  }

  @Test
  void shouldNotDeclareUnavailableWhenUsagePresent() {
    when(mapperProvider.getIfAvailable()).thenReturn(mapper);
    TokenUsageRecord record =
        new TokenUsageRecord(
            "qwen-plus", "user:1", null, null, TokenUsageType.CHAT, 12L, 8L, null, true);

    recorder.record(record);

    assertThat(
            registry.get(TokenUsageRecorderImpl.CALLS_METRIC).tag("type", "chat").counter().count())
        .isEqualTo(1.0);
    assertThat(
            registry
                .find(TokenUsageRecorderImpl.USAGE_UNAVAILABLE_METRIC)
                .tag("type", "chat")
                .counter())
        .isNull();
  }

  @Test
  void shouldSwallowPersistenceFailure() {
    when(mapperProvider.getIfAvailable()).thenReturn(mapper);
    when(mapper.insert(org.mockito.ArgumentMatchers.any(PlatformTokenUsageEntity.class)))
        .thenThrow(new IllegalStateException("database unavailable"));
    TokenUsageRecord record =
        new TokenUsageRecord(
            "qwen-plus", "user:1", null, null, TokenUsageType.SUMMARY, 1L, 2L, 3L, true);

    assertThatCode(() -> recorder.record(record)).doesNotThrowAnyException();
    assertThat(registry.get("ai.token.record.failure").counter().count()).isEqualTo(1.0);
  }
}
