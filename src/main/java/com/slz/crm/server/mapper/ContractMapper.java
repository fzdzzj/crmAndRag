package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.slz.crm.common.enumeration.ChartOperates.TimeRangeOperate;
import com.slz.crm.pojo.dto.ContractChartDTO;
import com.slz.crm.pojo.entity.ContractEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ContractMapper extends BaseMapper<ContractEntity> {
  /** 签约额度 */
  BigDecimal selectTotalSignAmount(LocalDateTime reportStartTime, LocalDateTime reportEndTime);

  /** 签约合同数量 */
  Long selectSignContractNum(
      LocalDateTime reportStartTime, LocalDateTime reportEndTime, Integer status);

  /** 阶段性查询签约合同数量 */
  List<ContractChartDTO> selectNumByPeriod(
      LocalDateTime startTime, LocalDateTime endTime, Integer status, TimeRangeOperate timeRange);

  /**
   * 阶段性查询业绩
   *
   * @param startTime
   * @param endTime
   * @param status
   * @param timeRange
   * @return
   */
  List<ContractChartDTO> selectPerformanceByPeriod(
      LocalDateTime startTime, LocalDateTime endTime, Integer status, TimeRangeOperate timeRange);

  /** 阶段性查询公司来源 */
  List<ContractChartDTO> selectCompanySourceByPeriod(
      LocalDateTime startTime, LocalDateTime endTime, Integer status);

  /**
   * 统计签约合同总数
   *
   * @param startTime 开始时间
   * @param endTime 结束时间
   * @return 签约合同总数
   */
  Long countSignedContracts(
      @Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);
}
