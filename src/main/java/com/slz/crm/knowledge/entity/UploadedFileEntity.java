package com.slz.crm.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库上传文件实体。
 */
@Data
@TableName("uploaded_file")
public class UploadedFileEntity {
    /** 数据库主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 上传人跨域引用。 */
    private String userId;

    /** 展示文件名。 */
    private String filename;

    /** 原始文件名。 */
    private String originalFilename;

    /** 小写文件类型。 */
    private String fileType;

    /** 文档业务唯一键。 */
    private String documentId;

    /** 对象存储 Key。 */
    private String storageKey;

    /** 文件大小。 */
    private Long fileSize;

    /** MIME 类型。 */
    private String contentType;

    /** 分块数。 */
    private Integer segmentCount;

    /** 向量数。 */
    private Integer vectorCount;

    /** 处理状态。 */
    private String status;

    /** 失败原因。 */
    private String errorMessage;

    /** 删除人跨域引用。 */
    private String deletedBy;

    /** 文件 SHA-256。 */
    private String fileHash;

    /** 批量任务业务键。 */
    private String batchTaskId;

    /** 所属知识库 ID 字符串。 */
    private String knowledgeBase;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;

    /** 软删除标记。 */
    @TableLogic
    private Boolean isDeleted;
}
