package com.slz.crm.server.controller;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.enumeration.ModelName;
import com.slz.crm.common.enumeration.PermissionOperates;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.result.Result;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.dto.SalesStageApprovalANDAttachmentDTO;
import com.slz.crm.pojo.dto.SalesStageApprovalDTO;
import com.slz.crm.pojo.dto.SalesStageApprovalPageDTO;
import com.slz.crm.pojo.entity.SalesStageApprovalEntity;
import com.slz.crm.pojo.entity.ApprovalAttachmentEntity;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.pojo.vo.UpdateSalesStageApprovalDTO;
import com.slz.crm.server.constant.MessageConstant;
import com.slz.crm.server.mapper.ApprovalAttachmentMapper;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AttachmentAccessService;
import com.slz.crm.server.service.SalesStageApprovalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

import static java.util.stream.Collectors.toList;

/**
 * 销售机会阶段审批
 */
@RestController
@RequestMapping("/sales/stage")
@Slf4j
public class SalesStageApprovalController {


    @Autowired
    private SalesStageApprovalService salesStageApprovalService;

    @Autowired
    private ApprovalAttachmentService approvalAttachmentService;

    @Autowired
    private ApprovalAttachmentMapper approvalAttachmentMapper;

    @Autowired
    private AttachmentAccessService attachmentAccessService;

    /**
     * 推进销售机会阶段
     * 支持普通文件上传方式：直接上传完整文件（fileData）
     * 文件上传为非必填项，可以不提供附件
     *
     * @param dto 销售机会阶段审批 DTO
     * @return 销售机会阶段审批 VO 分页列表
     */
    @PostMapping("/approval")
    @Transactional
    @RequirePermission(PermissionOperates.SALES_PROGRESS_SALE_OPPORTUNITY_STAGE)
    public Result<SalesStageApprovalVO> updateStage(@ModelAttribute SalesStageApprovalANDAttachmentDTO dto) {

        // 1. 创建审批记录
        SalesStageApprovalVO vo = salesStageApprovalService.approveStage(dto.getSalesStageApproval());

        // 2. 处理附件（普通上传）- 非必填项
        // 检查列表中是否存在有效的附件（fileData 和 fileName 不为空）
        if (dto.getApprovalAttachment() != null && !dto.getApprovalAttachment().isEmpty()) {
            List<ApprovalAttachmentDTO> validAttachments = dto.getApprovalAttachment().stream()
                    .filter(attachment -> attachment.getFileData() != null && !attachment.getFileData().isEmpty()
                            && attachment.getFileName() != null && !attachment.getFileName().isEmpty())
                    .collect(toList());
            if (!validAttachments.isEmpty()) {
                approvalAttachmentService.saveAttachments(vo.getId(), validAttachments);
            }
        }

        return Result.success(vo);
    }

    /**
     * 保存阶段推进草稿。
     * 草稿不会进入审批队列，但会立即创建协助申请并保存推进附件。
     */
    @PostMapping("/approval/draft")
    @Transactional
    @RequirePermission(PermissionOperates.SALES_PROGRESS_SALE_OPPORTUNITY_STAGE)
    public Result<SalesStageApprovalVO> saveDraft(@ModelAttribute SalesStageApprovalANDAttachmentDTO dto) {
        SalesStageApprovalVO vo = salesStageApprovalService.saveDraft(dto.getSalesStageApproval());
        saveApprovalAttachments(vo.getId(), dto.getApprovalAttachment());
        return Result.success(vo);
    }

    /**
     * 读取当前登录申请人在指定销售机会下尚未提交审批的草稿。
     * 草稿仅对其申请人可见，避免通过商机 ID 读取其他人的草稿内容或协助人信息。
     */
    @GetMapping("/approval/draft")
    @RequirePermission(PermissionOperates.SALES_PROGRESS_SALE_OPPORTUNITY_STAGE)
    public Result<SalesStageApprovalVO> getDraft(@RequestParam Long opportunityId) {
        return Result.success(salesStageApprovalService.getDraftByOpportunityId(opportunityId));
    }

    /** 保存已创建但尚未提交审批的阶段推进草稿修改。 */
    @PutMapping("/approval/{id}/draft")
    @Transactional
    @RequirePermission(PermissionOperates.SALES_PROGRESS_SALE_OPPORTUNITY_STAGE)
    public Result<SalesStageApprovalVO> updateDraft(
            @PathVariable Long id, @ModelAttribute SalesStageApprovalANDAttachmentDTO dto) {
        SalesStageApprovalVO vo = salesStageApprovalService.updateDraft(id, dto.getSalesStageApproval());
        saveApprovalAttachments(vo.getId(), dto.getApprovalAttachment());
        return Result.success(vo);
    }

    /** 将当前申请人保存的阶段推进草稿提交为待审批。 */
    @PostMapping("/approval/{id}/submit")
    @RequirePermission(PermissionOperates.SALES_PROGRESS_SALE_OPPORTUNITY_STAGE)
    public Result<Boolean> submitDraft(@PathVariable Long id) {
        return Result.success(salesStageApprovalService.submitDraft(id));
    }

