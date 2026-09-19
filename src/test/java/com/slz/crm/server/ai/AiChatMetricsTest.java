package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** AI 流式与工具运行指标行为验证。 */
class AiChatMetricsTest {

  private MeterRegistry registry;

  private AiChatMetrics metrics;

  private AiStreamRegistry streamRegistry;

  @BeforeEach
  void setUp() {
    registry = new SimpleMeterRegistry();
    streamRegistry = new AiStreamRegistry();
    metrics = new AiChatMetrics(registry, streamRegistry);
  }

  @Test
  void streamMetrics_shouldRecordActiveCountHeartbeatsAndTerminalDurations() {
    // 验证活跃 Gauge、心跳计数和带模型维度的终态计数/耗时
    AiStreamRegistry.ActiveStream activeStream =
        new AiStreamRegistry.ActiveStream(1L, new SseEmitter());
    streamRegistry.register(1L, activeStream);

    assertThat(registry.get("ai.chat.stream.active").gauge().value()).isEqualTo(1);

    metrics.recordHeartbeat();
    metrics.recordCompleted(activeStream, "qwen-plus", true);
    metrics.recordCancelled(activeStream, "qwen-plus", false);

    assertThat(registry.get("ai.chat.stream.heartbeats").counter().count()).isEqualTo(1);
    assertThat(
            registry
                .get("ai.chat.stream.completed")
                .tag("model", "qwen-plus")
                .tag("fallback", "true")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("ai.chat.stream.cancelled")
                .tag("model", "qwen-plus")
                .tag("fallback", "false")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("ai.chat.stream.duration")
                .tag("model", "qwen-plus")
                .tag("fallback", "false")
                .tag("status", "cancelled")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void streamMetrics_shouldRecordFirstTokenWhenMarked() {
    // 验证 CAS 只允许首个回答片段产生一次 token 延迟样本
    AiStreamRegistry.ActiveStream activeStream =
        new AiStreamRegistry.ActiveStream(1L, new SseEmitter());
    assertThat(activeStream.markFirstToken()).isTrue();
    assertThat(activeStream.markFirstToken()).isFalse();

    metrics.recordFirstToken(activeStream, "qwen-plus", true);

    assertThat(
            registry
                .get("ai.chat.stream.first-token")
                .tag("model", "qwen-plus")
                .tag("fallback", "true")
                .timer()
                .count())
        .isEqualTo(1);
  }

  @Test
  void toolMetrics_shouldRecordSuccessAndFailureTags() {
    // 验证工具指标按 tool/status 低基数维度拆分
    metrics.recordToolCall("probeTool", true, System.nanoTime());
    metrics.recordToolCall("probeTool", false, System.nanoTime());

    assertThat(
            registry
                .get("ai.tool.call.total")
                .tag("tool", "probeTool")
                .tag("status", "success")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("ai.tool.call.total")
                .tag("tool", "probeTool")
                .tag("status", "failure")
                .counter()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("ai.tool.call.duration")
                .tag("tool", "probeTool")
                .tag("status", "success")
                .timer()
                .count())
        .isEqualTo(1);
    assertThat(
            registry
                .get("ai.tool.call.duration")
                .tag("tool", "probeTool")
                .tag("status", "failure")
                .timer()
                .count())
        .isEqualTo(1);
  }
}
