package com.slz.crm.pojo.dto.ai;

import com.slz.crm.server.ai.validation.AskQuestion;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 新增商机草稿 payload（actionType = CREATE_OPPORTUNITY）
 * 客户双通道：companyId 或 companyName 至少其一（由 AiEntityResolver 解析）
 */
@Data
public class AiOpportunityDraftPayloadDTO {
    /** 客户双通道 */
    @AskQuestion("这个商机属于哪个客户公司？请提供公司编号或名称")
    private Long companyId;
    @AskQuestion("这个商机属于哪个客户公司？请提供公司名称")
    private String companyName;
    @NotBlank @AskQuestion("商机名称是什么？")
    private String opportunityName;
    @AskQuestion("商机金额是多少？")
    private BigDecimal amount;
    /** 预期成交日期（格式 yyyy-MM-dd HH:mm:ss，选填） */
    private String expectedCloseDate;
    /** 商机来源（选填） */
    private String source;
    /** 商机描述（选填） */
    private String description;
}
