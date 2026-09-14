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
import com.slz.crm.pojo.dto.AssistHandleDTO;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.pojo.dto.AssistAppendDTO;
import com.slz.crm.pojo.dto.AssistMessageDTO;
import com.slz.crm.pojo.dto.AssistReapplyDTO;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AttachmentDeleteResultVO;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.AssistMessageVO;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.pojo.vo.CustomerCompanyVO;
import com.slz.crm.pojo.vo.CustomerContactVO;
import com.slz.crm.pojo.vo.OpportunityDetailVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;
import com.slz.crm.server.service.ApprovalAttachmentService;
import com.slz.crm.server.service.AssistRequestService;
import com.slz.crm.server.service.AssistMessageService;
import com.slz.crm.server.service.CustomerCompanyService;
import com.slz.crm.server.service.CustomerContactService;
import com.slz.crm.server.service.SalesOpportunityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.List;

/**
 * 协助申请（登录用户可查看/处理自己被指派的协助）
 */
@RestController
@RequestMapping("/assist")
public class AssistController {

    @Autowired
    private AssistRequestService assistRequestService;

    @Autowired
    private AssistMessageService assistMessageService;

    @Autowired
    private ApprovalAttachmentService approvalAttachmentService;

    @Autowired
    private SalesOpportunityService salesOpportunityService;

    @Autowired
    private CustomerCompanyService customerCompanyService;

    @Autowired
    private CustomerContactService customerContactService;


