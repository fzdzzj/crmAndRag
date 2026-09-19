package com.slz.crm.pojo.dto;

import lombok.Data;

/** 协助申请明细（每位协助人可携带不同的协作目的与要求） */
@Data
public class AssistApplyItem {
  /** 协助人ID */
  private Long assistUserId;

  /** 协作目的（申请人填） */
  private String applyPurpose;

  /** 协作要求（申请人填） */
  private String applyRequirement;
}
