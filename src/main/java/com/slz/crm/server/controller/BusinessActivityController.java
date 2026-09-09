package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.result.Result;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.AddActivityContactRequestDTO;
import com.slz.crm.pojo.dto.AddActivityUserRequestDTO;
import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.dto.BatchDeleteActivityAssociationRequestDTO;
import com.slz.crm.pojo.dto.BusinessActivityDTO;
import com.slz.crm.pojo.dto.BusinessActivityQueryDTO;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AttachmentDeleteResultVO;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.BusinessActivityService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

/**
 * 业务活动
 */
@RestController
@RequestMapping("/business/activity")
public class BusinessActivityController {

    @Autowired
    private BusinessActivityService businessActivityService;

    @Autowired
    private ApprovalAttachmentService approvalAttachmentService;

    @Autowired
    private AttachmentAccessService attachmentAccessService;

    /**
     * 创建活动
     * @param dto 活动DTO
     * @return 是否创建成功
     */
    @PostMapping
    @RequirePermission(PermissionOperates.SALES_CREATE_BUSINESS_ACTIVITY)
    public Result<Boolean> create(@RequestBody BusinessActivityDTO dto) {
        return Result.success(businessActivityService.create(dto));
    }

    /**
     * 删除活动
     * @param idList 活动ID列表
     * @return 是否删除成功
     */
    @DeleteMapping
    @RequirePermission(PermissionOperates.SALES_DELETE_BUSINESS_ACTIVITY)
    public Result<Integer> delete(@RequestBody List<Long> idList) {
        return Result.success(businessActivityService.deleteByIds(idList));
    }

    /**
     * 更新活动
     * @param businessActivityDTOList 活动DTO列表
     * @return 成功更新的数量
     */
    @PutMapping
    @RequirePermission(PermissionOperates.SALES_UPDATE_BUSINESS_ACTIVITY)
    public Result<Integer> update(@RequestBody List<BusinessActivityDTO> businessActivityDTOList) {
        return Result.success(businessActivityService.updateList(businessActivityDTOList));
    }

    /**
     * 获取所有活动
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @return 活动列表
     */
    @GetMapping
    @RequirePermission(PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY)
    public Result<List<BusinessActivityVO>> getAllActivity(@RequestParam Integer pageNum, @RequestParam Integer pageSize) {
        return Result.success(businessActivityService.businessActivityQuery(pageNum, pageSize, new BusinessActivityQueryDTO()).getRecords());
    }

    /**
     * 自定义分页查询活动列表
     * @param pageNum 页码
     * @param pageSize 每页数量
     * @param dto 活动查询条件DTO
     * @return 活动列表
     */
    @PostMapping("/query")
    @RequirePermission(PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY)
    public Result<Page<BusinessActivityVO>> activityQuery(@RequestParam Integer pageNum, @RequestParam Integer pageSize, @RequestBody BusinessActivityQueryDTO dto) {
        Page<BusinessActivityVO> page = businessActivityService.businessActivityQuery(pageNum, pageSize, dto);
        return Result.success(page);
    }

    /**
     * 根据ID查询活动详情
     * @param id 活动ID
     * @return 活动详情
     */
    @GetMapping("/{id}")
    @RequirePermission(PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY)
    public Result<BusinessActivityVO> getById(@PathVariable Long id) {
        return Result.success(businessActivityService.getDetailById(id));
    }

    // ========== 商业活动附件管理接口 ==========

    /**
     * 上传商业活动附件（使用 @ModelAttribute 普通上传）
     *
     * @param id 活动ID
     * @param attachmentListRequest 附件DTO列表（包含文件数据）
     * @return 是否上传成功
     */
    @PostMapping("/{id}/attachments")
    @RequirePermission(PermissionOperates.SALES_UPDATE_BUSINESS_ACTIVITY)
    public Result<Boolean> uploadAttachments(
            @PathVariable("id") Long id,
            @ModelAttribute AttachmentListRequest attachmentListRequest) {
        if (!attachmentAccessService.canWriteAttachments(ModelName.BUSINESS_ACTIVITY, id, BaseUnit.getCurrentId())) {
            throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权上传业务活动附件");
        }
        approvalAttachmentService.saveAttachments(id, ModelName.BUSINESS_ACTIVITY, attachmentListRequest.getAttachments());
        return Result.success(true);
    }

