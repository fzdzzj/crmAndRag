package com.slz.crm.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档向量切片快照实体。
 */
@Data
@TableName("document_vector_chunk")
public class DocumentVectorChunkEntity {
    /** 数据库主键，同时作为引用协议中的 chunkId。 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 文档业务键。 */
    private String documentId;

    /** 切片序号。 */
    private Integer chunkIndex;

    /** 切片原文。 */
    private String chunkText;

    /** 切片哈希。 */
    private String chunkHash;

    /** 来源文件名。 */
    private String filename;

    /** 类目标签。 */
    private String category;

    /** 关键词。 */
    private String keywords;

    /** 扩展元数据 JSON。 */
    private String extraMetadataJson;

    /** 页码。 */
    private Integer pageNo;

    /** Excel 行号。 */
    private Integer rowIndex;

    /** 创建时间。 */
    private LocalDateTime createTime;

    /** 更新时间。 */
    private LocalDateTime updateTime;

    /** 软删除标记。 */
    @TableLogic
    private Boolean isDeleted;
}
