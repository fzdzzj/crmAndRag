package com.slz.crm.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 批量上传文件结果实体。
 */
@Data
@TableName("batch_file_result")
public class BatchFileResultEntity {
    /** 数据库主键。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 批量任务业务键。 */
    private String taskId;

    /** 文件名。 */
    private String fileName;

    /** 是否成功。 */
    private Boolean isSuccess;

    /** 失败原因。 */
    private String errorMessage;

    /** 文档业务键。 */
    private String documentId;

    /** 上传文件 ID。 */
    private Long uploadedFileId;

    /** 分块数。 */
    private Integer segmentCount;

    /** 处理状态。 */
    private String status;

    /** 对象存储 Key。 */
    private String storageKey;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;

    /** 软删除标记。 */
    @TableLogic
    private Boolean isDeleted;
}
