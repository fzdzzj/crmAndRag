package com.slz.crm.knowledge.document;

import java.util.List;

/**
 * 待向量化切片。
 *
 * @param text       切片文本
 * @param chunkIndex 全文档切片序号，0 起
 * @param pageNo     页码
 * @param rowIndex   Excel 行号
 * @param category   类目
 * @param keywords   关键词
 * @param parentText 所属逻辑段（父块）全文，双粒度索引用（提案4 任务 3.2）；
 *                   null = 无独立父块（fixed 策略恒为 null，或语义切分下切片自身即逻辑段）
 */
public record DocumentChunk(
        String text,
        int chunkIndex,
        Integer pageNo,
        Integer rowIndex,
        String category,
        List<String> keywords,
        String parentText) {
}
