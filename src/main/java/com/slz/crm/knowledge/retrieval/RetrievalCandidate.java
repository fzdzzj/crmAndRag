package com.slz.crm.knowledge.retrieval;

import com.slz.crm.platform.contract.VectorSearchHit;

/**
 * 检索路内候选（complete-hybrid-retrieval-and-rerank）：
 * {@code hit} 携带冻结契约数据（VectorSearchHit 原样透传），
 * {@code rerankScore} 为路内精排/融合后的混合分，仅供排序与引用相关度展示，不回写向量库。
 */
public record RetrievalCandidate(VectorSearchHit hit, double rerankScore) {

    /** 去重键：同一文档切片在向量路/稀疏路命中时视作同一候选。 */
    public String fusionKey() {
        return hit.documentId() + ":" + hit.chunkId();
    }
}
