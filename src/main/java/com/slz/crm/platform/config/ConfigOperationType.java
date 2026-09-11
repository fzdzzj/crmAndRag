package com.slz.crm.platform.config;

/**
 * 配置变更操作类型（写入版本历史与审计事件）。
 */
public enum ConfigOperationType {

    /** 首次创建（version=1） */
    CREATE,
    /** 值更新 */
    UPDATE,
    /** 回滚到历史版本（自身也 +1 版本） */
    ROLLBACK,
    /** 软删除 = 恢复静态默认 */
    DELETE,
    /** 软删后重新写入 = 复活（唯一键约束下不新建行） */
    REVIVE
}
