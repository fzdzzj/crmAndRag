package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import java.time.LocalDateTime;
import lombok.Data;

/** '日志'; */
@Data
@AutoTable
@Table(value = "customer_company_log", comment = "日志")
@TableName("customer_company_log")
public class CustomerCompanyLogEntity {
  /** 日志ID */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 操作 */
  @Column(comment = "操作（增删改查）", type = "varchar(20)")
  private String operation;

  /** 操作人ID */
  @Column(comment = "操作人ID", type = "bigint", notNull = true)
  private Long creatorId;

  /** 操作时间 */
  @Column(comment = "操作时间", type = "datetime", notNull = true, defaultValue = "now()")
  private LocalDateTime createTime;

  /** SQL语句 */
  @Column(comment = "`sql`", type = "text", notNull = true)
  private String sql;

  /** 操作表单 */
  @Column(comment = "操作表单", type = "varchar(20)", notNull = true)
  private String form;
}