    private void saveApprovalAttachments(Long approvalId, List<ApprovalAttachmentDTO> attachments) {
        if (approvalId == null || attachments == null || attachments.isEmpty()) {
            return;
        }
        List<ApprovalAttachmentDTO> validAttachments = attachments.stream()
                .filter(attachment -> attachment.getFileData() != null && !attachment.getFileData().isEmpty()
                        && attachment.getFileName() != null && !attachment.getFileName().isEmpty())
                .collect(toList());
        if (!validAttachments.isEmpty()) {
            approvalAttachmentService.saveAttachments(approvalId, validAttachments);
        }
    }

    /**
     * 审批推进请求
     */
    @PutMapping
    @RequirePermission(PermissionOperates.SALES_APPROVE_STAGE_ADVANCE)
    public Result<Boolean> updateStage(@ModelAttribute UpdateSalesStageApprovalDTO dto) {

        if (dto.getApprovalStatus() == null) {
            throw new BaseException(ErrorCode.PARAM_REQUIRED, "审批状态不能为空");
        }

        SalesStageApprovalDTO ans = new SalesStageApprovalDTO();
        BeanUtils.copyProperties(dto, ans);

        ans.setApprovalStatus(List.of(dto.getApprovalStatus()));
        return Result.success(salesStageApprovalService.updateById(ans));
    }

    /**
     * 删除推进请求
     */
    @DeleteMapping
    @Transactional
    @RequirePermission(PermissionOperates.SALES_DELETE_STAGE_ADVANCE)
    public Result<Boolean> deleteStage(@RequestParam List<Long> ids) {
        // 先查询审批记录的所有附件
        List<com.slz.crm.pojo.entity.ApprovalAttachmentEntity> attachments = approvalAttachmentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.slz.crm.pojo.entity.ApprovalAttachmentEntity>()
                        .in(com.slz.crm.pojo.entity.ApprovalAttachmentEntity::getAndId, ids)
                        .eq(com.slz.crm.pojo.entity.ApprovalAttachmentEntity::getModelName, com.slz.crm.common.enumeration.ModelName.APPROVAL_ATTACHMENT)
        );

        if (attachments != null && !attachments.isEmpty()) {
            Long currentUserId = BaseUnit.getCurrentId();
            for (ApprovalAttachmentEntity attachment : attachments) {
                if (!attachmentAccessService.canWriteAttachments(
                        ModelName.APPROVAL_ATTACHMENT, attachment.getAndId(), currentUserId)) {
                    throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权删除该审批附件");
                }
            }
            List<Long> attachmentIds = attachments.stream()
                    .map(com.slz.crm.pojo.entity.ApprovalAttachmentEntity::getId)
                    .collect(toList());
            approvalAttachmentService.removeByIds(attachmentIds, com.slz.crm.common.enumeration.ModelName.APPROVAL_ATTACHMENT);
        }

        salesStageApprovalService.removeByIds(ids);
        return Result.success(true);
    }

    /**
     * 自定义查询
     *
     * @param dto 销售机会阶段审批DTO
     * @return 销售机会阶段审批VO分页列表
     */
    @GetMapping
    @RequirePermission(PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE)
    public Result<Page<SalesStageApprovalVO>> getStage(SalesStageApprovalPageDTO dto) {

        if (dto.getPageNum() == null || dto.getPageSize() == null || dto.getPageNum() <= 0 || dto.getPageSize() <= 0) {
            dto.setPageSize(10);
            dto.setPageNum(1);
        }
        Page<SalesStageApprovalEntity> page = new Page<>(dto.getPageNum(), dto.getPageSize());
        return Result.success(salesStageApprovalService.getPage(page, dto.getDto()));
    }

    /**
     * 通过销售机会阶段审批ID获取附件
     */
    @GetMapping("/attachment")
    @RequirePermission(PermissionOperates.SALES_VIEW_SALE_OPPORTUNITY_STAGE)
    public Result<List<ApprovalAttachmentVO>> getApprovalAttachment(@RequestParam List<Long> approvalIds) {
        if (approvalIds == null || approvalIds.isEmpty()) {
            return Result.success(null);
        }
        // 模块权限不等于某条审批记录的读取权；先逐条校验真实附件，避免批量接口泄漏他人审批附件。
        List<ApprovalAttachmentEntity> attachments = approvalAttachmentMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<ApprovalAttachmentEntity>()
                        .in(ApprovalAttachmentEntity::getAndId, approvalIds)
                        .eq(ApprovalAttachmentEntity::getModelName, ModelName.APPROVAL_ATTACHMENT));
        for (ApprovalAttachmentEntity attachment : attachments) {
            attachmentAccessService.assertCanRead(attachment, BaseUnit.getCurrentId());
        }
        return Result.success(approvalAttachmentService.getByAndIds(approvalIds, ModelName.APPROVAL_ATTACHMENT));
    }



}
