package com.slz.crm.pojo.dto;

import lombok.Data;

/**
 * 客户联系人组合查询传输对象
 */
@Data
public class CustomerContactQueryDTO {
    /**
     * 联系人 ID
     */
    private Long id;
    /**
     * 公司 ID
     */
    private Long companyId;
    /**
     * 联系人姓名
     */
    private String name;
    /**
     * 职位
     */
    private String position;
    /**
     * 部门
     */
    private String dept;
    /**
     * 固定电话
     */
    private String phone;
    /**
     * 手机号码
     */
    private String mobile;
    /**
     * 邮箱地址
     */
    private String email;
    /**
     * 性别 0：女 1：男
     */
    private Integer gender;
    /**
     * 客户关系等级（1-9，9 为最紧密）
     */
    private Integer relationLevel;
    /**
     * 是否删除
     */
    private Boolean isDeleted;
}
