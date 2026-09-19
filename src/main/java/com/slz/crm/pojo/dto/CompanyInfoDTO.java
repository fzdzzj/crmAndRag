package com.slz.crm.pojo.dto;

import lombok.Data;

/** 公司信息数据传输对象 */
@Data
public class CompanyInfoDTO {
  /** 公司名称 */
  private String companyName;

  /** 所属行业 */
  private String industry;

  /** 客户来源 */
  private String source;

  /** 公司地址 */
  private String address;

  /** 联系电话 */
  private String phone;

  /** 公司网站 */
  private String website;

  /** 公司描述 */
  private String description;

  /** 客户等级 */
  private Integer grade;

  /** 负责人ID */
  private Long ownerId;

  /** 公司ID */
  private Long id;

  /** 是否删除 */
  private Boolean isDeleted;
}
