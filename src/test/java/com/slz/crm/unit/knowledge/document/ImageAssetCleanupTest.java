package com.slz.crm.unit.knowledge.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slz.crm.knowledge.document.ImageAssetCleanup;
import com.slz.crm.knowledge.document.ImageSliceMetadata;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.storage.InMemoryFileStorageService;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * add-kb-image-slice-retrieval 任务 1.1/4.1：图片资产删除联动清理单测。
 *
 * <p>断言：只删图片切片行指向的对象、普通切片行不受影响、依赖缺失时不抛出（返回 0）。
 */
@DisplayName("图片资产删除联动清理（任务组 1）")
class ImageAssetCleanupTest {

  private static final String DOCUMENT_ID = "doc-image-1";

  @Test
  @DisplayName("删除图片资产：图片行对象消失，普通切片行不受影响")
  void deletesOnlyImageAssets() {
    DocumentVectorChunkMapper chunkMapper = mock(DocumentVectorChunkMapper.class);
    InMemoryFileStorageService storage = new InMemoryFileStorageService();
    String imageKey =
        storage.store(new ByteArrayInputStream(new byte[] {1, 2, 3}), "image-1-0.png", "image/png");
    String textKey =
        storage.store(new ByteArrayInputStream(new byte[] {4}), "doc.pdf", "application/pdf");
    when(chunkMapper.selectMetadataRowsByDocumentId(DOCUMENT_ID))
        .thenReturn(List.of(imageRow(imageKey), textRow()));

    int deleted = ImageAssetCleanup.deleteAssets(DOCUMENT_ID, chunkMapper, storage);

    assertThat(deleted).isEqualTo(1);
    assertThat(storage.exists(imageKey)).isFalse();
    assertThat(storage.exists(textKey)).isTrue();
  }

  @Test
  @DisplayName("依赖缺失（兼容构造 / 空 documentId）：不清理也不抛出，返回 0")
  void missingDependencies_isNoOp() {
    InMemoryFileStorageService storage = new InMemoryFileStorageService();
    DocumentVectorChunkMapper chunkMapper = mock(DocumentVectorChunkMapper.class);

    assertThat(ImageAssetCleanup.deleteAssets(DOCUMENT_ID, null, storage)).isZero();
    assertThat(ImageAssetCleanup.deleteAssets(DOCUMENT_ID, chunkMapper, storage)).isZero();
    assertThat(ImageAssetCleanup.deleteAssets("  ", chunkMapper, storage)).isZero();
  }

  @Test
  @DisplayName("元数据非法/无 storageKey 的行：跳过，不产生半删异常")
  void malformedRows_areSkipped() {
    DocumentVectorChunkMapper chunkMapper = mock(DocumentVectorChunkMapper.class);
    InMemoryFileStorageService storage = new InMemoryFileStorageService();
    DocumentVectorChunkEntity malformed = new DocumentVectorChunkEntity();
    malformed.setId(1L);
    malformed.setDocumentId(DOCUMENT_ID);
    malformed.setExtraMetadataJson("{not-json");
    DocumentVectorChunkEntity noKey = new DocumentVectorChunkEntity();
    noKey.setId(2L);
    noKey.setDocumentId(DOCUMENT_ID);
    noKey.setExtraMetadataJson(ImageSliceMetadata.toJson(Map.of("imageSlice", Boolean.TRUE)));
    when(chunkMapper.selectMetadataRowsByDocumentId(DOCUMENT_ID))
        .thenReturn(List.of(malformed, noKey));

    assertThat(ImageAssetCleanup.deleteAssets(DOCUMENT_ID, chunkMapper, storage)).isZero();
  }

  @Test
  @DisplayName("单对象删除失败：不抛出（WARN 后继续），返回成功计数")
  void storageFailure_isSwallowed() {
    DocumentVectorChunkMapper chunkMapper = mock(DocumentVectorChunkMapper.class);
    InMemoryFileStorageService storage = mock(InMemoryFileStorageService.class);
    when(chunkMapper.selectMetadataRowsByDocumentId(DOCUMENT_ID))
        .thenReturn(List.of(imageRow("memory/x/image.png")));
    org.mockito.Mockito.doThrow(new IllegalStateException("storage down"))
        .when(storage)
        .delete("memory/x/image.png");

    assertThat(ImageAssetCleanup.deleteAssets(DOCUMENT_ID, chunkMapper, storage)).isZero();
  }

  private static DocumentVectorChunkEntity imageRow(String storageKey) {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put(ImageSliceMetadata.KEY_IMAGE_SLICE, Boolean.TRUE);
    extra.put(ImageSliceMetadata.KEY_IMAGE_STORAGE_KEY, storageKey);
    DocumentVectorChunkEntity row = new DocumentVectorChunkEntity();
    row.setId(11L);
    row.setDocumentId(DOCUMENT_ID);
    row.setExtraMetadataJson(ImageSliceMetadata.toJson(extra));
    return row;
  }

  private static DocumentVectorChunkEntity textRow() {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put("note", "普通文本切片");
    DocumentVectorChunkEntity row = new DocumentVectorChunkEntity();
    row.setId(12L);
    row.setDocumentId(DOCUMENT_ID);
    row.setExtraMetadataJson(ImageSliceMetadata.toJson(extra));
    return row;
  }
}
