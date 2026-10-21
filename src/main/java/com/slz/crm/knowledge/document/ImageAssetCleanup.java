package com.slz.crm.knowledge.document;

import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 图片资产删除联动清理（add-kb-image-slice-retrieval 任务 1.1，对齐 C-11 物理双清形态）。
 *
 * <p>文档删除（{@code KnowledgeAdminService#deleteFile}）与重建入库（{@code
 * DocumentIngestionService#reingest}）都会物理清切片行； 图片对象在文件存储里，必须在清行<b>之前</b>按行内 {@code
 * extra_metadata_json} 找到 storageKey 删除，否则留下无法再被授权的孤儿对象。
 *
 * <p>失败语义：单个对象删除失败只 WARN 不抛出（与既有双清一致：行清理仍是权威动作，对象残留不放大读取面——读取端点需要切片行 通过知识库授权，行没了对象就取不到）。
 */
public final class ImageAssetCleanup {

  private static final Logger LOG = LoggerFactory.getLogger(ImageAssetCleanup.class);

  private ImageAssetCleanup() {}

  /**
   * 删除某文档下全部图片切片资产对象。
   *
   * @param documentId 文档业务键
   * @param chunkMapper 切片 Mapper（可空：兼容构造下不清理）
   * @param storage 文件存储（可空：兼容构造下不清理）
   * @return 实际删除的对象个数
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 存储删除多源：单个对象失败不挡整篇清理
  public static int deleteAssets(
      String documentId, DocumentVectorChunkMapper chunkMapper, FileStorageService storage) {
    int deleted = 0;
    if (documentId != null && !documentId.isBlank()) {
      // multimodal-shared-space 任务 3.2：图片视觉向量与图片资产同链清理（未装配 image 库 = 零调用）。
      // 与文本侧 vectorStore.deleteByDocumentId 并列，构成「文本集合 + image 集合」双空间双清。
      ImageVectorCleanupRegistry.deleteByDocumentId(documentId);
    }
    if (documentId != null && !documentId.isBlank() && chunkMapper != null && storage != null) {
      List<DocumentVectorChunkEntity> rows = chunkMapper.selectMetadataRowsByDocumentId(documentId);
      for (DocumentVectorChunkEntity row : rows) {
        Map<String, Object> metadata = ImageSliceMetadata.fromJson(row.getExtraMetadataJson());
        String storageKey = ImageSliceMetadata.storageKeyOf(metadata);
        if (!storageKey.isBlank()) {
          try {
            storage.delete(storageKey);
            deleted++;
          } catch (Exception deleteException) {
            LOG.warn(
                "图片资产删除失败（行清理继续）: documentId={} key={}", documentId, storageKey, deleteException);
          }
        }
      }
    }
    return deleted;
  }
}
