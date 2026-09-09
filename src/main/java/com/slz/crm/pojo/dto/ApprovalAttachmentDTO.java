package com.slz.crm.pojo.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * 审批附件数据传输对象（通用附件DTO，支持多种模块）
 */
@Data
public class ApprovalAttachmentDTO {
    /**
     * 附件ID
     */
    private Long id;

    /**
     * 关联表ID（通用字段，用于关联不同模块的数据）
     */
    private Long andId;

    /**
     * 关联模块名称（使用ModelName常量类的值）
     * 例如：ModelName.APPROVAL_ATTACHMENT 或 ModelName.BUSINESS_ACTIVITY
     * 注意：此字段由后端设置，前端无需传入
     */
    private String modelName;

    /**
     * 文件名称（原始文件名）
     */
    private String fileName;

    /**
     * 文件类型（如：image/png、application/pdf）
     */
    private String fileType;

    /**
     * 文件数据（使用 @ModelAttribute 上传文件）
     */
    private MultipartFile fileData;

    /**
     * 上传人ID（后端设置，前端无需传入）
     */
    private Long uploaderId;
}
