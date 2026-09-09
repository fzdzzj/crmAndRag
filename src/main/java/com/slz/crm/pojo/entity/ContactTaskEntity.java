package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;
import java.util.Date;

/**
 * 联络任务表
 */
@Data
@AutoTable
@Table(value = "contact_task", comment = "联络任务表")
@TableName("contact_task")
@TableIndex(name = "idx_task_assignee", fields = {"assigneeId"})
@TableIndex(name = "idx_task_status", fields = {"status"})
@TableIndex(name = "fk_task_assigner", fields = {"assignerId"})
@TableIndex(name = "fk_task_creator", fields = {"creatorId"})
public class ContactTaskEntity {
    /**
     * 任务ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "任务ID")
    private Long id;

    /**
     * 任务标题
     */
    @Column(comment = "任务标题", type = "varchar(200)", notNull = true)
    private String taskTitle;

    /**
     * 关联客户公司ID(可选)
     */
    @Column(comment = "关联客户公司ID（可选）", type = "bigint")
    private Long companyId;

    /**
     * 关联联系人ID(可选)
     */
    @Column(comment = "关联联系人ID（可选）", type = "bigint")
    private Long contactId;

    /**
     * 关联销售机会ID(可选)
     */
    @Column(comment = "关联销售机会ID（可选）", type = "bigint")
    private Long opportunityId;

    /**
     * 任务类型(电话/邮件/拜访等)
     */
    @Column(comment = "任务类型（电话/邮件/拜访等）", type = "varchar(50)", notNull = true)
    private String taskType;

    /**
     * 任务内容(详细描述)
     */
    @Column(comment = "任务内容(详细描述)", type = "text")
    private String taskContent;

    /**
     * 开始时间
     */
    @Column(comment = "开始时间", type = "datetime")
    private Date startTime;

    /**
     * 结束时间
     */
    @Column(comment = "结束时间", type = "datetime")
    private Date endTime;

    /**
     * 优先级(0-9低到高)
     */
    @Column(comment = "优先级(0-9低到高)", type = "int", notNull = true)
    private Integer priority;

    /**
     * 状态(0未开始/1进行中/2已完成/3已取消)
     */
    @Column(comment = "状态(0未开始/1进行中/2已完成/3已取消)", type = "int", notNull = true, defaultValue = "0")
    private Integer status;

    /**
     * 任务执行人ID
     */
    @Column(comment = "任务执行人ID", type = "bigint", notNull = true)
    private Long assigneeId;

    /**
     * 指派人ID(分配任务的人)
     */
    @Column(comment = "任务分配人ID", type = "bigint")
    private Long assignerId;

    /**
     * 创建人ID
     */
    @Column(comment = "创建人ID", type = "bigint", notNull = true)
    private Long creatorId;

    /**
     * 创建时间
     */
    @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private Date createTime;

    /**
     * 最后更新时间
     */
    @Column(comment = "最后更新时间", type = "datetime")
    private Date updateTime;
}
