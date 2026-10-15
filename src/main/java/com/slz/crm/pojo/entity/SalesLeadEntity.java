package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 销售线索实体类（add-lead-management-and-conversion-funnel 任务 1.2）。
 *
 * <p>销售漏斗最前端入口：记录线索姓名、公司名、联系方式、来源与状态，支持「一键转化」为客户公司/ 首要联系人及可选销售机会，转化后回填转化关联 ID 与转化时间。
 */
@Data
@AutoTable
@Table(value = "sales_lead", comment = "销售线索表")
@TableName("sales_lead")
@TableIndex(
    name = "idx_sales_lead_owner",
    fields = {"ownerId", "isDeleted"})
@TableIndex(
    name = "idx_sales_lead_status",
    fields = {"status", "isDeleted"})
@TableIndex(
    name = "idx_sales_lead_company",
    fields = {"companyName"})
public class SalesLeadEntity {

  /** 线索ID */
  @TableId(type = IdType.AUTO)
  @Column(comment = "线索ID")
  private Long id;

  /** 联系人姓名 */
  @Column(comment = "联系人姓名", type = "varchar(100)", notNull = true)
  private String name;

  /** 公司名称 */
  @Column(comment = "公司名称", type = "varchar(200)", notNull = true)
  private String companyName;

  /** 电话 */
  @Column(comment = "电话", type = "varchar(50)")
  private String phone;

  /** 邮箱 */
  @Column(comment = "邮箱", type = "varchar(100)")
  private String email;

  /** 线索来源 */
  @Column(comment = "线索来源", type = "varchar(50)")
  private String source;

  /** 线索状态（0待跟进/1跟进中/2已转化/3已关闭无效） */
  @Column(comment = "线索状态(0待跟进/1跟进中/2已转化/3已关闭无效)", type = "int", notNull = true, defaultValue = "0")
  private Integer status;

  /** 负责人ID */
  @Column(comment = "负责人ID", type = "bigint", notNull = true)
  private Long ownerId;

  /** 创建人ID */
  @Column(comment = "创建人ID", type = "bigint", notNull = true)
  private Long creatorId;

  /** 转化后客户公司ID */
  @Column(comment = "转化后客户公司ID", type = "bigint")
  private Long convertedCompanyId;

  /** 转化后联系人ID */
  @Column(comment = "转化后联系人ID", type = "bigint")
  private Long convertedContactId;

  /** 转化后商机ID */
  @Column(comment = "转化后商机ID", type = "bigint")
  private Long convertedOpportunityId;

  /** 转化时间 */
  @Column(comment = "转化时间", type = "datetime")
  private LocalDateTime convertedTime;

  /** 备注 */
  @Column(comment = "备注", type = "varchar(500)")
  private String remark;

  /** 逻辑删除（0正常/1已删除） */
  @Column(comment = "逻辑删除(0正常/1已删除)", type = "int", notNull = true, defaultValue = "0")
  private Integer isDeleted;

  /** 创建时间 */
  @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime createTime;

  /** 更新时间 */
  @Column(comment = "更新时间", type = "datetime", defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime updateTime;
}
