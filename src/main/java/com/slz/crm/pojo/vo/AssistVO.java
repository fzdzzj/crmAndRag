package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 协助申请 VO
 */
@Data
public class AssistVO {
    /**
     * 协助记录ID
     */
    private Long id;
    /**
     * 模块名称（sales_stage_approval/business_activity）
     */
    private String modelName;
    /**
     * 关联业务记录ID
     */
    private Long recordId;
    /**
     * 申请人ID
     */
    private Long applicantId;
    /**
     * 协作目的（申请人填）
     */
    private String applyPurpose;
    /**
     * 协作要求（申请人填）
     */
    private String applyRequirement;
    /**
     * 申请人姓名
     */
    private String applicantName;
    /**
     * 协助人ID
     */
    private Long assistUserId;
    /**
     * 协助人姓名
     */
    private String assistUserName;
    /**
     * 协助人所属部门
     */
    private String assistUserDeptName;
    /**
     * 协助状态（0待协助/1已协助/2已驳回/3已拒绝）
     */
    private Integer assistStatus;
    /**
     * 协助内容/成果（协助人交付）
     */
    private String assistContent;
    /**
     * 驳回/拒绝理由
     */
    private String rejectReason;
    /**
     * 重新申请来源记录ID
     */
    private Long parentId;
    /**
     * 协助处理时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime assistTime;
    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime createTime;

    /**
     * 关联业务记录标题（审批：销售阶段推进审批；活动：活动标题；任务：任务标题）
     */
    private String recordTitle;

    /**
     * 关联业务记录内容摘要（审批：商机名称+审批备注；活动：活动内容；任务：任务内容）
     */
    private String recordContent;

    /**
     * 关联业务记录时间（审批：申请时间；活动：活动时间；任务：结束时间）
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime recordTime;

    /**
     * 关联商机ID（订单推进：跳转商机详情）
     */
    private Long opportunityId;

    /**
     * 关联商机名称
     */
    private String opportunityName;

    /**
     * 关联客户公司ID（实时详情跳转）
     */
    private Long companyId;

    /**
     * 关联客户公司名称
     */
    private String companyName;

    /**
     * 关联联系人ID（实时详情跳转）
     */
    private Long contactId;

    /**
     * 关联联系人名称
     */
    private String contactName;

    /**
     * 商机详情快照（JSON，仅详情接口返回；列表不返回）
     */
    private String snapshot;

    /**
     * 三类协助通用的终态业务快照（JSON，仅详情接口返回）
     */
    private String recordSnapshot;

    /**
     * 历史终态记录未生成快照时为 true，客户端不得回退读取实时业务数据。
     */
    private Boolean snapshotMissing;
}
