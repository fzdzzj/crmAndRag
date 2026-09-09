package com.slz.crm.pojo.dto.ai;

import com.slz.crm.server.ai.validation.AskQuestion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 订单草稿行项 DTO（合同追加订单 / 合同创建时复用）
 * 合同双通道：contractId 或 contractName 至少其一（由 AiEntityResolver 解析）
 * 创建合同场景下订单不填 contractId（执行时自动挂到新合同）
 */
@Data
public class AiOrderItemDraftDTO {
    @AskQuestion("这笔订单挂在哪个合同下？请提供合同编号或名称")
    private Long contractId;
    @AskQuestion("这笔订单挂在哪个合同下？请提供合同名称")
    private String contractName;
    @NotBlank @AskQuestion("订单包含哪些产品？")
    private String productName;
    @NotNull @Positive @AskQuestion("数量是多少？")
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    @Positive @AskQuestion("订单金额是多少？")
    private BigDecimal amount;
    private String remark;
}