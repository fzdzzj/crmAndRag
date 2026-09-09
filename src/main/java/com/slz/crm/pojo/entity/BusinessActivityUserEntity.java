package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.autotable.annotation.enums.IndexTypeEnum;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 商业活动与用户关联实体类
 */
@Data
@AutoTable
@Table(value = "business_activity_user", comment = "商业活动-员工关联表(记录活动参与的内部员工)")
@TableName("business_activity_user")
@TableIndex(name = "uk_activity_user_unique", fields = {"activityId", "userId"}, type = IndexTypeEnum.UNIQUE)
@TableIndex(name = "idx_act_user_activity", fields = {"activityId"})
@TableIndex(name = "idx_act_user_user", fields = {"userId"})
@TableIndex(name = "fk_act_user_creator", fields = {"creatorId"})
public class BusinessActivityUserEntity {

    /**
     * 关联ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "关联记录ID，唯一标识")
    private Long id;

    /**
     * 商业活动ID
     */
    @Column(comment = "关联商业活动ID", type = "bigint", notNull = true)
    private Long activityId;

    /**
     * 用户ID
     */
    @Column(comment = "关联公司员工ID（活动参与的内部员工）", type = "bigint", notNull = true)
    private Long userId;

    /**
     * 在活动中的角色类型
     */
    @Column(comment = "员工在活动中的角色（如：主持人/主讲人/记录人/陪同人）", type = "varchar(50)")
    private String userRole;

    /**
     * 创建人ID
     */
    @Column(comment = "关联记录创建人ID", type = "bigint", notNull = true)
    private Long creatorId;

    /**
     * 创建时间
     */
    @Column(comment = "记录创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime createTime;
}

