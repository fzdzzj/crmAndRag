package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class UpdateSalesStageApprovalDTO {
    /**
     * 审批记录ID
     */
    private Long id;
    /**
     * 审批状态（0待审批/1同意/2拒绝/3退回修改）
     */
    private Integer approvalStatus;
    /**
     * 审批意见（审批人的反馈）
     */
    private String approvalOpinion;
    /**
     * 申请时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime applyTime;
}
