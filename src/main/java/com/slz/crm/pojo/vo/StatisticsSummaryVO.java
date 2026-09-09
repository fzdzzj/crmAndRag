package com.slz.crm.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 综合统计数据视图对象
 * 用于返回整体的统计数据，包括商机、合同、回款等核心指标
 */
@Data
public class StatisticsSummaryVO {
    /**
     * 商机总数
     */
    private Long totalOpportunities;

    /**
     * 新增商机数（指定时间段内）
     */
    private Long newOpportunities;

    /**
     * 签约合同数（指定时间段内）
     */
    private Long signedContracts;

    /**
     * 签约总额（指定时间段内）
     */
    private BigDecimal totalContractAmount;

    /**
     * 回款总额（指定时间段内）
     */
    private BigDecimal totalPaymentAmount;

    /**
     * 回款笔数（指定时间段内）
     */
    private Long paymentCount;
}
