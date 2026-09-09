package com.slz.crm.pojo.dto;

import com.tangzc.mpe.autotable.annotation.Column;
import lombok.Data;

/**
 * 客户公司数据传输对象
 */
@Data
public class CustomerCompanyDTO {
    /**
     * 公司名称
     */
    private String companyName;
    /**
     * 所属行业
     */
    private String industry;
    /**
     * 客户属性（代理/直销）
     */
    private String customerType;
    /**
     * 归属集团
     */
    private String belongGroup;
    /**
     * 部门
     */
    private String dept;
    /**
     * 公司地址
     */
    private String address;
    /**
     * 联系电话
     */
    private String phone;
    /**
     * 公司网站
     */
    private String website;
    /**
     * 公司描述
     */
    private String description;
    /**
     * 客户等级（0-9，0 最低，9 最高）
     */
    private Integer grade;
    /**
     * 负责人ID
     */
    private Long ownerId;
    /**
     * 公司ID
     */
    private Long id;
    /**
     * 是否删除
     */
    private Boolean isDeleted;
}
