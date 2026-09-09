package com.slz.crm.server.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.result.Result;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.dto.ContactTaskDTO;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AttachmentDeleteResultVO;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.ContactTaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * 联络任务控制器
 */
@RestController
@RequestMapping("/contactTask")
@Slf4j
public class ContactTaskController {

    @Autowired
    private ContactTaskService contactTaskService;

    @Autowired
    private ApprovalAttachmentService approvalAttachmentService;

    @Autowired
    private AttachmentAccessService attachmentAccessService;

    /**
     * 上传联络任务普通附件（创建人/指派人/执行人/超管；协助人必须走 assist 专用接口）
     */
    @PostMapping("/{id}/attachments")
    @RequirePermission(PermissionOperates.TASK_UPDATE_TASK)
    public Result<Boolean> uploadAttachments(
            @PathVariable Long id,
            @org.springframework.web.bind.annotation.ModelAttribute UploadAttachmentsRequest request) {
        if (!attachmentAccessService.canWriteAttachments(ModelName.CONTACT_TASK, id, BaseUnit.getCurrentId())) {
            throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权上传联络任务附件");
        }
        approvalAttachmentService.saveAttachments(id, ModelName.CONTACT_TASK, request.getAttachments());
        return Result.success(true);
    }

    /**
     * 查询联络任务附件（含下载URL）
     */
    @GetMapping("/{id}/attachments")
    @RequirePermission(PermissionOperates.TASK_VIEW_TASK)
    public Result<List<ApprovalAttachmentVO>> attachments(@PathVariable Long id) {
        if (!attachmentAccessService.canReadAttachments(ModelName.CONTACT_TASK, id, BaseUnit.getCurrentId())) {
            throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权读取该联络任务附件");
        }
        return Result.success(approvalAttachmentService.getByAndIds(
                Collections.singletonList(id), ModelName.CONTACT_TASK));
    }

    /**
     * 删除联络任务附件（上传人本人/创建人/指派人/执行人/超管可删）
     */
    @DeleteMapping("/{id}/attachments")
    @RequirePermission(PermissionOperates.TASK_DELETE_TASK)
    public Result<AttachmentDeleteResultVO> deleteAttachments(
            @PathVariable Long id,
            @RequestParam List<Long> attachmentIds) {
        if (!attachmentAccessService.canWriteAttachments(ModelName.CONTACT_TASK, id, BaseUnit.getCurrentId())) {
            throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权删除联络任务附件");
        }
        return Result.success(approvalAttachmentService.removeAuthorizedByIds(
                attachmentIds, ModelName.CONTACT_TASK, id));
    }

    /**
     * 上传附件请求体（multipart 索引字段）
     */
    public static class UploadAttachmentsRequest {
        private List<ApprovalAttachmentDTO> attachments;

        public List<ApprovalAttachmentDTO> getAttachments() {
            return attachments;
        }

        public void setAttachments(List<ApprovalAttachmentDTO> attachments) {
            this.attachments = attachments;
        }
    }

    /**
     * 创建联络任务
     */
    @PostMapping("/create")
    @RequirePermission(PermissionOperates.TASK_CREATE_TASK)
    public Result<Boolean> create(@RequestBody ContactTaskDTO contactTaskDTO) {
        Boolean result = contactTaskService.create(contactTaskDTO);
        return Result.success(result);
    }

    /**
     * 批量删除联络任务
     */
    @DeleteMapping("/batch")
    @RequirePermission(PermissionOperates.TASK_DELETE_TASK)
    public Result<Integer> deleteByIds(@RequestBody List<Long> idList) {
        Integer count = contactTaskService.deleteByIds(idList);
        return Result.success(count);
    }

    /**
     * 删除单个联络任务
     */
    @DeleteMapping("/{id}")
    @RequirePermission(PermissionOperates.TASK_DELETE_TASK)
    public Result<Boolean> deleteById(@PathVariable Long id) {
        Boolean result = contactTaskService.deleteById(id);
        return Result.success(result);
    }

    /**
     * 批量更新联络任务
     */
    @PutMapping("/batch")
    @RequirePermission(PermissionOperates.TASK_UPDATE_TASK)
    public Result<Integer> updateList(@RequestBody List<ContactTaskDTO> contactTaskDTOList) {
        Integer count = contactTaskService.updateList(contactTaskDTOList);
        return Result.success(count);
    }

    /**
     * 更新单个联络任务
     */
    @PutMapping("/update")
    @RequirePermission(PermissionOperates.TASK_UPDATE_TASK)
    public Result<Boolean> update(@RequestBody ContactTaskDTO contactTaskDTO) {
        Boolean result = contactTaskService.update(contactTaskDTO);
        return Result.success(result);
    }

    /**
     * 根据ID查询联络任务详情
     */
    @GetMapping("/{id}")
    @RequirePermission(PermissionOperates.TASK_VIEW_TASK)
    public Result<ContactTaskVO> getById(@PathVariable Long id) {
        ContactTaskVO contactTaskVO = contactTaskService.getById(id);
        return Result.success(contactTaskVO);
    }

    /**
     * 分页查询所有联络任务
     */
    @GetMapping("/all")
    @RequirePermission(PermissionOperates.TASK_VIEW_TASK)
    public Result<Page<ContactTaskVO>> getAll(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        Page<ContactTaskVO> page = contactTaskService.query(pageNum, pageSize,new ContactTaskDTO());
        return Result.success(page);
    }

    /**
     * 根据条件分页查询联络任务
     */
    @PostMapping("/query")
    @RequirePermission(PermissionOperates.TASK_VIEW_TASK)
    public Result<Page<ContactTaskVO>> query(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize,
            @RequestBody ContactTaskDTO contactTaskDTO) {
        Page<ContactTaskVO> page = contactTaskService.query(pageNum, pageSize, contactTaskDTO);
        return Result.success(page);
    }

    /**
     * 根据执行人ID查询任务列表
     */
    @GetMapping("/assignee/{assigneeId}")
    @RequirePermission(PermissionOperates.TASK_VIEW_TASK)
    public Result<List<ContactTaskVO>> getByAssigneeId(@PathVariable Long assigneeId) {
        List<ContactTaskVO> tasks = contactTaskService.getByAssigneeId(assigneeId);
        return Result.success(tasks);
    }

    /**
     * 根据公司ID查询任务列表
     */
    @GetMapping("/company/{companyId}")
    @RequirePermission(PermissionOperates.TASK_VIEW_TASK)
    public Result<List<ContactTaskVO>> getByCompanyId(@PathVariable Long companyId) {
        List<ContactTaskVO> tasks = contactTaskService.getByCompanyId(companyId);
        return Result.success(tasks);
    }

    /**
     * 根据联系人ID查询任务列表
     */
    @GetMapping("/contact/{contactId}")
    @RequirePermission(PermissionOperates.TASK_VIEW_TASK)
    public Result<List<ContactTaskVO>> getByContactId(@PathVariable Long contactId) {
        List<ContactTaskVO> tasks = contactTaskService.getByContactId(contactId);
        return Result.success(tasks);
    }

    /**
     * 根据销售机会ID查询任务列表
     */
    @GetMapping("/opportunity/{opportunityId}")
    @RequirePermission(PermissionOperates.TASK_VIEW_TASK)
    public Result<List<ContactTaskVO>> getByOpportunityId(@PathVariable Long opportunityId) {
        List<ContactTaskVO> tasks = contactTaskService.getByOpportunityId(opportunityId);
        return Result.success(tasks);
    }
}
