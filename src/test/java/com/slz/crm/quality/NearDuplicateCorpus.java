package com.slz.crm.quality;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.BitSet;
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
   * @param index 本文档内提取序号（1 起；按 SHA-256 去重后的候选计数，<b>退化剔除者仍占号</b>，故保留 base 的序号可能不连续）
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
   * @param taken 去重且剔除退化 base 后实际保留的图数
   */
  record DocumentCount(String docKey, int requested, int taken) {}

  /**
   * 一条 base 的像素属性（锚 {@code corpus.baseDetail} 的一行）。
   *
   * <p>唯一色数按<b>白底合成后</b>的 RGB 逐像素精确计数（{@code BitSet} 覆盖 2^24 色域，不抽样）；均值与标准差取自亮度 {@code 0.299R +
   * 0.587G + 0.114B}，保留 4 位小数（确定性：无浮点顺序依赖，逐像素单调累加）。
   *
   * @param baseKey 图键
   * @param colorModel 源 PNG 的颜色模型（{@code getClass().getSimpleName()#组件数}）
   * @param hasAlpha 源 PNG 是否带 alpha
   * @param uniqueColors 白底合成后的唯一色数（≤ 1 即「像素全同」）
   * @param mean 亮度均值
   * @param stdDev 亮度标准差
   */
  record BasePixel(
      String baseKey,
      String colorModel,
      boolean hasAlpha,
      int uniqueColors,
      double mean,
      double stdDev) {}

  /**
   * 一条退化 base 的剔除登记（不静默保留、不静默丢弃）。
   *
   * @param baseKey 图键
   * @param uniqueColors 白底合成后的唯一色数
   * @param reason 剔除原因
   */
  record ExcludedBase(String baseKey, int uniqueColors, String reason) {}

  /**
   * 语料全集。
   *
   * @param bases 保留的 base 图（SHA-256 全局去重且非退化）
   * @param variants 变体图
   * @param skips 跳过的变体（最短边不足）
   * @param documents 逐文档的实际保留图数
   * @param basePixels 逐 base 的像素属性（与本记录 {@code bases} 同序、等长）
   * @param excludedBases 退化 base 剔除登记（唯一色数 ≤ 1）
   */
  record Corpus(
      List<Base> bases,
      List<Variant> variants,
      List<Skip> skips,
      List<DocumentCount> documents,
      List<BasePixel> basePixels,
      List<ExcludedBase> excludedBases) {

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
    List<BasePixel> basePixels = new ArrayList<>();
    List<ExcludedBase> excludedBases = new ArrayList<>();
    List<DocumentCount> documents = new ArrayList<>();
    Set<String> seenDigests = new LinkedHashSet<>();
    Map<String, Integer> plan = plan();
    for (RealPdfAssets.Asset asset : RealPdfAssets.assets()) {
      Integer requested = plan.get(asset.key());
      if (requested == null) {
        continue;
      }
      int extracted = 0;
      int kept = 0;
      for (RealPdfAssets.Extracted item : RealPdfAssets.extract(asset.pdf(), requested)) {
        if (!seenDigests.add(RealPdfAssets.sha256(item.png()))) {
          continue;
        }
        // 提取序号按「去重后的候选」累加（退化剔除者仍占号）：保留 base 的键与首轮逐字对齐，
        // 剔除登记里的键也能直接与复核报告 §关键问题 的键对上。
        extracted++;
        String key = key(asset.key(), item.pageNo(), extracted);
        BasePixel pixels = pixelsOf(key, readPng(item.png()));
        if (pixels.uniqueColors() <= 1) {
          excludedBases.add(
              new ExcludedBase(
                  key, pixels.uniqueColors(), "白底合成后像素全同（唯一色数 ≤ 1）：无可识别图像内容，不作为 base、不生成变体"));
          continue;
        }
        kept++;
        bases.add(
            new Base(
                asset.key(),
                item.pageNo(),
                extracted,
                key,
                item.png(),
                item.width(),
                item.height()));
        basePixels.add(pixels);
      }
      documents.add(new DocumentCount(asset.key(), requested, kept));
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
        List.copyOf(bases),
        List.copyOf(variants),
        List.copyOf(skips),
        List.copyOf(documents),
        List.copyOf(basePixels),
        List.copyOf(excludedBases));
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

  /** 双线性重采样（模拟客户端预览图 / 缩略图；<b>先铺白底</b>再合成，否则 alpha 源会合成成黑）。 */
  private static BufferedImage scale(BufferedImage source, double factor) {
    int width = Math.max(1, (int) Math.round(source.getWidth() * factor));
    int height = Math.max(1, (int) Math.round(source.getHeight() * factor));
    BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = target.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, width, height);
      graphics.drawImage(source, 0, 0, width, height, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }

  /** 四周各裁 {@code margin} 比例（模拟版面裁切 / 去页眉页脚；<b>先铺白底</b>再合成）。 */
  private static BufferedImage crop(BufferedImage source, double margin) {
    int insetX = (int) Math.round(source.getWidth() * margin);
    int insetY = (int) Math.round(source.getHeight() * margin);
    int width = Math.max(1, source.getWidth() - 2 * insetX);
    int height = Math.max(1, source.getHeight() - 2 * insetY);
    BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = target.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, width, height);
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

  /**
   * 统一转 {@code TYPE_INT_RGB}（JPEG 不支持 alpha；也避免 ARGB/灰度源产出不稳定字节）。
   *
   * <p><b>必须先铺白底再合成</b>（缺陷修复 D1，`proposal.md` §9）：{@code TYPE_INT_RGB} 的初始像素是黑， 直接用带 alpha 的源
   * {@code drawImage} 时透明区会与黑底做 source-over，得到「近纯黑」图——五类变体因此坍缩成同一张图，
   * 同源近重复对退化成「同图对」。铺白底后透明区按白处理（与浏览器/PDF 渲染器的默认背景一致）。
   *
   * @param source 源位图
   * @return {@code TYPE_INT_RGB} 位图（白底）
   */
  private static BufferedImage rgb(BufferedImage source) {
    if (source.getType() == BufferedImage.TYPE_INT_RGB) {
      return source;
    }
    BufferedImage target =
        new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = target.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, source.getWidth(), source.getHeight());
      graphics.drawImage(source, 0, 0, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }

  /**
   * 逐像素统计白底合成后的唯一色数与亮度分布（退化 base 的判据来源，见 {@link BasePixel}）。
   *
   * @param baseKey 图键
   * @param source 源 PNG 位图
   * @return 像素属性
   */
  static BasePixel pixelsOf(String baseKey, BufferedImage source) {
    BufferedImage flat = rgb(source);
    BitSet seen = new BitSet(1 << 24);
    double sum = 0D;
    double sumSquares = 0D;
    long count = 0L;
    for (int y = 0; y < flat.getHeight(); y++) {
      for (int x = 0; x < flat.getWidth(); x++) {
        int pixel = flat.getRGB(x, y);
        int red = (pixel >> 16) & 0xFF;
        int green = (pixel >> 8) & 0xFF;
        int blue = pixel & 0xFF;
        seen.set((red << 16) | (green << 8) | blue);
        double luma = 0.299D * red + 0.587D * green + 0.114D * blue;
        sum += luma;
        sumSquares += luma * luma;
        count++;
      }
    }
    double mean = count == 0L ? 0D : sum / count;
    double variance = count == 0L ? 0D : Math.max(0D, sumSquares / count - mean * mean);
    String colorModel =
        source.getColorModel().getClass().getSimpleName()
            + "#"
            + source.getColorModel().getNumComponents();
    return new BasePixel(
        baseKey,
        colorModel,
        source.getColorModel().hasAlpha(),
        seen.cardinality(),
        round(mean),
        round(Math.sqrt(variance)));
  }

  /** 4 位小数（确定性的十进制收敛，用于锚里的像素属性）。 */
  private static double round(double value) {
    return Math.round(value * 10000D) / 10000D;
  }
}
