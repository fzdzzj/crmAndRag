package com.slz.crm.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.pojo.ao.Privacy;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SalesNumVO implements Privacy {
    /**
     * 商机总量
     */
    private long totalSalesOpportunityNum;

    /**
     * 已签约商机数量
     */
    private long totalSignOpportunityNum;

    /**
     * 新增商机数量
     */
    private long totalNewSalesOpportunityNum;

    /**
     * 报表起始时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime reportStartTime;

    /**
     * 报表结束时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime reportEndTime;
}
