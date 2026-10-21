package com.slz.crm.knowledge.document;

import java.util.List;
import java.util.Map;

/**
 * 待向量化切片。
 *
 * @param text 切片文本
 * @param chunkIndex 全文档切片序号，0 起
 * @param pageNo 页码
 * @param rowIndex Excel 行号
 * @param category 类目
 * @param keywords 关键词
 * @param parentText 所属逻辑段（父块）全文，双粒度索引用（提案4 任务 3.2）； null = 无独立父块（fixed 策略恒为 null，或语义切分下切片自身即逻辑段）
 * @param extraMetadata 附加元数据（add-kb-image-slice-retrieval 任务 1.3）：图片描述切片携带资产引用，落切片行 {@code
 *     extra_metadata_json} 并抄进向量 payload；普通文本切片为 null（存量口径不变）
 */
public record DocumentChunk(
    String text,
    int chunkIndex,
    Integer pageNo,
    Integer rowIndex,
    String category,
    List<String> keywords,
    String parentText,
    Map<String, Object> extraMetadata) {

  /** 兼容构造（既有调用点/测试）：无附加元数据。 */
  public DocumentChunk(
      String text,
      int chunkIndex,
      Integer pageNo,
      Integer rowIndex,
      String category,
      List<String> keywords,
      String parentText) {
    this(text, chunkIndex, pageNo, rowIndex, category, keywords, parentText, null);
  }

  /**
   * 复制并重排切片序号（图片切片在文本切片之后合并且共用一套 chunkIndex 空间）。
   *
   * @param index 新的全文档序号
   * @return 仅 chunkIndex 变化的切片
   */
  public DocumentChunk withChunkIndex(int index) {
    return new DocumentChunk(
        text, index, pageNo, rowIndex, category, keywords, parentText, extraMetadata);
  }
}
