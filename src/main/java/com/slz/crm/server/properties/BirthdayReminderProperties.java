package com.slz.crm.server.properties;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 生日提醒配置属性 */
@Data
@Component
@ConfigurationProperties(prefix = "slz.birthday.reminder")
public class BirthdayReminderProperties {

  /** 生日提前提醒天数 */
  private int days;

  /** 是否启用提醒 */
  private boolean enabled;
}
