package com.slz.crm.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tangzc.autotable.annotation.AutoTable;
import com.tangzc.autotable.annotation.TableIndex;
import com.tangzc.mpe.autotable.annotation.Column;
import com.tangzc.mpe.autotable.annotation.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审批附件表(通用附件表,支持多种模块的文件存储)
 */
@Data
@AutoTable
@Table(value = "approval_attachment", comment = "审批附件表")
@TableName("approval_attachment")
@TableIndex(name = "fk_attachment_approval", fields = {"andId"})
public class ApprovalAttachmentEntity {
    /**
     * 附件ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "附件ID")
    private Long id;

    /**
     * 关联表ID(通用字段,用于关联不同模块的数据)
     */
    @TableField("and_id")
    @Column(comment = "关联表ID", type = "bigint", notNull = true)
    private Long andId;

    /**
     * 关联模块名字(使用ModelName常量类的值)
     * 例如:approval_attachment、business_activity
     */
    @TableField("model_name")
    @Column(comment = "模块名", type = "varchar(20)", notNull = true, defaultValue = "'approval_attachment'")
    private String modelName;

    /**
     * 文件名称(原始文件名)
     */
    @TableField("file_name")
    @Column(comment = "文件名称（原始文件名）", type = "varchar(200)", notNull = true)
    private String fileName;

    /**
     * 文件存储路径(服务器存储地址)
     */
    @TableField("file_path")
    @Column(comment = "文件存储路径（服务器存储地址）", type = "varchar(500)", notNull = true)
    private String filePath;

    /**
     * 文件大小(单位:字节)
     */
    @TableField("file_size")
    @Column(comment = "文件大小（单位：字节）", type = "bigint", notNull = true)
    private Long fileSize;

    /**
     * 文件类型(如:image/png、application/pdf)
     */
    @TableField("file_type")
    @Column(comment = "文件类型（如：image/png、application/pdf）", type = "varchar(100)")
    private String fileType;

    /**
     * 上传时间
     */
    @TableField("upload_time")
    @Column(comment = "上传时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime uploadTime;

    /**
     * 上传人ID
     */
    @TableField("uploader_id")
    @Column(comment = "上传人ID", type = "bigint")
    private Long uploaderId;
}
