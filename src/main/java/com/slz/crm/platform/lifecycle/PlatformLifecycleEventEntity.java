package com.slz.crm.platform.lifecycle;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 文档生命周期幂等事件。 */
@Data
@TableName("platform_lifecycle_event")
public class PlatformLifecycleEventEntity {

  /** 事件主键。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 文档业务 ID。 */
  private String documentId;

  /** 事件类型。 */
  private String eventType;

  /** 状态版本。 */
  private Long statusVersion;

  /** 幂等键。 */
  private String idempotencyKey;

  /** 扩展数据。 */
  private String payload;

  /** 处理完成时间。 */
  private LocalDateTime processedTime;

  /** 创建时间。 */
  private LocalDateTime createTime;

  /** 更新时间。 */
  private LocalDateTime updateTime;
}
