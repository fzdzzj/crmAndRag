package com.slz.crm.server.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.slz.crm.pojo.dto.AssistApplyItem;
import com.slz.crm.pojo.dto.AssistHandleDTO;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AssistRelatedRecordVO;
import com.slz.crm.pojo.vo.AssistVO;
import com.slz.crm.pojo.vo.BusinessActivityVO;
import com.slz.crm.pojo.vo.ContactTaskVO;
import com.slz.crm.pojo.vo.SalesStageApprovalVO;

import java.util.List;
import java.util.Set;

/**
 * 协助申请服务
 */
public interface AssistRequestService {

    /**
     * 批量创建协助申请记录（每位协助人可携带不同的目的/要求）
     *
     * @param modelName   模块名称（ModelName 常量）
     * @param recordId    关联业务记录ID
     * @param applicantId 申请人ID
     * @param applyList   协助申请明细（可空，空则跳过）
     */
    void createAssists(String modelName, Long recordId, Long applicantId, List<AssistApplyItem> applyList);

    /**
     * 同步一条待协助业务记录：已有协助人更新内容，新增协助人创建待协助记录；
     * 请求中未出现且仍处于待协助状态的协助人删除。
     */
    void updatePendingAssists(String modelName, Long recordId, Long applicantId, List<AssistApplyItem> applyList);

    /**
     * 按业务记录批量查询协助记录（含协助人姓名、部门）
     *
     * @param modelName 模块名称
     * @param recordIds 业务记录ID列表
     * @return 协助VO列表
     */
    List<AssistVO> listAssistsByRecords(String modelName, List<Long> recordIds);

    /**
     * 查询某条业务记录的协助记录（含协助人姓名、部门）
     *
     * @param modelName 模块名称
     * @param recordId  业务记录ID
     * @return 协助VO列表
     */
    List<AssistVO> listAssistsByRecord(String modelName, Long recordId);

    /**
     * 查询指定用户作为协助人的待处理业务记录ID集合。
     *
     * 仅用于单条业务详情/操作的实时协助授权，不得用于扩大普通业务列表范围。
     *
     * @param modelName 模块名称
     * @param userId    用户ID
     * @return 业务记录ID集合
     */
    Set<Long> getRelatedRecordIdsByUser(String modelName, Long userId);

    /**
     * 按隐私可见性返回某条业务记录的协助列表：
     * 超管/申请人可见全部协助记录；协助人只能看到指派给自己的记录；其他人返回空列表
     *
     * @param modelName     模块名称
     * @param recordId      业务记录ID
     * @param currentUserId 当前登录用户ID
     * @return 可见的协助VO列表
     */
    List<AssistVO> getVisibleAssists(String modelName, Long recordId, Long currentUserId);

    /**
     * 协助人处理协助申请（仅本人可操作）
     *
     * @param dto 协助处理信息
     * @return 是否成功
     */
    Boolean handleAssist(AssistHandleDTO dto);

    /**
     * 分页查询当前登录用户被指派的协助申请
     *
     * @param pageNum      页码
     * @param pageSize     每页数量
     * @param assistStatus 协助状态过滤（可空）
     * @return 协助VO分页
     */
    Page<AssistVO> pageMyAssists(Integer pageNum, Integer pageSize, Integer assistStatus);

    /**
     * 分页查询当前登录用户作为申请人发起的协助申请
     *
     * @param pageNum      页码
     * @param pageSize     每页数量
     * @param assistStatus 协助状态过滤（可空）
     * @return 协助VO分页
     */
    Page<AssistVO> pageMyApplications(Integer pageNum, Integer pageSize, Integer assistStatus);

    /**
     * 查询协助详情（终态使用按来源模型冻结的 recordSnapshot；列表接口不返回，仅详情按需）
     *
     * @param id 协助记录ID
     * @return 协助VO（含 recordSnapshot）
     */
    AssistVO getDetail(Long id);

