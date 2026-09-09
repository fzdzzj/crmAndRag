package com.slz.crm.pojo.dto;

import lombok.Data;

/**
 * 销售阶段审批分页查询数据传输对象
 */
@Data
public class SalesStageApprovalPageDTO {
    /**
     * 销售阶段审批DTO
     */
    private SalesStageApprovalDTO dto;
    /**
     * 页码
     */
    private Integer pageNum;
    /**
     * 每页条数
     */
    private Integer pageSize;
}
