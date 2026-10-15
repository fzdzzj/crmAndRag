package com.slz.crm.server.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.enumeration.ChartOperates.TimeRangeOperate;
import com.slz.crm.pojo.dto.ContractChartDTO;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.vo.ContractExpiringVO;
import com.slz.crm.pojo.vo.ContractOverduePaymentVO;
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

  /**
   * 分页查询到期/临期合同（add-contract-warning-and-overdue-reminder 任务 1.1）。
   *
   * <p>条件：合同已生效（contract_status=1）且 end_date 非空且在未来 {@code days} 天（含）内到期或已超期； 关联统计已确认回款
   * （payment_status=0）与未结敞口。调用方传入{@code Page} 由 MyBatis-Plus 分页拦截器填充 total/records。
   *
   * @param page 分页对象（第一参数，供分页插件拦截）
   * @param days 到期预警窗口天数（默认 30）
   * @return 到期/临期合同分页 VO
   */
  Page<ContractExpiringVO> selectExpiringContracts(
      Page<ContractExpiringVO> page, @Param("days") int days);

  /**
   * 分页查询逾期应收账款合同（add-contract-warning-and-overdue-reminder 任务 1.1）。
   *
   * <p>条件：合同已生效（contract_status=1）且未结敞口（total_amount - 已确认回款）> 0，且生效超 30 天 或 距到期不足 15 天。
   *
   * @param page 分页对象（第一参数，供分页插件拦截）
   * @return 逾期应收账款合同分页 VO
   */
  Page<ContractOverduePaymentVO> selectOverduePaymentContracts(Page<ContractOverduePaymentVO> page);
}
