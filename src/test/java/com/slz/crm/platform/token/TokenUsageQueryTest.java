package com.slz.crm.platform.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 「AI 用量」查询条件校验单测（token-usage-admin-view 任务 2.4，零 Spring）。
 *
 * <p>锁死：缺省近 30 天、双端同时提供、区间倒置/未来时间/超 366 天/非法 usageType 一律拒绝。
 */
class TokenUsageQueryTest {

  private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 22, 12, 0);

  @Test
  @DisplayName("缺省区间 = 近 30 天，usageType 为空不过滤")
  void defaultsToLast30Days() {
    TokenUsageQuery query = TokenUsageQuery.of(null, null, null, NOW);
    assertThat(query.getEnd()).isEqualTo(NOW);
    assertThat(query.getStart()).isEqualTo(NOW.minusDays(30));
    assertThat(query.getUsageTypeNames()).isNull();
  }

  @Test
  @DisplayName("只给单端 → 拒绝：必须同时提供或同时省略")
  void rejectsSingleBoundary() {
    assertThatThrownBy(() -> TokenUsageQuery.of("2026-09-01T00:00:00", null, null, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须同时提供");
    assertThatThrownBy(() -> TokenUsageQuery.of(null, "2026-09-01T00:00:00", null, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须同时提供");
  }

  @Test
  @DisplayName("区间倒置 → 拒绝；end 等于 start 也拒绝")
  void rejectsReversedOrEmptyRange() {
    assertThatThrownBy(
            () -> TokenUsageQuery.of("2026-09-10T00:00:00", "2026-09-09T00:00:00", null, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须早于");
    assertThatThrownBy(
            () -> TokenUsageQuery.of("2026-09-10T00:00:00", "2026-09-10T00:00:00", null, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("必须早于");
  }

  @Test
  @DisplayName("终点晚于当前时间 → 拒绝")
  void rejectsFutureEnd() {
    assertThatThrownBy(
            () -> TokenUsageQuery.of("2026-09-01T00:00:00", "2026-09-23T00:00:00", null, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不能晚于当前时间");
  }

  @Test
  @DisplayName("区间上限 366 天：恰好 366 天放行，多 1 秒拒绝")
  void enforcesMaxWindow() {
    LocalDateTime end = NOW.minusDays(1);
    TokenUsageQuery exact =
        TokenUsageQuery.of(end.minusDays(366).toString(), end.toString(), null, NOW);
    assertThat(Duration.between(exact.getStart(), exact.getEnd())).isEqualTo(Duration.ofDays(366));

    LocalDateTime tooWideStart = end.minusDays(366).minusSeconds(1);
    assertThatThrownBy(() -> TokenUsageQuery.of(tooWideStart.toString(), end.toString(), null, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("366");
  }

  @Test
  @DisplayName("usageType 接受 wire 名/枚举名且大小写不敏感；同时带两种库内口径")
  void resolvesUsageTypeNames() {
    assertThat(TokenUsageQuery.of(null, null, "chat", NOW).getUsageTypeNames())
        .containsExactly("CHAT", "chat");
    assertThat(TokenUsageQuery.of(null, null, "EMBEDDING", NOW).getUsageTypeNames())
        .containsExactly("EMBEDDING", "embedding");
    assertThat(TokenUsageQuery.of(null, null, "  ", NOW).getUsageTypeNames()).isNull();
  }

  @Test
  @DisplayName("非法 usageType / 非法时间格式 → 拒绝")
  void rejectsUnknownTypeAndBadFormat() {
    assertThatThrownBy(() -> TokenUsageQuery.of(null, null, "image", NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("不支持的 usageType");
    assertThatThrownBy(() -> TokenUsageQuery.of("2026-09-01", "2026-09-02T00:00:00", null, NOW))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ISO-8601");
  }

  @Test
  @DisplayName("空格分隔的时间也接受（查询串未转义时的常见写法）")
  void acceptsSpaceSeparator() {
    TokenUsageQuery query =
        TokenUsageQuery.of("2026-09-01 00:00:00", "2026-09-02 00:00:00", null, NOW);
    assertThat(query.getStart()).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
    assertThat(query.getEnd()).isEqualTo(LocalDateTime.of(2026, 9, 2, 0, 0));
  }
}
