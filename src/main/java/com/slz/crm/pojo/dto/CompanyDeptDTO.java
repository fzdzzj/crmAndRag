package com.slz.crm.pojo.dto;

import lombok.Data;

/**
 * 部门主数据新增 DTO
 *
 * @author CRM Team
 */
@Data
public class CompanyDeptDTO {
    /**
     * 部门ID（编辑时必填）
     */
    private Long id;
    /**
     * 所属集团ID
     */
    private Long groupId;
    /**
     * 部门名称
     */
    private String deptName;
}
