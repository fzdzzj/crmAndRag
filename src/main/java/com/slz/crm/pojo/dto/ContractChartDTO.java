package com.slz.crm.pojo.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 合同图表数据传输对象
 */
@Data
public class ContractChartDTO {
    /** * 图表标题 */
    private String chartTitle;
    /** * 数量 */
    private Long num;
    /** * 业绩金额 */
    private BigDecimal performance;
}
