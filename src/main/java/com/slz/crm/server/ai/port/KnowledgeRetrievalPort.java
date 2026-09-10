package com.slz.crm.server.ai.port;

/**
 * C↔B 知识库检索集成缝；B 提供真实实现。
 */
public interface KnowledgeRetrievalPort {
    /** 按授权范围检索知识库；无命中返回空结果。 */
    KnowledgeRetrievalResult retrieve(KnowledgeRetrievalQuery query);
}
