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
 * 项目文件表
 */
@Data
@AutoTable
@Table(value = "project_file", comment = "项目文件表")
@TableName("project_file")
@TableIndex(name = "idx_file_category", fields = {"category"})
@TableIndex(name = "idx_file_activity", fields = {"activityId"})
@TableIndex(name = "idx_file_opportunity", fields = {"opportunityId"})
@TableIndex(name = "idx_file_contract", fields = {"contractId"})
@TableIndex(name = "idx_file_order", fields = {"orderId"})
@TableIndex(name = "idx_file_uploader", fields = {"uploaderId"})
public class ProjectFileEntity {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    @Column(comment = "主键ID")
    private Long id;

    /**
     * 文件名
     */
    @TableField("file_name")
    @Column(comment = "文件名", type = "varchar(200)", notNull = true)
    private String fileName;

    /**
     * 文件存储路径
     */
    @TableField("file_path")
    @Column(comment = "文件存储路径", type = "varchar(500)", notNull = true)
    private String filePath;

    /**
     * 文件类型(如:image/png、application/pdf)
     */
    @TableField("file_type")
    @Column(comment = "文件类型", type = "varchar(100)")
    private String fileType;

    /**
     * 文件大小(字节)
     */
    @TableField("file_size")
    @Column(comment = "文件大小（字节）", type = "bigint", notNull = true)
    private Long fileSize;

    /**
     * 分类(VISIT_RECORD拜访记录/MEETING_MINUTES交流纪要/PROPOSAL方案/BID_DOCUMENT投标文件/PROJECT_CONTRACT项目合同)
     */
    @TableField("category")
    @Column(comment = "分类", type = "varchar(30)", notNull = true)
    private String category;

    /**
     * 上传时间
     */
    @TableField("upload_time")
    @Column(comment = "上传时间", type = "datetime", notNull = true, defaultValue = "CURRENT_TIMESTAMP")
    private LocalDateTime uploadTime;

    /**
     * 上传人员ID
     */
    @TableField("uploader_id")
    @Column(comment = "上传人员ID", type = "bigint", notNull = true)
    private Long uploaderId;

    /**
     * 主题
     */
    @TableField("theme")
    @Column(comment = "主题", type = "varchar(200)")
    private String theme;

    /**
     * 说明
     */
    @TableField("description")
    @Column(comment = "说明", type = "text")
    private String description;

    /**
     * 业务活动ID(可选)
     */
    @TableField("activity_id")
    @Column(comment = "业务活动ID", type = "bigint")
    private Long activityId;

    /**
     * 销售机会ID(可选)
     */
    @TableField("opportunity_id")
    @Column(comment = "销售机会ID", type = "bigint")
    private Long opportunityId;

    /**
     * 合同ID(可选)
     */
    @TableField("contract_id")
    @Column(comment = "合同ID", type = "bigint")
    private Long contractId;

    /**
     * 订单项ID(可选,仅contract_id有值时使用)
     */
    @TableField("order_id")
    @Column(comment = "订单项ID", type = "bigint")
    private Long orderId;
}
