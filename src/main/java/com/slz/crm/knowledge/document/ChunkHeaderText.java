package com.slz.crm.knowledge.document;

/**
 * 块头（上下文头）构造（提案4 任务 2.1，方案05 Chunk Header）。
 *
 * <p>嵌入文本 = {@code 【文件名 | 类目 | 页码】} + 块文本，让碎片块脱离文档上下文后
 * 仍携带全局视野（文件名/类目/页级锚点），缓解检索语义漂移。</p>
 *
 * <p><b>展示分离</b>：本头只用于嵌入输入——DB {@code chunk_text}、向量记录文本与
 * {@code SourceReference.excerpt} 一律保持原文，引用可读性不受前缀污染。
 * Excel 以行号为锚点（{@code 第n行}），其余类型用页码（{@code 第n页}）。</p>
 */
public final class ChunkHeaderText {

    private ChunkHeaderText() {
    }

    /**
     * 拼装带上下文头的嵌入文本。
     *
     * @param filename  来源文件名
     * @param category  业务类目；空归一为「未分类」
     * @param pageNo    页码；Excel 行锚点存在时忽略
     * @param rowIndex  Excel 行号；非空时头里用「第n行」
     * @param chunkText 切片原文
     */
    public static String wrap(String filename, String category, Integer pageNo, Integer rowIndex, String chunkText) {
        StringBuilder header = new StringBuilder("【");
        header.append(filename == null || filename.isBlank() ? "未命名" : filename.strip())
                .append(" | ")
                .append(category == null || category.isBlank() ? "未分类" : category.strip());
        if (rowIndex != null) {
            header.append(" | 第").append(rowIndex).append("行");
        } else if (pageNo != null) {
            header.append(" | 第").append(pageNo).append("页");
        }
        header.append("】\n");
        return header.append(chunkText == null ? "" : chunkText).toString();
    }
}
