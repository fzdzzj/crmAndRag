package com.slz.crm.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 分片上传会话实体。
 */
@Data
@TableName("chunk_upload_session")
public class ChunkUploadSessionEntity {
    /** 数据库主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 分片上传业务键。 */
    private String uploadSessionId;

    /** 批量任务业务键。 */
    private String taskId;

    /** 上传人跨域引用。 */
    private String userId;

    /** 原始文件名。 */
    private String originalFilename;

    /** 总分片数。 */
    private Integer totalChunks;

    /** 已接收分片数。 */
    private Integer receivedChunks;

    /** 外部上传 ID。 */
    private String uploadId;

    /** 会话状态。 */
    private String status;

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
