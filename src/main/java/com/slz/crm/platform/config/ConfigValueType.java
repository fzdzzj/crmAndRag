package com.slz.crm.platform.config;

/**
 * 动态配置值类型（校验护栏的一部分）。
 *
 * <p>每个配置键在 {@link DynamicConfigKeyRegistry} 里声明一个类型；写入时按类型解析 + 范围/枚举校验，读取时按类型还原成 Java
 * 对象交给消费者（对齐冻结契约 {@link com.slz.crm.platform.contract.DynamicConfigService} 的
 * String/Integer/Long/Boolean/Double/List&lt;String&gt; 六种类型）。
 *
 * <p>为什么存序列化文本而非强类型列：配置值语义千差万别（提示词 / 阈值 / 开关 / 类目列表）， 统一 {@code text} 存储 +
 * 按类型解析，便于新增键而无需改表；风险由写入期校验护栏兜住。
 */
public enum ConfigValueType {

  /** 单行/多行文本（提示词、模型名、Provider 名） */
  STRING,
  /** 32 位整数（topK、限流阈值、缓存上限） */
  INTEGER,
  /** 64 位整数（Token 预算等大数） */
  LONG,
  /** 布尔开关（strict-KB、意图过滤开关等） */
  BOOLEAN,
  /** 浮点（温度、相似度阈值） */
  DOUBLE,
  /** 字符串列表（意图类目、关键词），DB 中存紧凑 JSON 数组文本 */
  STRING_LIST;

  /**
   * 解析结果：{@link #valid()} 为 false 时 {@link #errorMessage()} 给出超管可读的拒绝原因； 为 true 时 {@link
   * #canonical()} 是落库/回滚用的规范化文本，{@link #typed()} 是内存态类型化值。
   */
  public record Parsed(Object typed, String canonical, boolean valid, String errorMessage) {

    static Parsed ok(Object typed, String canonical) {
      return new Parsed(typed, canonical, true, null);
    }

    static Parsed fail(String errorMessage) {
      return new Parsed(null, null, false, errorMessage);
    }
  }

  /**
   * 把写入的原始文本解析并校验为规范化值。
   *
   * @param raw 原始输入（STRING 会 trim，其余类型按原样解析）
   * @param def 该配置键的元数据（范围/枚举/长度护栏）
   * @return 解析结果；失败时给出可读错误信息（写入方据此拒绝并保持原值）
   */
  public Parsed parse(String raw, ConfigKeyDefinition def) {
    if (raw == null) {
      return Parsed.fail("值不能为空");
    }
    return switch (this) {
      case STRING -> parseString(raw.trim(), def);
      case INTEGER -> parseInteger(raw.trim(), def);
      case LONG -> parseLong(raw.trim(), def);
      case BOOLEAN -> parseBoolean(raw.trim());
      case DOUBLE -> parseDouble(raw.trim(), def);
      case STRING_LIST -> parseStringList(raw.trim(), def);
    };
  }

  /**
   * 把 DB 中已存储的规范化文本还原为类型化对象（缓存加载用）。
   *
   * <p>与 {@link #parse(String, ConfigKeyDefinition)} 的区别：存储值理论上已通过写入校验， 但为防御历史脏数据/手工改库，解析失败时返回
   * {@code null}（读取方回退默认值）， 而不是抛异常打断读取链路。
   *
   * @param canonical 规范化存储文本
   * @param def 该配置键的元数据
   * @return 类型化值；非法/不可解析返回 {@code null}
   */
  public Object parseStored(String canonical, ConfigKeyDefinition def) {
    if (canonical == null) {
      return null;
    }
    Parsed parsed = parse(canonical, def);
    return parsed.valid() ? parsed.typed() : null;
  }

  private Parsed parseString(String trimmed, ConfigKeyDefinition def) {
    if (trimmed.isEmpty()) {
      return Parsed.fail("值不能为空");
    }
    if (trimmed.length() > def.maxValueLength()) {
      return Parsed.fail("值长度超过上限（最大 " + def.maxValueLength() + " 字符）");
    }
    if (!def.allowedValues().isEmpty() && !def.allowedValues().contains(trimmed)) {
      return Parsed.fail("取值必须在枚举范围内：" + def.allowedValues());
    }
    return Parsed.ok(trimmed, trimmed);
  }

  private Parsed parseInteger(String trimmed, ConfigKeyDefinition def) {
    final int value;
    try {
      value = Integer.parseInt(trimmed);
    } catch (NumberFormatException e) {
      return Parsed.fail("必须是整数，实际值：" + trimmed);
    }
    String rangeError = def.rangeError(trimmed);
    if (rangeError != null) {
      return Parsed.fail(rangeError);
    }
    return Parsed.ok(value, Integer.toString(value));
  }

  private Parsed parseLong(String trimmed, ConfigKeyDefinition def) {
    final long value;
    try {
      value = Long.parseLong(trimmed);
    } catch (NumberFormatException e) {
      return Parsed.fail("必须是长整数，实际值：" + trimmed);
    }
    String rangeError = def.rangeError(trimmed);
    if (rangeError != null) {
      return Parsed.fail(rangeError);
    }
    return Parsed.ok(value, Long.toString(value));
  }

  private Parsed parseBoolean(String trimmed) {
    if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed)) {
      boolean value = Boolean.parseBoolean(trimmed);
      return Parsed.ok(value, Boolean.toString(value));
    }
    return Parsed.fail("必须是 true/false，实际值：" + trimmed);
  }

  private Parsed parseDouble(String trimmed, ConfigKeyDefinition def) {
    final double value;
    try {
      value = Double.parseDouble(trimmed);
    } catch (NumberFormatException e) {
      return Parsed.fail("必须是数值，实际值：" + trimmed);
    }
    if (!Double.isFinite(value)) {
      return Parsed.fail("必须是有限数值，实际值：" + trimmed);
    }
    String rangeError = def.rangeError(trimmed);
    if (rangeError != null) {
      return Parsed.fail(rangeError);
    }
    return Parsed.ok(value, Double.toString(value));
  }

  private Parsed parseStringList(String trimmed, ConfigKeyDefinition def) {
    try {
      var entries = def.objectMapper().readValue(trimmed, def.listType());
      if (entries.isEmpty()) {
        return Parsed.fail("列表不能为空（至少一个条目）");
      }
      if (entries.size() > def.maxListSize()) {
        return Parsed.fail("列表条目数超过上限（最大 " + def.maxListSize() + " 条）");
      }
      for (String entry : entries) {
        if (entry == null || entry.isBlank()) {
          return Parsed.fail("列表条目不能为空字符串");
        }
        if (entry.length() > def.maxEntryLength()) {
          return Parsed.fail("列表条目长度超过上限（最大 " + def.maxEntryLength() + " 字符）");
        }
      }
      String canonical = def.objectMapper().writeValueAsString(entries);
      return Parsed.ok(new java.util.ArrayList<>(entries), canonical);
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      return Parsed.fail("必须是 JSON 字符串数组（如 [\"类目A\",\"类目B\"]），实际值：" + trimmed);
    }
  }
}
