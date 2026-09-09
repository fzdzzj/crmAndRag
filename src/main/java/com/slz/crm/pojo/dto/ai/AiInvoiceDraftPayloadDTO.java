package com.slz.crm.pojo.dto.ai;

import com.slz.crm.server.ai.validation.AskQuestion;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 新增发票草稿 payload（actionType = CREATE_INVOICE）
 * 合同双通道：contractId 或 contractName 至少其一（由 AiEntityResolver 解析）
 */
@Data
public class AiInvoiceDraftPayloadDTO {
    @AskQuestion("这张发票挂在哪个合同下？请提供合同编号或名称")
    private Long contractId;
    @AskQuestion("这张发票挂在哪个合同下？请提供合同名称")
    private String contractName;
    /** 关联回款（选填） */
    private Long paymentId;
    @NotBlank @AskQuestion("发票号码是什么？")
    private String invoiceNo;
    @NotNull @Positive @AskQuestion("发票金额是多少？")
    private BigDecimal invoiceAmount;
    /** 开票日期（格式 yyyy-MM-dd HH:mm:ss，选填，缺省当天） */
    private String invoiceDate;
    @AskQuestion("发票类型是什么？（如：增值税专用发票/增值税普通发票）")
    private String invoiceType;
    private String remark;
}
