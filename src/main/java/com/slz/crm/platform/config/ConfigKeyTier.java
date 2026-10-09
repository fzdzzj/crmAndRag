package com.slz.crm.platform.config;

/**
 * 动态配置键权限档位（add-dynamic-config-key-tier-acl 任务 1.3）。
 *
 * <p>企业三档分层的键级 ACL 落位：OPERATIONAL（运营调参，608 持有者可写）、COST（成本开关，超管专写）、 STRUCTURAL（变更管理，超管专写）。定级封闭表见
 * {@link ConfigKeyTierPolicy}，改档需 owner 拍板并同步 docs/dynamic-config-keys.md 的「权限档位」列与 tier ACL 专节。
 */
public enum ConfigKeyTier {
  /** 运营调参档：608 持有者可写（方法级注解强制），超管直通 */
  OPERATIONAL,
  /** 成本开关档：超管专写（服务层 96005 闸，语义与升级前一致） */
  COST,
  /** 变更管理档：超管专写（服务层 96005 闸，语义与升级前一致） */
  STRUCTURAL
}
