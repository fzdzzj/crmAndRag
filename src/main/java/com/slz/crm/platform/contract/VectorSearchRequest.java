package com.slz.crm.platform.contract;

import java.util.Collections;
import java.util.Map;

/**
 * 向量检索请求（冻结契约）。
 *
 * @param queryVector 查询向量，维度必须与集合一致
 * @param topK        返回条数上限（必须 &gt; 0）
 * @param minScore    相似度下限（0~1，为 null 表示不过滤；低于阈值的命中会被丢弃）
 * @param filter      元数据过滤（如 knowledgeBaseId / category）；空 Map 表示不过滤
 */
public record VectorSearchRequest(
        float[] queryVector,
        int topK,
        Double minScore,
        Map<String, Object> filter) {

    /**
     * 构造自检并冻结 filter（防止调用方在请求返回后改 Map 影响实现内部缓存）。
     *
     * @throws IllegalArgumentException 查询向量缺失或 topK 非法时抛出
     */
    public VectorSearchRequest {
        if (queryVector == null || queryVector.length == 0) {
            throw new IllegalArgumentException("VectorSearchRequest.queryVector 不能为空");
        }
        if (topK <= 0) {
            throw new IllegalArgumentException("VectorSearchRequest.topK 必须大于 0");
        }
        filter = filter == null ? Collections.emptyMap() : Collections.unmodifiableMap(filter);
    }
}
