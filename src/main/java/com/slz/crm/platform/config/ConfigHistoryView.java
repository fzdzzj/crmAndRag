package com.slz.crm.platform.config;

import java.time.LocalDateTime;

/**
 * 动态配置版本历史视图（管理端查看/回滚定位用；旧值/新值已按敏感度掩码）。
 *
 * @param version       本次变更后的版本号（回滚目标 = 该版本行的 newValue）
 * @param operationType CREATE/UPDATE/ROLLBACK/DELETE/REVIVE
 * @param oldValue      变更前值（已掩码）
 * @param newValue      变更后值（已掩码；DELETE 为空）
 * @param operatorRef   操作人 user:&lt;id&gt;
 * @param remark        操作备注
 * @param createTime    操作时间
 */
public record ConfigHistoryView(
        Integer version,
        String operationType,
        String oldValue,
        String newValue,
        String operatorRef,
        String remark,
        LocalDateTime createTime) {
}
