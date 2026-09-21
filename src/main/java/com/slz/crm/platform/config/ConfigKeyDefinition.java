package com.slz.crm.platform.config;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;

/**
 * 单个动态配置键的完整元数据（校验护栏的“schema”）。
 *
 * <p>为什么 schema 放代码而非全放 DB：动态配置的消费方（B/C/D）按固定键编程， 键的类型/范围/枚举必须稳定可校验；代码注册表能给出强校验 + 中文说明
 * （超管界面据此展示“含义/默认值/影响面”），并防止超管误建出消费方不认识的新键。
 *
 * @param key 完整配置键（点分，如 {@code rag.retrieval.topK}，全局唯一）
 * @param namespace 命名空间（{@code ai.prompt}/{@code ai.model}/{@code rag.retrieval}/{@code
 *     rag.intent}/{@code business}）
 * @param type 值类型（决定解析与校验规则）
 * @param defaultValue 展示用静态默认值（真实回退由消费方调用 get 时自带 default 决定）
 * @param description 含义与影响面说明（超管界面展示，必须中文、可读）
 * @param minValue 数值下界（十进制文本；非数值类型为 null）
 * @param maxValue 数值上界（十进制文本；非数值类型为 null）
 * @param allowedValues 枚举白名单（STRING 类型用；空集合表示不限）
 * @param sensitive 敏感值标记（true 时管理端读取/审计掩码；密钥类仍走环境变量，本域一般不承载）
 * @param maxValueLength STRING 值最大长度
 * @param maxListSize STRING_LIST 最大条目数
 * @param maxEntryLength STRING_LIST 单条最大长度
 * @param objectMapper STRING_LIST 的 JSON 解析器（由注册表统一注入）
 */
public record ConfigKeyDefinition(
    String key,
    String namespace,
    ConfigValueType type,
    String defaultValue,
    String description,
    String minValue,
    String maxValue,
    Set<String> allowedValues,
    boolean sensitive,
    int maxValueLength,
    int maxListSize,
    int maxEntryLength,
    ObjectMapper objectMapper) {

  /** 默认文本长度上限（提示词等长文本键可覆盖） */
  public static final int DEFAULT_MAX_VALUE_LENGTH = 10000;

  /**
   * 数值范围校验：越界返回可读错误信息，在界内返回 {@code null}。
   *
   * @param canonical 已通过类型解析的十进制文本（保证可被 BigDecimal 解析）
   */
  public String rangeError(String canonical) {
    String result = null;
    if (minValue != null || maxValue != null) {
      java.math.BigDecimal value = new java.math.BigDecimal(canonical);
      if (minValue != null && value.compareTo(new java.math.BigDecimal(minValue)) < 0) {
        result = "取值不能小于 " + minValue + "，实际值：" + canonical;
      } else if (maxValue != null && value.compareTo(new java.math.BigDecimal(maxValue)) > 0) {
        result = "取值不能大于 " + maxValue + "，实际值：" + canonical;
      }
    }
    return result;
  }

  /** STRING_LIST 反序列化目标类型（List&lt;String&gt;） */
  TypeReference<java.util.List<String>> listType() {
    return new TypeReference<java.util.List<String>>() {};
  }

  /** 构造时自检（注册表在启动期调用）：键必须以命名空间开头、范围/枚举与类型匹配， 把“schema 写错”这类程序缺陷在启动期暴露，而不是运行期炸在超管面前。 */
  public void selfCheck() {
    if (key == null || !key.startsWith(namespace + ".")) {
      throw new IllegalArgumentException("配置键必须以命名空间开头：" + key);
    }
    if (type == ConfigValueType.STRING && !allowedValues.isEmpty() && allowedValues.contains("")) {
      throw new IllegalArgumentException("枚举白名单不允许空字符串：" + key);
    }
    if (type != ConfigValueType.INTEGER
        && type != ConfigValueType.LONG
        && type != ConfigValueType.DOUBLE
        && (minValue != null || maxValue != null)) {
      throw new IllegalArgumentException("只有数值类型可以声明范围：" + key);
    }
    if ((minValue != null) != (maxValue != null)) {
      throw new IllegalArgumentException("范围必须同时给出上下界（或都不给）：" + key);
    }
  }
}
