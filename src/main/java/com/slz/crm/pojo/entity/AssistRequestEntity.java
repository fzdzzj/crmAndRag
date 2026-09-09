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
 * 协助申请记录表（跨模块通用：销售阶段推进审批、商业活动等）
 */
@Data
@AutoTable
@Table(value = "assist_request", comment = "协助申请记录表")
@TableName("assist_request")
@TableIndex(name = "idx_assist_model_record", fields = {"modelName", "recordId"})
@TableIndex(name = "idx_assist_user_status", fields = {"assistUserId", "assistStatus"})
@TableIndex(name = "idx_assist_applicant", fields = {"applicantId", "createTime"})
@TableIndex(name = "uk_assist_pending", fields = {"modelName", "recordId", "assistUserId", "pendingKey"}, type = IndexTypeEnum.UNIQUE)
public class AssistRequestEntity {
    /**
     * 协助记录ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "协助记录ID")
    private Long id;
    /**
     * 模块名称（sales_stage_approval/business_activity）
     */
    @Column(comment = "模块名称（sales_stage_approval/business_activity）", type = "varchar(50)", notNull = true)
    private String modelName;
    /**
     * 关联业务记录ID（审批记录ID/活动ID）
     */
    @Column(comment = "关联业务记录ID", type = "bigint", notNull = true)
    private Long recordId;
    /**
     * 申请人ID
     */
    @Column(comment = "申请人ID", type = "bigint", notNull = true)
    private Long applicantId;
    /**
     * 协作目的（申请人填）
     */
    @Column(comment = "协作目的（申请人填）", type = "varchar(200)")
    private String applyPurpose;
    /**
     * 协作要求（申请人填，具体要对方做什么）
     */
    @Column(comment = "协作要求（申请人填，具体要对方做什么）", type = "text")
    private String applyRequirement;
    /**
     * 协助人ID
     */
    @Column(comment = "协助人ID", type = "bigint", notNull = true)
    private Long assistUserId;
    /**
     * 协助状态（0待协助/1已协助/2已驳回/3已拒绝/4已取消）
     */
    @Column(comment = "协助状态（0待协助/1已协助/2已驳回/3已拒绝/4已取消）", type = "int", notNull = true, defaultValue = "0")
    private Integer assistStatus;
    /**
     * 协助内容/成果（协助人交付）
     */
    @Column(comment = "协助内容/成果（协助人交付）", type = "text")
    private String assistContent;
    /**
     * 驳回/拒绝理由（协助人填）
     */
    @Column(comment = "驳回/拒绝理由（协助人填）", type = "text")
    private String rejectReason;
    /**
     * 业务终结导致协助取消时的系统原因
     */
    @Column(comment = "业务终结取消原因", type = "text")
    private String cancelReason;
    /**
     * 重新申请来源记录ID（首次为空；驳回后重新申请指向原记录）
     */
    @Column(comment = "重新申请来源记录ID", type = "bigint")
    private Long parentId;
    /**
     * 仅待协助记录写入稳定值 PENDING；终态清空，以解决 MySQL 可空唯一索引不约束多条 NULL 的问题。
     */
    @Column(comment = "待协助唯一键", type = "varchar(16)")
    private String pendingKey;
    /**
     * 旧审批专用商机详情快照（历史兼容列；新流程不再写入或读取）
     */
    @Deprecated
    @Column(comment = "商机详情快照（处理完成时冻结，JSON）", type = "text")
    private String opportunitySnapshot;
    /**
     * 协助终态业务快照（活动/任务/审批通用，JSON）
     */
    @Column(comment = "协助终态业务快照（JSON）", type = "text")
    private String recordSnapshot;
    /**
     * 协助处理时间
     */
    @Column(comment = "协助处理时间", type = "datetime")
    private LocalDateTime assistTime;
    /**
     * 创建时间
     */
    @Column(comment = "记录创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime createTime;
}
