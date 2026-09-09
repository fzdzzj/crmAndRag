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
 * 商业活动与联系人关联实体类
 */
@Data
@AutoTable
@Table(value = "business_activity_contact", comment = "商业活动-联系人关联表(记录活动涉及的外部联系人)")
@TableName("business_activity_contact")
@TableIndex(name = "uk_activity_contact_unique", fields = {"activityId", "contactId"}, type = IndexTypeEnum.UNIQUE)
@TableIndex(name = "idx_act_contact_activity", fields = {"activityId"})
@TableIndex(name = "idx_act_contact_contact", fields = {"contactId"})
@TableIndex(name = "fk_act_contact_creator", fields = {"creatorId"})
public class BusinessActivityContactEntity {

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
     * 联系人ID
     */
    @Column(comment = "关联客户联系人ID（活动涉及的外部联系人）", type = "bigint", notNull = true)
    private Long contactId;

    /**
     * 在活动中的角色类型
     */
    @Column(comment = "联系人在活动中的角色（如：决策者/技术对接人/参会人）", type = "varchar(50)")
    private String contactRole;

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
