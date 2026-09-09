package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 销售机会实体类
 */
@Data
@AutoTable
@Table(value = "sales_opportunity", comment = "销售机会表")
@TableName("sales_opportunity")
@TableIndex(name = "idx_opportunity_owner", fields = {"ownerId"})
@TableIndex(name = "idx_opportunity_stage", fields = {"stage"})
public class SalesOpportunityEntity {
    /**
     * 销售机会ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "销售机会ID")
    private Long id;
    /**
     * 销售机会名称
     */
    @TableField("opportunity_name")
    @Column(comment = "机会名称（如：XX公司年度服务采购）", type = "varchar(100)", notNull = true)
    private String opportunityName;
    /**
     * 公司ID
     */
    @TableField("company_id")
    @Column(comment = "关联客户公司ID", type = "bigint", notNull = true)
    private Long companyId;
    /**
     * 联系人ID
     */
    @TableField("contact_id")
    @Column(comment = "主要联系人ID(核心对接人)", type = "bigint")
    private Long contactId;
    /**
     * 销售机会阶段
     */
    @TableField("stage")
    @Column(comment = "当前阶段（0种子/1潜在商机/2确认商机/3储备项目/4立项签约/5关闭）", type = "int", notNull = true)
    private Integer stage;
    /**
     * 销售机会预计金额
     */
    @TableField("amount")
    @Column(comment = "预计金额（预估成交金额）", type = "decimal(15,2)")
    private BigDecimal amount;
    /**
     * 销售机会预计关闭日期
     */
    @TableField("expected_close_date")
    @Column(comment = "预计成交日期", type = "datetime")
    private LocalDateTime expectedCloseDate;
    /**
     * 销售机会来源
     */
    @TableField("source")
    @Column(comment = "机会来源", type = "varchar(50)")
    private String source;
    /**
     * 销售机会描述
     */
    @TableField("description")
    @Column(comment = "机会描述（如：客户需求、跟进要点）", type = "text")
    private String description;
    /**
     * 销售机会负责人ID
     */
    @TableField("owner_id")
    @Column(comment = "负责人ID（跟进销售）", type = "bigint", notNull = true)
    private Long ownerId;
    /**
     * 销售机会创建人ID
     */
    @TableField("creator_id")
    @Column(comment = "创建人ID", type = "bigint", notNull = true)
    private Long creatorId;
    /**
     * 销售机会创建时间
     */
    @TableField("create_time")
    @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime createTime;
    /**
     * 销售机会更新时间
     */
    @TableField("update_time")
    @Column(comment = "最后更新时间", type = "datetime")
    private LocalDateTime updateTime;
    /**
     * 审批人ID
     */
    @TableField("approver_id")
    @Column(comment = "审批人ID", type = "bigint", notNull = true)
    private Long approverId;
    /**
     * 是否删除(0-正常,1-删除)
     */
    @TableField("is_deleted")
    @Column(comment = "是否删除", type = "bit", notNull = true, defaultValue = "0")
    private Boolean isDeleted;

}
