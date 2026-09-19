package com.slz.crm.platform.security;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 内容安全分级事件。 */
@Data
@TableName("platform_content_security_event")
public class PlatformContentSecurityEventEntity {

  /** 事件主键。 */
  @TableId(type = IdType.AUTO)
  private Long id;

  /** 来源类型。 */
  private String sourceType;

  /** 来源业务 ID。 */
  private String sourceId;

  /** 风险等级。 */
  private String riskLevel;

  /** 处置动作。 */
  private String action;

  /** 命中规则。 */
  private String matchedRule;

  /** 命中原因。 */
  private String reason;

  /** 策略版本与脱敏后元数据。 */
  private String payload;

  /** 创建时间。 */
  private LocalDateTime createTime;

  /** 更新时间。 */
  private LocalDateTime updateTime;
}
