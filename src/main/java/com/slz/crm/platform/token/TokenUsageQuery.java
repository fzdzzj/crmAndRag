package com.slz.crm.platform.token;

import com.slz.crm.platform.contract.TokenUsageType;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;

/**
 * 「AI 用量」查询条件（只读、纯校验单元，便于零 Spring 单测）。
 *
 * <p>契约（token-usage-admin-view 任务 2.3/2.4）：
 *
 * <ul>
 *   <li>时间区间缺省 = 近 30 天；两端必须同时提供或同时省略；
 *   <li>区间上限 366 天；起点必须早于终点；终点不得晚于「当前时间」；
 *   <li>{@code usageType} 可空；取值接受 wire 名（chat/embedding/...）或枚举名，大小写不敏感。
 * </ul>
 *
 * <p>校验失败一律抛 {@link IllegalArgumentException}，由 controller 统一转成 VALIDATION(96007)，不新增错误码。
 */
@Getter
public final class TokenUsageQuery {

  /** 缺省区间长度（天）。 */
  static final int DEFAULT_DAYS = 30;

  /** 区间上限（天）。 */
  static final int MAX_DAYS = 366;

  /** 区间起点（含）。 */
  private LocalDateTime start;

  /** 区间终点（不含）。 */
  private LocalDateTime end;

  /** usage_type 过滤值（枚举名 + wire 名，兼容历史写入口径）；null = 不过滤。 */
  private List<String> usageTypeNames;

  private TokenUsageQuery() {}

  /**
   * 解析并校验查询条件。
   *
   * @param startText 起点文本（ISO-8601，可空）
   * @param endText 终点文本（ISO-8601，可空）
   * @param usageTypeText 计量类型（可空）
   * @param now 校验基准时间（显式传入便于单测）
   * @return 校验通过的查询条件
   * @throws IllegalArgumentException 区间倒置 / 未来时间 / 超 366 天 / 非法 usageType / 时间格式错误
   */
  public static TokenUsageQuery of(
      String startText, String endText, String usageTypeText, LocalDateTime now) {
    boolean startBlank = isBlank(startText);
    boolean endBlank = isBlank(endText);
    if (startBlank != endBlank) {
      throw new IllegalArgumentException("startTime 与 endTime 必须同时提供，或同时省略（省略 = 近 30 天）");
    }
    TokenUsageQuery query = new TokenUsageQuery();
    query.start = startBlank ? now.minusDays(DEFAULT_DAYS) : parse(startText, "startTime");
    query.end = endBlank ? now : parse(endText, "endTime");
    query.usageTypeNames = resolveTypeNames(usageTypeText);
    validateWindow(query.start, query.end, now);
    return query;
  }

  private static void validateWindow(LocalDateTime start, LocalDateTime end, LocalDateTime now) {
    if (!start.isBefore(end)) {
      throw new IllegalArgumentException("startTime 必须早于 endTime");
    }
    if (end.isAfter(now)) {
      throw new IllegalArgumentException("endTime 不能晚于当前时间");
    }
    if (Duration.between(start, end).compareTo(Duration.ofDays(MAX_DAYS)) > 0) {
      throw new IllegalArgumentException("时间区间不得超过 " + MAX_DAYS + " 天");
    }
  }

  private static LocalDateTime parse(String text, String field) {
    LocalDateTime result;
    try {
      // 同时接受 "2026-09-01T00:00:00" 与 "2026-09-01 00:00:00" 两种写法
      result = LocalDateTime.parse(text.trim().replace(' ', 'T'));
    } catch (DateTimeParseException exception) {
      throw new IllegalArgumentException(
          field + " 需为 ISO-8601 时间（如 2026-09-01T00:00:00），实际值：" + text, exception);
    }
    return result;
  }

  private static List<String> resolveTypeNames(String usageTypeText) {
    List<String> result;
    if (isBlank(usageTypeText)) {
      result = null;
    } else {
      TokenUsageType matched = null;
      String normalized = usageTypeText.trim();
      for (TokenUsageType type : TokenUsageType.values()) {
        if (type.wireName().equalsIgnoreCase(normalized)
            || type.name().equalsIgnoreCase(normalized)) {
          matched = type;
        }
      }
      if (matched == null) {
        throw new IllegalArgumentException(
            "不支持的 usageType：" + usageTypeText + "（可选 chat/embedding/ocr/vision/summary/intent）");
      }
      // 同时带枚举名与 wire 名：写入口径历史上两种大小写都有，IN 两个常量不破坏索引
      result = new ArrayList<>(2);
      result.add(matched.name());
      result.add(matched.wireName());
    }
    return result;
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
