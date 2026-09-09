package com.slz.crm.server.controller;
import com.slz.crm.common.enumeration.PermissionOperates;

import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.vo.ContractNumVO;
import com.slz.crm.pojo.vo.SalesNumVO;
import com.slz.crm.server.service.ReportService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 报表
 */
@RestController
@RequestMapping("/report")
@Slf4j
public class ReportController {

    @Autowired
    private ReportService reportService;

    /**
     * 根据时间查询签约合同数量
     * @param reportStartTime 报表起始时间
     * @param reportEndTime 报表结束时间
     * @return 签约合同数量
     */
    @GetMapping("/contract")
    public Result<ContractNumVO> getTotalSignContractNum(@RequestParam(required = false) LocalDateTime reportStartTime,
                                                         @RequestParam(required = false) LocalDateTime reportEndTime){
        //参数验证
        if (reportStartTime != null && reportEndTime != null && reportStartTime.isAfter(reportEndTime)) {
            return Result.error("开始日期不能晚于结束日期");
        }

        return Result.success(reportService.getTotalSignContractNum(reportStartTime, reportEndTime));
    }

    /**
     * 根据时间查询商机数量
     * @param reportStartTime 报表起始时间
     * @param reportEndTime 报表结束时间
     * @return 商机数量
     */
    @GetMapping("/business")
    public Result<SalesNumVO> getTotalBusinessNum(@RequestParam(required = false) LocalDateTime reportStartTime,
                                                  @RequestParam(required = false) LocalDateTime reportEndTime){
        //参数验证
        if (reportStartTime != null && reportEndTime != null && reportStartTime.isAfter(reportEndTime)) {
            return Result.error("开始日期不能晚于结束日期");
        }

        return Result.success(reportService.getTotalBusinessNum(reportStartTime, reportEndTime));
    }
}
