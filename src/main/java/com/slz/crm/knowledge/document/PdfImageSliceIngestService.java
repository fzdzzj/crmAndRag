package com.slz.crm.knowledge.document;

import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;

/**
 * PDF 内嵌图片切片摄取（add-kb-image-slice-retrieval 任务 1.1~1.4，方案 B 文本降维）。
 *
 * <p>链路：{@link PdfImageExtractor} 取内嵌图片对象 → 每图 {@link ModelProvider#vision} 生成描述 → {@link
 * VisionIngestSupport} 质量闸门 + VISION 计量 → 图片对象落 {@link FileStorageService}（与文档原件同一存储基建）→ 描述文本转成
 * {@link DocumentChunk} 交现成 chunk/embed 链路，资产引用写切片元数据（零迁移）。
 *
 * <p>开关与帽（动态配置，默认关 = 默认行为零变化）：{@value #ENABLED_KEY}（默认 false）、{@value #KB_ALLOWLIST_KEY} （KB 维度灰度，空
 * = 全部库）、{@value #MAX_IMAGES_KEY}（每文档图片数帽，默认 5，范围 1~20）。
 *
 * <p>失败语义：单图解码/外呼/闸门/落盘任一失败只跳过该图，绝不打断整篇文档解析；未装配存储或关闭开关时返回空表且零外呼。
 */
@Service
public class PdfImageSliceIngestService {

  private static final Logger LOG = LoggerFactory.getLogger(PdfImageSliceIngestService.class);

  /** 总开关键（默认 false；与 vision-pdf 分离，互不牵连）。 */
  public static final String ENABLED_KEY = "rag.retrieval.image-slice-ingest.enabled";

  /** KB 维度灰度：逗号分隔的 KB id 白名单；空 = 开关开启时全部知识库生效。 */
  public static final String KB_ALLOWLIST_KEY = "rag.retrieval.image-slice-ingest.kb-allowlist";

  /** 每文档最多处理的内嵌图片数。 */
  public static final String MAX_IMAGES_KEY =
      "rag.retrieval.image-slice-ingest.max-images-per-document";

  public static final int DEFAULT_MAX_IMAGES = 5;

  /** 每文档图片数帽上限（与 vision-pdf 的页数帽同量级）。 */
  static final int HARD_MAX_IMAGES = 20;

  /** 单图描述最短合格长度（与整页转写同一闸门形态）。 */
  static final int MIN_DESCRIPTION_CHARS = VisionIngestSupport.MIN_COMPACT_CHARS;

  /** 图片对象 MIME（解码后统一转 PNG，避免 JPX 等前端不支持的格式）。 */
  static final String IMAGE_MIME_TYPE = "image/png";

  /**
   * 描述提示词：与 {@link PdfVisionTranscriber#TRANSCRIBE_PROMPT} 同纪律——只转写可见内容、不清标「（截图不清）」、
   * 不猜测补全、不加解释、不寒暄；目标是让描述文本能被现有文本向量空间召回。
   */
  static final String DESCRIBE_PROMPT =
      """
            你是精确的图片内容转写引擎。把这张图片的全部内容转写为结构化 Markdown：
            1. 逐字提取图中所有可见文字：标题、正文、图表标注、图例、坐标轴、页眉页脚、页码。
            2. 代码截图：用三个反引号包裹完整转写，尽量保留缩进与符号；无法确认的字符标注（截图不清）。
            3. 架构图/流程图：用文字加箭头描述节点与连接关系；有分层或分组要说明层级。
            4. 表格：转成 Markdown 表格，保留行列与表头。
            5. 图中极小、模糊或被截断而无法确认的文字，一律标注“（截图不清）”，禁止猜测补全成看似合理的值。
            6. 只转写图中确实存在的内容，不要添加解释、背景知识或图里没有的信息，不要编造数字/模型名/文献。
            7. 直接输出转写结果，不要寒暄。""";

