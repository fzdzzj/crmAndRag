package com.slz.crm.platform.config.controller;

import lombok.Data;

/** 回滚配置项请求体。 */
@Data
public class ConfigRollbackRequest {

  /** 目标版本号（须早于当前版本；回滚目标值 = 该版本历史行的 new_value） */
  private Integer version;

  /** 操作备注（可为空，默认填“回滚到版本 N”） */
  private String remark;
}