    /**
     * 查询待协助记录关联的商机、公司和主要联系人索引。
     *
     * 仅申请人、协助人和超管可调用，且终态协助不再授予实时关联详情访问权。
     *
     * @param id 协助记录ID
     * @return 后端反查得到的关联对象索引
     */
    AssistRelatedRecordVO getRelatedRecord(Long id);

    /**
     * 通过协助记录受控读取审批详情（仅审批来源协助可用）。
     */
    SalesStageApprovalVO getRelatedApproval(Long id);

    /**
     * 通过协助记录受控读取业务活动详情（仅活动来源协助可用）。
     */
    BusinessActivityVO getRelatedActivity(Long id);

    /**
     * 通过协助记录受控读取联络任务详情（仅任务来源协助可用）。
     */
    ContactTaskVO getRelatedTask(Long id);

    /**
     * 查询本次业务活动协助的来源活动附件。
     *
     * 活动ID必须精确等于该协助的来源记录，且协助仍待处理；
     * 联络任务和审批协助不得调用此方法。
     *
     * @param id         协助记录ID
     * @param activityId 业务活动ID
     * @return 活动附件列表
     */
    List<ApprovalAttachmentVO> getRelatedActivityAttachments(Long id, Long activityId);

    /**
     * 查询当前协助来源联络任务的附件（仅待协助期间）。
     */
    List<ApprovalAttachmentVO> getRelatedTaskAttachments(Long id);

    /**
     * 删除当前待协助来源的多个附件。来源模型和记录必须与 assistId 精确匹配。
     */
    com.slz.crm.pojo.vo.AttachmentDeleteResultVO deleteRelatedAttachments(Long id, List<Long> attachmentIds);

    /**
     * 以协助上下文上传来源业务的活动/任务附件，绕过普通模块权限但不绕过记录和生命周期授权。
     */
    void uploadRelatedAttachments(Long id, List<com.slz.crm.pojo.dto.ApprovalAttachmentDTO> attachments);

    /**
     * 由业务创建人、活动参与人或任务执行/指派人发起协助申请。
     */
    void applyAssists(String modelName, Long recordId, List<AssistApplyItem> applyList);

    /**
     * 驳回后重新申请：创建新协助记录（parent_id 指向原记录），原记录保留
     *
     * @param originalAssistId 原驳回记录ID
     * @param applyList        重新申请明细（仅原协助人一项，包含新的目的/要求）
     * @return 新协助记录ID
     */
    Long reapply(Long originalAssistId, List<AssistApplyItem> applyList);

    /**
     * 在同一业务记录上追加新的协助人；不修改、不覆盖既有协助记录。
     */
    void appendAssists(Long originalAssistId, List<AssistApplyItem> applyList);

    /**
     * 当前用户是否可操作某条业务记录（申请人/协助人/审批人/创建人/超管）
     *
     * @param modelName 模块名称
     * @param recordId  业务记录ID
     * @param userId    当前用户ID
     * @return 是否可操作
     */
    boolean isOperable(String modelName, Long recordId, Long userId);

    /**
     * 判断当前用户能否写入协助交付物附件。
     * 交付物不属于业务活动/联络任务的普通附件，必须单独按协助生命周期校验。
     */
    boolean canWriteAssistDelivery(Long assistId, Long userId);

    /**
     * 协助来源专用入口的统一前置校验：当前用户必须是申请人或协助人，
     * 来源模型必须匹配，且协助仍处于待协助状态。
     */
    com.slz.crm.pojo.entity.AssistRequestEntity requirePendingAssistSource(
            Long assistId, String expectedModelName, Long userId);

    /**
     * 将某业务记录下仍待协助的记录取消；每条记录先冻结快照，再写入取消原因。
     */
    void cancelPendingByRecord(String modelName, Long recordId, String reason);

    /**
     * 删除某模块下多个业务记录的协助记录（级联删除）
     *
     * @param modelName 模块名称
     * @param recordIds 业务记录ID列表
     */
    void deleteByRecords(String modelName, List<Long> recordIds);
}
