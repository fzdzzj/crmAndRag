package com.slz.crm.server.service;

import com.slz.crm.pojo.dto.ApprovalAttachmentDTO;
import com.slz.crm.pojo.vo.ApprovalAttachmentVO;
import com.slz.crm.pojo.vo.AttachmentDeleteResultVO;

import java.util.List;
import java.util.Set;

public interface ApprovalAttachmentService {
    /**
     * 保存审批附件
     * @param approvalAttachmentDTO 审批附件DTO列表
     */
    void saveBatch(List<ApprovalAttachmentDTO> approvalAttachmentDTO);


    /**
     * 根据审批ID删除审批附件（已废弃）
     * @param ids 审批ID列表
     * @deprecated
     */
    @Deprecated
    void removeByApprovalIds(List<Long> ids);

    /**
     * 根据审批ID获取审批附件列表（已废弃）
     * @param approvalIds 审批ID列表
     * @return 审批附件列表
     * @deprecated 使用 {@link #getByAndIds(List, String)} 替代
     */
    @Deprecated
    List<ApprovalAttachmentVO> getByApprovalIds(List<Long> approvalIds);

    /**
     * 保存附件列表（支持普通上传）（已废弃）
     * @param approvalId 审批ID
     * @param attachmentDTOList 附件DTO列表
     * @deprecated 使用 {@link #saveAttachments(Long, String, List)} 替代
     */
    @Deprecated
    void saveAttachments(Long approvalId, List<ApprovalAttachmentDTO> attachmentDTOList);

    // ========== 通用附件管理方法 ==========

    /**
     * 根据关联ID和模块名称删除附件
     * @param andIds 关联ID列表
     * @param modelName 模块名称（使用ModelName常量类的值）
     */
    void removeByIds(List<Long> andIds, String modelName);

    /**
     * 根据关联ID和模块名称获取附件列表
     * @param andIds 关联ID列表
     * @param modelName 模块名称（使用ModelName常量类的值）
     * @return 附件列表
     */
    List<ApprovalAttachmentVO> getByAndIds(List<Long> andIds, String modelName);

    /**
     * 查询来源附件并签发绑定指定待协助记录的实时下载地址。
     */
    List<ApprovalAttachmentVO> getByAndIds(List<Long> andIds, String modelName, Long activeAssistId);

    /** 协助来源专用读取：调用方已完成协助范围校验，不叠加普通业务行权限。 */
    List<ApprovalAttachmentVO> getByAndIdsForAssist(
            List<Long> andIds, String modelName, Long activeAssistId);


    /**
     * 保存附件列表（使用 @ModelAttribute 普通上传）
     * @param andId 关联ID
     * @param modelName 模块名称（使用ModelName常量类的值）
     * @param attachmentDTOList 附件DTO列表
     */
    void saveAttachments(Long andId, String modelName, List<ApprovalAttachmentDTO> attachmentDTOList);

    /**
     * 按关联ID和模块名称物理删除附件（含磁盘文件）
     * @param andIds 关联ID列表
     * @param modelName 模块名称（使用ModelName常量类的值）
     */
    void removeByAndIds(List<Long> andIds, String modelName);

    /**
     * 主动删除前校验：上传人本人可删自己的；申请人/业务创建人/超管可删全部；其他拒绝
     *
     * @param attachmentIds 附件ID列表
     * @param modelName     模块名称
     */
    void assertCanDelete(List<Long> attachmentIds, String modelName);

    /**
     * 逐项校验并删除附件。附件不存在、模块不符、路径归属不符或无权限时均保留，
     * 返回对应原因；其它有权限的附件仍继续删除。
     *
     * @param expectedAndId 带路径的删除接口传入关联记录 ID；不限制时传 {@code null}
     */
    AttachmentDeleteResultVO removeAuthorizedByIds(List<Long> attachmentIds, String modelName, Long expectedAndId);

    /**
     * 删除协助来源附件：调用方已完成 assistId、模型、来源记录和生命周期校验，
     * 本方法只按精确来源记录逐项删除，避免跨业务记录误删。
     */
    AttachmentDeleteResultVO removeSourceAttachments(List<Long> attachmentIds, String modelName, Long sourceRecordId);

    /**
     * 查询附件实际归属的业务记录 ID，仅供写入授权在删除前校验使用。
     */
    Set<Long> getRelatedRecordIds(List<Long> attachmentIds, String modelName);

    /**
     * 根据附件ID下载附件（包含文件二进制数据）
     * @param attachmentId 附件ID
     * @return 包含文件数据的VO
     */
    byte[] downloadAttachment(Long attachmentId);
}
