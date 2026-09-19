package com.slz.crm.pojo.dto;

import java.util.List;
import lombok.Data;

/** 驳回后重新申请 DTO */
@Data
public class AssistReapplyDTO {
  /** 原驳回记录ID */
  private Long originalAssistId;

  /** 重新申请明细：仅允许一项，且协助人必须与原协助人相同。 */
  private List<AssistApplyItem> assistApplyList;
}
