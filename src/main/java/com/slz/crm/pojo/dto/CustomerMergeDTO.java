package com.slz.crm.pojo.dto;

import lombok.Data;

/**
 * 客户合并数据传输对象
 */
@Data
public class CustomerMergeDTO {
    /** * 合并记录ID */
    private Long id;
    /** * 目标公司ID（保留的公司） */
    private Long targetCompanyId;
    /** * 被合并公司ID（要合并的公司） */
    private Long mergedCompanyId;
    /** * 合并备注说明 */
    private String remark;
}
