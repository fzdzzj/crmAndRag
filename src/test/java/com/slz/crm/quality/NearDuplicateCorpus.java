package com.slz.crm.quality;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * 近重复定标语料（calibrate-near-dup-bands 任务 1.2，零外呼）：真实公有领域 PDF 的 base 位图 + 内存里合成的 5 类同源变体。
 *
 * <p><b>为什么要造变体</b>：v1 语料是「9 图 + 9 图两两组合」，<b>结构上不可能产生同源近重复对</b>，所以 {@code NEAR_HIGH} / {@code
 * NEAR_MID} 在 v1 锚的 90 对上零样本。本类把「同图的不同版本」造进语料，才第一次看得到间隔带 {@code [0.9887, 0.9944)} 里装的是什么。
 *
 * <p><b>语料来源</b>：复用 {@link RealPdfAssets} 已登记的三份美国政府出版物（{@code sources.json} 逐份登记 URL + SHA-256 +
 * 授权原文），<b>不下载新文件、不新增二进制</b>：变体只在内存里生成、只落余弦，{@code git} 里不出现任何图片字节。每份文档的取图数在本类 里写死（8 / 4 /
 * 12），<b>不动</b> {@link RealPdfAssets#MAX_IMAGES_PER_DOCUMENT}——那是既有真跑 IT 的成本帽，动了会改它的 成本口径。
 *
 * <p><b>确定性</b>：无随机源、无时间戳、无并发；同一 base 两次生成必须字节相等（{@link NearDuplicateCorpusTest} 守）。JPEG 质量用显式
 * {@link ImageWriteParam}（不走默认值，避免 JDK 默认值漂移）；重采样用双线性、裁切用整数边距——都是纯函数式变换。
 *
 * <p><b>记账纪律</b>：base 按 SHA-256 全局去重，去重后不足计划值时<b>记实际值、不凑数</b>；变体最短边 {@code < 48px} 的跳过并进 {@link
 * Corpus#skips()}（不静默丢弃）。
 *
 * <p><b>未覆盖类</b>：同页重复扫描（同一张纸扫两次）无法在无扫描仪的条件下合成，本类<b>不覆盖</b>，由 {@code
 * docs/rag-quality/image-near-duplicate-bands-v2.md} 如实标注。
 */
final class NearDuplicateCorpus {

  /** 变体最短边下限：低于此值的缩略图/裁切图不再是「同一张图的可识别版本」（跳过并登记）。 */
  static final int MIN_EDGE_PX = 48;

  /** 变体类型（顺序 = 强度从轻到重，单调性检验按此序）。 */
  static final String KIND_JPEG_Q80 = "jpeg-q80";

  static final String KIND_SCALE_085 = "scale-085";

  static final String KIND_SCALE_070 = "scale-070";

  static final String KIND_CROP_04 = "crop-04";

  static final String KIND_CROP_12 = "crop-12";

  /** 变体类型全序（强度从轻到重；单调性断言的唯一顺序来源）。 */
  static final List<String> KINDS =
      List.of(KIND_JPEG_Q80, KIND_SCALE_085, KIND_SCALE_070, KIND_CROP_04, KIND_CROP_12);

  /** {@code JPEG(q0.80)} 二次压缩的质量参数（写死，不取默认值）。 */
  private static final float JPEG_QUALITY = 0.80F;

  /** 缩放变体的比例。 */
  private static final double SCALE_085_FACTOR = 0.85D;

  private static final double SCALE_070_FACTOR = 0.70D;

  /** 裁切变体的四周边距比例。 */
  private static final double CROP_04_MARGIN = 0.04D;

  private static final double CROP_12_MARGIN = 0.12D;

  private NearDuplicateCorpus() {}

  /**
   * 一份 base 位图（来自真实 PDF，经生产提取器）。
   *
   * @param docKey 出版物键（{@code sources.json} 的 {@code key}；同类判定依据）
   * @param pageNo 页码（1 起）
   * @param index 本文档内序号（1 起，按提取顺序）
   * @param key 图键（{@code docKey-p<page>-b<index>}）
   * @param png 原始 PNG 字节（生产提取器输出，未改写）
   * @param width 宽
   * @param height 高
   */
  record Base(
      String docKey, int pageNo, int index, String key, byte[] png, int width, int height) {}

  /**
   * 一份同源变体。
   *
   * @param kind 变体类型（{@link #KINDS} 之一）
   * @param baseKey 回指的 base 图键
   * @param key 变体图键（{@code baseKey@kind}，前缀即 baseKey）
   * @param png 变体 PNG 字节（内存生成）
   * @param width 宽
   * @param height 高
   */
  record Variant(String kind, String baseKey, String key, byte[] png, int width, int height) {}

  /**
   * 一条跳过登记（不静默丢弃）。
   *
   * @param baseKey 回指的 base 图键
   * @param kind 变体类型
   * @param width 变换后宽
   * @param height 变换后高
   * @param reason 原因
   */
  record Skip(String baseKey, String kind, int width, int height, String reason) {}

  /**
   * 一份文档的实际取图数。
   *
   * @param docKey 出版物键
   * @param requested 计划取图数（传给生产提取器的上界）
   * @param taken 去重后实际取到的图数
   */
  record DocumentCount(String docKey, int requested, int taken) {}

  /**
   * 语料全集。
   *
   * @param bases base 图（SHA-256 全局去重后）
   * @param variants 变体图
   * @param skips 跳过的变体（最短边不足）
   * @param documents 逐文档的实际取图数
   */
  record Corpus(
      List<Base> bases, List<Variant> variants, List<Skip> skips, List<DocumentCount> documents) {

    /**
     * @return 图总数 = base + 变体
     */
    int imageCount() {
      return bases.size() + variants.size();
    }
  }

  /**
   * 生成语料（唯一入口，纯确定性）。
   *
   * @return 语料全集
   */
  static Corpus generate() {
    List<Base> bases = new ArrayList<>();
    List<DocumentCount> documents = new ArrayList<>();
    Set<String> seenDigests = new LinkedHashSet<>();
    Map<String, Integer> plan = plan();
    for (RealPdfAssets.Asset asset : RealPdfAssets.assets()) {
      Integer requested = plan.get(asset.key());
      if (requested == null) {
        continue;
      }
      int taken = 0;
      for (RealPdfAssets.Extracted extracted : RealPdfAssets.extract(asset.pdf(), requested)) {
        if (!seenDigests.add(RealPdfAssets.sha256(extracted.png()))) {
          continue;
        }
        taken++;
        bases.add(
            new Base(
                asset.key(),
                extracted.pageNo(),
                taken,
                key(asset.key(), extracted.pageNo(), taken),
                extracted.png(),
                extracted.width(),
                extracted.height()));
      }
      documents.add(new DocumentCount(asset.key(), requested, taken));
    }
    List<Variant> variants = new ArrayList<>();
    List<Skip> skips = new ArrayList<>();
    for (Base base : bases) {
      BufferedImage source = readPng(base.png());
      for (String kind : KINDS) {
        BufferedImage transformed = transform(source, kind);
        if (Math.min(transformed.getWidth(), transformed.getHeight()) < MIN_EDGE_PX) {
          skips.add(
              new Skip(
                  base.key(),
                  kind,
                  transformed.getWidth(),
                  transformed.getHeight(),
                  "最短边 < " + MIN_EDGE_PX + "px（低于可识别下限）"));
          continue;
        }
        variants.add(
            new Variant(
                kind,
                base.key(),
                base.key() + "@" + kind,
                toPng(transformed),
                transformed.getWidth(),
                transformed.getHeight()));
      }
    }
    return new Corpus(
        List.copyOf(bases), List.copyOf(variants), List.copyOf(skips), List.copyOf(documents));
  }

  /**
   * 计划：每份出版物取几张位图（写成常量而不是复用 {@code RealPdfAssets.MAX_IMAGES_PER_DOCUMENT}，后者是既有真跑 IT 的成本帽）。
   *
   * @return 出版物键 → 计划取图数（顺序即提取顺序）
   */
  static Map<String, Integer> plan() {
    Map<String, Integer> plan = new LinkedHashMap<>();
    plan.put("nist-sp800-101r1", 8);
    plan.put("nist-sp800-183", 4);
    plan.put("doe-renewable-energy-framework", 12);
    return plan;
  }

  /**
   * 图键：{@code docKey-p<page>-b<index>}（稳定、可回指）。
   *
   * @param docKey 出版物键
   * @param pageNo 页码
   * @param index 文档内序号
   * @return 图键
   */
  static String key(String docKey, int pageNo, int index) {
    return docKey + "-p" + pageNo + "-b" + index;
  }

  /** 按类型做一次确定性变换（不原地改 source）。 */
  private static BufferedImage transform(BufferedImage source, String kind) {
    BufferedImage result;
    switch (kind) {
      case KIND_JPEG_Q80 -> result = jpegRoundTrip(source);
      case KIND_SCALE_085 -> result = scale(source, SCALE_085_FACTOR);
      case KIND_SCALE_070 -> result = scale(source, SCALE_070_FACTOR);
      case KIND_CROP_04 -> result = crop(source, CROP_04_MARGIN);
      case KIND_CROP_12 -> result = crop(source, CROP_12_MARGIN);
      default -> throw new IllegalArgumentException("未知变体类型: " + kind);
    }
    return result;
  }

  /** PNG → JPEG(q0.80) → 解码回位图（模拟二次压缩 / 转格式）。 */
  private static BufferedImage jpegRoundTrip(BufferedImage source) {
    byte[] jpeg = writeJpeg(source);
    try {
      BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(jpeg));
      if (decoded == null) {
        throw new IllegalStateException("JPEG 变体解码失败（无可用解码器？）");
      }
      return decoded;
    } catch (IOException exception) {
      throw new IllegalStateException("JPEG 变体解码失败", exception);
    }
  }

  /** 双线性重采样（模拟客户端预览图 / 缩略图）。 */
  private static BufferedImage scale(BufferedImage source, double factor) {
    int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
    int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
    BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = target.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.drawImage(source, 0, 0, width, height, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }

  /** 四周各裁 {@code margin} 比例（模拟版面裁切 / 去页眉页脚）。 */
  private static BufferedImage crop(BufferedImage source, double margin) {
    int insetX = (int) Math.round(source.getWidth() * margin);
    int insetY = (int) Math.round(source.getHeight() * margin);
    int width = Math.max(1, source.getWidth() - 2 * insetX);
    int height = Math.max(1, source.getHeight() - 2 * insetY);
    BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = target.createGraphics();
    try {
      graphics.drawImage(
          source, 0, 0, width, height, insetX, insetY, insetX + width, insetY + height, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }

  /** 显式 JPEG 编码（质量写死 0.80，不依赖 JDK 默认值）。 */
  private static byte[] writeJpeg(BufferedImage source) {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) {
      throw new IllegalStateException("找不到 JPEG 编码器（运行环境缺少 ImageIO 插件？）");
    }
    ImageWriter writer = writers.next();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ImageOutputStream stream = ImageIO.createImageOutputStream(out)) {
      writer.setOutput(stream);
      ImageWriteParam param = writer.getDefaultWriteParam();
      param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
      param.setCompressionQuality(JPEG_QUALITY);
      writer.write(null, new IIOImage(rgb(source), null, null), param);
    } catch (IOException exception) {
      throw new IllegalStateException("JPEG 变体编码失败", exception);
    } finally {
      writer.dispose();
    }
    return out.toByteArray();
  }

  /** 位图 → PNG 字节（变体统一以 PNG 落给 embedding，MIME 与生产同值 {@code image/png}）。 */
  private static byte[] toPng(BufferedImage image) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try {
      ImageIO.write(rgb(image), "png", out);
    } catch (IOException exception) {
      throw new IllegalStateException("PNG 变体编码失败", exception);
    }
    return out.toByteArray();
  }

  /** 解码 PNG（失败即抛，不吞）。 */
  private static BufferedImage readPng(byte[] png) {
    try {
      BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
      if (image == null) {
        throw new IllegalStateException("PNG 解码失败（无可用解码器？）");
      }
      return image;
    } catch (IOException exception) {
      throw new IllegalStateException("PNG 解码失败", exception);
    }
  }

  /** 统一转 {@code TYPE_INT_RGB}（JPEG 不支持 alpha；也避免 ARGB/灰度源产出不稳定字节）。 */
  private static BufferedImage rgb(BufferedImage source) {
    if (source.getType() == BufferedImage.TYPE_INT_RGB) {
      return source;
    }
    BufferedImage target =
        new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = target.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.drawImage(source, 0, 0, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }
}
