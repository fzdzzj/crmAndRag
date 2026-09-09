package com.slz.crm.server.service;

import com.slz.crm.pojo.vo.ContractNumVO;
import com.slz.crm.pojo.vo.SalesNumVO;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public interface ReportService {
    /**
     * 根据时间统计合同总数
     * @param reportStartTime 报表起始时间
     * @param reportEndTime 报表结束时间
     * @return
     */
    ContractNumVO getTotalSignContractNum(LocalDateTime reportStartTime, LocalDateTime reportEndTime);

    /**
     * 根据时间统计商机总数
     * @param reportStartTime 报表起始时间
     * @param reportEndTime 报表结束时间
     * @return
     */
    SalesNumVO getTotalBusinessNum(LocalDateTime reportStartTime, LocalDateTime reportEndTime);
}
