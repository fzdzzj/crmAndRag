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
 */
public record DocumentChunk(
        String text,
        int chunkIndex,
        Integer pageNo,
        Integer rowIndex,
        String category,
        List<String> keywords) {
}
