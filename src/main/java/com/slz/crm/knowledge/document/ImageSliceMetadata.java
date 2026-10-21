package com.slz.crm.knowledge.document;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 图片切片元数据口径（add-kb-image-slice-retrieval 任务 1.3）。
 *
 * <p>切片→图片资产的追溯引用不新增列/不新增资产表：写进既有 {@code document_vector_chunk.extra_metadata_json} 与向量
 * payload（{@code VectorRecord.metadata}），检索期只读 payload 即可回填 {@code SourceReference}。键集在此集中定义，
 * 入库侧（{@link DocumentIngestionSupport}）、清理侧（{@link ImageAssetCleanup}）与检索侧（{@code
 * SourceReferenceFactory}）共用同一口径。
 */
public final class ImageSliceMetadata {

  /** 来源类型取值：图片切片命中（受控解冻登记见 openspec/changes/add-kb-image-slice-retrieval）。 */
  public static final String SOURCE_TYPE_IMAGE = "IMAGE";

  /** 切片行标记：本条切片由 PDF 内嵌图片描述生成。 */
  public static final String KEY_IMAGE_SLICE = "imageSlice";

  /** 向量 payload 来源类型键（与 SSE sources 的 sourceType 同口径）。 */
  public static final String KEY_SOURCE_TYPE = "sourceType";

  /** 向量 payload 图片资产主键（= 图片切片行 DB 主键）。 */
  public static final String KEY_IMAGE_ASSET_ID = "imageAssetId";

  /** 图片对象在文件存储中的 key（删除联动清理与读取端点依赖）。 */
  public static final String KEY_IMAGE_STORAGE_KEY = "imageStorageKey";

  /** 图片 MIME 类型（MVP 恒为 image/png）。 */
  public static final String KEY_IMAGE_MIME_TYPE = "imageMimeType";

  /** 图片宽度（像素，落盘前的显示尺寸）。 */
  public static final String KEY_IMAGE_WIDTH = "imageWidth";

  /** 图片高度（像素，落盘前的显示尺寸）。 */
  public static final String KEY_IMAGE_HEIGHT = "imageHeight";

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private ImageSliceMetadata() {}

  /**
   * 元数据序列化为切片行 {@code extra_metadata_json}（仅标量，长度远小于列上限 1000）。
   *
   * @param metadata 键值对（null 视为空表）
   * @return JSON 文本；空表返回 null（保持存量行口径：该列为空）
   */
  public static String toJson(Map<String, Object> metadata) {
    String json = null;
    if (metadata != null && !metadata.isEmpty()) {
      try {
        json = MAPPER.writeValueAsString(metadata);
      } catch (JsonProcessingException exception) {
        throw new IllegalStateException("图片切片元数据序列化失败", exception);
      }
    }
    return json;
  }

  /**
   * 解析切片行 {@code extra_metadata_json}；缺失或非法一律返回空表（失败不放大权限，只让该行退化为普通切片）。
   *
   * @param json 列值（可空）
   * @return 元数据键值对（永不为 null）
   */
  public static Map<String, Object> fromJson(String json) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    if (json != null && !json.isBlank()) {
      try {
        Map<String, Object> parsed =
            MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        if (parsed != null) {
          metadata.putAll(parsed);
        }
      } catch (JsonProcessingException exception) {
        metadata.clear();
      }
    }
    return metadata;
  }

  /** 是否为图片切片元数据（含图片资产 key）。 */
  public static boolean isImageSlice(Map<String, Object> metadata) {
    return metadata != null
        && Boolean.TRUE.equals(metadata.get(KEY_IMAGE_SLICE))
        && !storageKeyOf(metadata).isBlank();
  }

  /** 取图片对象存储 key；缺失/非文本返回空串（调用方据此跳过，不做任何拼接假设）。 */
  public static String storageKeyOf(Map<String, Object> metadata) {
    String key = "";
    if (metadata != null) {
      Object value = metadata.get(KEY_IMAGE_STORAGE_KEY);
      key = value == null ? "" : String.valueOf(value).strip();
    }
    return key;
  }
}
