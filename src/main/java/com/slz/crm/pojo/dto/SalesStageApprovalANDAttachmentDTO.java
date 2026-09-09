package com.slz.crm.pojo.dto;

import com.slz.crm.pojo.vo.AddSalesStageApprovalDTO;
import lombok.Data;

import java.util.List;

/**
 * 销售阶段审批与附件关联数据传输对象
 */
@Data
public class SalesStageApprovalANDAttachmentDTO {

    /** * 销售阶段审批信息 */
    private AddSalesStageApprovalDTO salesStageApproval;
    /** * 审批附件列表 */
    private List<ApprovalAttachmentDTO> approvalAttachment;

}
