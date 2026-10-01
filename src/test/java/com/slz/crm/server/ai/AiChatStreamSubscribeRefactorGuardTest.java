package com.slz.crm.server.ai;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * AI 流订阅重构守卫（卡 P-e）：纯 JVM 静态扫描，锁三类回归。
 *
 * <ol>
 *   <li>{@code AiChatStreamLifecycle} 的流装配必须以 {@code onErrorResume} 在流内吸收错误并以 {@code Flux.empty()}
 *       正常收束，{@code doOnComplete} 保持在 {@code onErrorResume} 上游，且不出现 {@code
 *       .doOnError(...).subscribe()} 裸订阅反模式（逃逸错误会落入 Hooks.onErrorDropped 静默丢失）；
 *   <li>{@code ReactorOnErrorDroppedExtension} 不再保留 expectDropped 白名单方法与豁免类型登记字段；
 *   <li>全量测试源码零 expectDropped 调用（零白名单断言，卡 P-e 后不存在任何合法丢弃）。
 * </ol>
 *
 * <p>扫描相对仓库根目录进行（surefire 工作目录 = 模块 basedir），与既有守卫测试同构。
 */
@DisplayName("AI 流订阅重构守卫（卡 P-e）：onErrorResume 内化错误 + 白名单零残留")
class AiChatStreamSubscribeRefactorGuardTest {

  private static final Path LIFECYCLE_SOURCE =
      Paths.get("src/main/java/com/slz/crm/server/ai/AiChatStreamLifecycle.java");

  private static final Path EXTENSION_SOURCE =
      Paths.get("src/test/java/com/slz/crm/server/ai/ReactorOnErrorDroppedExtension.java");

  private static final Path TEST_ROOT = Paths.get("src/test/java");

  /** doOnError 与 subscribe 落在同一条语句（以分号收束）即判为裸订阅反模式，跨行用 DOTALL 匹配。 */
  private static final Pattern DO_ONERROR_THEN_SUBSCRIBE =
      Pattern.compile("\\.doOnError\\([^;]*\\.subscribe\\(", Pattern.DOTALL);

  private static final Pattern EXPECT_DROPPED_CALL = Pattern.compile("\\.expectDropped\\s*\\(");

  @Test
  @DisplayName("流装配必须 onErrorResume 内化 + Flux.empty 收束，且无 doOnError→subscribe 反模式")
  void lifecycleStreamChainInternalizesErrors() throws IOException {
    String source = Files.readString(LIFECYCLE_SOURCE, StandardCharsets.UTF_8);
    int completeIdx = source.indexOf(".doOnComplete(");
    int onErrorResumeIdx = source.indexOf(".onErrorResume(");
    assertThat(completeIdx).as("AiChatStreamLifecycle 应保留 doOnComplete 正常完成收尾").isPositive();
    assertThat(onErrorResumeIdx)
        .as("AiChatStreamLifecycle 应包含 .onErrorResume(（卡 P-e 错误内化）")
        .isPositive();
    assertThat(source).contains("Flux.empty()");
    assertThat(completeIdx)
        .as("doOnComplete 必须位于 onErrorResume 之前：错误路径不得误触发正常完成收尾")
        .isLessThan(onErrorResumeIdx);
    assertThat(DO_ONERROR_THEN_SUBSCRIBE.matcher(source).find())
        .as("AiChatStreamLifecycle 不得包含 .doOnError(...)…subscribe() 裸订阅反模式")
        .isFalse();
  }

  @Test
  @DisplayName("ReactorOnErrorDroppedExtension 必须删除 expectDropped 白名单机制")
  void extensionHasNoWhitelistMechanism() throws IOException {
    String source = Files.readString(EXTENSION_SOURCE, StandardCharsets.UTF_8);
    assertThat(source).doesNotContainIgnoringCase("expectedtypes");
    assertThat(source).doesNotContain("expectDropped");
  }

  @Test
  @DisplayName("全量测试源码零 expectDropped 调用（零白名单断言）")
  void noTestSourceCallsExpectDropped() throws IOException {
    List<Path> javaSources;
    try (Stream<Path> paths = Files.walk(TEST_ROOT)) {
      javaSources = paths.filter(path -> path.toString().endsWith(".java")).sorted().toList();
    }
    assertThat(javaSources.size())
        .as("扫描到的测试源码文件过少，守卫扫描路径失效（应在仓库根目录运行）")
        .isGreaterThanOrEqualTo(100);
    for (Path path : javaSources) {
      String source = Files.readString(path, StandardCharsets.UTF_8);
      assertThat(EXPECT_DROPPED_CALL.matcher(source).find())
          .as("%s 不得调用 expectDropped（卡 P-e 起白名单已废除，任何被丢弃的异常一律判败）", path)
          .isFalse();
    }
  }
}
