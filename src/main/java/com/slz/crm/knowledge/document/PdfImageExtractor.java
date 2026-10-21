package com.slz.crm.knowledge.document;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PDF 内嵌图片对象提取（add-kb-image-slice-retrieval 任务 1.1）。
 *
 * <p>取页资源字典里的 XObject（{@code PDResources#getXObjectNames} → {@code PDImageXObject}），<b>不是</b>整页渲染：
 * 整页渲染会把文字层也画进位图，与本卡「图片对象独立切片」的目标不同；整页那条路由 {@link PdfVisionTranscriber} 负责。
 *
 * <p>无新依赖：解码沿用 PDFBox 3 的 {@code PDImageXObject#getImage()}，缩放/编码复用 {@link
 * PdfVisionTranscriber#scaleToMaxEdge} 与 JDK ImageIO；页内顺序按 XObject 名排序，保证同一 PDF 每次提取顺序一致（切片可复现）。
 */
final class PdfImageExtractor {

  private static final Logger LOG = LoggerFactory.getLogger(PdfImageExtractor.class);

  /** 最短边小于此值的对象视为装饰/图标，不送 VLM 也不落盘。 */
  static final int MIN_EDGE_PX = 64;

  /** 送 VLM 的图片最长边上限（与整页转写同口径）。 */
  static final int MAX_EDGE_PX = PdfVisionTranscriber.MAX_EDGE_PX;

  private PdfImageExtractor() {}

  /**
   * 提取 PDF 内嵌图片对象（按页升序、页内按 XObject 名升序）。
   *
   * @param pdfBytes PDF 字节
   * @param maxImages 最多提取张数（&gt;0；0/负数返回空表）
   * @return 提取结果（不超过 maxImages），任何单图解码失败只跳过该图
   * @throws java.io.IOException PDF 打开失败（交给上层按解析失败处置）
   */
  static List<ExtractedImage> extract(byte[] pdfBytes, int maxImages) throws java.io.IOException {
    List<ExtractedImage> images = new ArrayList<>();
    if (pdfBytes != null && maxImages > 0) {
      try (PDDocument document = Loader.loadPDF(pdfBytes)) {
        for (int pageNo = 1;
            pageNo <= document.getNumberOfPages() && images.size() < maxImages;
            pageNo++) {
          collectPageImages(document, pageNo, maxImages, images);
        }
      }
    }
    return images;
  }

  /** 单页 XObject 收集（页内按名排序，只取位图类 XObject）。 */
  private static void collectPageImages(
      PDDocument document, int pageNo, int maxImages, List<ExtractedImage> images) {
    PDPage page = document.getPage(pageNo - 1);
    PDResources resources = page == null ? null : page.getResources();
    if (resources != null) {
      List<COSName> names = new ArrayList<>();
      for (COSName name : resources.getXObjectNames()) {
        names.add(name);
      }
      names.sort(Comparator.comparing(COSName::getName));
      int sequence = 0;
      for (COSName name : names) {
        if (images.size() >= maxImages) {
          break;
        }
        ExtractedImage image = toExtractedImage(resources, name, pageNo, sequence);
        if (image != null) {
          images.add(image);
          sequence++;
        }
      }
    }
  }

  /** 单个 XObject → PNG（跳过蒙版/过小/无法解码的对象，返回 null 表示不采用）。 */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 单图解码多源（JPX/蒙版/损坏对象），跳过该图不打断整篇
  private static ExtractedImage toExtractedImage(
      PDResources resources, COSName name, int pageNo, int sequence) {
    ExtractedImage result = null;
    try {
      PDXObject xobject = resources.getXObject(name);
      if (xobject instanceof PDImageXObject image && !image.isStencil()) {
        BufferedImage raw = image.getImage();
        if (raw != null && Math.min(raw.getWidth(), raw.getHeight()) >= MIN_EDGE_PX) {
          BufferedImage scaled = PdfVisionTranscriber.scaleToMaxEdge(raw, MAX_EDGE_PX);
          byte[] png = toPng(scaled);
          if (png.length > 0) {
            result =
                new ExtractedImage(pageNo, sequence, png, scaled.getWidth(), scaled.getHeight());
          }
        }
      }
    } catch (Exception exception) {
      LOG.debug("PDF 内嵌图片解码失败，跳过: page={} xobject={}", pageNo, name.getName(), exception);
    }
    return result;
  }

  private static byte[] toPng(BufferedImage image) throws java.io.IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    if (!ImageIO.write(image, "PNG", out)) {
      throw new IllegalStateException("ImageIO 无法写出 PNG");
    }
    return out.toByteArray();
  }

  /**
   * 提取结果。
   *
   * @param pageNo 页码（1 起，落 {@code page_no} 与 {@code SourceReference.pageNo}）
   * @param sequence 页内序号（0 起，按 XObject 名排序后计）
   * @param png PNG 字节
   * @param width 实际落盘宽度
   * @param height 实际落盘高度
   */
  record ExtractedImage(int pageNo, int sequence, byte[] png, int width, int height) {}
}
