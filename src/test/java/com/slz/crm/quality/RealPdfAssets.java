package com.slz.crm.quality;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 真实 PDF 语料入口（production-gray-real-run 任务 1.1 / 1.2）。
 *
 * <p><b>为什么这批语料是全库唯一的二进制测试资产</b>：前四张多模态卡的语料都是<b>程序化确定性图/PDF</b>（{@code pdfBytes()} 现场生成），
 * 好处是可复现，坏处是从未穿过"真实生产文档"这条最难走的路——真实 PDF 的图对象常见是矢量图、&lt;64px 装饰图、JPX/JBIG2 编码、或被优化器
 * 移除，这些坑只有真资产才撞得到。本类把 owner 拍板入库的公有领域政府出版物（NIST / DOE）作为<b>只读</b>语料接入，来源与授权逐份登记在 {@code
 * sources.json}（URL + SHA-256 + 授权声明），任何测试不得改写这些 PDF。
 *
 * <p><b>与生产实现的关系</b>：本类<b>只做读取与反射桥</b>，提取/切片/向量一律走生产实现（{@code PdfImageExtractor}、 {@code
 * PdfImageSliceIngestService}、{@code ImageVisualVectorSupport}、{@code
 * ImageVectorFlushSupport}）。为什么用反射：那三个类的可见性是包内 （{@code com.slz.crm.knowledge.document}），质量评测在
 * {@code com.slz.crm.quality}；反射让"打开生产实现"这件事保持在测试侧， 免去为评测放宽生产可见性（{@code src/main} 零 diff 是本卡硬约束）。
 */
final class RealPdfAssets {

  /** 真实 PDF 资产目录（随 repo 分发；只读，测试不得写）。 */
  static final Path DIR = Path.of("src", "test", "resources", "rag-quality", "real-pdf");

  /** 来源与授权登记文件（与 PDF 同目录，唯一授权真相源）。 */
  static final String MANIFEST_FILE = "sources.json";

  /** 单份 PDF 体积上限（owner 口径：控制 repo 负担）。 */
  static final int MAX_BYTES = 5 * 1024 * 1024;

  /** 每份文档最多取几张图（成本帽：真实文档动辄上百张内嵌图，真跑要可控）。 */
  static final int MAX_IMAGES_PER_DOCUMENT = 3;

  /** 图对象最短边下限（生产 {@code PdfImageExtractor#MIN_EDGE_PX} 同值，单测据此判"可提取"）。 */
  static final int MIN_EDGE_PX = 64;

  private static final ObjectMapper MAPPER = new ObjectMapper();

  private RealPdfAssets() {}

  /**
   * 一份登记在册的真实 PDF。
   *
   * @param key 稳定键（用例编号/落盘用，不随文件名漂移）
   * @param file 仓库内文件名（{@link #DIR} 下）
   * @param title 出版物标题
   * @param agency 发布机构（NIST / DOE）
   * @param sourceUrl 原始下载 URL（授权可溯源）
   * @param sha256 资产摘要（防替换：单测核对）
   * @param bytes 体积（字节；单测核对 ≤ {@link #MAX_BYTES}）
   * @param pages 页数
   * @param verifiedRasterImages 入库时实测可提取位图数（单测复核，不得凭空写）
   * @param license 授权结论
   * @param licenseEvidence 授权声明页 URL
   * @param licenseQuote 授权声明原文摘录
   */
  record Asset(
      String key,
      String file,
      String title,
      String agency,
      String sourceUrl,
      String sha256,
      long bytes,
      int pages,
      int verifiedRasterImages,
      String license,
      String licenseEvidence,
      String licenseQuote) {

    /**
     * @return 本资产 PDF 字节
     */
    byte[] pdf() {
      return RealPdfAssets.bytes(this);
    }

    /**
     * @return 本资产在仓库内的绝对化路径
     */
    Path path() {
      return DIR.resolve(file).toAbsolutePath();
    }
  }

  /**
   * 读取登记表里的全部资产（顺序即判分/落盘顺序，跑之前固定）。
   *
   * @return 资产表（不可变）
   */
  static List<Asset> assets() {
    JsonNode root = manifest();
    List<Asset> assets = new ArrayList<>();
    for (JsonNode node : root.path("assets")) {
      assets.add(
          new Asset(
              node.path("key").asText(),
              node.path("file").asText(),
              node.path("title").asText(),
              node.path("agency").asText(),
              node.path("sourceUrl").asText(),
              node.path("sha256").asText(),
              node.path("bytes").asLong(),
              node.path("pages").asInt(),
              node.path("verifiedRasterImages").asInt(),
              node.path("license").asText(),
              node.path("licenseEvidence").asText(),
              node.path("licenseQuote").asText()));
    }
    return List.copyOf(assets);
  }

