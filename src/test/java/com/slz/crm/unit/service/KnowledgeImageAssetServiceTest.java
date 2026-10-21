package com.slz.crm.unit.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.DataScopeLevel;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.document.ImageSliceMetadata;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.storage.InMemoryFileStorageService;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import com.slz.crm.server.service.KnowledgeImageAssetService;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * add-kb-image-slice-retrieval 任务 2.2：图片资产读取服务正反向单测（全 mock 桩，零外呼）。
 *
 * <p>鉴权语义断言：权限不宽于所属 KB——授权集合不含该库一律 12002；非图片行/软删行/缺行一律参数错误（不区分，避免探测）。
 */
@DisplayName("知识库图片资产读取服务（任务组 2）")
class KnowledgeImageAssetServiceTest {

  private static final String KB_ID = "5";

  private static final String ASSET_ID = "90210";

  private final DocumentVectorChunkMapper chunkMapper = mock(DocumentVectorChunkMapper.class);
  private final UploadedFileMapper uploadedFileMapper = mock(UploadedFileMapper.class);
  private final KnowledgeBaseAuthorizationService authorizationService =
      mock(KnowledgeBaseAuthorizationService.class);
  private final InMemoryFileStorageService storage = new InMemoryFileStorageService();

  private KnowledgeImageAssetService service() {
    return new KnowledgeImageAssetService(
        chunkMapper, uploadedFileMapper, authorizationService, storage);
  }

  private UserContext user(Long id) {
    return new UserContext(id, 3L, 10L, DataScopeLevel.NONE, null);
  }

  private String storeAsset(byte[] bytes) {
    return storage.store(new ByteArrayInputStream(bytes), "image-1-0.png", "image/png");
  }

  private DocumentVectorChunkEntity imageChunk(String storageKey, boolean deleted) {
    Map<String, Object> extra = new LinkedHashMap<>();
    extra.put(ImageSliceMetadata.KEY_IMAGE_SLICE, Boolean.TRUE);
    extra.put(ImageSliceMetadata.KEY_IMAGE_STORAGE_KEY, storageKey);
    extra.put(ImageSliceMetadata.KEY_IMAGE_MIME_TYPE, "image/png");
    DocumentVectorChunkEntity chunk = new DocumentVectorChunkEntity();
    chunk.setId(Long.valueOf(ASSET_ID));
    chunk.setDocumentId("doc-image-1");
    chunk.setChunkIndex(0);
    chunk.setChunkText("服务台架构图：接入层 -> 调度层 -> 数据层");
    chunk.setExtraMetadataJson(ImageSliceMetadata.toJson(extra));
    chunk.setIsDeleted(deleted);
    return chunk;
  }

  private UploadedFileEntity file(String kbId) {
    UploadedFileEntity uploaded = new UploadedFileEntity();
    uploaded.setDocumentId("doc-image-1");
    uploaded.setKnowledgeBase(kbId);
    return uploaded;
  }

  @Test
  @DisplayName("授权命中：返回图片字节（资产 id → 切片行 → 所属 KB → 存储 key）")
  void authorizedUser_getsImageBytes() {
    byte[] png = new byte[] {1, 2, 3, 4};
    String key = storeAsset(png);
    when(chunkMapper.selectById(Long.valueOf(ASSET_ID))).thenReturn(imageChunk(key, false));
    when(uploadedFileMapper.selectOne(any())).thenReturn(file(KB_ID));
    when(authorizationService.authorizedKnowledgeBaseIds(any(UserContext.class), any()))
        .thenReturn(List.of(5L));

    KnowledgeImageAssetService.ImageAsset asset = service().load(ASSET_ID, user(7L));

    assertThat(asset.content()).isEqualTo(png);
  }

  @Test
  @DisplayName("无知识库授权：12002（持有资产 id 不放大读取面）")
  void unauthorizedUser_isDenied() {
    String key = storeAsset(new byte[] {9});
    when(chunkMapper.selectById(Long.valueOf(ASSET_ID))).thenReturn(imageChunk(key, false));
    when(uploadedFileMapper.selectOne(any())).thenReturn(file(KB_ID));
    when(authorizationService.authorizedKnowledgeBaseIds(any(UserContext.class), any()))
        .thenReturn(List.of(99L));

    assertThatThrownBy(() -> service().load(ASSET_ID, user(7L)))
        .isInstanceOf(BaseException.class)
        .extracting(thrown -> ((BaseException) thrown).getCode())
        .isEqualTo(ErrorCode.PERMISSION_DENIED.getCode());
  }

  @Test
  @DisplayName("非图片切片行：参数错误（不能借端点读普通切片）")
  void nonImageChunk_isRejected() {
    DocumentVectorChunkEntity chunk = imageChunk("memory/x.png", false);
    chunk.setExtraMetadataJson(ImageSliceMetadata.toJson(Map.of("note", "普通切片")));
    when(chunkMapper.selectById(Long.valueOf(ASSET_ID))).thenReturn(chunk);

    assertThatThrownBy(() -> service().load(ASSET_ID, user(7L)))
        .isInstanceOf(BaseException.class)
        .extracting(thrown -> ((BaseException) thrown).getCode())
        .isEqualTo(ErrorCode.PARAM_FORMAT_ERROR.getCode());
  }

  @Test
  @DisplayName("软删切片行 / 非法 id：参数错误")
  void deletedChunkAndBadId_areRejected() {
    when(chunkMapper.selectById(Long.valueOf(ASSET_ID)))
        .thenReturn(imageChunk("memory/x.png", true));

    assertThatThrownBy(() -> service().load(ASSET_ID, user(7L)))
        .isInstanceOf(BaseException.class)
        .extracting(thrown -> ((BaseException) thrown).getCode())
        .isEqualTo(ErrorCode.PARAM_FORMAT_ERROR.getCode());
    assertThatThrownBy(() -> service().load("not-a-number", user(7L)))
        .isInstanceOf(BaseException.class)
        .extracting(thrown -> ((BaseException) thrown).getCode())
        .isEqualTo(ErrorCode.PARAM_FORMAT_ERROR.getCode());
  }

  @Test
  @DisplayName("所属文档已删/不存在：参数错误（不泄露内部状态）")
  void missingDocument_isRejected() {
    String key = storeAsset(new byte[] {7});
    when(chunkMapper.selectById(Long.valueOf(ASSET_ID))).thenReturn(imageChunk(key, false));
    when(uploadedFileMapper.selectOne(any())).thenReturn(null);

    assertThatThrownBy(() -> service().load(ASSET_ID, user(7L)))
        .isInstanceOf(BaseException.class)
        .extracting(thrown -> ((BaseException) thrown).getCode())
        .isEqualTo(ErrorCode.PARAM_FORMAT_ERROR.getCode());
  }

  @Test
  @DisplayName("存储对象缺失：服务不可用（不抛原始 IO 异常给前端）")
  void missingStorageObject_isServiceUnavailable() {
    when(chunkMapper.selectById(Long.valueOf(ASSET_ID)))
        .thenReturn(imageChunk("absent/key.png", false));
    when(uploadedFileMapper.selectOne(any())).thenReturn(file(KB_ID));
    when(authorizationService.authorizedKnowledgeBaseIds(any(UserContext.class), any()))
        .thenReturn(List.of(5L));

    assertThatThrownBy(() -> service().load(ASSET_ID, user(7L)))
        .isInstanceOf(BaseException.class)
        .extracting(thrown -> ((BaseException) thrown).getCode())
        .isEqualTo(ErrorCode.SERVICE_UNAVAILABLE.getCode());
  }
}
