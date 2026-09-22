package com.slz.crm.platform.config.service;

import com.slz.crm.platform.config.ConfigHistoryView;
import com.slz.crm.platform.config.ConfigItemView;
import com.slz.crm.platform.config.ConfigKeyDefinition;
import com.slz.crm.platform.config.entity.DynamicConfigHistoryEntity;
import com.slz.crm.platform.config.entity.DynamicConfigItemEntity;

/**
 * 动态配置管理端视图与敏感值掩码支持类（tighten-pmd-residual-325 任务 6.3 拆自 DynamicConfigAdminService，行为等价）。纯静态、无状态。
 */
final class DynamicConfigViewSupport {

  private DynamicConfigViewSupport() {}

  /** 敏感值掩码：全掩为 {@code ******}，不泄露任何片段（长度也不给，避免辅助爆破）。 空值显示 {@code (空)} 以便与“有值但被掩码”区分。 */
  static String mask(String raw, boolean sensitive) {
    String result;
    if (!sensitive) {
      result = raw;
    } else if (raw == null || raw.isBlank()) {
      result = "(空)";
    } else {
      result = "******";
    }
    return result;
  }

  /** 实体 + 注册表元数据 → 管理端视图（敏感值掩码、软删置空 value） */
  static ConfigItemView toView(DynamicConfigItemEntity row, ConfigKeyDefinition def) {
    boolean deleted = row != null && Boolean.TRUE.equals(row.getIsDeleted());
    String value = (row == null || deleted) ? null : row.getConfigValue();
    return new ConfigItemView(
        def.key(),
        def.namespace(),
        def.type().name(),
        value == null ? null : mask(value, def.sensitive()),
        mask(def.defaultValue(), def.sensitive()),
        def.description(),
        row == null ? null : row.getVersion(),
        row == null ? null : row.getUpdateTime(),
        row == null ? null : row.getUpdatedBy(),
        deleted,
        def.sensitive(),
        def.minValue(),
        def.maxValue(),
        def.allowedValues().isEmpty() ? null : def.allowedValues());
  }

  /** 历史行 + 注册表元数据 → 历史视图（敏感值掩码） */
  static ConfigHistoryView toHistoryView(DynamicConfigHistoryEntity h, ConfigKeyDefinition def) {
    return new ConfigHistoryView(
        h.getVersion(),
        h.getOperationType(),
        mask(h.getOldValue(), def.sensitive()),
        mask(h.getNewValue(), def.sensitive()),
        h.getOperatorRef(),
        h.getRemark(),
        h.getCreateTime());
  }
}
