package com.slz.crm.pojo.dto.ai;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 草稿工具返回结构（后端→LLM→前端）
 */
@Data
@Builder
public class AiDraftResult {
    private String pendingId;
    private String status;
    private List<String> missingFields;
    private List<String> questions;
    private Integer askRound;
    private String preview;
}
