package com.slz.crm.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDate;
import lombok.Data;

/** 客户联系人备注 DTO */
@Data
public class CustomerContactRemarkDTO {

  /** 备注 ID（更新时使用） */
  private Long id;

  /** 联系人 ID */
  private Long contactId;

  /** 备注类型（1-喜好，2-住址，3-本人出生日期，4-亲属出生日期，5-自定义） */
  private Integer remarkType;

  /** 备注内容（喜好、住址、自定义类型可填） */
  private String remarkContent;

  /** 备注姓名 */
  private String remarkName;

  /** 备注出生日期 */
  @JsonFormat(pattern = "yyyy-MM-dd", timezone = "GMT+8")
  private LocalDate remarkDate;
}
