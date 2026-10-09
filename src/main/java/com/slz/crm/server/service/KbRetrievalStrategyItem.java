package com.slz.crm.server.service;

/**
 * per-KB 策略清单单键生效条目（add-per-kb-retrieval-strategy-override 任务 4）。
 *
 * <p>清单一览来源标注：OVERRIDE=本库覆盖 / GLOBAL=全局动态配置 / DEFAULT=注册表默认。三层合并语义：覆盖 &gt; 全局 &gt; 默认。
 *
 * @param key 覆盖键（12 键白名单内）
 * @param source 生效来源：OVERRIDE / GLOBAL / DEFAULT
 * @param effectiveValue 当前生效值（覆盖或全局或默认，随来源而定）
 * @param storedOverride 本库已存的覆盖值（无则 null）
 * @param version 本库覆盖当前版本（无覆盖则 0）
 */
public record KbRetrievalStrategyItem(
    String key, String source, String effectiveValue, String storedOverride, int version) {

  /** 生效来源标注常量 */
  public static final String SOURCE_OVERRIDE = "OVERRIDE";

  public static final String SOURCE_GLOBAL = "GLOBAL";
  public static final String SOURCE_DEFAULT = "DEFAULT";
}