  /**
   * 读取授权登记表原文（单测核对入口）。
   *
   * @return 登记表根节点
   */
  static JsonNode manifest() {
    Path path = DIR.resolve(MANIFEST_FILE);
    try {
      return MAPPER.readTree(Files.readString(path));
    } catch (IOException exception) {
      throw new IllegalStateException("真实 PDF 授权登记表读取失败: " + path.toAbsolutePath(), exception);
    }
  }

  /**
   * 读取资产字节（只读；不做任何改写，保证 SHA-256 与登记一致）。
   *
   * @param asset 资产
   * @return PDF 字节
   */
  static byte[] bytes(Asset asset) {
    try {
      return Files.readAllBytes(DIR.resolve(asset.file()));
    } catch (IOException exception) {
      throw new IllegalStateException("真实 PDF 资产读取失败: " + asset.file(), exception);
    }
  }

  /**
   * SHA-256（登记表与单测共用同一算法）。
   *
   * @param data 字节
   * @return 小写十六进制摘要
   */
  static String sha256(byte[] data) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(data));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 不可用", exception);
    }
  }

  /**
   * 生产提取器的一次提取结果（{@code PdfImageExtractor.ExtractedImage} 的测试侧投影）。
   *
   * @param pageNo 页码（1 起）
   * @param width 落盘宽度
   * @param height 落盘高度
   * @param png PNG 字节
   */
  record Extracted(int pageNo, int width, int height, byte[] png) {}

  /**
   * 调生产 {@code PdfImageExtractor#extract}（包内可见，故反射）。
   *
   * @param pdf PDF 字节
   * @param maxImages 最多取几张
   * @return 提取结果（生产口径：跳过 stencil / 最短边 &lt; 64px / 无法解码的对象）
   */
  static List<Extracted> extract(byte[] pdf, int maxImages) {
    List<Extracted> result = new ArrayList<>();
    try {
      Class<?> extractor = Class.forName("com.slz.crm.knowledge.document.PdfImageExtractor");
      Method extract = extractor.getDeclaredMethod("extract", byte[].class, int.class);
      extract.setAccessible(true);
      for (Object image : (List<?>) extract.invoke(null, pdf, maxImages)) {
        result.add(project(image));
      }
      return result;
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("调生产 PdfImageExtractor#extract 失败（内部契约变动？）", exception);
    }
  }

  /**
   * 读摄取链暂存的视觉向量（{@code ImageVisualVectorSupport#staged()}，包内可见故反射）。
   *
   * <p>必须在 {@code ImageVectorFlushSupport#flush} <b>之前</b>读：flush 消费后清 ThreadLocal，那时再读只能拿到空表。
   *
   * @return storageKey → 真视觉向量（读不到返回空表）
   */
  @SuppressWarnings("unchecked")
  static Map<String, float[]> stagedVectors() {
    try {
      Class<?> support = Class.forName("com.slz.crm.knowledge.document.ImageVisualVectorSupport");
      Method staged = support.getDeclaredMethod("staged");
      staged.setAccessible(true);
      return new LinkedHashMap<>((Map<String, float[]>) staged.invoke(null));
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException("读取摄取链暂存视觉向量失败（内部契约变动？）", exception);
    }
  }

  /** 生产提取结果 record → 测试侧投影（访问器是包内 record 的方法，故仍需 setAccessible）。 */
  private static Extracted project(Object image) throws ReflectiveOperationException {
    Class<?> type = image.getClass();
    Method pageNo = type.getDeclaredMethod("pageNo");
    Method width = type.getDeclaredMethod("width");
    Method height = type.getDeclaredMethod("height");
    Method png = type.getDeclaredMethod("png");
    for (Method method : List.of(pageNo, width, height, png)) {
      method.setAccessible(true);
    }
    return new Extracted(
        (Integer) pageNo.invoke(image),
        (Integer) width.invoke(image),
        (Integer) height.invoke(image),
        (byte[]) png.invoke(image));
  }
}
