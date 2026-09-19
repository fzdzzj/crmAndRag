package com.slz.crm.platform.reconcile;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 跨存储对账差异明细。 */
@Data
@TableName("platform_reconcile_item")
public class PlatformReconcileItemEntity {

  /** 明细主键。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 报告业务 ID。 */
  private String reportId;

  /** 存储类型。 */
  private String storageType;

  /** 资源业务 ID。 */
  private String resourceId;

  /** 差异类型。 */
  private String diffType;

  /** 差异详情。 */
  private String detail;

  /** 建议或执行动作。 */
  private String action;

  /** 是否已处理。 */
  private boolean resolved;

  /** 处理时间。 */
  private LocalDateTime resolvedTime;

  /** 创建时间。 */
  private LocalDateTime createTime;

  /** 更新时间。 */
  private LocalDateTime updateTime;
}
