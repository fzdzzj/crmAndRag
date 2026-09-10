package com.slz.crm.server.ai.port;

import com.slz.crm.platform.contract.SourceReference;

import java.util.List;

/**
 * 知识库检索结果，字段冻结于 contracts-frozen.md §8。
 *
 * @param context  供模型使用的上下文
 * @param sources  来源引用
 * @param hitCount 命中数量
 */
public record KnowledgeRetrievalResult(
        String context,
        List<SourceReference> sources,
        int hitCount) {

    /** 返回空结果，避免调用方处理 null。 */
    public static KnowledgeRetrievalResult empty() {
        return new KnowledgeRetrievalResult("", List.of(), 0);
    }
}
