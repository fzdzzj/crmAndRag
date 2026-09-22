package com.slz.crm.server.controller;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.ChartDataType;
import com.slz.crm.common.enumeration.ChartOperates.ChartOperate;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.ServiceException;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.DataStatisticsDTO;
import com.slz.crm.pojo.vo.ChartDataVO;
import com.slz.crm.pojo.vo.OpportunityStageDistributionVO;
import com.slz.crm.pojo.vo.StatisticsSummaryVO;
import com.slz.crm.server.service.DataStatisticsService;
import java.io.IOException;
import java.time.LocalDateTime;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/dataStatistics")
@Slf4j
public class DataStatisticsController {
  @Autowired private DataStatisticsService dataStatisticsService;

  /** 获取图表数据（推荐使用，返回 JSON 数据） */
  @PostMapping("/chartData")
  // apply-permission-matrix 任务 2.1：报表生成权限（缓存已下沉至 DataStatisticsServiceImpl.getChartData）
  @RequirePermission(PermissionOperates.REPORT_GENERATE_REPORT)
  public Result<ChartDataVO> getChartData(@RequestBody DataStatisticsDTO dataStatisticsDTO) {
    Result<ChartDataVO> result;
    try {
      // 参数校验
      if (dataStatisticsDTO.getDataType() == null) {
        result = Result.error("数据类型不能为空");
      } else {
        // 分类选择图表类型
        ChartOperate chartType =
            (dataStatisticsDTO.getDataType() == ChartDataType.CUSTOMER_SOURCE)
                ? ChartOperate.PIE_CHART
                : ChartOperate.LINE_CHART;
        dataStatisticsDTO.setChartType(chartType);

        ChartDataVO chartData = dataStatisticsService.getChartData(dataStatisticsDTO);
        result = Result.success(chartData);
      }
    } catch (IOException e) {
      log.error("获取图表数据错误", e);
      throw new ServiceException("获取图表数据错误", e);
    }
    return result;
  }

  /** 生成图表图片（已废弃，请使用 /chartData 接口） */
  @PostMapping("/chart")
  @Deprecated
  // apply-permission-matrix 任务 2.1：报表生成权限（缓存已下沉至 DataStatisticsServiceImpl.generateChart）
  @RequirePermission(PermissionOperates.REPORT_GENERATE_REPORT)
  public ResponseEntity<byte[]> generateLineChart(
      @RequestBody DataStatisticsDTO dataStatisticsDTO) {
    try {
      // 参数校验
      if (dataStatisticsDTO.getDataType() == null) {
        throw new ServiceException("数据类型不能为空");
      }

      // 分类选择图表
      ChartOperate chartType =
          (dataStatisticsDTO.getDataType() == ChartDataType.CUSTOMER_SOURCE)
              ? ChartOperate.PIE_CHART
              : ChartOperate.LINE_CHART;
      dataStatisticsDTO.setChartType(chartType);
      byte[] chartImage = dataStatisticsService.generateChart(dataStatisticsDTO);

      return ResponseEntity.ok()
          .contentType(MediaType.IMAGE_JPEG)
          .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"chart.jpg\"")
          .body(chartImage);
    } catch (IOException e) {
      log.error("生成图表错误", e);
      throw new ServiceException("生成图表错误", e);
    }
  }

  /**
   * 获取综合统计数据
   *
   * @param request 统计时间范围请求
   * @return 综合统计数据
   */
  @PostMapping("/summary")
  // apply-permission-matrix 任务 2.1：报表查看权限
  @RequirePermission(PermissionOperates.REPORT_VIEW_REPORT)
  public Result<StatisticsSummaryVO> getStatisticsSummary(@RequestBody StatisticsRequest request) {
    LocalDateTime startTime = request.getStartTime();
    LocalDateTime endTime = request.getEndTime();

    final Result<StatisticsSummaryVO> result;
    if (startTime == null || endTime == null) {
      result = Result.error("开始时间和结束时间不能为空");
    } else if (endTime.isBefore(startTime)) {
      result = Result.error("结束时间不能早于开始时间");
    } else {
      StatisticsSummaryVO summary = dataStatisticsService.getStatisticsSummary(startTime, endTime);
      result = Result.success(summary);
    }
    return result;
  }

  /**
   * 获取商机阶段分布数据
   *
   * @return 商机阶段分布数据
   */
  @GetMapping("/opportunityStageDistribution")
  // apply-permission-matrix 任务 2.1：报表查看权限
  @RequirePermission(PermissionOperates.REPORT_VIEW_REPORT)
  public Result<OpportunityStageDistributionVO> getOpportunityStageDistribution() {
    OpportunityStageDistributionVO distribution =
        dataStatisticsService.getOpportunityStageDistribution();
    return Result.success(distribution);
  }

  /** 统计时间范围请求对象 */
  @Data
  public static class StatisticsRequest {
    /** 开始时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime startTime;

    /** 结束时间 */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
    private LocalDateTime endTime;
  }
}