  private final ModelProvider modelProvider;
  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  private final ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider;
  private final ObjectProvider<FileStorageService> fileStorageServiceProvider;

  public PdfImageSliceIngestService(
      ModelProvider modelProvider,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider,
      ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider,
      ObjectProvider<FileStorageService> fileStorageServiceProvider) {
    this.modelProvider = modelProvider;
    this.dynamicConfigProvider = dynamicConfigProvider;
    this.tokenUsageRecorderProvider = tokenUsageRecorderProvider;
    this.fileStorageServiceProvider = fileStorageServiceProvider;
  }

  /**
   * 生成该文档的图片描述切片（不做任何持久化；切片行/向量由调用方走现成 chunk/embed 链路）。
   *
   * @param content 文档原始字节（仅 PDF 生效）
   * @param filename 原始文件名（判断是否 PDF）
   * @param category 业务类目（与文本切片同口径落到切片行）
   * @param knowledgeBaseId 所属知识库（KB 灰度判定；可空 = 只在白名单为空时放行）
   * @return 图片描述切片（chunkIndex 由调用方统一重排）；关闭/非 PDF/无图/全被闸门拒绝时为空表
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 图片提取旁路：任何异常只丢弃图片切片，不打断文本摄取
  public List<DocumentChunk> buildSlices(
      byte[] content, String filename, String category, String knowledgeBaseId) {
    List<DocumentChunk> slices = new ArrayList<>();
    FileStorageService storage = fileStorageServiceProvider.getIfAvailable();
    if (storage != null && isPdf(filename) && content != null && content.length > 0) {
      if (enabled(knowledgeBaseId)) {
        try {
          List<PdfImageExtractor.ExtractedImage> images =
              PdfImageExtractor.extract(content, resolveMaxImages());
          slices.addAll(describeAndStore(images, storage, category));
        } catch (Exception exception) {
          // 图片切片旁路绝不打断整篇解析：文本切片照常入库
          LOG.warn("PDF 内嵌图片提取失败，跳过图片切片: filename={}", filename, exception);
        }
      }
    }
    return slices;
  }

  /** 开关判定：总开关 + KB 灰度（任一不满足即整条旁路关闭，零外呼零落盘）。 */
  public boolean enabled(String knowledgeBaseId) {
    DynamicConfigService config = resolveConfig();
    Boolean on =
        config == null ? Boolean.FALSE : config.get(ENABLED_KEY, Boolean.class, Boolean.FALSE);
    return Boolean.TRUE.equals(on) && kbAllowed(config, knowledgeBaseId);
  }

  /** 每文档图片数帽（默认 5，范围 1~20）。 */
  public int resolveMaxImages() {
    DynamicConfigService config = resolveConfig();
    Integer configured =
        config == null ? null : config.get(MAX_IMAGES_KEY, Integer.class, DEFAULT_MAX_IMAGES);
    int value = DEFAULT_MAX_IMAGES;
    if (configured != null && configured >= 1) {
      value = Math.min(configured, HARD_MAX_IMAGES);
    }
    return value;
  }

  /** KB 灰度：白名单为空 = 全部库；否则必须命中（按 KB id 精确匹配，不做前缀/模糊匹配）。 */
  private boolean kbAllowed(DynamicConfigService config, String knowledgeBaseId) {
    String raw = config == null ? null : config.get(KB_ALLOWLIST_KEY, String.class, "");
    boolean allowed = raw == null || raw.isBlank();
    if (!allowed) {
      String target = knowledgeBaseId == null ? "" : knowledgeBaseId.strip();
      for (String token : raw.split(",")) {
        if (!target.isEmpty() && token.strip().equals(target)) {
          allowed = true;
          break;
        }
      }
    }
    return allowed;
  }

