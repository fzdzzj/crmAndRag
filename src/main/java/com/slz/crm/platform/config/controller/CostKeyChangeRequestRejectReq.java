package com.slz.crm.platform.config.controller;

import lombok.Data;

/** 驳回成本键变更申请请求（add-cost-key-approval-workflow 任务 3.1）。 */
@Data
public class CostKeyChangeRequestRejectReq {
  /** 驳回理由 */
  private String rejectReason;
}