    /**
     * 分页查询当前登录用户被指派的协助申请
     *
     * @param pageNum      页码
     * @param pageSize     每页数量
     * @param assistStatus 协助状态过滤（0待协助/1已协助/2已驳回/3已拒绝，可空）
     * @return 协助VO分页
     */
    @GetMapping("/my")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<Page<AssistVO>> myAssists(@RequestParam Integer pageNum,
                                            @RequestParam Integer pageSize,
                                            @RequestParam(required = false) Integer assistStatus) {
        if (pageNum == null || pageNum <= 0) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize <= 0) {
            pageSize = 10;
        }
        return Result.success(assistRequestService.pageMyAssists(pageNum, pageSize, assistStatus));
    }

    /**
     * 协助人处理协助申请（提交协助意见）
     *
     * @param dto 协助处理信息
     * @return 是否成功
     */
    @PutMapping
    // apply-permission-matrix 任务 1.2：AI 模块协助处理
    @RequirePermission(PermissionOperates.AI_ASSIST_HANDLE)
    public Result<Boolean> handle(@RequestBody AssistHandleDTO dto) {
        return Result.success(assistRequestService.handleAssist(dto));
    }

    /**
     * 分页查询当前登录用户作为申请人发起的协助申请
     *
     * @param pageNum      页码
     * @param pageSize     每页数量
     * @param assistStatus 协助状态过滤（0待协助/1已协助/2已驳回/3已拒绝，可空）
     * @return 协助VO分页
     */
    @GetMapping("/applications")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<Page<AssistVO>> myApplications(@RequestParam Integer pageNum,
                                                 @RequestParam Integer pageSize,
                                                 @RequestParam(required = false) Integer assistStatus) {
        if (pageNum == null || pageNum <= 0) {
            pageNum = 1;
        }
        if (pageSize == null || pageSize <= 0) {
            pageSize = 10;
        }
        return Result.success(assistRequestService.pageMyApplications(pageNum, pageSize, assistStatus));
    }

    /**
     * 驳回后重新申请（原记录保留，新记录 parent_id 指向原记录）
     *
     * @param dto 重新申请信息
     * @return 新协助记录ID
     */
    @PostMapping("/reapply")
    // apply-permission-matrix 任务 1.2：AI 模块协助申请
    @RequirePermission(PermissionOperates.AI_ASSIST_APPLY)
    public Result<Long> reapply(@RequestBody AssistReapplyDTO dto) {
        return Result.success(assistRequestService.reapply(
                dto.getOriginalAssistId(), dto.getAssistApplyList()));
    }

    /**
     * 在同一业务记录上追加新的协助人。原协助记录和处理历史均保留。
     */
    @PostMapping("/append")
    // apply-permission-matrix 任务 1.2：AI 模块协助申请
    @RequirePermission(PermissionOperates.AI_ASSIST_APPLY)
    public Result<Boolean> append(@RequestBody AssistAppendDTO dto) {
        assistRequestService.appendAssists(dto.getOriginalAssistId(), dto.getAssistApplyList());
        return Result.success(true);
    }

    /**
     * 由活动参与人、任务执行人/指派人发起新的协助申请。
     */
    @PostMapping("/apply")
    // apply-permission-matrix 任务 1.2：AI 模块协助申请
    @RequirePermission(PermissionOperates.AI_ASSIST_APPLY)
    public Result<Boolean> apply(@RequestParam String modelName,
                                 @RequestParam Long recordId,
                                 @RequestBody List<AssistApplyItem> applyList) {
        assistRequestService.applyAssists(modelName, recordId, applyList);
        return Result.success(true);
    }

    /**
     * 查询协助详情（含商机快照与附件元数据；列表不返回，仅详情按需）
     *
     * @param id 协助记录ID
     * @return 协助VO（含 snapshot）
     */
    @GetMapping("/{id}/detail")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<AssistVO> detail(@PathVariable Long id) {
        return Result.success(assistRequestService.getDetail(id));
    }

    /** 查询本次协助的过程消息。 */
    @GetMapping("/{id}/messages")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<List<AssistMessageVO>> messages(@PathVariable Long id) {
        return Result.success(assistMessageService.listVisible(id));
    }

    /** 待协助期间，申请人与协助人可发送文本说明。 */
    @PostMapping("/{id}/messages")
    // apply-permission-matrix 任务 1.2：AI 模块协助申请
    @RequirePermission(PermissionOperates.AI_ASSIST_APPLY)
    public Result<Boolean> sendMessage(@PathVariable Long id, @RequestBody AssistMessageDTO dto) {
        assistMessageService.sendText(id, dto == null ? null : dto.getContent());
        return Result.success(true);
    }

    /**
     * 查询协助记录关联的只读业务对象索引。
     */
    @GetMapping("/{id}/related")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<AssistRelatedRecordVO> related(@PathVariable Long id) {
        return Result.success(assistRequestService.getRelatedRecord(id));
    }

    /**
     * 从协助页按需查看关联销售机会详情；目标商机由后端通过 assistId 反查。
     */
    @GetMapping("/{id}/opportunity")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<OpportunityDetailVO> opportunity(@PathVariable Long id) {
        AssistRelatedRecordVO related = assistRequestService.getRelatedRecord(id);
        if (related.getOpportunityId() == null) {
            throw new BaseException(ErrorCode.OPPORTUNITY_NOT_EXISTS, "该协助记录未关联销售机会");
        }
        return Result.success(salesOpportunityService.getOpportunityDetailById(related.getOpportunityId()));
    }

    /**
     * 从协助页按需查看关联客户公司详情；目标公司由后端通过 assistId 反查。
     */
    @GetMapping("/{id}/company")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<CustomerCompanyVO> company(@PathVariable Long id) {
        AssistRelatedRecordVO related = assistRequestService.getRelatedRecord(id);
        if (related.getCompanyId() == null) {
            throw new BaseException(ErrorCode.COMPANY_NOT_EXISTS, "该协助记录未关联客户公司");
        }
        return Result.success(customerCompanyService.getCompanyDetail(related.getCompanyId()));
    }

    /**
     * 从协助页按需查看主要联系人详情；目标联系人由后端通过 assistId 反查。
     */
    @GetMapping("/{id}/contact")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<CustomerContactVO> contact(@PathVariable Long id) {
        AssistRelatedRecordVO related = assistRequestService.getRelatedRecord(id);
        if (related.getContactId() == null) {
            throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "该协助记录未关联主要联系人");
        }
        CustomerContactVO contact = customerContactService.get(related.getContactId());
        if (contact == null) {
            throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "主要联系人不存在或已删除");
        }
        return Result.success(contact);
    }

    /**
     * 从协助页按需查看审批详情。目标审批记录只能由 assistId 反查，不能由前端传入任意 recordId。
     */
    @GetMapping("/{id}/approval")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<SalesStageApprovalVO> approval(@PathVariable Long id) {
        return Result.success(assistRequestService.getRelatedApproval(id));
    }

    /**
     * 从协助页按需查看业务活动详情。仅允许业务活动来源的待协助记录。
     */
    @GetMapping("/{id}/activity")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<BusinessActivityVO> activity(@PathVariable Long id) {
        return Result.success(assistRequestService.getRelatedActivity(id));
    }

    /**
     * 从协助页按需查看联络任务详情。仅允许联络任务来源的待协助记录。
     */
    @GetMapping("/{id}/task")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<ContactTaskVO> task(@PathVariable Long id) {
        return Result.success(assistRequestService.getRelatedTask(id));
    }

    /** 当前业务活动协助的附件（仅待协助期间）。 */
    @GetMapping("/{id}/activity/attachments")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<List<ApprovalAttachmentVO>> sourceActivityAttachments(@PathVariable Long id) {
        AssistVO assist = assistRequestService.getDetail(id);
        if (!ModelName.BUSINESS_ACTIVITY.equals(assist.getModelName())) {
            throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "当前协助来源不是业务活动");
        }
        return Result.success(assistRequestService.getRelatedActivityAttachments(id, assist.getRecordId()));
    }

    /** 当前联络任务协助的附件（仅待协助期间）。 */
    @GetMapping("/{id}/task/attachments")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<List<ApprovalAttachmentVO>> sourceTaskAttachments(@PathVariable Long id) {
        return Result.success(assistRequestService.getRelatedTaskAttachments(id));
    }

    /** 通过协助上下文上传当前业务活动/联络任务附件。 */
    @PostMapping("/{id}/source-attachments")
    // apply-permission-matrix 任务 1.2：AI 模块协助申请
    @RequirePermission(PermissionOperates.AI_ASSIST_APPLY)
    public Result<Boolean> uploadSourceAttachments(
            @PathVariable Long id,
            @ModelAttribute UploadAttachmentsRequest request) {
        assistRequestService.uploadRelatedAttachments(id, request.getAttachments());
        return Result.success(true);
    }

    /**
     * 删除当前待协助来源的多个业务附件。
     * 该接口必须携带 assistId，不能用普通活动/任务删除接口代替。
     */
    @DeleteMapping("/{id}/source-attachments")
    // apply-permission-matrix 任务 1.2：AI 模块协助处理
    @RequirePermission(PermissionOperates.AI_ASSIST_HANDLE)
    public Result<AttachmentDeleteResultVO> deleteSourceAttachments(
            @PathVariable Long id,
            @RequestParam List<Long> attachmentIds) {
        return Result.success(assistRequestService.deleteRelatedAttachments(id, attachmentIds));
    }

    /**
     * 上传协助交付物附件（申请人/协助人/超管）
     */
    @PostMapping("/{id}/attachments")
    // apply-permission-matrix 任务 1.2：AI 模块协助申请
    @RequirePermission(PermissionOperates.AI_ASSIST_APPLY)
    public Result<Boolean> uploadAttachments(
            @PathVariable Long id,
            @org.springframework.web.bind.annotation.ModelAttribute UploadAttachmentsRequest request) {
        if (!assistRequestService.canWriteAssistDelivery(id, BaseUnit.getCurrentId())) {
            throw new BaseException(ErrorCode.PERMISSION_DENIED, "协助已结束，仅可查看历史附件");
        }
        approvalAttachmentService.saveAttachments(id, ModelName.ASSIST_REQUEST, request.getAttachments());
        return Result.success(true);
    }

    /**
     * 查询协助交付物附件（含下载URL）
     */
    @GetMapping("/{id}/attachments")
    // apply-permission-matrix 任务 1.2：AI 模块协助查看
    @RequirePermission(PermissionOperates.AI_ASSIST_VIEW)
    public Result<List<ApprovalAttachmentVO>> attachments(@PathVariable Long id) {
        if (!assistRequestService.isOperable(ModelName.ASSIST_REQUEST, id, BaseUnit.getCurrentId())) {
            throw new BaseException(ErrorCode.PERMISSION_DENIED);
        }
        return Result.success(approvalAttachmentService.getByAndIds(
                Collections.singletonList(id), ModelName.ASSIST_REQUEST));
    }

    /**
     * 删除协助交付物附件（上传人本人/申请人/超管可删）
     */
    @DeleteMapping("/{id}/attachments")
    // apply-permission-matrix 任务 1.2：AI 模块协助处理
    @RequirePermission(PermissionOperates.AI_ASSIST_HANDLE)
    public Result<AttachmentDeleteResultVO> deleteAttachments(
            @PathVariable Long id,
            @RequestParam List<Long> attachmentIds) {
        if (!assistRequestService.canWriteAssistDelivery(id, BaseUnit.getCurrentId())) {
            throw new BaseException(ErrorCode.PERMISSION_DENIED, "协助已结束，仅可查看历史附件");
        }
        return Result.success(approvalAttachmentService.removeAuthorizedByIds(
                attachmentIds, ModelName.ASSIST_REQUEST, id));
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
}
