package com.slz.crm.pojo.dto;

import lombok.Data;

/**
 * 部门新增/编辑 DTO
 */
@Data
public class SysDeptDTO {
    /**
     * 部门ID（编辑时必填）
     */
    private Long id;
    /**
     * 部门名称
     */
    private String deptName;
    /**
     * 上级部门ID（可空）
     */
    private Long parentId;
    /**
     * 排序
     */
    private Integer sort;
    /**
     * 状态（1启用/0停用）
     */
    private Integer status;
}
