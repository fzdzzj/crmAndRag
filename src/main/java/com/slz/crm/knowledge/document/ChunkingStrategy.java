package com.slz.crm.knowledge.document;

import java.util.List;

/**
 * 切分策略抽象（提案4 任务 1.1，方案02 Semantic Chunking）。
 *
 * <p>策略只负责把<b>单个页级</b>的归一化文本切分为切片序列；页码/行号锚点由
 * {@link DocumentService} 按页统一附加——策略从不跨页聚合，保证任何策略下
 * D15 页级来源引用的锚点语义不变。</p>
 */
public interface ChunkingStrategy {

    /**
     * 把单个页级的归一化文本切分为切片序列。
     *
     * @param normalizedPageText 已归一化（换行统一、空白折叠、去首尾）的页文本
     * @return 有序切片；不允许为 null，也不允许包含空文本
     */
    List<StrategyChunk> split(String normalizedPageText);

    /**
     * 单个策略切片。
     *
     * @param text       切片文本
     * @param parentText 所属逻辑段（父块）全文；null = 无独立父块（切片自身即逻辑段）
     */
    record StrategyChunk(String text, String parentText) {
    }
}
