package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.autotable.annotation.enums.IndexTypeEnum;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 集团主数据实体类
 *
 * <p>客户归属集团的主数据来源，客户表仍保留 belong_group 文本字段，不强外键
 *
 * @author CRM Team
 */
@Data
@AutoTable
@Table(value = "company_group", comment = "集团主数据表")
@TableName("company_group")
@TableIndex(
    name = "uk_company_group_name",
    fields = {"groupName"},
    type = IndexTypeEnum.UNIQUE)
public class CompanyGroupEntity {

  /** 集团ID */
  @TableId(type = IdType.AUTO)
  @Column(comment = "集团ID")
  private Long id;

  /** 集团名称 */
  @Column(comment = "集团名称", type = "varchar(50)", notNull = true)
  private String groupName;

  /** 状态 */
  @Column(comment = "状态（1-启用，0-停用）", type = "tinyint", notNull = true, defaultValue = "1")
  private Integer status;

  /** 创建时间 */
  @Column(comment = "创建时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime createTime;

  /** 更新时间 */
  @Column(comment = "更新时间", type = "datetime")
  private LocalDateTime updateTime;
}
