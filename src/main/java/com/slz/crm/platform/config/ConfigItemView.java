package com.slz.crm.platform.config;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * 动态配置项管理端视图（超管配置中心展示用）。
 *
 * <p>{@code value} 已按敏感度掩码（敏感键只显示 {@code ******}）；{@code defaultValue} 为
 * 静态默认（消费方回退真实默认由各消费点自带 default 决定，此处仅展示）。</p>
 *
 * @param key          配置键
 * @param namespace    命名空间
 * @param valueType    值类型
 * @param value        当前动态值（已掩码；null = 无覆盖，走静态默认）
 * @param defaultValue 静态默认值（展示用，已掩码）
 * @param description  含义与影响面说明
 * @param version      当前版本（无覆盖为 null）
 * @param updatedAt    最后变更时间（无覆盖为 null）
 * @param updatedBy    最后变更人（无覆盖为 null）
 * @param deleted      是否已软删（true = 恢复默认中）
 * @param sensitive    是否敏感键
 * @param minValue     数值下界（展示校验护栏）
 * @param maxValue     数值上界（展示校验护栏）
 * @param allowedValues 枚举白名单（展示校验护栏；无约束为 null）
 */
public record ConfigItemView(
        String key,
        String namespace,
        String valueType,
        String value,
        String defaultValue,
        String description,
        Integer version,
        LocalDateTime updatedAt,
        String updatedBy,
        boolean deleted,
        boolean sensitive,
        String minValue,
        String maxValue,
        Set<String> allowedValues) {
}
