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

/** 客户公海流转记录实体（add-customer-public-sea-pool 任务 1.3，映射 customer_pool_log 表）。 */
@Data
@AutoTable
@Table(value = "customer_pool_log", comment = "客户公海流转记录表（全生命周期流转时间线）")
@TableName("customer_pool_log")
@TableIndex(
    name = "idx_pool_log_company",
    fields = {"companyId"})
public class CustomerPoolLogEntity {

  /** 流转记录主键 ID */
  @TableId(type = IdType.AUTO)
  @Column(comment = "流转记录主键 ID", type = "bigint")
  private Long id;

  /** 关联客户公司 ID */
  @Column(comment = "关联客户公司 ID", type = "bigint", notNull = true)
  private Long companyId;

  /** 操作人 ID（系统自动回收记录为 0） */
  @Column(comment = "操作人 ID（系统自动回收记录为 0）", type = "bigint", notNull = true)
  private Long operatorId;

  /** 流转动作：CLAIM-认领 / RELEASE-主动退回 / ASSIGN-主管分派 / AUTO_RECYCLE-系统超时自动回收 */
  @Column(comment = "流转动作：CLAIM/RELEASE/ASSIGN/AUTO_RECYCLE", type = "varchar(32)", notNull = true)
  private String actionType;

  /** 原负责人 ID */
  @Column(comment = "原负责人 ID", type = "bigint")
  private Long previousOwnerId;

  /** 新负责人 ID */
  @Column(comment = "新负责人 ID", type = "bigint")
  private Long newOwnerId;

  /** 流转原因或备注说明 */
  @Column(comment = "流转原因或备注说明", type = "varchar(255)")
  private String reason;

  /** 记录时间 */
  @Column(comment = "记录时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
  private LocalDateTime createTime;
}
