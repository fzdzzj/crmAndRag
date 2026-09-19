package com.slz.crm.server.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.slz.crm.pojo.entity.ContractEntity;
import com.slz.crm.pojo.entity.SalesOpportunityEntity;
import com.slz.crm.pojo.vo.ContractNumVO;
import com.slz.crm.pojo.vo.SalesNumVO;
import com.slz.crm.server.mapper.ContractMapper;
import com.slz.crm.server.mapper.PaymentRecordMapper;
import com.slz.crm.server.mapper.SalesOpportunityMapper;
import com.slz.crm.server.service.ReportService;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class ReportServiceImpl implements ReportService {

  @Autowired private SalesOpportunityMapper salesOpportunityMapper;

  @Autowired private ContractMapper contractMapper;

  @Autowired private PaymentRecordMapper paymentRecordMapper;

  @Override
  public ContractNumVO getTotalSignContractNum(
      LocalDateTime reportStartTime, LocalDateTime reportEndTime) {
    // 设置时间范围
    LocalDateTime[] timeRange = getTimeRange(reportStartTime, reportEndTime);
    reportStartTime = timeRange[0];
    reportEndTime = timeRange[1];

    // 总签约额度
    BigDecimal totalSignAmount =
        contractMapper.selectTotalSignAmount(reportStartTime, reportEndTime);

    // 查询签约合同总数&新增合同总数
    Long total =
        contractMapper.selectCount(
            new LambdaQueryWrapper<ContractEntity>()
                .between(ContractEntity::getSignDate, reportStartTime, reportEndTime));

    ContractNumVO contractNumVO =
        ContractNumVO.builder()
            .totalSignAmount(totalSignAmount)
            .totalSignContractNum(total)
            .totalNewContractNum(total)
            .reportStartTime(reportStartTime)
            .reportEndTime(reportEndTime)
            .build();

    return contractNumVO;
  }

  @Override
  public SalesNumVO getTotalBusinessNum(
      LocalDateTime reportStartTime, LocalDateTime reportEndTime) {
    // 设置时间范围
    LocalDateTime[] timeRange = getTimeRange(reportStartTime, reportEndTime);
    reportStartTime = timeRange[0];
    reportEndTime = timeRange[1];

    // 查询已签约商机总数
    Long totalSignSalesOpportunityNum =
        salesOpportunityMapper.selectCount(
            new LambdaQueryWrapper<SalesOpportunityEntity>()
                .between(SalesOpportunityEntity::getCreateTime, reportStartTime, reportEndTime)
                .in(SalesOpportunityEntity::getStage, 4));

    // 查询商机总量&新增商机总量
    Long totalNewSalesOpportunityNum =
        salesOpportunityMapper.selectCount(
            new LambdaQueryWrapper<SalesOpportunityEntity>()
                .between(SalesOpportunityEntity::getCreateTime, reportStartTime, reportEndTime));

    SalesNumVO salesNumVO =
        SalesNumVO.builder()
            .totalSignOpportunityNum(totalSignSalesOpportunityNum)
            .totalSalesOpportunityNum(totalNewSalesOpportunityNum)
            .reportStartTime(reportStartTime)
            .reportEndTime(reportEndTime)
            .build();

    return salesNumVO;
  }

  /** 获取时间范围，如果都为null则返回当前年份范围 */
  private LocalDateTime[] getTimeRange(LocalDateTime startTime, LocalDateTime endTime) {
    if (startTime == null && endTime == null) {
      int currentYear = LocalDateTime.now().getYear();
      return new LocalDateTime[] {
        LocalDateTime.of(currentYear, 1, 1, 0, 0, 0),
        LocalDateTime.of(currentYear, 12, 31, 23, 59, 59)
      };
    }
    return new LocalDateTime[] {startTime, endTime};
  }
}
