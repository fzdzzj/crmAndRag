package com.slz.crm.platform.config.service;

import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.platform.config.ConfigKeyDefinition;
import com.slz.crm.platform.config.ConfigKeyTier;
import com.slz.crm.platform.config.ConfigKeyTierPolicy;
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

  /**
   * 写路径键级 ACL 守卫（add-dynamic-config-key-tier-acl 任务 2.1）：身份 → 键存在 → 档位闸。
   *
   * <p>OPERATIONAL 档放行（608 由方法级 {@code @RequirePermission} 在拦截器层强制）； COST/STRUCTURAL
   * 档仅超管可写（FORBIDDEN 96005，语义与升级前超管专写一致）。 守卫顺序契约：未登录(96003) → 未知键(96007) → 档位闸(96005)。
   *
   * @return 一次解析复用的操作者 + 键定义（避免调用方双重查找）
   */
  static WriteAccess requireKeyWriteAccess(
      CurrentUserResolver userResolver, DynamicConfigKeyRegistry registry, String key) {
    ConfigOperator op = userResolver.resolve();
    if (op == null) {
      throw new ServiceException(
          PlatformErrorCode.UNAUTHORIZED.getCode(), PlatformErrorCode.UNAUTHORIZED.getMessage());
    }
    ConfigKeyDefinition def = requireDefinition(registry, key);
    if (ConfigKeyTierPolicy.tierOf(def) != ConfigKeyTier.OPERATIONAL && !op.isSuperAdmin()) {
      throw new ServiceException(
          PlatformErrorCode.FORBIDDEN.getCode(), PlatformErrorCode.FORBIDDEN.getMessage());
    }
    return new WriteAccess(op, def);
  }

  /** 写路径键级 ACL 检查结果（操作者 + 键定义一次解析复用）。 */
  record WriteAccess(ConfigOperator operator, ConfigKeyDefinition definition) {}

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
