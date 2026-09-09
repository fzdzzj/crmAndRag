package com.slz.crm.pojo.vo;

import lombok.Data;

import java.util.List;

/**
 * 分片状态VO
 */
@Data
public class ChunkStatusVO {
    /**
     * 文件唯一标识（MD5或UUID）
     */
    private String fileIdentifier;

    /**
     * 已上传的分片索引列表（用于断点续传）
     */
    private List<Integer> uploadedChunks;

    /**
     * 是否所有分片都已上传完成
     */
    private Boolean isComplete;
}




