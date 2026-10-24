package com.slz.crm.quality;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * 近重复变体 → 合成 PDF 装配器（near-dup-ingest-cooccurrence 任务 A.2，零外呼，决策 D1/D5）。
 *
 * <p><b>为什么必须包 PDF</b>：生产入库路径 {@code PdfImageSliceIngestService#buildSlices} 只对 {@code
 * isPdf(filename)} 生效（{@code :130}），直接把 PNG 喂给检索链等于绕过「真实 ingest」——本卡要测的是入库共现，装配必须走生产路径的入口格式。
 * 本类只做「PNG 字节 → 内嵌位图 PDF」这一件事，提取/切片/向量一律由生产实现完成。
 *
 * <p><b>结构与顺序不变式</b>：一图一页、页尺寸 = 图片尺寸、按输入顺序落页；{@code PdfImageExtractor} 按页升序提取， 因此「提取顺序 = 输入顺序」由 PDF
 * 结构保证（与 {@code PdfIngestImageFixture} 同款不变式）。编码用 {@link
 * LosslessFactory}（无损），解码回来的位图像素与输入逐位一致，尺寸天然逐一对上（R0）。
 *
 * <p><b>帽</b>：每份 PDF 图片数 ≤ {@value #MAX_IMAGES_PER_PDF}（= 生产 {@code
 * HARD_MAX_IMAGES}，超帽部分生产提取器直接截断）； 超过时按顺序切成多份返回，调用方逐份走 ingest。
 *
 * <p><b>确定性</b>：无时间戳、无随机源——PDFBox 不写 CreationDate（本类不设 info 字典），同一输入两次生成字节一致。
 */
final class NearDupIngestPdfBuilder {

  /** 每份 PDF 图片数帽（= 生产 {@code PdfImageSliceIngestService.HARD_MAX_IMAGES} 同值，本卡不复制生产常量引用面）。 */
  static final int MAX_IMAGES_PER_PDF = 20;

  private NearDupIngestPdfBuilder() {}

  /**
   * 单图合成 PDF：一页，页尺寸 = 图片尺寸，图片满页无缩放内嵌（S0 探针用，逐图隔离闸门判定）。
   *
   * @param png 变体/base 的 PNG 字节
   * @return PDF 字节
   */
  static byte[] buildSingleImagePdf(byte[] png) {
    return buildMultiImagePdf(List.of(png));
  }

  /**
   * 批量变体 → 合成 PDF 序列：按输入顺序每 {@value #MAX_IMAGES_PER_PDF} 图切一份，每份一图一页。
   *
   * <p>顺序不变式：第 {@code k} 份 PDF 的第 {@code j} 页 ↔ 输入第 {@code k * MAX + j} 张图——ingest 侧以 {@code
   * slice.pageNo()}（PDF 页号，1 起）回指输入下标，闸门拒图也不破坏映射（拒图只是丢切片行，页号不重排）。
   *
   * @param pngImages 保序的 PNG 字节序列（非空）
   * @return PDF 字节序列（每份 ≤ {@value #MAX_IMAGES_PER_PDF} 图）
   */
  static List<byte[]> buildPdfs(List<byte[]> pngImages) {
    if (pngImages == null || pngImages.isEmpty()) {
      throw new IllegalArgumentException("合成 PDF 需要至少一张图");
    }
    List<byte[]> pdfs = new ArrayList<>();
    for (int from = 0; from < pngImages.size(); from += MAX_IMAGES_PER_PDF) {
      int to = Math.min(from + MAX_IMAGES_PER_PDF, pngImages.size());
      pdfs.add(buildMultiImagePdf(pngImages.subList(from, to)));
    }
    return List.copyOf(pdfs);
  }

  /** 一份多页 PDF：每页一图（页尺寸 = 图尺寸，lossless 内嵌），页序 = 输入序。 */
  private static byte[] buildMultiImagePdf(List<byte[]> pngImages) {
    try (PDDocument document = new PDDocument()) {
      for (byte[] png : pngImages) {
        BufferedImage image = readPng(png);
        PDPage page = new PDPage(new PDRectangle(image.getWidth(), image.getHeight()));
        document.addPage(page);
        PDImageXObject xobject = LosslessFactory.createFromImage(document, image);
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          content.drawImage(xobject, 0F, 0F, image.getWidth(), image.getHeight());
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("合成 PDF 生成失败", exception);
    }
  }

  /** 解码 PNG（失败即抛，不吞；与语料侧同一纪律）。 */
  static BufferedImage readPng(byte[] png) {
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
}
