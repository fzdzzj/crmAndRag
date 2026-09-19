package com.slz.crm.pojo.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库列表项 VO（admin）。
 */
@Data
public class KnowledgeBaseVO {
    private Long id;
    private String name;
    private String displayName;
    private String visibility;
    private String ownerUserId;
    private LocalDateTime createTime;
}
