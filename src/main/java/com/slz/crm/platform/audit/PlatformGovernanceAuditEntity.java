package com.slz.crm.platform.audit;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 平台治理审计持久化实体。
 */
@Data
@TableName("platform_governance_audit")
public class PlatformGovernanceAuditEntity {

    /** 审计主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 事件类型。 */
    private String eventType;

    /** 操作人。 */
    private String actorUserRef;

    /** 目标类型。 */
    private String targetType;

    /** 目标 ID。 */
    private String targetId;

    /** 动作。 */
    private String action;

    /** 结果。 */
    private String result;

    /** 扩展数据。 */
    private String detail;

    /** 链路 ID。 */
    private String traceId;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;
}
