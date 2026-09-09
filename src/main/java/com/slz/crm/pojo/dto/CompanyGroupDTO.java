package com.slz.crm.pojo.dto;

import lombok.Data;

/**
 * 集团主数据新增 DTO
 *
 * @author CRM Team
 */
@Data
public class CompanyGroupDTO {
    /**
     * 集团ID（编辑时必填）
     */
    private Long id;
    /**
     * 集团名称
     */
    private String groupName;
}
