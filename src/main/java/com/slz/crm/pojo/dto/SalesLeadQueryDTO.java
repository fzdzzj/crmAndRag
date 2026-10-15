package com.slz.crm.pojo.dto;

import java.time.LocalDateTime;
import lombok.Data;
import org.springframework.format.annotation.DateTimeFormat;

/** 销售线索分页查询条件数据传输对象（add-lead-management-and-conversion-funnel 任务 2.1）。 */
@Data
public class SalesLeadQueryDTO {
  /** 联系人姓名（模糊） */
  private String name;

  /** 公司名称（模糊） */
  private String companyName;

  /** 电话（模糊） */
  private String phone;

  /** 线索来源 */
  private String source;

  /** 线索状态（0待跟进/1跟进中/2已转化/3已关闭无效） */
  private Integer status;

  /** 负责人ID */
  private Long ownerId;

  /** 最小创建时间 */
  @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
  private LocalDateTime minCreateTime;

  /** 最大创建时间 */
  @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss")
  private LocalDateTime maxCreateTime;
}
