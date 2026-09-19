package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.common.enumeration.ChartOperates.TimeRangeOperate;
import com.slz.crm.pojo.dto.PaymentChartDTO;
import com.slz.crm.pojo.entity.PaymentRecordEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PaymentRecordMapper extends BaseMapper<PaymentRecordEntity> {
  /**
   * 查询回款额度
   *
   * @param reportStartTime 报表起始时间
   * @param reportEndTime 报表结束时间
   * @return 回款额度
   */
  BigDecimal selectTotalRemittanceAmount(
      LocalDateTime reportStartTime, LocalDateTime reportEndTime);

  /**
   * 阶段性查询回款金额
   *
   * @param startTime 开始时间
   * @param endTime 结束时间
   * @param paymentStatus 回款状态(0已确认/1待确认/2已作废)
   * @param timeRange 时间范围
   * @return 回款金额统计列表
   */
  List<PaymentChartDTO> selectPaymentAmountByPeriod(
      LocalDateTime startTime,
      LocalDateTime endTime,
      Integer paymentStatus,
      TimeRangeOperate timeRange);

  /**
   * 阶段性查询回款笔数
   *
   * @param startTime 开始时间
   * @param endTime 结束时间
   * @param paymentStatus 回款状态(0已确认/1待确认/2已作废)
   * @param timeRange 时间范围
   * @return 回款笔数统计列表
   */
  List<PaymentChartDTO> selectPaymentCountByPeriod(
      LocalDateTime startTime,
      LocalDateTime endTime,
      Integer paymentStatus,
      TimeRangeOperate timeRange);

  /**
   * 查询回款状态分布
   *
   * @param startTime 开始时间
   * @param endTime 结束时间
   * @return 回款状态分布列表
   */
  List<PaymentChartDTO> selectPaymentStatusByPeriod(LocalDateTime startTime, LocalDateTime endTime);
}
