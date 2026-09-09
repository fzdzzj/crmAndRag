package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.result.Result;
import com.slz.crm.pojo.dto.ProjectFileDTO;
import com.slz.crm.pojo.dto.ProjectFileQueryDTO;
import com.slz.crm.pojo.vo.ProjectFileVO;
import com.slz.crm.server.service.ProjectFileService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 项目文件管理
 */
@RestController
@RequestMapping("/project/file")
public class ProjectFileController {

    @Autowired
    private ProjectFileService projectFileService;

    // ========== 文件上传接口 ==========

    /**
     * 基于业务活动上传文件
     * @param activityId 业务活动ID
     * @param fileListRequest 文件DTO列表
     * @return 是否上传成功
     */
    @PostMapping("/upload/activity/{activityId}")
    @RequirePermission(PermissionOperates.SALES_UPLOAD_PROJECT_FILE)
    public Result<Boolean> uploadByActivity(
            @PathVariable("activityId") Long activityId,
            @ModelAttribute ProjectFileListRequest fileListRequest) {
        projectFileService.uploadByActivity(activityId, fileListRequest.getFiles());
        return Result.success(true);
    }

    /**
     * 关联订单上传文件
     * @param orderId 订单项ID
     * @param fileListRequest 文件DTO列表
     * @return 是否上传成功
     */
    @PostMapping("/upload/order/{orderId}")
    @RequirePermission(PermissionOperates.SALES_UPLOAD_PROJECT_FILE)
    public Result<Boolean> uploadByOrder(
            @PathVariable("orderId") Long orderId,
            @ModelAttribute ProjectFileListRequest fileListRequest) {
        projectFileService.uploadByOrder(orderId, fileListRequest.getFiles());
        return Result.success(true);
    }

    /**
     * 单独上传文件
     * @param fileListRequest 文件DTO列表
     * @return 是否上传成功
     */
    @PostMapping("/upload/standalone")
    @RequirePermission(PermissionOperates.SALES_UPLOAD_PROJECT_FILE)
    public Result<Boolean> uploadStandalone(@ModelAttribute ProjectFileListRequest fileListRequest) {
        projectFileService.uploadStandalone(fileListRequest.getFiles());
        return Result.success(true);
    }

    // ========== 文件查询接口 ==========

    /**
     * 查看销售机会下的文件列表
     * @param opportunityId 销售机会ID
     * @return 文件列表
     */
    @GetMapping("/list/opportunity/{opportunityId}")
    @RequirePermission(PermissionOperates.SALES_VIEW_PROJECT_FILE)
    public Result<List<ProjectFileVO>> listByOpportunityId(@PathVariable("opportunityId") Long opportunityId) {
        return Result.success(projectFileService.listByOpportunityId(opportunityId));
    }

    /**
     * 分页查询文件列表
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @param queryDTO 查询条件
     * @return 分页结果
     */
    @PostMapping("/query")
    @RequirePermission(PermissionOperates.SALES_VIEW_PROJECT_FILE)
    public Result<Page<ProjectFileVO>> queryPage(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestBody(required = false) ProjectFileQueryDTO queryDTO) {
        return Result.success(projectFileService.queryPage(pageNum, pageSize, queryDTO));
    }

    /**
     * 查看业务活动下的文件列表
     * @param activityId 业务活动ID
     * @return 文件列表
     */
    @GetMapping("/list/activity/{activityId}")
    @RequirePermission(PermissionOperates.SALES_VIEW_PROJECT_FILE)
    public Result<List<ProjectFileVO>> listByActivityId(@PathVariable("activityId") Long activityId) {
        return Result.success(projectFileService.listByActivityId(activityId));
    }

    /**
     * 查看订单项下的文件列表
     * @param orderId 订单项ID
     * @return 文件列表
     */
    @GetMapping("/list/order/{orderId}")
    @RequirePermission(PermissionOperates.SALES_VIEW_PROJECT_FILE)
    public Result<List<ProjectFileVO>> listByOrderId(@PathVariable("orderId") Long orderId) {
        return Result.success(projectFileService.listByOrderId(orderId));
    }

    /**
     * 查看合同下的文件列表(聚合所有订单项文件)
     * @param contractId 合同ID
     * @return 文件列表
     */
    @GetMapping("/list/contract/{contractId}")
    @RequirePermission(PermissionOperates.SALES_VIEW_PROJECT_FILE)
    public Result<List<ProjectFileVO>> listByContractId(@PathVariable("contractId") Long contractId) {
        return Result.success(projectFileService.listByContractId(contractId));
    }

    // ========== 文件删除接口 ==========

    /**
     * 删除文件
     * @param ids 文件ID列表
     * @return 是否删除成功
     */
    @DeleteMapping
    @RequirePermission(PermissionOperates.SALES_DELETE_PROJECT_FILE)
    public Result<Boolean> deleteByIds(@RequestParam List<Long> ids) {
        projectFileService.deleteByIds(ids);
        return Result.success(true);
    }

    // ========== 内部类 ==========

    /**
     * 文件列表请求包装类
     */
    @Data
    public static class ProjectFileListRequest {
        private List<ProjectFileDTO> files;
    }
}
