package com.slz.crm.pojo.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索 dry-run 响应。
 */
@Data
public class KnowledgeAdminRetrievalResponse {
    private String query;
    private Integer topK;
    private Boolean usedVector;
    private List<Candidate> candidates;

    @Data
    public static class Candidate {
        private String chunkId;
        private String text;
        private Double score;
        private Map<String, Object> metadata;
        private Long knowledgeBaseId;
        private String filename;
    }
}