  /** 逐图「描述 → 闸门 → 落盘」，任一环节失败跳过该图。 */
  private List<DocumentChunk> describeAndStore(
      List<PdfImageExtractor.ExtractedImage> images, FileStorageService storage, String category) {
    List<DocumentChunk> slices = new ArrayList<>(images.size());
    for (PdfImageExtractor.ExtractedImage image : images) {
      String description = describe(image);
      if (description != null) {
        String storageKey = store(storage, image);
        if (!storageKey.isBlank()) {
          slices.add(toChunk(image, description, storageKey, category));
        }
      }
    }
    return slices;
  }

  /** 单图 VLM 描述：外呼 + 闸门 + 计量（失败返回 null，交给调用方跳过该图）。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 视觉外呼多源失败：回退跳过该图，不打断摄取
  private String describe(PdfImageExtractor.ExtractedImage image) {
    String description = null;
    ModelCallResult<String> result = null;
    boolean success = false;
    try {
      Media media =
          new Media(MimeType.valueOf(IMAGE_MIME_TYPE), new ByteArrayResource(image.png()));
      UserMessage message =
          UserMessage.builder().text(DESCRIBE_PROMPT).media(List.of(media)).build();
      result = modelProvider.vision(new Prompt(List.of(message)), ModelCallOptions.defaults());
      String content = result == null ? null : result.content();
      if (VisionIngestSupport.passesQualityGate(content, MIN_DESCRIPTION_CHARS)) {
        success = true;
        description = content.strip();
      } else {
        LOG.info("图片描述未过质量闸门，跳过该图: page={} sequence={}", image.pageNo(), image.sequence());
      }
    } catch (Exception visionException) {
      LOG.warn(
          "图片描述调用失败，跳过该图: page={} sequence={}", image.pageNo(), image.sequence(), visionException);
    } finally {
      VisionIngestSupport.recordUsage(result, success, tokenUsageRecorderProvider);
    }
    return description;
  }

  /** 图片对象落盘（与文档原件同一 FileStorageService）；返回 storageKey，失败返回空串。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 存储异常：跳过该图，不打断摄取
  private String store(FileStorageService storage, PdfImageExtractor.ExtractedImage image) {
    String storageKey = "";
    try {
      String objectName = "image-" + image.pageNo() + "-" + image.sequence() + ".png";
      storageKey =
          storage.store(new ByteArrayInputStream(image.png()), objectName, IMAGE_MIME_TYPE);
      if (storageKey == null) {
        storageKey = "";
      }
    } catch (Exception storeException) {
      LOG.warn(
          "图片对象落盘失败，跳过该图: page={} sequence={}", image.pageNo(), image.sequence(), storeException);
    }
    return storageKey;
  }

  /** 描述切片：文本 = 描述，一行一图（无父块），资产引用进切片元数据。 */
  private DocumentChunk toChunk(
      PdfImageExtractor.ExtractedImage image,
      String description,
      String storageKey,
      String category) {
    Map<String, Object> extraMetadata = new LinkedHashMap<>();
    extraMetadata.put(ImageSliceMetadata.KEY_IMAGE_SLICE, Boolean.TRUE);
    extraMetadata.put(ImageSliceMetadata.KEY_IMAGE_STORAGE_KEY, storageKey);
    extraMetadata.put(ImageSliceMetadata.KEY_IMAGE_MIME_TYPE, IMAGE_MIME_TYPE);
    extraMetadata.put(ImageSliceMetadata.KEY_IMAGE_WIDTH, image.width());
    extraMetadata.put(ImageSliceMetadata.KEY_IMAGE_HEIGHT, image.height());
    return new DocumentChunk(
        description, 0, image.pageNo(), null, category, List.of(), null, extraMetadata);
  }

  private DynamicConfigService resolveConfig() {
    return dynamicConfigProvider == null ? null : dynamicConfigProvider.getIfAvailable();
  }

  private static boolean isPdf(String filename) {
    boolean pdf = false;
    if (filename != null && filename.lastIndexOf('.') >= 0) {
      pdf =
          "pdf".equals(filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT));
    }
    return pdf;
  }
}
