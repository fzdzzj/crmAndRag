package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.slz.crm.server.properties.AiProperties;
import com.slz.crm.server.service.AiMessageService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 卡 G 档4：{@code crm.ai.llm-timeout-seconds} 的 0/负数/缺省语义回归锁——无效配置回落默认 60s（不是「不限」）， 并打出含实际值与回落值的
 * WARN；正常值原样透传。
 */
class AiChatStreamLifecycleTimeoutTest {

  private static AiChatStreamLifecycle lifecycleWithLlmTimeout(Integer llmTimeoutSeconds) {
    AiProperties properties = new AiProperties();
    properties.setLlmTimeoutSeconds(llmTimeoutSeconds);
    AiStreamRegistry registry = new AiStreamRegistry();
    return new AiChatStreamLifecycle(
        mock(ChatClient.Builder.class),
        properties,
        mock(AiMessageService.class),
        registry,
        mock(AiChatPromptService.class),
        mock(AiChatSseEventWriter.class),
        new AiChatStreamHeartbeat(properties, mock(ScheduledExecutorService.class)),
        new AiChatMetrics(new SimpleMeterRegistry(), registry),
        mock(AiAssistantMessageStore.class),
        "qwen-plus");
  }

  @Test
  void zeroMeansInvalidConfigAndFallsBackToDefaultSixtySeconds() {
    assertThat(lifecycleWithLlmTimeout(0).resolveLlmTimeout()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void negativeMeansInvalidConfigAndFallsBackToDefaultSixtySeconds() {
    assertThat(lifecycleWithLlmTimeout(-5).resolveLlmTimeout()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void nullMeansUnsetConfigAndFallsBackToDefaultSixtySeconds() {
    assertThat(lifecycleWithLlmTimeout(null).resolveLlmTimeout()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void positiveValueIsPassedThroughUnchanged() {
    assertThat(lifecycleWithLlmTimeout(45).resolveLlmTimeout()).isEqualTo(Duration.ofSeconds(45));
  }

  @Test
  void invalidConfigEmitsWarnWithActualValueAndFallbackValue() {
    Logger logger = (Logger) LoggerFactory.getLogger(AiChatStreamLifecycle.class);
    ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      lifecycleWithLlmTimeout(0).resolveLlmTimeout();
      lifecycleWithLlmTimeout(-5).resolveLlmTimeout();
      lifecycleWithLlmTimeout(null).resolveLlmTimeout();
    } finally {
      logger.detachAppender(appender);
    }
    List<String> warns =
        appender.list.stream()
            .filter(event -> event.getLevel() == Level.WARN)
            .map(ILoggingEvent::getFormattedMessage)
            .filter(message -> message.contains("llm-timeout-seconds"))
            .toList();
    assertThat(warns)
        .hasSize(3)
        .anySatisfy(message -> assertThat(message).contains("实际值=0", "回落默认 60 秒"))
        .anySatisfy(message -> assertThat(message).contains("实际值=-5", "回落默认 60 秒"))
        .anySatisfy(message -> assertThat(message).contains("实际值=null", "回落默认 60 秒"));
  }
}
