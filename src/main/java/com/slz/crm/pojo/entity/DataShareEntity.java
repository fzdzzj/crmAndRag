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

/** '数据共享表'; */
@Data
@AutoTable
@Table(value = "data_share", comment = "数据共享表")
@TableName("data_share")
@TableIndex(
    name = "uk_share_unique",
    fields = {"resourceId", "resourceType", "shareTo", "shareType"},
    type = IndexTypeEnum.UNIQUE)
public class DataShareEntity {
  /** 共享记录ID */
  @TableId(type = IdType.AUTO)
  @Column(comment = "共享记录ID")
  private Long id;

  /** 资源类型(表名,如customer_company,sales_opportunity等) */
  @Column(comment = "资源类型(表名)", type = "varchar(50)", notNull = true)
  private String resourceType;

  /** 资源ID(对应类型的记录ID) */
  @Column(comment = "资源ID(对应类型的记录ID)", type = "bigint", notNull = true)
  private Long resourceId;

  /** 共享人ID */
  @Column(comment = "共享人ID", type = "bigint", notNull = true)
  private Long shareFrom;

  /** 被共享人/角色ID */
  @Column(comment = "被共享人/角色ID", type = "bigint", notNull = true)
  private Long shareTo;

  /** 共享类型(1-用户,2-角色) */
  @Column(comment = "共享类型(1-用户,2-角色)", type = "tinyint", notNull = true)
  private Integer shareType;

  /** 过期时间(NULL表示永久) */
  @Column(comment = "过期时间（NULL表示永久）", type = "datetime")
  private LocalDateTime expireTime;

  /** 共享时间 */
  @Column(comment = "共享时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime createTime;

  /** 是否回收 */
  @Column(comment = "是否回收(0--正常,1--权限已回收)", type = "int")
  private Integer isReclaim;
}
