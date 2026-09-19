package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.ao.Privacy;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/** 销售阶段审批VO */
@Data
public class SalesStageApprovalVO implements Privacy {
  /** 审批记录ID */
  private Long id;

  /** 关联销售机会ID */
  private Long opportunityId;

  /** 关联销售机会名称 */
  private String opportunityName;

  /** 当前阶段（变更前的阶段） */
  private String currentStage;

  /** 目标阶段（申请变更到的阶段） */
  private String targetStage;

  /** 审批人ID（上级或指定审批人） */
  private Long approverId;

  /** 审批人姓名 */
  private String approverName;

  /** 审批状态（0待审批/1同意/2拒绝/3退回修改） */
  private Integer approvalStatus;

  /** 是否已正式提交审批；草稿保存后为 false。 */
  private Boolean approvalTriggered;

  /** 审批状态描述 */
  private String approvalStatusDesc;

  /** 审批意见（审批人的反馈） */
  private String approvalOpinion;

  /** 审批备注 */
  private String message;

  /** 申请时间 */
  @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
  private LocalDateTime applyTime;

  /** 协助人列表（仅申请人/协助人可见） */
  private List<AssistVO> assistUsers;

  /**
   * 从Entity创建VO，需要传入销售机会名称、当前阶段名称和目标阶段名称
   *
   * @param entity 销售阶段审批实体
   * @param opportunityName 销售机会名称
   * @param currentStage 当前阶段名称
   * @param targetStage 目标阶段名称
   * @param approverName 审批人姓名
   * @return SalesStageApprovalVO
   */
  public static SalesStageApprovalVO fromEntity(
      SalesStageApprovalEntity entity,
      String opportunityName,
      String currentStage,
      String targetStage,
      String approverName) {
    if (entity == null) {
      return null;
    }

    SalesStageApprovalVO vo = new SalesStageApprovalVO();
    vo.setId(entity.getId());
    vo.setOpportunityId(entity.getOpportunityId());
    vo.setOpportunityName(opportunityName);
    vo.setCurrentStage(currentStage);
    vo.setTargetStage(targetStage);
    vo.setApproverId(entity.getApproverId());
    vo.setApproverName(approverName);
    vo.setApprovalStatus(entity.getApprovalStatus());
    vo.setApprovalTriggered(!Boolean.FALSE.equals(entity.getApprovalTriggered()));
    // 设置审批状态描述
    String statusDesc =
        switch (entity.getApprovalStatus()) {
          case 0 -> "待审批";
          case 1 -> "同意";
          case 2 -> "拒绝";
          case 3 -> "退回修改";
          default -> "未知状态";
        };
    vo.setApprovalStatusDesc(statusDesc);
    vo.setApprovalOpinion(entity.getApprovalOpinion());
    vo.setMessage(entity.getMessage());
    vo.setApplyTime(entity.getApplyTime());

    return vo;
  }
}
