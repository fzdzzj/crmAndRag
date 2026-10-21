package com.slz.crm.knowledge.document;

import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文本切片与图片描述切片合并（add-kb-image-slice-retrieval 任务 1.3）。
 *
 * <p>为什么单独成类：摄取编排（{@link DocumentIngestionService}）与入库装配（{@link DocumentIngestionSupport}）都已被 PMD
 * {@code NcssCount}（类级 150）压在上限附近，图片切片旁路的分支/循环放进主类会直接顶破道闸；这里承接合并、重排序号与旧资产清理， 两个调用点各只留一行，主类规模不变。
 *
 * <p>序号语义：图片切片追加在文本切片之后并统一重排 {@code chunkIndex}——单一序号空间保证切片行唯一键 {@code uk(document_id,
 * chunk_index)} 与「第几块」引用语义不变；判空放在合并之后，只有图片、没有文本层的 PDF 也能由图片描述切片承接。
 */
final class ImageSliceIngestSupport {

  private static final Logger LOG = LoggerFactory.getLogger(ImageSliceIngestSupport.class);

  private ImageSliceIngestSupport() {}

  /**
   * 首次入库：文本切片 + 图片描述切片合并并重排序号。
   *
   * @param documentService 文本解析/分块服务
   * @param imageSliceIngestService 图片切片旁路（可空 = 未装配，整条旁路关闭）
   * @param storedContent 存储侧文档流（文本解析）
   * @param rawContent 原始字节（图片提取复用，避免二次读存储）
   * @param filename 原始文件名
   * @param category 业务类目
   * @param knowledgeBaseId 所属知识库（图片切片 KB 灰度判定）
   * @return 合并后的切片（非空）
   * @throws Exception 文本解析失败（图片旁路自身异常不外抛）
   */
  static List<DocumentChunk> parseWithImageSlices(
      DocumentService documentService,
      PdfImageSliceIngestService imageSliceIngestService,
      InputStream storedContent,
      byte[] rawContent,
      String filename,
      String category,
      String knowledgeBaseId)
      throws Exception {
    List<DocumentChunk> imageSlices =
        imageSlices(imageSliceIngestService, rawContent, filename, category, knowledgeBaseId);
    List<DocumentChunk> chunks = new ArrayList<>();
    if (imageSlices.isEmpty()) {
      // 旁路关闭/未装配/无图：走升级前入口，空文档仍抛「文档解析结果为空」（默认行为逐字节不变）
      chunks.addAll(documentService.process(storedContent, filename, category));
    } else {
      // 旁路生效：允许文本为空，由图片描述切片承接；判空放在合并之后
      chunks.addAll(documentService.processOrEmpty(storedContent, filename, category));
      chunks.addAll(imageSlices);
    }
    return renumber(chunks, filename);
  }

  /**
   * 重建入库：读回原始字节 → 合并切片 → 物理删行<b>之前</b>清旧图片资产对象。
   *
   * <p>顺序不可颠倒：资产 storageKey 只存在于切片行的 {@code extra_metadata_json}，行删后已无从得知要删哪些对象。
   *
   * @param documentService 文本解析/分块服务
   * @param imageSliceIngestService 图片切片旁路（可空）
   * @param storedContent 存储侧文档流
   * @param filename 原始文件名
   * @param category 业务类目
   * @param knowledgeBaseId 所属知识库
   * @param documentId 文档业务键（资产清理范围）
   * @param chunkMapper 切片 Mapper（资产清理读行）
   * @param storage 文件存储（资产清理删对象）
   * @return 合并后的切片（非空）
   * @throws Exception 文本解析失败
   */
  static List<DocumentChunk> parseReingestWithImageSlices(
      DocumentService documentService,
      PdfImageSliceIngestService imageSliceIngestService,
      InputStream storedContent,
      String filename,
      String category,
      String knowledgeBaseId,
      String documentId,
      DocumentVectorChunkMapper chunkMapper,
      FileStorageService storage)
      throws Exception {
    byte[] contentBytes =
        DocumentIngestionService.readAllBytesBounded(
            storedContent, DocumentIngestionService.MAX_DOCUMENT_BYTES);
    List<DocumentChunk> chunks =
        parseWithImageSlices(
            documentService,
            imageSliceIngestService,
            new ByteArrayInputStream(contentBytes),
            contentBytes,
            filename,
            category,
            knowledgeBaseId);
    ImageAssetCleanup.deleteAssets(documentId, chunkMapper, storage);
    return chunks;
  }

  /** 重排全文档 chunkIndex（图片切片与文本切片共用单一序号空间）。 */
  private static List<DocumentChunk> renumber(List<DocumentChunk> chunks, String filename) {
    List<DocumentChunk> merged = new ArrayList<>(chunks.size());
    for (int index = 0; index < chunks.size(); index++) {
      merged.add(chunks.get(index).withChunkIndex(index));
    }
    if (merged.isEmpty()) {
      throw new IllegalStateException("文档解析结果为空: " + filename);
    }
    return merged;
  }

  /** 图片切片旁路：默认关 / 未装配 → 空表（零外呼零落盘）；异常一律丢弃图片切片，绝不打断文本摄取。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 旁路异常不得打断文本摄取
  private static List<DocumentChunk> imageSlices(
      PdfImageSliceIngestService imageSliceIngestService,
      byte[] rawContent,
      String filename,
      String category,
      String knowledgeBaseId) {
    List<DocumentChunk> slices = new ArrayList<>();
    if (imageSliceIngestService != null) {
      try {
        slices.addAll(
            imageSliceIngestService.buildSlices(rawContent, filename, category, knowledgeBaseId));
      } catch (Exception exception) {
        LOG.warn("图片切片摄取失败，跳过（文本切片照常入库）: filename={}", filename, exception);
      }
    }
    return slices;
  }
}
