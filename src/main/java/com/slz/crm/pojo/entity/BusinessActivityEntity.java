package com.slz.crm.pojo.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

/**
 * 商业活动实体类
 */
@Data
@AutoTable
@Table(value = "business_activity", comment = "商业活动主表(存储活动核心信息)")
@TableName("business_activity")
@TableIndex(name = "idx_activity_company", fields = {"companyId"})
@TableIndex(name = "idx_activity_creator", fields = {"creatorId"})
public class BusinessActivityEntity {

    /**
     * 活动ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "商业活动ID，唯一标识")
    private Long id;

    /**
     * 活动标题
     */
    @Column(comment = "活动标题（如：XX公司产品演示会、客户需求沟通会）", type = "varchar(200)", notNull = true)
    private String activityTitle;

    /**
     * 活动类型
     */
    @Column(comment = "活动类型（电话沟通/邮件往来/线下拜访/线上会议/产品演示等）", type = "varchar(50)", notNull = true)
    private String activityType;

    /**
     * 活动内容
     */
    @Column(comment = "活动内容（详细描述：沟通要点、需求反馈、演示内容等）", type = "text")
    private String activityContent;

    /**
     * 活动时间
     */
    @Column(comment = "活动发生时间", type = "datetime", notNull = true)
    private LocalDateTime activityTime;

    /**
     * 活动持续时间
     */
    @Column(comment = "活动时长（单位：分钟，无时长则为NULL）", type = "int")
    private Integer activityDuration;

    /**
     * 客户公司ID
     */
    @Column(comment = "关联客户公司ID（可选，若活动绑定具体客户公司）", type = "bigint")
    private Long companyId;

    /**
     * 商机ID
     */
    @Column(comment = "关联销售机会ID（可选，若活动为跟进特定商机）", type = "bigint")
    private Long opportunityId;

    /**
     * 创建人ID
     */
    @Column(comment = "活动创建人ID（通常为活动发起人）", type = "bigint", notNull = true)
    private Long creatorId;

    /**
     * 创建时间
     */
    @Column(comment = "记录创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime createTime;

    /**
     * 活动备注
     */
    @Column(comment = "备注（如：活动效果、后续待办等）", type = "varchar(500)")
    private String remark;

    /**
     * 联络任务ID
     */
    @Column(comment = "关联联络任务ID", type = "int")
    private Long taskId;

}