    /**
     * 附件列表请求包装类
     */
    @Data
    public static class AttachmentListRequest {
        private List<ApprovalAttachmentDTO> attachments;
    }

    /**
     * 获取商业活动附件列表
     * @param id 活动ID
     * @return 附件列表（包含带令牌的下载URL）
     */
    @GetMapping("/{id}/attachments")
    @RequirePermission(PermissionOperates.SALES_VIEW_BUSINESS_ACTIVITY)
    public Result<List<ApprovalAttachmentVO>> getAttachments(@PathVariable("id") Long id) {
        // 先复用业务详情的记录级隐私校验，再由附件服务校验附件读取范围。
        businessActivityService.getDetailById(id);
        if (!attachmentAccessService.canReadAttachments(ModelName.BUSINESS_ACTIVITY, id, BaseUnit.getCurrentId())) {
            throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权读取该业务活动附件");
        }
        List<ApprovalAttachmentVO> attachments = approvalAttachmentService.getByAndIds(
                List.of(id),
                ModelName.BUSINESS_ACTIVITY
        );
        return Result.success(attachments);
    }

    /**
     * 删除商业活动附件（通过附件ID删除）
     * @param attachmentIds 附件ID列表
     * @return 是否删除成功
     */
    @DeleteMapping("/attachments")
    @RequirePermission(PermissionOperates.SALES_DELETE_BUSINESS_ACTIVITY)
    public Result<AttachmentDeleteResultVO> deleteAttachments(@RequestParam List<Long> attachmentIds) {
        Set<Long> activityIds = approvalAttachmentService.getRelatedRecordIds(
                attachmentIds, ModelName.BUSINESS_ACTIVITY);
        for (Long activityId : activityIds) {
            if (!attachmentAccessService.canWriteAttachments(
                    ModelName.BUSINESS_ACTIVITY, activityId, BaseUnit.getCurrentId())) {
                throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权删除业务活动附件");
            }
        }
        // 主动删除分级：上传人本人/申请人/创建人/超管可删，协助人删别人的拒绝
        return Result.success(approvalAttachmentService.removeAuthorizedByIds(
                attachmentIds, ModelName.BUSINESS_ACTIVITY, null));
    }

    // ========== 商业活动联系人关联接口 ==========

    /**
     * 向活动添加联系人
     * @param activityId 活动ID
     * @param contactRequestList 联系人请求列表
     * @return 是否添加成功
     */
    @PostMapping("/{activityId}/contacts")
    @RequirePermission(PermissionOperates.SALES_UPDATE_BUSINESS_ACTIVITY)
    public Result<Boolean> addContacts(
            @PathVariable("activityId") Long activityId,
            @RequestBody List<AddActivityContactRequestDTO> contactRequestList) {
        return Result.success(businessActivityService.addContactsToActivity(activityId, contactRequestList));
    }

    // ========== 商业活动用户关联接口 ==========

    /**
     * 向活动添加用户
     * @param activityId 活动ID
     * @param userRequestList 用户请求列表
     * @return 是否添加成功
     */
    @PostMapping("/{activityId}/users")
    @RequirePermission(PermissionOperates.SALES_UPDATE_BUSINESS_ACTIVITY)
    public Result<Boolean> addUsers(
            @PathVariable("activityId") Long activityId,
            @RequestBody List<AddActivityUserRequestDTO> userRequestList) {
        return Result.success(businessActivityService.addUsersToActivity(activityId, userRequestList));
    }

    // ========== 商业活动关联批量删除接口 ==========

    /**
     * 批量删除活动的联系人和用户
     * @param activityId 活动ID
     * @param request 删除请求
     * @return 是否删除成功
     */
    @DeleteMapping("/{activityId}/associations")
    @RequirePermission(PermissionOperates.SALES_DELETE_BUSINESS_ACTIVITY)
    public Result<Boolean> deleteAssociations(
            @PathVariable("activityId") Long activityId,
            @RequestBody BatchDeleteActivityAssociationRequestDTO request) {
        return Result.success(businessActivityService.deleteAssociations(activityId, request));
    }
}
