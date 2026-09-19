package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import java.time.LocalDate;
import java.time.LocalDateTime;
import lombok.Data;

/** 客户联系人备注实体类 */
@Data
@AutoTable
@Table(value = "customer_contact_remark", comment = "客户联系人备注表")
@TableName("customer_contact_remark")
@TableIndex(
    name = "idx_contact_id",
    fields = {"contactId"})
@TableIndex(
    name = "idx_remark_type",
    fields = {"remarkType"})
public class CustomerContactRemarkEntity {

  /** 备注 ID */
  @TableId(type = IdType.AUTO)
  @Column(comment = "备注 ID")
  private Long id;

  /** 联系人 ID */
  @TableField("contact_id")
  @Column(comment = "联系人 ID", type = "bigint", notNull = true)
  private Long contactId;

  /** 备注类型（1-喜好，2-住址，3-本人出生日期，4-亲属出生日期，5-自定义） */
  @TableField("remark_type")
  @Column(comment = "备注类型（1-喜好，2-住址，3-本人出生日期，4-亲属出生日期，5-自定义）", type = "tinyint", notNull = true)
  private Integer remarkType;

  /** 备注内容（喜好、住址、自定义类型可填） */
  @TableField("remark_content")
  @Column(comment = "备注内容（喜好、住址、自定义类型可填）", type = "varchar(500)")
  private String remarkContent;

  /** 备注姓名 */
  @TableField("remark_name")
  @Column(comment = "备注姓名", type = "varchar(50)")
  private String remarkName;

  /** 备注出生日期 */
  @TableField("remark_date")
  @Column(comment = "备注出生日期", type = "date")
  private LocalDate remarkDate;

  /** 创建人 ID */
  @TableField("creator_id")
  @Column(comment = "创建人 ID", type = "bigint", notNull = true)
  private Long creatorId;

  /** 创建时间 */
  @TableField("create_time")
  @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime createTime;

  /** 更新时间 */
  @TableField("update_time")
  @Column(comment = "更新时间", type = "datetime")
  private LocalDateTime updateTime;
}
