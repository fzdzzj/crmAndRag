package com.slz.crm.server.service.impl;

import com.slz.crm.common.enumeration.ChartOperates.ChartOperate;
import com.slz.crm.common.enumeration.ChartOperates.TimeRangeOperate;
import com.slz.crm.common.enumeration.ChartDataType;
import com.slz.crm.common.untils.DataChartUtils;
import com.slz.crm.pojo.dto.ContractChartDTO;
import com.slz.crm.pojo.dto.DataStatisticsDTO;
import com.slz.crm.pojo.dto.PaymentChartDTO;
import com.slz.crm.pojo.vo.ChartDataVO;
import com.slz.crm.pojo.vo.OpportunityStageDistributionVO;
import com.slz.crm.pojo.vo.StatisticsSummaryVO;
import com.slz.crm.server.constant.ContractConstant;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.service.DataStatisticsService;
import org.jfree.chart.ChartUtils;
import org.jfree.chart.JFreeChart;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class DataStatisticsServiceImpl implements DataStatisticsService {
    @Autowired
    private ContractMapper contractMapper;

    @Autowired
    private PaymentRecordMapper paymentRecordMapper;

    @Autowired
    private SalesOpportunityMapper salesOpportunityMapper;

    @Override
    public ChartDataVO getChartData(DataStatisticsDTO dataStatisticsDTO) throws IOException {
        // 参数校验
        if (dataStatisticsDTO.getDataType() == null) {
            throw new IllegalArgumentException("数据类型不能为空");
        }

        // 计算时间范围
        LocalDateTime[] calculatedTimeRange = DataChartUtils.calculateDateRange(
                dataStatisticsDTO.getEndTime(),
                dataStatisticsDTO.getStartTime(),
                dataStatisticsDTO.getTimeRange()
        );
        // 设置时间范围
        dataStatisticsDTO.setEndTime(calculatedTimeRange[1]);
        dataStatisticsDTO.setStartTime(calculatedTimeRange[0]);

        Map<String, Number> resultMap = new LinkedHashMap<>();
        ChartDataType dataType = dataStatisticsDTO.getDataType();

        // 根据数据类型查询不同的数据
        switch (dataType) {
            case CONTRACT_NUM:
                resultMap = getContractNumData(dataStatisticsDTO, calculatedTimeRange);
                break;
            case PERFORMANCE:
                resultMap = getPerformanceData(dataStatisticsDTO, calculatedTimeRange);
                break;
            case CUSTOMER_SOURCE:
                resultMap = getCustomerSourceData(dataStatisticsDTO);
                break;
            case PAYMENT_AMOUNT:
                resultMap = getPaymentAmountData(dataStatisticsDTO, calculatedTimeRange);
                break;
            case PAYMENT_COUNT:
                resultMap = getPaymentCountData(dataStatisticsDTO, calculatedTimeRange);
                break;
            default:
                throw new IllegalArgumentException("不支持的数据类型: " + dataType);
        }

        // 创建图表数据对象(不设置标题,由前端处理)
        ChartDataVO chartDataVO = new ChartDataVO();
        chartDataVO.setData(resultMap);
        chartDataVO.setChartType(dataStatisticsDTO.getChartType() == ChartOperate.LINE_CHART ? "line" : "pie");

        // 设置坐标轴标签（仅折线图）
        if (dataStatisticsDTO.getChartType() == ChartOperate.LINE_CHART) {
            chartDataVO.setXAxisLabel("Time");
            chartDataVO.setYAxisLabel("Value");
        }

        return chartDataVO;
    }

    /**
     * 获取签约合同数数据
     */
    private Map<String, Number> getContractNumData(DataStatisticsDTO dataStatisticsDTO, LocalDateTime[] timeRange) {
        List<ContractChartDTO> chartData = contractMapper.selectNumByPeriod(
                dataStatisticsDTO.getStartTime(),
                dataStatisticsDTO.getEndTime(),
                ContractConstant.SIGN,
                dataStatisticsDTO.getTimeRange()
        );

        return fillContractTimeSeriesData(chartData, timeRange, dataStatisticsDTO.getTimeRange());
    }

    /**
     * 获取业绩数据
     */
    private Map<String, Number> getPerformanceData(DataStatisticsDTO dataStatisticsDTO, LocalDateTime[] timeRange) {
        List<ContractChartDTO> chartData = contractMapper.selectPerformanceByPeriod(
                dataStatisticsDTO.getStartTime(),
                dataStatisticsDTO.getEndTime(),
                ContractConstant.SIGN,
                dataStatisticsDTO.getTimeRange()
        );

        return fillContractTimeSeriesData(chartData, timeRange, dataStatisticsDTO.getTimeRange());
    }

    /**
     * 获取客户来源分布数据
     */
    private Map<String, Number> getCustomerSourceData(DataStatisticsDTO dataStatisticsDTO) {
        List<ContractChartDTO> chartData = contractMapper.selectCompanySourceByPeriod(
                dataStatisticsDTO.getStartTime(),
                dataStatisticsDTO.getEndTime(),
                ContractConstant.SIGN
        );

        return chartData.stream()
                .collect(Collectors.toMap(
                        ContractChartDTO::getChartTitle,
                        ContractChartDTO::getNum
                ));
    }

    /**
     * 获取回款金额数据
     */
    private Map<String, Number> getPaymentAmountData(DataStatisticsDTO dataStatisticsDTO, LocalDateTime[] timeRange) {
        List<PaymentChartDTO> chartData = paymentRecordMapper.selectPaymentAmountByPeriod(
                dataStatisticsDTO.getStartTime(),
                dataStatisticsDTO.getEndTime(),
                null,
                dataStatisticsDTO.getTimeRange()
        );

        return fillPaymentTimeSeriesData(chartData, timeRange, dataStatisticsDTO.getTimeRange(), true);
    }

    /**
     * 获取回款笔数数据
     */
    private Map<String, Number> getPaymentCountData(DataStatisticsDTO dataStatisticsDTO, LocalDateTime[] timeRange) {
        List<PaymentChartDTO> chartData = paymentRecordMapper.selectPaymentCountByPeriod(
                dataStatisticsDTO.getStartTime(),
                dataStatisticsDTO.getEndTime(),
                null,
                dataStatisticsDTO.getTimeRange()
        );

        return fillPaymentTimeSeriesData(chartData, timeRange, dataStatisticsDTO.getTimeRange(), false);
    }

    /**
     * 填充时间序列数据(合同数据)
     */
    private Map<String, Number> fillContractTimeSeriesData(List<ContractChartDTO> chartData, LocalDateTime[] timeRange, TimeRangeOperate rangeType) {
        Map<String, Number> result = chartData.stream()
                .collect(Collectors.toMap(
                        ContractChartDTO::getChartTitle,
                        ContractChartDTO::getNum
                ));

        // 计算时间间距
        long between = (rangeType == TimeRangeOperate.THIS_YEAR)
                ? ChronoUnit.MONTHS.between(timeRange[0], timeRange[1]) + 1
                : ChronoUnit.DAYS.between(timeRange[0], timeRange[1]) + 1;

        // 判断是否需要补全日期数据
        if (chartData.size() != between) {
            // 设置日期格式
            DateTimeFormatter formatter = (rangeType == TimeRangeOperate.THIS_YEAR)
                    ? DateTimeFormatter.ofPattern("yyyy-MM")
                    : DateTimeFormatter.ofPattern("yyyy-MM-dd");

            Map<String, Number> resultMap = new LinkedHashMap<>();
            LocalDateTime currentTime = timeRange[0];

            // 补全日期
            while (!currentTime.isAfter(timeRange[1])) {
                String dateKey = formatter.format(currentTime);
                resultMap.put(dateKey, result.getOrDefault(dateKey, 0));
                // 根据类型选择按天或按月递增
                currentTime = (rangeType == TimeRangeOperate.THIS_YEAR)
                        ? currentTime.plusMonths(1)
                        : currentTime.plusDays(1);
            }
            return resultMap;
        }

        return result;
    }

    /**
     * 填充时间序列数据(回款数据)
     */
    private Map<String, Number> fillPaymentTimeSeriesData(List<PaymentChartDTO> chartData, LocalDateTime[] timeRange, TimeRangeOperate rangeType, boolean isAmount) {
        Map<String, Number> result = chartData.stream()
                .collect(Collectors.toMap(
                        PaymentChartDTO::getChartTitle,
                        isAmount ? PaymentChartDTO::getPaymentAmount : PaymentChartDTO::getPaymentCount
                ));

        // 计算时间间距
        long between = (rangeType == TimeRangeOperate.THIS_YEAR)
                ? ChronoUnit.MONTHS.between(timeRange[0], timeRange[1]) + 1
                : ChronoUnit.DAYS.between(timeRange[0], timeRange[1]) + 1;

        // 判断是否需要补全日期数据
        if (chartData.size() != between) {
            // 设置日期格式
            DateTimeFormatter formatter = (rangeType == TimeRangeOperate.THIS_YEAR)
                    ? DateTimeFormatter.ofPattern("yyyy-MM")
                    : DateTimeFormatter.ofPattern("yyyy-MM-dd");

            Map<String, Number> resultMap = new LinkedHashMap<>();
            LocalDateTime currentTime = timeRange[0];

            // 补全日期
            while (!currentTime.isAfter(timeRange[1])) {
                String dateKey = formatter.format(currentTime);
                resultMap.put(dateKey, result.getOrDefault(dateKey, 0));
                // 根据类型选择按天或按月递增
                currentTime = (rangeType == TimeRangeOperate.THIS_YEAR)
                        ? currentTime.plusMonths(1)
                        : currentTime.plusDays(1);
            }
            return resultMap;
        }

        return result;
    }

    @Override
    @Deprecated
    public byte[] generateChart(DataStatisticsDTO dataStatisticsDTO) throws IOException {
        // 使用新的 getChartData 方法
        ChartDataVO chartData = getChartData(dataStatisticsDTO);

        // 生成图表图片
        JFreeChart chart = DataChartUtils.createChart(
                dataStatisticsDTO.getChartType(),
                dataStatisticsDTO.getDataType() != null ? dataStatisticsDTO.getDataType().getDescription() : "Chart",
                chartData.getData()
        );
        return convertChartToJpeg(chart);
    }

    /**
     * 将图表转换为JPEG字节数组
     */
    public byte[] convertChartToJpeg(JFreeChart chart) throws IOException {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

        // 设置图片尺寸和质量
        ChartUtils.writeChartAsJPEG(outputStream, chart, 800, 600);

        byte[] chartImageData = outputStream.toByteArray();
        outputStream.close();

        return chartImageData;
    }

    /**
     * 获取综合统计数据
     */
    @Override
    public StatisticsSummaryVO getStatisticsSummary(LocalDateTime startTime, LocalDateTime endTime) {
        StatisticsSummaryVO summary = new StatisticsSummaryVO();

        // 商机总数（所有时间）
        Long totalOpportunities = salesOpportunityMapper.countTotalOpportunities();
        summary.setTotalOpportunities(totalOpportunities != null ? totalOpportunities : 0L);

        // 新增商机数（指定时间段内）
        Long newOpportunities = salesOpportunityMapper.countNewOpportunities(startTime, endTime);
        summary.setNewOpportunities(newOpportunities != null ? newOpportunities : 0L);

        // 签约合同数（指定时间段内）
        Long signedContracts = contractMapper.countSignedContracts(startTime, endTime);
        summary.setSignedContracts(signedContracts != null ? signedContracts : 0L);

        // 签约总额（指定时间段内）
        var totalContractAmount = contractMapper.selectTotalSignAmount(startTime, endTime);
        summary.setTotalContractAmount(totalContractAmount != null ? totalContractAmount : java.math.BigDecimal.ZERO);

        // 回款总额（指定时间段内）
        var totalPaymentAmount = paymentRecordMapper.selectTotalRemittanceAmount(startTime, endTime);
        summary.setTotalPaymentAmount(totalPaymentAmount != null ? totalPaymentAmount : java.math.BigDecimal.ZERO);

        // 回款笔数（查询指定时间段内的回款记录数）
        Long paymentCount = paymentRecordMapper.selectCount(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.slz.crm.pojo.entity.PaymentRecordEntity>()
                        .between(com.slz.crm.pojo.entity.PaymentRecordEntity::getCreateTime, startTime, endTime)
                        .ne(com.slz.crm.pojo.entity.PaymentRecordEntity::getPaymentStatus, 2) // 排除已作废
        );
        summary.setPaymentCount(paymentCount != null ? paymentCount : 0L);

        return summary;
    }

    /**
     * 获取商机阶段分布数据
     */
    @Override
    public OpportunityStageDistributionVO getOpportunityStageDistribution() {
        OpportunityStageDistributionVO distribution = new OpportunityStageDistributionVO();

        // 获取商机总数
        Long totalCount = salesOpportunityMapper.countTotalOpportunities();
        distribution.setTotalCount(totalCount != null ? totalCount : 0L);

        // 统计各阶段的商机数量
        Long stage0Count = salesOpportunityMapper.countByStage(0);
        Long stage1Count = salesOpportunityMapper.countByStage(1);
        Long stage2Count = salesOpportunityMapper.countByStage(2);
        Long stage3Count = salesOpportunityMapper.countByStage(3);
        Long stage4Count = salesOpportunityMapper.countByStage(4);
        Long stage5Count = salesOpportunityMapper.countByStage(5);

        // 设置各阶段数量
        distribution.setStage0Count(stage0Count != null ? stage0Count : 0L);
        distribution.setStage1Count(stage1Count != null ? stage1Count : 0L);
        distribution.setStage2Count(stage2Count != null ? stage2Count : 0L);
        distribution.setStage3Count(stage3Count != null ? stage3Count : 0L);
        distribution.setStage4Count(stage4Count != null ? stage4Count : 0L);
        distribution.setStage5Count(stage5Count != null ? stage5Count : 0L);

        // 计算占比（保留两位小数）
        if (distribution.getTotalCount() > 0) {
            distribution.setStage0Percentage(calculatePercentage(distribution.getStage0Count(), distribution.getTotalCount()));
            distribution.setStage1Percentage(calculatePercentage(distribution.getStage1Count(), distribution.getTotalCount()));
            distribution.setStage2Percentage(calculatePercentage(distribution.getStage2Count(), distribution.getTotalCount()));
            distribution.setStage3Percentage(calculatePercentage(distribution.getStage3Count(), distribution.getTotalCount()));
            distribution.setStage4Percentage(calculatePercentage(distribution.getStage4Count(), distribution.getTotalCount()));
            distribution.setStage5Percentage(calculatePercentage(distribution.getStage5Count(), distribution.getTotalCount()));
        } else {
            // 如果总数为0，所有占比都设为0
            distribution.setStage0Percentage(0.0);
            distribution.setStage1Percentage(0.0);
            distribution.setStage2Percentage(0.0);
            distribution.setStage3Percentage(0.0);
            distribution.setStage4Percentage(0.0);
            distribution.setStage5Percentage(0.0);
        }

        return distribution;
    }

    /**
     * 计算百分比（保留两位小数）
     * @param count 数量
     * @param total 总数
     * @return 百分比
     */
    private Double calculatePercentage(Long count, Long total) {
        if (total == 0) {
            return 0.0;
        }
        return Math.round(count * 10000.0 / total) / 100.0;
    }
}
