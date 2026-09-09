package com.slz.crm.server.service;

import com.slz.crm.pojo.dto.DataStatisticsDTO;
import com.slz.crm.pojo.vo.ChartDataVO;
import com.slz.crm.pojo.vo.OpportunityStageDistributionVO;
import com.slz.crm.pojo.vo.StatisticsSummaryVO;

import java.io.IOException;
import java.time.LocalDateTime;

public interface DataStatisticsService {
        /**
         * 生成图表（已废弃，请使用 getChartData）
         * @param dataStatisticsDTO 数据统计DTO
         * @return 图表字节数组
         * @deprecated 使用 getChartData 替代
         */
        @Deprecated
        byte[] generateChart(DataStatisticsDTO dataStatisticsDTO) throws IOException;

        /**
         * 获取图表数据（推荐使用）
         * @param dataStatisticsDTO 数据统计DTO
         * @return 图表数据
         */
        ChartDataVO getChartData(DataStatisticsDTO dataStatisticsDTO) throws IOException;

        /**
         * 获取综合统计数据
         * @param startTime 开始时间
         * @param endTime 结束时间
         * @return 综合统计数据
         */
        StatisticsSummaryVO getStatisticsSummary(LocalDateTime startTime, LocalDateTime endTime);

        /**
         * 获取商机阶段分布数据
         * @return 商机阶段分布数据
         */
        OpportunityStageDistributionVO getOpportunityStageDistribution();
}
