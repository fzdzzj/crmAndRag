package com.slz.crm.pojo.dto;

import lombok.Data;

/**
 * 协助处理 DTO
 */
@Data
public class AssistHandleDTO {
    /**
     * 协助记录ID
     */
    private Long id;
    /**
     * 协助状态（1已协助/2已驳回/3已拒绝）
     */
    private Integer assistStatus;
    /**
     * 协助内容/成果（状态=1 时必填）
     */
    private String assistContent;
    /**
     * 驳回/拒绝理由（状态=2/3 时必填）
     */
    private String rejectReason;
}
