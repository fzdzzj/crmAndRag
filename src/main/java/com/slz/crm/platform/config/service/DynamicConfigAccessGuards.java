package com.slz.crm.platform.config.service;

import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.platform.config.ConfigKeyDefinition;
import com.slz.crm.platform.config.ConfigOperator;
import com.slz.crm.platform.config.CurrentUserResolver;
import com.slz.crm.platform.config.DynamicConfigKeyRegistry;
import com.slz.crm.platform.config.entity.DynamicConfigItemEntity;
import com.slz.crm.platform.config.mapper.DynamicConfigItemMapper;
import com.slz.crm.platform.contract.PlatformErrorCode;

/**
 * 动态配置管理的越权/存在性守卫支持类（tighten-pmd-residual-325 任务 6.3 拆自
 * DynamicConfigAdminService，行为等价）。纯静态、无状态，依赖经参数传入。
 */
final class DynamicConfigAccessGuards {

  private DynamicConfigAccessGuards() {}

  /** 仅超管（roleId=1）可进入管理路径；未登录抛 UNAUTHORIZED，非超管抛 FORBIDDEN */
  static ConfigOperator requireSuperAdmin(CurrentUserResolver userResolver) {
    ConfigOperator op = userResolver.resolve();
    if (op == null) {
      throw new ServiceException(
          PlatformErrorCode.UNAUTHORIZED.getCode(), PlatformErrorCode.UNAUTHORIZED.getMessage());
    }
    if (!op.isSuperAdmin()) {
      throw new ServiceException(
          PlatformErrorCode.FORBIDDEN.getCode(), PlatformErrorCode.FORBIDDEN.getMessage());
    }
    return op;
  }

  /** 键必须已注册，否则抛 VALIDATION */
  static ConfigKeyDefinition requireDefinition(DynamicConfigKeyRegistry registry, String key) {
    return registry
        .definitionOf(key)
        .orElseThrow(
            () -> new ServiceException(PlatformErrorCode.VALIDATION.getCode(), "未知配置键：" + key));
  }

  /** 按键查任意行（含软删行；唯一键保证至多一行） */
  static DynamicConfigItemEntity findByKeyAny(DynamicConfigItemMapper itemMapper, String key) {
    return itemMapper.selectOne(
        new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<
                DynamicConfigItemEntity>()
            .eq(DynamicConfigItemEntity::getConfigKey, key));
  }
}
