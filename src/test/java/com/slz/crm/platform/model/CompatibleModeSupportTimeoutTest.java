package com.slz.crm.platform.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.http.client.ClientHttpRequestFactorySettings;
import org.springframework.http.HttpStatus;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.web.client.RestClient;

/**
 * 卡 G 档1 永久回归锁 1：{@code CompatibleModeSupport.buildRequestFactory(...)} 的超时取值与真实生效行为。用本地 JDK {@link
 * HttpServer} 造桩（不起外呼、不引 MockWebServer），与既有 {@code ModelProviderThinkingTest} 同一手法。
 *
 * <p>三层证据：设置值（60→60s、0/负数→不限、connect 取新键）、行为（挂死的响应必须被 read timeout 掐断，断口是 {@link
 * HttpTimeoutException}；{@code 0} 必须是"不限"而不是"立刻超时"）、接线（走生产 chat 路时该超时真的生效——摘掉档1接线，第三条
 * 断言即因调用直接成功而红）。
 *
 * <p>实测锚点（2026-09-29，本机）：read=1s 时 RestClient 于 1064ms、走 chat 模型于 1309ms 抛 {@code
 * ResourceAccessException}（root cause {@code HttpTimeoutException: Request cancelled}）。
 */
class CompatibleModeSupportTimeoutTest {

  private static final String COMPLETIONS_PATH = "/chat/completions";

  /** 桩在"挂死"用例里的响应延迟：必须显著大于 read timeout，才能区分"超时生效"与"响应最终到达"。 */
  private static final long STALL_DELAY_MILLIS = 5_000L;

  private HttpServer server;
  private volatile long responseDelayMillis;
  private volatile boolean releaseHandler;

