package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.UserHandoverDTO;
import com.slz.crm.pojo.dto.UserHandoverQueryDTO;
import com.slz.crm.pojo.vo.HandoverStatisticsVO;
import com.slz.crm.pojo.vo.UserHandoverVO;
import com.slz.crm.server.service.UserHandoverService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户交接控制器
 * 处理用户离职时的数据交接
 */
@RestController
@RequestMapping("/user/handover")
@Slf4j
public class UserHandoverController {

    private final UserHandoverService userHandoverService;

    @Autowired
    public UserHandoverController(UserHandoverService userHandoverService) {
        this.userHandoverService = userHandoverService;
    }

    /**
     * 执行用户离职交接
     * 将离职用户的任务、客户、销售机会全部转移给接收用户
     *
     * @param dto 交接参数
     * @return 交接记录
     */
    @PostMapping("/execute")
    @RequirePermission(PermissionOperates.SYSTEM_UPDATE_USER)
    public Result<UserHandoverVO> executeHandover(@RequestBody UserHandoverDTO dto) {
        UserHandoverVO result = userHandoverService.executeHandover(dto);
        return Result.success(result);
    }

    /**
     * 查询交接记录
     *
     * @param queryDTO 查询参数
     * @return 交接记录分页列表
     */
    @PostMapping("/query")
    @RequirePermission(PermissionOperates.SYSTEM_VIEW_USER)
    public Result<Page<UserHandoverVO>> queryHandoverRecords(@RequestBody UserHandoverQueryDTO queryDTO) {
        Page<UserHandoverVO> result = userHandoverService.queryHandoverRecords(queryDTO);
        return Result.success(result);
    }

    /**
     * 获取用户待交接资源统计
     *
     * @param userId 用户ID
     * @return 各类型资源统计列表
     */
    @GetMapping("/statistics/{userId}")
    @RequirePermission(PermissionOperates.SYSTEM_VIEW_USER)
    public Result<List<HandoverStatisticsVO>> getHandoverStatistics(@PathVariable Long userId) {
        List<HandoverStatisticsVO> stats = userHandoverService.getHandoverStatistics(userId);
        return Result.success(stats);
    }
}
