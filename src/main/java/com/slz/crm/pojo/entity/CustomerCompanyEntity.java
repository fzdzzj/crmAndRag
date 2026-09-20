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
 * 客户公司实体类
 *
 * @author Y
 */
@Data
@AutoTable
@Table(value = "customer_company", comment = "客户公司表")
@TableName("customer_company")
@TableIndex(
    name = "idx_industry_status",
    fields = {"industry", "status"})
@TableIndex(
    name = "idx_owner",
    fields = {"ownerId"})
public class CustomerCompanyEntity {

  /** 客户公司ID */
  @TableId(type = IdType.AUTO)
  @Column(comment = "客户唯一标识ID")
  private Long id;

  /** 客户公司名称 */
  @Column(comment = "公司名称（客户主体名称）", type = "varchar(100)", notNull = true)
  private String companyName;

  /** 客户公司行业 */
  @Column(comment = "所属行业（用于客户分类）", type = "varchar(50)")
  private String industry;

  /** 客户状态 */
  @Column(comment = "客户状态（用于标识客户当前阶段）", type = "varchar(20)")
  private String status;

  /** 客户公司地址 */
  @Column(comment = "公司地址", type = "varchar(200)")
  private String address;

  /** 客户公司联系电话 */
  @Column(comment = "公司固定电话", type = "varchar(20)")
  private String phone;

  /** 客户公司官网 */
  @Column(comment = "公司官方网站", type = "varchar(100)")
  private String website;

  /** 客户公司描述 */
  @Column(comment = "公司描述（补充说明信息）", type = "text")
  private String description;

  /** 客户公司创建人ID */
  @Column(comment = "创建人ID（记录数据创建者）", type = "bigint", notNull = true)
  private Long creatorId;

  /** 客户公司负责人ID */
  @Column(comment = "负责人ID（当前跟进的销售人员）", type = "bigint")
  private Long ownerId;

  /** 客户公司是否删除 */
  @Column(comment = "是否删除（0-正常，1-回收站）", type = "tinyint", notNull = true, defaultValue = "0")
  private Boolean isDeleted;

  /** 客户公司创建时间 */
  @Column(comment = "记录创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime createTime;

  /** 客户公司更新时间 */
  @Column(comment = "记录最后更新时间", type = "datetime")
  private LocalDateTime updateTime;

  /** 客户公司等级（0-9，0 最低，9 最高） */
  @Column(comment = "等级0最低", type = "int", notNull = true, defaultValue = "0")
  private Integer grade;

  /** 客户属性 */
  @Column(comment = "客户属性（代理/直销）", type = "varchar(20)")
  private String customerType;

  /** 归属集团 */
  @Column(comment = "归属集团", type = "varchar(50)")
  private String belongGroup;

  /** 部门 */
  @Column(comment = "部门（客户具体到部门级别）", type = "varchar(50)")
  private String dept;

  /** 客户来源（V28 补列，客户来源分布图表的数据源；getter/setter 由类级 @Data 生成） */
  @Column(comment = "客户来源", type = "varchar(50)")
  private String source;
}
