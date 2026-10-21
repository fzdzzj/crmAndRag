package com.slz.crm.knowledge.document;

import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * 真视觉向量的摄取侧生产与暂存（multimodal-shared-space 任务 3.1）。
 *
 * <p>为什么单独成类：{@link PdfImageSliceIngestService} 已被 PMD {@code NcssCount}（类级 150）压在预算附近，视觉 embedding
 * 的外呼、 计量与暂存放进主类会直接顶破道闸；这里承接"逐图 embedImage → 暂存 → 由 {@link ImageVectorFlushSupport} 消费"的整段形态，主类只留
 * 开关与一行调用。
 *
 * <p>为什么暂存：视觉向量落 image 集合需要 {@code documentId} 与切片行主键 {@code chunkId}，两者在解析阶段都还不存在；暂存以 storageKey
 * 为键，chunk 落库后按行回填这两个标识（视觉向量与描述切片共享同一 {@code chunkId}，引用回查与融合键口径一致）。
 *
 * <p>失败语义：单图视觉 embedding 失败只丢该图的视觉向量（描述切片与文本检索不受影响）；暂存表在每次 {@code begin} 时整体替换，不跨文档串味。
 */
final class ImageVisualVectorSupport {

  private static final Logger LOG = LoggerFactory.getLogger(ImageVisualVectorSupport.class);

  private static final ThreadLocal<Map<String, float[]>> STAGED = new ThreadLocal<>();

  private ImageVisualVectorSupport() {}

  /** 重置本线程暂存表并返回（每次摄取尝试一张新表）。 */
  static Map<String, float[]> begin() {
    Map<String, float[]> staged = new LinkedHashMap<>();
    STAGED.set(staged);
    return staged;
  }

  /** 本线程暂存的视觉向量（storageKey → 向量）；未开启/已消费时为空表。 */
  static Map<String, float[]> staged() {
    Map<String, float[]> current = STAGED.get();
    return current == null ? Map.of() : current;
  }

  /** 清空本线程暂存（消费后/作用域结束调用）。 */
  static void end() {
    STAGED.remove();
  }

  /**
   * 单图真视觉 embedding 并暂存：图→向量（不做文本拼接），EMBEDDING 计量成败都记录。
   *
   * @param staged 暂存表（null = 视觉路未开启，零外呼）
   * @param storageKey 图片对象存储键（暂存键）
   * @param image 抽取出的内嵌图片（PNG 字节）
   * @param modelProvider 模型出口
   * @param recorderProvider 计量记录器（可空）
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 视觉 embedding 外呼多源失败：跳过该图视觉向量，不打断摄取
  static void embedAndStage(
      Map<String, float[]> staged,
      String storageKey,
      PdfImageExtractor.ExtractedImage image,
      ModelProvider modelProvider,
      ObjectProvider<TokenUsageRecorder> recorderProvider) {
    if (staged == null) {
      return;
    }
    ModelCallResult<float[]> result = null;
    boolean success = false;
    try {
      result = modelProvider.embedImage(image.png(), PdfImageSliceIngestService.IMAGE_MIME_TYPE);
      success = result != null && result.vector() != null && result.vector().length > 0;
      if (success) {
        staged.put(storageKey, result.vector());
      } else {
        LOG.info("图片视觉向量为空，跳过该图: page={} sequence={}", image.pageNo(), image.sequence());
      }
    } catch (Exception embedException) {
      LOG.warn(
          "图片视觉向量生成失败，跳过该图（描述切片不受影响）: page={} sequence={}",
          image.pageNo(),
          image.sequence(),
          embedException);
    } finally {
      VisionIngestSupport.recordEmbeddingUsage(result, success, recorderProvider);
    }
  }
}
