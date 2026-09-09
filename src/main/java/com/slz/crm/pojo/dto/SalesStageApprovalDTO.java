package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 销售机会阶段审批DTO
 */
@Data
public class SalesStageApprovalDTO {

    /**
     * 审批记录ID
     */
    private Long id;
    /**
     * 关联销售机会ID
     */
    private Long opportunityId;
    /**
     * 关联销售机会名称
     */
    private String opportunityName;
    /**
     * 当前阶段（变更前的阶段）
     */
    private Integer currentStage;
    /**
     * 目标阶段（申请变更到的阶段）
     */
    private Integer targetStage;
    /**
     * 审批人ID（上级或指定审批人）
     */
    private Long approverId;
    /**
     * 审批人名称
     */
    private String approverName;
    /**
     * 审批状态（0待审批/1同意/2拒绝/3退回修改）
     */
    private List<Integer> approvalStatus;
    /**
     * 审批意见（审批人的反馈）
     */
    private String approvalOpinion;
    /**
     * 审批备注
     */
    private String message;
    /**
     * 申请时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime applyTime;

    /**
     * 协助人ID（查询过滤：只看我是协助人的记录）
     */
    private Long assistUserId;

}
