package com.slz.crm.pojo.vo;

import java.time.LocalDate;
import lombok.Data;

/**
 * 未来 N 天生日清单 VO（add-source-citation-and-dashboard 任务 3.1）：一个联系人的某条生日备注（本人或亲属）对应一条记录， 镜像 {@link
 * com.slz.crm.pojo.dto.BirthdayInfoDTO} 语义并补充联系人 id/name 与出生年。
 */
@Data
public class ContactBirthdayVO {

  /** 联系人 ID */
  private Long contactId;

  /** 联系人姓名 */
  private String contactName;

  /** 是否为本人（true-本人，false-亲属） */
  private Boolean isSelf;

  /** 过生日的人姓名（本人取联系人姓名，亲属取备注姓名） */
  private String name;

  /** 出生日期 */
  private LocalDate birthday;

  /** 出生年份（由出生日期提取） */
  private Integer birthdayYear;

  /** 距离生日还有几天 */
  private Integer daysUntil;

  /** 即将过生日的日期（今年/明年） */
  private LocalDate upcomingBirthday;
}
