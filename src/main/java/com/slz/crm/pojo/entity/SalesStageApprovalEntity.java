package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * '销售阶段变更审批表';
 */
@Data
@AutoTable
@Table(value = "sales_stage_approval", comment = "销售阶段变更审批表")
@TableName("sales_stage_approval")
@TableIndex(name = "idx_approval_opportunity", fields = {"opportunityId"})
@TableIndex(name = "idx_approval_status", fields = {"approvalStatus"})
public class SalesStageApprovalEntity {
    /**
     * 审批记录ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "审批记录ID")
    private Long id;
    /**
     * 关联销售机会ID
     */
    @Column(comment = "关联销售机会ID", type = "bigint", notNull = true)
    private Long opportunityId;
    /**
     * 当前阶段(变更前的阶段)
     */
    @Column(comment = "当前阶段（变更前的阶段）", type = "varchar(30)", notNull = true)
    private Integer currentStage;
    /**
     * 目标阶段(申请变更到的阶段)
     */
    @Column(comment = "目标阶段（申请变更到的阶段）", type = "varchar(30)", notNull = true)
    private Integer targetStage;
    /**
     * 申请人ID(发起阶段变更的销售)
     */
    @Column(comment = "申请人ID（发起阶段变更的销售）", type = "bigint", notNull = true)
    private Long applicantId;
    /**
     * 审批人ID(上级或指定审批人)
     */
    @Column(comment = "审批人ID（上级或指定审批人）", type = "bigint", notNull = true)
    private Long approverId;
    /**
     * 审批状态(0待审批/1同意/2拒绝/3退回修改)
     */
    @Column(comment = "审批状态（0待审批/1同意/2拒绝/3退回修改）", type = "int", notNull = true)
    private Integer approvalStatus;
    /** 是否已正式提交审批；false 表示仅保存草稿，但协助申请已发出。 */
    @Column(comment = "是否已正式提交审批", type = "tinyint(1)", defaultValue = "1")
    private Boolean approvalTriggered;
    /**
     * 审批意见(审批人的反馈)
     */
    @Column(comment = "审批意见（审批人的反馈）", type = "text")
    private String approvalOpinion;
    /**
     * 申请时间
     */
    @Column(comment = "申请时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime applyTime;
    /**
     * 审批完成时间
     */
    @Column(comment = "审批完成时间", type = "datetime")
    private LocalDateTime approvalTime;

    /**
     * 审批备注
     */
    @Column(comment = "审批备注", type = "text")
    private String message;

    /**
     * 乐观锁版本号：防多端/多会话并发修改草稿时静默覆盖，
     * 配合 OptimisticLockerInnerInterceptor 使用；冲突时 update 影响行数为 0。
     */
    @Version
    @Column(comment = "乐观锁版本号", type = "int", notNull = true, defaultValue = "0")
    private Integer version;
}
