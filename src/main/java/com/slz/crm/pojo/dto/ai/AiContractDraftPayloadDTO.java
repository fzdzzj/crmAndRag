package com.slz.crm.pojo.dto.ai;

import com.slz.crm.server.ai.validation.AskQuestion;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import lombok.Data;

/**
 * 创建合同草稿 payload（actionType = CREATE_CONTRACT） 合同基本信息 + 订单明细列表；商机双通道：opportunityId 或
 * opportunityName（由 AiEntityResolver 解析）
 */
@Data
public class AiContractDraftPayloadDTO {
  @NotBlank
  @AskQuestion("合同名称是什么？")
  private String contractName;

  @AskQuestion("这个合同关联哪个商机？请提供商机编号或名称")
  private Long opportunityId;

  @AskQuestion("这个合同关联哪个商机？请提供商机名称、公司名或联系人")
  private String opportunityName;

  /** 商机解析辅助：公司名（选填，仅用于名称→ID 解析） */
  private String opportunityCompanyName;

  /** 商机解析辅助：联系人（选填，仅用于名称→ID 解析） */
  private String opportunityContactName;

  @NotNull
  @Positive
  @AskQuestion("合同金额是多少？")
  private BigDecimal totalAmount;

  @Valid @NotEmpty private List<AiOrderItemDraftDTO> orders;
}