  @BeforeEach
  void setUp() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext(
        COMPLETIONS_PATH,
        exchange -> {
          try {
            waitForResponseMoment();
            byte[] resp =
                "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}"
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(resp);
            }
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          } catch (Exception ignored) {
            // 客户端已超时断开：桩只需保持"服务端不主动结束响应"，无断言可做
          }
        });
    server.start();
  }

  @AfterEach
  void tearDown() {
    releaseHandler = true;
    if (server != null) {
      server.stop(0);
    }
  }

  /**
   * 分片等待而非一次 sleep 满：tearDown 置位 releaseHandler 后桩立即收工，否则 {@code server.stop(0)} 会一直等到桩睡完
   * （会把每个用例的墙钟时间拖成"响应延迟"）。
   */
  private void waitForResponseMoment() throws InterruptedException {
    long deadline = System.currentTimeMillis() + responseDelayMillis;
    while (!releaseHandler && System.currentTimeMillis() < deadline) {
      Thread.sleep(20L);
    }
  }

  @Test
  void requestFactorySettingsCarryConfiguredConnectAndReadTimeouts() {
    ClientHttpRequestFactorySettings settings =
        CompatibleModeSupport.requestFactorySettings(10, 60);

    assertThat(settings.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(settings.readTimeout()).isEqualTo(Duration.ofSeconds(60));
  }

  @Test
  void nonPositiveSecondsMeanUnlimitedNotInstantTimeout() {
    ClientHttpRequestFactorySettings zeros = CompatibleModeSupport.requestFactorySettings(0, 0);
    assertThat(zeros.connectTimeout()).isNull();
    assertThat(zeros.readTimeout()).isNull();

    ClientHttpRequestFactorySettings negatives =
        CompatibleModeSupport.requestFactorySettings(-1, -5);
    assertThat(negatives.connectTimeout()).isNull();
    assertThat(negatives.readTimeout()).isNull();

    ClientHttpRequestFactorySettings mixed = CompatibleModeSupport.requestFactorySettings(10, 0);
    assertThat(mixed.connectTimeout()).isEqualTo(Duration.ofSeconds(10));
    assertThat(mixed.readTimeout()).isNull();
  }

  @Test
  void zeroReadTimeoutIsNotAnInstantTimeoutBehaviourally() {
    responseDelayMillis = 400L;
    RestClient client =
        RestClient.builder()
            .requestFactory(CompatibleModeSupport.buildRequestFactory(5, 0))
            .baseUrl(baseUrl())
            .build();

    assertThat(client.get().uri(COMPLETIONS_PATH).retrieve().toBodilessEntity().getStatusCode())
        .as("timeoutSeconds=0 必须表达'不限'：400ms 才响应的服务端不能被当成'立刻超时'")
        .isEqualTo(HttpStatus.OK);
  }

  @Test
  void builtFactoryBoundsAStalledResponse() {
    responseDelayMillis = STALL_DELAY_MILLIS;
    RestClient client =
        RestClient.builder()
            .requestFactory(CompatibleModeSupport.buildRequestFactory(5, 1))
            .baseUrl(baseUrl())
            .build();

    TimedFailure failure =
        failureWithin(() -> client.get().uri(COMPLETIONS_PATH).retrieve().toBodilessEntity());

    assertThat(failure.thrown())
        .as("read timeout=1s 必须生效：挂死的响应不能无限期占住调用线程（实测失败于 %d ms）", failure.elapsedMillis())
        .isNotNull();
    assertThat(failure.thrown())
        .as("断口应是 HTTP 超时（不是连接被拒/解析失败等别的原因）")
        .hasRootCauseInstanceOf(HttpTimeoutException.class);
    assertThat(failure.elapsedMillis())
        .as(
            "应在 1s 读超时附近失败，而不是等桩的 %d ms 响应到达（实测 %d ms）",
            STALL_DELAY_MILLIS, failure.elapsedMillis())
        .isLessThan(3_000L);
  }

  @Test
  void compatibleChatModelChatHonoursConfiguredReadTimeout() {
    responseDelayMillis = STALL_DELAY_MILLIS;
    ModelProviderProperties props = new ModelProviderProperties();
    props.setBaseUrl(baseUrl());
    props.setApiKey("test-key");
    props.setConnectTimeoutSeconds(5);
    props.setTimeoutSeconds(1);
    ModelProviderImpl provider =
        new ModelProviderImpl(emptyProvider(), emptyProvider(), props, new MockEnvironment());

    TimedFailure failure = failureWithin(() -> provider.chat(new Prompt(new UserMessage("hi"))));

    assertThat(failure.thrown())
        .as(
            "档1 接线：platform.ai.model.timeout-seconds 必须装到 sync RestClient 上——置 1 时挂死外呼必须被掐断，"
                + "摘掉 buildCompatibleChatModel 里的 requestFactory 接线本断言即因调用直接成功而红（实测 %d ms）",
            failure.elapsedMillis())
        .isNotNull();
    assertThat(failure.thrown())
        .as("断口应是 HTTP 超时（不是连接被拒/解析失败等别的原因）")
        .hasRootCauseInstanceOf(HttpTimeoutException.class);
    assertThat(failure.elapsedMillis())
        .as(
            "应在 1s 读超时附近失败，而不是等桩的 %d ms 响应到达（实测 %d ms）",
            STALL_DELAY_MILLIS, failure.elapsedMillis())
        .isLessThan(3_000L);
  }

  // ---------- 探针：在 daemon 线程上执行并限时取回，把"超时未生效"变成可判定的红而不是测试挂死 ----------

  private record TimedFailure(Throwable thrown, long elapsedMillis) {}

  @FunctionalInterface
  private interface ModelCallProbe {
    void run() throws Exception;
  }

  private static TimedFailure failureWithin(ModelCallProbe probe) {
    ExecutorService pool =
        Executors.newSingleThreadExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "model-timeout-probe");
              thread.setDaemon(true);
              return thread;
            });
    long startedAt = System.nanoTime();
    try {
      Future<Throwable> future =
          pool.submit(
              () -> {
                try {
                  probe.run();
                  return null;
                } catch (Throwable thrown) {
                  return thrown;
                }
              });
      try {
        return new TimedFailure(future.get(20, TimeUnit.SECONDS), elapsedMillis(startedAt));
      } catch (TimeoutException e) {
        throw new AssertionError("模型调用在 20s 内既不返回也不报错——说明 HTTP 超时根本没装上去", e);
      } catch (ExecutionException e) {
        throw new AssertionError("探针任务自身异常终止", e);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new AssertionError("等待模型调用结果时被中断", e);
    } finally {
      pool.shutdownNow();
    }
  }

  private static long elapsedMillis(long startedAt) {
    return (System.nanoTime() - startedAt) / 1_000_000L;
  }

  private String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  @SuppressWarnings("unchecked")
  private <T> ObjectProvider<T> emptyProvider() {
    return new ObjectProvider<T>() {
      @Override
      public T getObject() {
        throw new IllegalStateException("no bean");
      }

      @Override
      public T getIfAvailable() {
        return null;
      }
    };
  }
}
