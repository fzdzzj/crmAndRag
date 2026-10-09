package com.slz.crm.platform.config.controller;

import lombok.Data;

/** 提交成本键变更申请请求（add-cost-key-approval-workflow 任务 3.1）。 */
@Data
public class CostKeyChangeRequestSubmitReq {
  /** 配置键 */
  private String configKey;

  /** 期望配置值 */
  private String requestedValue;

  /** 申请理由 */
  private String reason;
}
