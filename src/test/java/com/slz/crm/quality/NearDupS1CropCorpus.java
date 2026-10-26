package com.slz.crm.quality;

import com.slz.crm.knowledge.retrieval.ConstraintQuerySplitter;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 卡 32 腿 A 语料构造器（near-dup-band-entry-and-tier-fill，零外呼）。
 *
 * <p><b>与既有冻结类的关系</b>：只读复用 {@link NearDuplicateCorpus#generate()} 的 base 序与 {@link NearDupS1Corpus}
 * 的查询形态推导（{@link NearDupS1Corpus#formOf}），<b>不修改任何一个</b>——两者的变体清单与顺序均被冻结锚 replay 引用（proposal
 * R1「新语料必须另立类」）。本类另立三种<b>纯几何</b>变体：四周边距 {@code crop-02}/{@code crop-04}/{@code crop-06}（D1：卡 31
 * 已证质量型 jpeg-95/jpeg-85/scale-95 全落 SAME_IMAGE（cos ≥ 0.997653），再花质量型即重复劳动； 全仓唯一实测带内证据是 S0 探针的
 * {@code crop-04} cos=0.9934 ∈ [0.982, 0.9944)）。裁切语义与 {@code NearDuplicateCorpus#crop} 同款（四边等比
 * inset、 TYPE_INT_RGB、白底合成），边距参数类内写死（D2：先钉死后测分，看到分数后不得回改重跑）。
 *
 * <p><b>规则全部跑前冻结</b>（proposal R1）：base 取 {@code nist-sp800-101r1} 生成序前 2 个「短边 ≥64px
 * 且非纯色/低色数」者（选择口径与 卡 31 {@code NearDupS1Corpus} 完全同序 ⇒ 同两个
 * base）；裁切后短边用<b>本地像素断言</b>（不花调用），不足时按「更小边距」梯子换参数重造 （只许少切不许多切，0.01 封底），梯子走完仍不足即抛（停手上报，不许弃图凑数）；查询按「2
 * base × 3 形态」机械推导（复用卡 31 冻结形态规则）， 文本取自 base 所在页的 PDFBox 提取文本（零外呼），图像分量 = base 位图。
 *
 * <p><b>确定性</b>：无随机源、无时间戳；裁切为确定性子区复制，同一输入两次生成字节相等（守卫断言）。
 */
final class NearDupS1CropCorpus {

  /** base 来源出版物键（R1 冻结：与卡 31 同源，不下载新资产）。 */
  static final String DOC_KEY = "nist-sp800-101r1";

  /** 生产提取器最短边下限（PdfImageExtractor.MIN_EDGE_PX 同值；本地像素断言用）。 */
  static final int MIN_EDGE_PX = 64;

  /** 变体类型①：2% 边距裁切。 */
  static final String KIND_CROP_02 = "crop-02";

  /** 变体类型②：4% 边距裁切（S0 探针实测 cos=0.9934 的同款边距）。 */
  static final String KIND_CROP_04 = "crop-04";

  /** 变体类型③：6% 边距裁切。 */
  static final String KIND_CROP_06 = "crop-06";

  /** 变体类型全序（边距从小到大；锚 corpusRule.variantKinds 同序）。 */
  static final List<String> KINDS = List.of(KIND_CROP_02, KIND_CROP_04, KIND_CROP_06);

  /** {@code crop-02} 的四周边距比例（写死，D2）。 */
  static final double MARGIN_02 = 0.02D;

  /** {@code crop-04} 的四周边距比例（写死，D2）。 */
  static final double MARGIN_04 = 0.04D;

  /** {@code crop-06} 的四周边距比例（写死，D2）。 */
  static final double MARGIN_06 = 0.06D;

  /** 边距全序（锚 corpusRule.params 同序）。 */
  static final List<Double> MARGINS = List.of(MARGIN_02, MARGIN_04, MARGIN_06);

  /** 边距步进（梯子换档的确定性步长，0.01 封底）。 */
  static final double MARGIN_STEP = 0.01D;

  /** 查询形态全序（复用卡 31 冻结形态：title / first-sentence / caption-keywords）。 */
  static final List<String> FORMS = NearDupS1Corpus.FORMS;

  private NearDupS1CropCorpus() {}

  /** 一份腿 A base（真实 PDF 提取图 + 其所在页文本长度）。 */
  record CropBase(String key, int pageNo, int width, int height, byte[] png, int pageTextChars) {}

  /**
   * 一份腿 A 裁切变体。
   *
   * @param param 落锚参数（{@code m0.06} 等，梯子换档时如实记实际边距）
   */
  record CropVariant(
      String kind, String baseKey, String key, byte[] png, int width, int height, String param) {}

  /** 一条腿 A 查询（文本机械推导自 base 所在页；图像分量 = base 位图）。 */
  record CropQuery(int index, String baseKey, String form, String text) {}

  /** 腿 A 语料全集（2 base × 3 裁切变体 × 6 查询）。 */
  record CropCorpus(List<CropBase> bases, List<CropVariant> variants, List<CropQuery> queries) {

    /** base 键 → base。 */
    CropBase baseByKey(String key) {
      return bases.stream().filter(b -> b.key().equals(key)).findFirst().orElse(null);
    }
  }

  /**
   * 生成腿 A 语料（唯一入口，纯确定性，零外呼）。
   *
   * <p>全部构造规则在此本地校验（先钉规则后花钱）：base 合规（与卡 31 同序同口径）、变体裁切后短边 ≥64px（梯子兜底，走完即抛）、查询非空且单路 （{@code
   * ConstraintQuerySplitter} 不拆分 ⇒ 每查询恰 1 次 embed，预算可精确）。
   */
  static CropCorpus generate() {
    NearDuplicateCorpus.Corpus corpus = NearDuplicateCorpus.generate();
    List<NearDuplicateCorpus.Base> candidates =
        corpus.bases().stream()
            .filter(base -> DOC_KEY.equals(base.docKey()))
            .filter(base -> Math.min(base.width(), base.height()) >= MIN_EDGE_PX)
            .toList();
    if (candidates.size() < 2) {
      throw new IllegalStateException(
          "停手："
              + DOC_KEY
              + " 生成序短边 ≥"
              + MIN_EDGE_PX
              + "px 的 base 不足 2 个（实测 "
              + candidates.size()
              + "），语料规则无法落地");
    }
    RealPdfAssets.Asset asset =
        RealPdfAssets.assets().stream()
            .filter(item -> DOC_KEY.equals(item.key()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("资产表缺 " + DOC_KEY));
    byte[] pdf = asset.pdf();

    List<CropBase> bases = new ArrayList<>();
    for (NearDuplicateCorpus.Base base : candidates.subList(0, 2)) {
      String pageText = pageTextOf(pdf, base.pageNo());
      if (pageText.isBlank()) {
        throw new IllegalStateException(
            "停手：base " + base.key() + " 所在页 " + base.pageNo() + " 无可提取文本，查询规则无法落地");
      }
      bases.add(
          new CropBase(
              base.key(),
              base.pageNo(),
              base.width(),
              base.height(),
              base.png(),
              pageText.length()));
    }

    List<CropVariant> variants = new ArrayList<>();
    for (CropBase base : bases) {
      BufferedImage source = readPng(base.png());
      for (int index = 0; index < KINDS.size(); index++) {
        variants.add(cropVariant(source, base, KINDS.get(index), MARGINS.get(index)));
      }
    }

    List<CropQuery> queries = new ArrayList<>();
    int index = 0;
    for (CropBase base : bases) {
      String pageText = pageTextOf(pdf, base.pageNo());
      for (String form : FORMS) {
        String text = NearDupS1Corpus.formOf(form, pageText);
        if (text.isBlank()) {
          throw new IllegalStateException(
              "停手：base " + base.key() + " 形态 " + form + " 推导出空查询（页面文本形状超预期）");
        }
        if (ConstraintQuerySplitter.split(text).size() != 1) {
          throw new IllegalStateException(
              "停手：查询被 ConstraintQuerySplitter 拆成多路（预算 1 embed/查询 被破坏）: " + text);
        }
        queries.add(new CropQuery(index++, base.key(), form, text));
      }
    }
    return new CropCorpus(List.copyOf(bases), List.copyOf(variants), List.copyOf(queries));
  }

  /** 单个裁切变体：目标边距起，落地短边不足时按 {@value #MARGIN_STEP} 步进向下换更小边距重造（R1：只许少切、不许弃图）， 0.01 封底仍不足即抛。 */
  private static CropVariant cropVariant(
      BufferedImage source, CropBase base, String kind, double targetMargin) {
    Double used = null;
    BufferedImage cropped = null;
    for (double margin = targetMargin; margin >= MARGIN_STEP - 1e-9; margin -= MARGIN_STEP) {
      BufferedImage candidate = crop(source, margin);
      if (Math.min(candidate.getWidth(), candidate.getHeight()) >= MIN_EDGE_PX) {
        used = margin;
        cropped = candidate;
        break;
      }
    }
    if (used == null) {
      throw new IllegalStateException(
          "停手：base "
              + base.key()
              + " 的 "
              + kind
              + " 变体边距梯子（步进 "
              + MARGIN_STEP
              + " 下探至 "
              + MARGIN_STEP
              + "）全部短边 <"
              + MIN_EDGE_PX
              + "px（base 短边 "
              + Math.min(base.width(), base.height())
              + "px，无法通过换边距救回，不许弃图）");
    }
    return new CropVariant(
        kind,
        base.key(),
        base.key() + "@" + kind,
        toPng(cropped),
        cropped.getWidth(),
        cropped.getHeight(),
        "m" + String.format(Locale.ROOT, "%.2f", used));
  }

  /** PDFBox 单页文本提取（零外呼；失败即抛不吞）。 */
  private static String pageTextOf(byte[] pdf, int pageNo) {
    try (PDDocument document = Loader.loadPDF(pdf)) {
      PDFTextStripper stripper = new PDFTextStripper();
      stripper.setStartPage(pageNo);
      stripper.setEndPage(pageNo);
      return stripper.getText(document).strip();
    } catch (IOException exception) {
      throw new IllegalStateException("页面文本提取失败: page " + pageNo, exception);
    }
  }

  /** 四边等比 inset 裁切（与 NearDuplicateCorpus#crop 同语义：TYPE_INT_RGB、白底、确定性子区复制）。 */
  private static BufferedImage crop(BufferedImage source, double margin) {
    int insetX = (int) Math.round(source.getWidth() * margin);
    int insetY = (int) Math.round(source.getHeight() * margin);
    int width = Math.max(1, source.getWidth() - 2 * insetX);
    int height = Math.max(1, source.getHeight() - 2 * insetY);
    BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = target.createGraphics();
    try {
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, width, height);
      graphics.drawImage(
          source, 0, 0, width, height, insetX, insetY, insetX + width, insetY + height, null);
    } finally {
      graphics.dispose();
    }
    return target;
  }

  /** 位图 → PNG 字节（变体统一以 PNG 落给 embedding，MIME 与生产同值 image/png）。 */
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

  /** 统一 TYPE_INT_RGB（白底合成，与 NearDuplicateCorpus#rgb 同纪律）。 */
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
}
