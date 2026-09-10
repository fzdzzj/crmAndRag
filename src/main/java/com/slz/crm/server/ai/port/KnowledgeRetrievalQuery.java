package com.slz.crm.server.ai.port;

import java.util.List;

/**
 * 知识库检索查询，字段冻结于 contracts-frozen.md §8。
 *
 * @param query          改写后的用户查询
 * @param userId         当前用户主键；用于与 UserContext 交叉校验
 * @param kbScope        允许的知识库 ID；空表示用户可见全部
 * @param topK           期望命中数；空或小于 1 时用默认值
 * @param imageVector    多模态检索向量；可空
 * @param intentCategory 意图/类目过滤；可空
 */
public record KnowledgeRetrievalQuery(
        String query,
        Long userId,
        List<String> kbScope,
        Integer topK,
        float[] imageVector,
        String intentCategory) {
}
