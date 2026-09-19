package com.slz.crm.pojo.dto.ai;

import com.slz.crm.server.ai.validation.AskQuestion;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 新增回款草稿 payload（actionType = CREATE_PAYMENT） 合同双通道：contractId 或 contractName 至少其一（由
 * AiEntityResolver 解析）
 */
@Data
public class AiPaymentDraftPayloadDTO {
  @AskQuestion("这笔回款挂在哪个合同下？请提供合同编号或名称")
  private Long contractId;

  @AskQuestion("这笔回款挂在哪个合同下？请提供合同名称")
  private String contractName;

  /** 关联订单项（选填） */
  private Long orderItemId;

  @NotNull
  @Positive
  @AskQuestion("回款金额是多少？")
  private BigDecimal paymentAmount;

  /** 回款日期（格式 yyyy-MM-dd HH:mm:ss，选填，缺省当天） */
  private String paymentDate;

  @AskQuestion("回款方式是什么？（如：银行转账/现金/支票）")
  private String paymentMethod;

  /** 回款状态（选填，缺省由业务层定） */
  private Integer paymentStatus;

  private String remark;
}
