package com.slz.crm.platform.reconcile;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 跨存储对账报告。 */
@Data
@TableName("platform_reconcile_report")
public class PlatformReconcileReportEntity {

  /** 报告主键。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 报告业务 ID。 */
  private String reportId;

  /** 扫描类型。 */
  private String scanType;

  /** 是否 dry-run。 */
  private boolean dryRun;

  /** 状态。 */
  private String status;

  /** 差异数量。 */
  @TableField("total_differences")
  private Integer totalDifferences;

  /** 开始时间。 */
  private LocalDateTime startedTime;

  /** 完成时间。 */
  private LocalDateTime completedTime;

  /** 清理保留截止时间。 */
  private LocalDateTime retainedUntil;

  /** 操作人。 */
  private String operatorUserRef;

  /** 创建时间。 */
  private LocalDateTime createTime;

  /** 更新时间。 */
  private LocalDateTime updateTime;
}
