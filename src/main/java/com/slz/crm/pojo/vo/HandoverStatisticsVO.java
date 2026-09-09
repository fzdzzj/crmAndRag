package com.slz.crm.pojo.vo;

import lombok.Data;

/**
 * 交接资源统计VO
 */
@Data
public class HandoverStatisticsVO {

    /**
     * 资源类型标识（task、customer、opportunity）
     */
    private String type;

    /**
     * 资源类型名称（任务、客户、销售机会）
     */
    private String typeName;

    /**
     * 数量
     */
    private Integer count;
}
