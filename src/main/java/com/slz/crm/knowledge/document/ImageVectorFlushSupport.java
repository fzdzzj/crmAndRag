package com.slz.crm.knowledge.document;

import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.platform.contract.CrmVectorStore;
import com.slz.crm.platform.contract.VectorRecord;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 视觉向量落库（multimodal-shared-space 任务 3.1，真视觉路）。
 *
 * <p>为什么要有独立的一步：视觉向量在解析阶段就拿到了（{@link PdfImageSliceIngestService} 逐图 {@code embedImage}），但写 image
 * 集合还需要两个解析阶段不存在的标识——文档 {@code documentId} 与描述切片行主键 {@code chunkId}。两者在 chunk 写库后才可得， 故本类在 chunk
 * 落库之后，把暂存表里的视觉向量按 storageKey 回填到对应切片行上：
 *
 * <ul>
 *   <li>{@code documentId} = 切片行所属文档（与文本向量同口径，删除/重建按文档清理）；
 *   <li>{@code chunkId} = 描述切片行主键（与文本向量同一 chunkId，引用回查与图文融合键一致——视觉命中与文本命中指向同一物理切片时 {@code
 *       fusionKey} 相同，不同切片永不撞键）。
 * </ul>
 *
 * <p>payload 口径复用 {@link DocumentIngestionSupport#createVectorRecord} 的单一实现：视觉命中的 {@code
 * SourceReference} 与文本命中逐字段一致（含 {@code sourceType=IMAGE} 与资产键），引用十字段口径零改动。
 *
 * <p>失败语义：整批写入失败只 WARN（描述切片与文本检索不受影响，视觉路退化为空），暂存表无论如何都清空。
 */
public final class ImageVectorFlushSupport {

  private static final Logger LOG = LoggerFactory.getLogger(ImageVectorFlushSupport.class);

  private ImageVectorFlushSupport() {}

  /**
   * 把本线程暂存的视觉向量写进 image 集合（无暂存/未装配 = 零调用，返回 0）。
   *
   * @param file 文档实体（取 documentId / 类目 / 文件名等 payload 口径）
   * @param children 已落库子块行（与 {@code chunks} 同序，chunk 写库后返回）
   * @param chunks 切片（与子块行同序；图片切片据 {@code extraMetadata} 判定）
   * @param imageVectorStore 图片视觉向量库（null = 未装配，零调用）
   * @return 实际写入的视觉向量条数
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 视觉路写库多源失败：只丢视觉向量，不打断已完成的文本摄取
  public static int flush(
      UploadedFileEntity file,
      List<DocumentVectorChunkEntity> children,
      List<DocumentChunk> chunks,
      CrmVectorStore imageVectorStore) {
    Map<String, float[]> staged = ImageVisualVectorSupport.staged();
    int flushed = 0;
    if (imageVectorStore != null
        && file != null
        && children != null
        && chunks != null
        && !staged.isEmpty()) {
      try {
        List<VectorRecord> records = toImageRecords(file, children, chunks, staged);
        if (!records.isEmpty()) {
          imageVectorStore.upsertAll(records);
          flushed = records.size();
        }
      } catch (Exception exception) {
        LOG.warn("视觉向量写 image 集合失败（描述切片与文本检索不受影响）: documentId={}", file.getDocumentId(), exception);
      } finally {
        ImageVisualVectorSupport.end();
      }
    }
    return flushed;
  }

  /** 逐切片行配对视觉向量；只有图片描述切片（带资产 storageKey）才有对应视觉向量。 */
  private static List<VectorRecord> toImageRecords(
      UploadedFileEntity file,
      List<DocumentVectorChunkEntity> children,
      List<DocumentChunk> chunks,
      Map<String, float[]> staged) {
    List<VectorRecord> records = new ArrayList<>();
    int pairCount = Math.min(children.size(), chunks.size());
    for (int index = 0; index < pairCount; index++) {
      DocumentVectorChunkEntity entity = children.get(index);
      DocumentChunk chunk = chunks.get(index);
      if (entity != null
          && chunk != null
          && ImageSliceMetadata.isImageSlice(chunk.extraMetadata())) {
        float[] vector = staged.get(ImageSliceMetadata.storageKeyOf(chunk.extraMetadata()));
        if (vector != null && vector.length > 0) {
          records.add(DocumentIngestionSupport.createVectorRecord(file, entity, vector, chunk));
        }
      }
    }
    return records;
  }
}
