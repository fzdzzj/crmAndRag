package com.slz.crm.pojo.dto;

import lombok.Data;
import org.springframework.web.multipart.MultipartFile;

/**
 * 项目文件上传DTO
 */
@Data
public class ProjectFileDTO {

    /**
     * 文件分类(VISIT_RECORD/MEETING_MINUTES/PROPOSAL/BID_DOCUMENT/PROJECT_CONTRACT)
     */
    private String category;

    /**
     * 主题
     */
    private String theme;

    /**
     * 说明
     */
    private String description;

    /**
     * 销售机会ID(可选,单独上传时使用)
     */
    private Long opportunityId;

    /**
     * 合同ID(可选,合同详情页上传时使用)
     */
    private Long contractId;

    /**
     * 文件数据
     */
    private MultipartFile fileData;
}
