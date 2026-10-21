package com.slz.crm.server.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.knowledge.auth.KnowledgeBaseAuthorizationService;
import com.slz.crm.knowledge.document.ImageSliceMetadata;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.knowledge.entity.UploadedFileEntity;
import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import com.slz.crm.server.mapper.UploadedFileMapper;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 知识库图片切片资产读取（add-kb-image-slice-retrieval 任务 2.2）。
 *
 * <p>鉴权双保险、权限不宽于所属 KB：端点挂既有 803 {@code AI_CHAT_SESSION}（与 {@code KnowledgeChunkController}
 * 的文档切片读取同口径—— 能发起 AI 会话即可查看来源原文），服务内再按「图片切片行 → 所属 documentId →
 * uploaded_file.knowledge_base」解析知识库并复用 {@link
 * KnowledgeBaseAuthorizationService#authorizedKnowledgeBaseIds}：无授权的库一律 12002，不因持有资产 id 而放大读取面。
 *
 * <p>用途：AI 回答里 {@code sourceType=IMAGE} 的来源缩略图（前端用带 token 的 XHR 拉 blob，故端点返回二进制而非 {@code Result}
 * 包装）。
 */
@Service
public class KnowledgeImageAssetService {

  private static final Logger LOG = LoggerFactory.getLogger(KnowledgeImageAssetService.class);

  /** 单图资产大小上限（提取侧最宽 1600px PNG，1 MiB 足够；超限按参数错误拒绝，避免无界缓冲）。 */
  static final int MAX_ASSET_BYTES = 1024 * 1024;

  private final DocumentVectorChunkMapper chunkMapper;
  private final UploadedFileMapper uploadedFileMapper;
  private final KnowledgeBaseAuthorizationService authorizationService;
  private final FileStorageService fileStorageService;

  public KnowledgeImageAssetService(
      DocumentVectorChunkMapper chunkMapper,
      UploadedFileMapper uploadedFileMapper,
      KnowledgeBaseAuthorizationService authorizationService,
      FileStorageService fileStorageService) {
    this.chunkMapper = chunkMapper;
    this.uploadedFileMapper = uploadedFileMapper;
    this.authorizationService = authorizationService;
    this.fileStorageService = fileStorageService;
  }

  /**
   * 读取图片资产。
   *
   * @param assetId 图片资产 id（= 图片切片行主键，来自 {@code SourceReference.documentId}）
   * @param user 当前登录用户（无授权返回 12002）
   * @return 图片字节
   */
  public ImageAsset load(String assetId, UserContext user) {
    String storageKey = resolveAuthorizedStorageKey(assetId, user);
    return new ImageAsset(readBytes(storageKey));
  }

  /** 资产 id → 图片切片行 → 所属知识库授权 → storageKey（任一环节不满足即抛业务异常，不降级放行）。 */
  private String resolveAuthorizedStorageKey(String assetId, UserContext user) {
    DocumentVectorChunkEntity chunk = imageSliceRow(assetId);
    Map<String, Object> metadata = ImageSliceMetadata.fromJson(chunk.getExtraMetadataJson());
    String kbId = knowledgeBaseOf(chunk.getDocumentId());
    if (!authorized(user, kbId)) {
      throw new BaseException(ErrorCode.PERMISSION_DENIED, "无权访问该知识库图片");
    }
    return ImageSliceMetadata.storageKeyOf(metadata);
  }

  /** 图片切片行：id 非法/行不存在/已软删/非图片切片一律按参数错误处理（不区分，避免探测资产存在性）。 */
  private DocumentVectorChunkEntity imageSliceRow(String assetId) {
    Long id = parseAssetId(assetId);
    DocumentVectorChunkEntity chunk = id == null ? null : chunkMapper.selectById(id);
    if (chunk == null || Boolean.TRUE.equals(chunk.getIsDeleted())) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "图片资产不存在");
    }
    boolean imageSlice =
        ImageSliceMetadata.isImageSlice(ImageSliceMetadata.fromJson(chunk.getExtraMetadataJson()));
    if (!imageSlice) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "不是图片切片资产");
    }
    return chunk;
  }

  /** 资产 id 解析（非数字返回 null，由调用方统一按「不存在」处置，不区分非法与缺失）。 */
  private Long parseAssetId(String assetId) {
    Long id = null;
    if (assetId != null) {
      try {
        id = Long.valueOf(assetId.strip());
      } catch (NumberFormatException invalid) {
        LOG.debug("图片资产 id 非法: {}", assetId, invalid);
      }
    }
    return id;
  }

  /** 切片行 → 未软删 uploaded_file → knowledge_base 字符串；行缺失按参数错误（不泄露内部状态）。 */
  private String knowledgeBaseOf(String documentId) {
    UploadedFileEntity file =
        uploadedFileMapper.selectOne(
            new QueryWrapper<UploadedFileEntity>()
                .eq("document_id", documentId)
                .eq("is_deleted", false));
    if (file == null || file.getKnowledgeBase() == null || file.getKnowledgeBase().isBlank()) {
      throw new BaseException(ErrorCode.PARAM_FORMAT_ERROR, "图片所属文档不存在");
    }
    return file.getKnowledgeBase();
  }

  /** 复用知识库检索授权链：授权集合不含该库即无读权限（非数字 kbId 一律视为无授权，不放宽）。 */
  private boolean authorized(UserContext user, String kbId) {
    boolean authorized = false;
    if (user != null) {
      try {
        List<Long> ids =
            authorizationService.authorizedKnowledgeBaseIds(user, List.of(String.valueOf(kbId)));
        authorized = ids != null && ids.contains(Long.valueOf(kbId));
      } catch (NumberFormatException invalidKb) {
        authorized = false;
      }
    }
    return authorized;
  }

  /** 读对象字节（有界：最多读上限+1 字节，超限拒绝，避免异常对象撑爆内存）。 */
  private byte[] readBytes(String storageKey) {
    byte[] bytes = readBounded(storageKey);
    if (bytes.length > MAX_ASSET_BYTES) {
      throw new BaseException(ErrorCode.PARAM_OUT_OF_RANGE, "图片资产超过读取上限");
    }
    return bytes;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 存储读取多源：统一按不可用上报
  private byte[] readBounded(String storageKey) {
    byte[] bytes;
    try (InputStream content = fileStorageService.open(storageKey)) {
      bytes = content.readNBytes(MAX_ASSET_BYTES + 1);
    } catch (Exception readException) {
      LOG.warn("图片资产读取失败: key={}", storageKey, readException);
      throw new BaseException(ErrorCode.SERVICE_UNAVAILABLE, "图片资产不可读取");
    }
    return bytes;
  }

  /**
   * 图片资产响应体。
   *
   * @param content 图片字节（PNG）
   */
  public record ImageAsset(byte[] content) {}
}
