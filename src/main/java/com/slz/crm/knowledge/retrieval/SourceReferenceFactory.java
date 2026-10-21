package com.slz.crm.knowledge.retrieval;

import com.slz.crm.knowledge.document.ImageSliceMetadata;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.VectorSearchHit;
import java.util.Locale;
import java.util.Map;

/**
 * 向量命中 → 来源引用映射（add-kb-image-slice-retrieval 任务 2.1 从 {@link KnowledgeRetrievalServiceImpl} 抽出）。
 *
 * <p>契约面零改动：仍然只填 {@link SourceReference} 既有十字段；新增的只是 {@code sourceType=IMAGE} 取值与其字段口径 （受控解冻登记见
 * {@code openspec/changes/add-kb-image-slice-retrieval/specs/knowledge-rag/spec-delta.md}）： {@code
 * documentId}=图片资产 id、{@code filename}=描述摘要、{@code chunkId}={@code IMAGE:{资产id}}。
 */
public final class SourceReferenceFactory {

  /** 图片资产摘要长度上限（{@code filename} 只放摘要，完整描述在 {@code excerpt}）。 */
  static final int IMAGE_SUMMARY_CHARS = 60;

  /** 图片资产 chunkId 前缀（与业务实体来源 {@code X:{id}} 同形）。 */
  public static final String IMAGE_CHUNK_PREFIX = ImageSliceMetadata.SOURCE_TYPE_IMAGE + ":";

  private SourceReferenceFactory() {}

  /**
   * 单条向量命中映射为来源引用。
   *
   * @param hit 向量命中（metadata 至少含 filename/fileType 或图片资产键）
   * @param relevanceScore 相似度得分（0~1）
   * @return 冻结契约 SourceReference（十字段）
   */
  public static SourceReference from(VectorSearchHit hit, double relevanceScore) {
    Map<String, Object> metadata = hit.metadata();
    String imageAssetId = string(metadata.get(ImageSliceMetadata.KEY_IMAGE_ASSET_ID));
    SourceReference reference;
    if (imageAssetId.isBlank()) {
      reference = documentReference(hit, metadata, relevanceScore);
    } else {
      reference = imageReference(hit, imageAssetId, relevanceScore);
    }
    return reference;
  }

  /** 文档类来源：沿用升级前逐字节逻辑（fileType 空则回落文件名后缀，再回落 txt）。 */
  private static SourceReference documentReference(
      VectorSearchHit hit, Map<String, Object> metadata, double relevanceScore) {
    String filename = string(metadata.get("filename"));
    String fileType = string(metadata.get("fileType"));
    if (fileType.isBlank() && filename.contains(".")) {
      fileType = filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
    }
    return new SourceReference(
        fileType.isBlank() ? "txt" : fileType,
        "hybrid",
        filename,
        hit.documentId(),
        hit.chunkId(),
        nonNegativeInteger(metadata.get("chunkIndex")),
        positiveInteger(metadata.get("pageNo")),
        positiveInteger(metadata.get("rowIndex")),
        hit.text(),
        relevanceScore);
  }

  /** 图片切片来源：documentId=资产 id（= 图片切片行主键）、filename=描述摘要、chunkId=IMAGE:{资产 id}。 */
  private static SourceReference imageReference(
      VectorSearchHit hit, String imageAssetId, double relevanceScore) {
    return new SourceReference(
        ImageSliceMetadata.SOURCE_TYPE_IMAGE,
        "hybrid",
        summarize(hit.text()),
        imageAssetId,
        IMAGE_CHUNK_PREFIX + imageAssetId,
        nonNegativeInteger(hit.metadata().get("chunkIndex")),
        positiveInteger(hit.metadata().get("pageNo")),
        null,
        hit.text(),
        relevanceScore);
  }

  /** 描述摘要：压掉换行与多余空白后截断（超长补省略号），空描述回落固定文案。 */
  static String summarize(String text) {
    String compact = text == null ? "" : text.replaceAll("\\s+", " ").strip();
    String summary = compact;
    if (summary.length() > IMAGE_SUMMARY_CHARS) {
      summary = summary.substring(0, IMAGE_SUMMARY_CHARS) + "…";
    }
    if (summary.isEmpty()) {
      summary = "图片切片";
    }
    return summary;
  }

  private static String string(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private static Integer positiveInteger(Object value) {
    Integer parsed = integer(value);
    return parsed != null && parsed > 0 ? parsed : null;
  }

  private static Integer nonNegativeInteger(Object value) {
    Integer parsed = integer(value);
    return parsed != null && parsed >= 0 ? parsed : null;
  }

  private static Integer integer(Object value) {
    return value instanceof Number number ? number.intValue() : null;
  }
}
