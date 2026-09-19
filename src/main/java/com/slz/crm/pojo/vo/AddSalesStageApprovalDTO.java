package com.slz.crm.pojo.vo;

import java.util.List;
import lombok.Data;

@Data
public class AddSalesStageApprovalDTO {
  /** 关联销售机会 ID */
  private Long opportunityId;

  /** 目标阶段（申请变更到的阶段） */
  private Integer targetStage;

  /** 审批人 ID（上级或指定审批人） */
  private Long approverId;

  /** 审批备注（必填项，支持多行文本输入） */
  private String message;

  /** 协助申请明细（每位协助人各自的目的/要求）。 */
  private List<com.slz.crm.pojo.dto.AssistApplyItem> assistApplyList;
}
