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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * S1 窗内共现语料构造器（near-dup-ingest-s1-window-cooccurrence 任务 A.2/A.3，零外呼）。
 *
 * <p><b>与 {@link NearDuplicateCorpus} 的关系</b>：只读复用其 {@code generate()} 的 base 序（生成序 =
 * 提取序），<b>不往里加变体种类</b>——该类的变体清单与顺序被 6 份冻结锚的 {@code selectionRule} 引用。本类另立三种 S1 变体（{@code
 * jpeg-95}/{@code jpeg-85}/{@code scale-95}，对应卡 28 预注册的 「原生分辨率重编码 / 轻度 JPEG 重压 / 整图微缩」三项语义，D1
 * 澄清），参数在类内写死（D2：先钉死后测分，看到分数后不得回改重跑）。
 *
 * <p><b>规则全部跑前冻结</b>（proposal R1/R3）：base 取 {@code nist-sp800-101r1} 生成序前 2 个「短边 ≥64px」者（{@code
 * ExcludedBase} 纯色/低色数排除口径已在 {@code generate()} 内生效，唯一色数 ≤1 者不进 bases）；变体短边用<b>本地像素断言</b>（不花调用），
 * {@code scale-95} 落地边不足时按「减缩」梯子换参数重造（R1），梯子走完仍不足即抛（停手上报，不许弃图凑数）；查询按「2 base × 3 形态」机械推导， 文本取自 base
 * 所在页的 PDFBox 提取文本（零外呼，D4），图像分量 = base 位图。
 *
 * <p><b>确定性</b>：无随机源、无时间戳；JPEG 质量显式 {@code ImageWriteParam}，缩放双线性，同一输入两次生成字节相等（守卫断言）。
 */
final class NearDupS1Corpus {

  /** base 来源出版物键（proposal R1 冻结：只用 nist-sp800-101r1，不下载新资产）。 */
  static final String DOC_KEY = "nist-sp800-101r1";

  /** 生产提取器最短边下限（PdfImageExtractor.MIN_EDGE_PX 同值；NearDupIngestGateProbeIT 同款本地断言）。 */
  static final int MIN_EDGE_PX = 64;

  /** 变体类型（顺序冻结：轻 → 重）。 */
  static final String KIND_JPEG_95 = "jpeg-95";

  static final String KIND_JPEG_85 = "jpeg-85";

  static final String KIND_SCALE_95 = "scale-95";

  /** 变体类型全序（锚 corpusRule.variantKinds 同序）。 */
  static final List<String> KINDS = List.of(KIND_JPEG_95, KIND_JPEG_85, KIND_SCALE_95);

  /** {@code jpeg-95} 的 JPEG 质量参数（写死，D2）。 */
  static final float JPEG_95_QUALITY = 0.95F;

  /** {@code jpeg-85} 的 JPEG 质量参数（写死，D2）。 */
  static final float JPEG_85_QUALITY = 0.85F;

  /** {@code scale-95} 的名义缩放比例（写死，D2）。 */
  static final double SCALE_95_FACTOR = 0.95D;

  /**
   * scale 落地边不足时的「减缩」梯子（R1 换参数重造：只许少缩不许放大，0.98 封顶保持「微缩」语义； 走完仍 &lt;64px 即抛——base 短边 &lt;65
   * 时任何微缩都保不住 64px，停手上报）。
   */
  static final List<Double> SCALE_LADDER = List.of(0.95D, 0.96D, 0.97D, 0.98D);

  /** 查询形态①：页面标题式短查询。 */
  static final String FORM_TITLE = "title";

  /** 查询形态②：正文首句。 */
  static final String FORM_FIRST_SENTENCE = "first-sentence";

  /** 查询形态③：表格/图注关键词组。 */
  static final String FORM_CAPTION_KEYWORDS = "caption-keywords";

  /** 查询形态全序（锚 queries 的 form 取值域）。 */
  static final List<String> FORMS = List.of(FORM_TITLE, FORM_FIRST_SENTENCE, FORM_CAPTION_KEYWORDS);

  /** title 截断长度（机械规则冻结）。 */
  static final int TITLE_MAX_CHARS = 40;

  /** first-sentence 截断长度。 */
  static final int SENTENCE_MAX_CHARS = 120;

  /** first-sentence 的终结符扫描窗口。 */
  static final int SENTENCE_SCAN_CHARS = 200;

  /** first-sentence 下限（过短则延到第二个终结符）。 */
  static final int SENTENCE_MIN_CHARS = 20;

  /** first-sentence 无终结符时的兜底长度。 */
  static final int SENTENCE_FALLBACK_CHARS = 80;

  /** caption-keywords 截断长度。 */
  static final int KEYWORD_MAX_CHARS = 120;

  /** caption-keywords 词数上限。 */
  static final int KEYWORD_MAX_TOKENS = 8;

  /** 表格/图注行判别（英文出版物口径；匹配不到回落末行）。 */
  private static final Pattern CAPTION_LINE =
      Pattern.compile("(?i).*(table|figure|fig\\.|exhibit).*");

  /** 句终结符集合（中英混收，机械规则）。 */
  private static final String SENTENCE_TERMINATORS = "。．.！!？?；;";

  private NearDupS1Corpus() {}

  /** 一份 S1 base（真实 PDF 提取图 + 其所在页文本）。 */
  record S1Base(String key, int pageNo, int width, int height, byte[] png, int pageTextChars) {}

  /**
   * 一份 S1 变体。
   *
   * @param param 落锚参数（{@code q0.95} / {@code q0.85} / {@code x0.95}（实际所用比例，梯子换档时如实记））
   */
  record S1Variant(
      String kind, String baseKey, String key, byte[] png, int width, int height, String param) {}

  /** 一条 S1 查询（文本机械推导自 base 所在页；图像分量 = base 位图）。 */
  record S1Query(int index, String baseKey, String form, String text) {}

  /** S1 语料全集（2 base × 3 变体 × 6 查询）。 */
  record S1Corpus(List<S1Base> bases, List<S1Variant> variants, List<S1Query> queries) {

    /** base 键 → base。 */
    S1Base baseByKey(String key) {
      return bases.stream().filter(b -> b.key().equals(key)).findFirst().orElse(null);
    }
  }

  /**
   * 生成 S1 语料（唯一入口，纯确定性，零外呼）。
   *
   * <p>全部构造规则在此本地校验（先钉规则后花钱）：base 合规、变体短边 ≥64px、查询非空且单路（{@code ConstraintQuerySplitter} 不拆分 ⇒ 每查询恰
   * 1 次 embed，预算可精确）。
   */
  static S1Corpus generate() {
    NearDuplicateCorpus.Corpus corpus = NearDuplicateCorpus.generate();
    List<NearDuplicateCorpus.Base> candidates =
        corpus.bases().stream()
            .filter(base -> DOC_KEY.equals(base.docKey()))
            .filter(base -> Math.min(base.width(), base.height()) >= MIN_EDGE_PX)
            .toList();
    if (candidates.size() < 2) {
      throw new IllegalStateException(
          "停手：nist-sp800-101r1 生成序短边 ≥"
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

    List<S1Base> bases = new ArrayList<>();
    for (NearDuplicateCorpus.Base base : candidates.subList(0, 2)) {
      String pageText = pageTextOf(pdf, base.pageNo());
      if (pageText.isBlank()) {
        throw new IllegalStateException(
            "停手：base " + base.key() + " 所在页 " + base.pageNo() + " 无可提取文本，查询规则无法落地");
      }
      bases.add(
          new S1Base(
              base.key(),
              base.pageNo(),
              base.width(),
              base.height(),
              base.png(),
              pageText.length()));
    }

    List<S1Variant> variants = new ArrayList<>();
    for (S1Base base : bases) {
      BufferedImage source = readPng(base.png());
      variants.add(jpegVariant(source, base, KIND_JPEG_95, JPEG_95_QUALITY));
      variants.add(jpegVariant(source, base, KIND_JPEG_85, JPEG_85_QUALITY));
      variants.add(scaleVariant(source, base));
    }

    List<S1Query> queries = new ArrayList<>();
    int index = 0;
    for (S1Base base : bases) {
      String pageText = pageTextOf(pdf, base.pageNo());
      for (String form : FORMS) {
        String text = formOf(form, pageText);
        if (text.isBlank()) {
          throw new IllegalStateException(
              "停手：base " + base.key() + " 形态 " + form + " 推导出空查询（页面文本形状超预期）");
        }
        if (ConstraintQuerySplitter.split(text).size() != 1) {
          throw new IllegalStateException(
              "停手：查询被 ConstraintQuerySplitter 拆成多路（预算 1 embed/查询 被破坏）: " + text);
        }
        queries.add(new S1Query(index++, base.key(), form, text));
      }
    }
    return new S1Corpus(List.copyOf(bases), List.copyOf(variants), List.copyOf(queries));
  }

  /** 单形态机械推导（跑前冻结；形态外的输入即抛）。 */
  static String formOf(String form, String pageText) {
    return switch (form) {
      case FORM_TITLE -> titleOf(pageText);
      case FORM_FIRST_SENTENCE -> firstSentenceOf(pageText);
      case FORM_CAPTION_KEYWORDS -> captionKeywordsOf(pageText);
      default -> throw new IllegalArgumentException("未知查询形态: " + form);
    };
  }

  /** ①页面标题式短查询：首个非空行，空白压缩，截 {@value #TITLE_MAX_CHARS} 字符。 */
  static String titleOf(String pageText) {
    List<String> lines = nonBlankLines(pageText);
    return truncate(compact(lines.get(0)), TITLE_MAX_CHARS);
  }

  /**
   * ②正文首句：跳过首行（标题行）后的正文，空白压缩；前 {@value #SENTENCE_SCAN_CHARS} 字符内找首个句终结符（{@value
   * #SENTENCE_TERMINATORS}）截到含终结符；不足 {@value #SENTENCE_MIN_CHARS} 字符延到第二个终结符；仍无则取前 {@value
   * #SENTENCE_FALLBACK_CHARS} 字符；统一截 {@value #SENTENCE_MAX_CHARS}。
   */
  static String firstSentenceOf(String pageText) {
    List<String> lines = nonBlankLines(pageText);
    String body =
        lines.size() > 1 ? String.join(" ", lines.subList(1, lines.size())) : lines.get(0);
    body = compact(body);
    int scan = Math.min(SENTENCE_SCAN_CHARS, body.length());
    int first = terminatorAt(body, 0, scan);
    int cut;
    if (first >= 0 && first + 1 >= SENTENCE_MIN_CHARS) {
      cut = first + 1;
    } else {
      int second = first >= 0 ? terminatorAt(body, first + 1, scan) : -1;
      cut = second >= 0 ? second + 1 : Math.min(SENTENCE_FALLBACK_CHARS, body.length());
    }
    return truncate(body.substring(0, cut), SENTENCE_MAX_CHARS);
  }

  /**
   * ③表格/图注关键词组：首个匹配 {@code (?i).*(table|figure|fig.|exhibit).*} 的行，无则末个非空行；取前 {@value
   * #KEYWORD_MAX_TOKENS} 个空白分隔词，空格连接，截 {@value #KEYWORD_MAX_CHARS}。
   */
  static String captionKeywordsOf(String pageText) {
    List<String> lines = nonBlankLines(pageText);
    String line = null;
    for (String candidate : lines) {
      if (CAPTION_LINE.matcher(candidate).matches()) {
        line = candidate;
        break;
      }
    }
    if (line == null) {
      line = lines.get(lines.size() - 1);
    }
    List<String> tokens = new ArrayList<>();
    for (String token : line.split("\\s+")) {
      if (!token.isEmpty()) {
        tokens.add(token);
      }
      if (tokens.size() == KEYWORD_MAX_TOKENS) {
        break;
      }
    }
    return truncate(String.join(" ", tokens), KEYWORD_MAX_CHARS);
  }

  /** JPEG 重编码变体：原生分辨率 PNG→JPEG(q)→解码回位图（D1：「原生分辨率重编码」语义）。 */
  private static S1Variant jpegVariant(
      BufferedImage source, S1Base base, String kind, float quality) {
    BufferedImage decoded = jpegRoundTrip(source, quality);
    assertMinEdge(decoded, base.key() + "@" + kind);
    return new S1Variant(
        kind,
        base.key(),
        base.key() + "@" + kind,
        toPng(decoded),
        decoded.getWidth(),
        decoded.getHeight(),
        "q" + String.format(Locale.ROOT, "%.2f", quality));
  }

  /** 整图微缩变体：梯子第一个保住 {@value #MIN_EDGE_PX}px 短边的比例（R1 换参数重造；走完即抛）。 */
  private static S1Variant scaleVariant(BufferedImage source, S1Base base) {
    Double used = null;
    BufferedImage scaled = null;
    for (double factor : SCALE_LADDER) {
      BufferedImage candidate = scale(source, factor);
      if (Math.min(candidate.getWidth(), candidate.getHeight()) >= MIN_EDGE_PX) {
        used = factor;
        scaled = candidate;
        break;
      }
    }
    if (used == null) {
      throw new IllegalStateException(
          "停手：base "
              + base.key()
              + " 的 scale 变体梯子 "
              + SCALE_LADDER
              + " 全部短边 <"
              + MIN_EDGE_PX
              + "px（base 短边 "
              + Math.min(base.width(), base.height())
              + "px，无法通过换参数救回，不许弃图）");
    }
    return new S1Variant(
        KIND_SCALE_95,
        base.key(),
        base.key() + "@" + KIND_SCALE_95,
        toPng(scaled),
        scaled.getWidth(),
        scaled.getHeight(),
        "x" + String.format(Locale.ROOT, "%.2f", used));
  }

  /** 短边本地像素断言（不花调用；不足即抛，调用方在花钱前就会停）。 */
  private static void assertMinEdge(BufferedImage image, String key) {
    if (Math.min(image.getWidth(), image.getHeight()) < MIN_EDGE_PX) {
      throw new IllegalStateException(
          "停手：变体 "
              + key
              + " 短边 "
              + Math.min(image.getWidth(), image.getHeight())
              + "px < "
              + MIN_EDGE_PX
              + "px");
    }
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

  /** 非空行列表（strip 后非空，保持原序）。 */
  private static List<String> nonBlankLines(String pageText) {
    List<String> lines = new ArrayList<>();
    for (String line : pageText.split("\n")) {
      if (!line.strip().isEmpty()) {
        lines.add(line.strip());
      }
    }
    if (lines.isEmpty()) {
      throw new IllegalStateException("页面文本无非空行");
    }
    return lines;
  }

  /** 空白压缩为单空格。 */
  private static String compact(String text) {
    return text.replaceAll("\\s+", " ").strip();
  }

  /** 超长截断（确定性子串，不加省略标记）。 */
  private static String truncate(String text, int maxChars) {
    return text.length() <= maxChars ? text : text.substring(0, maxChars);
  }

  /** [from, until) 内首个句终结符下标；无则 -1。 */
  private static int terminatorAt(String body, int from, int until) {
    for (int index = from; index < until && index < body.length(); index++) {
      if (SENTENCE_TERMINATORS.indexOf(body.charAt(index)) >= 0) {
        return index;
      }
    }
    return -1;
  }

  /** PNG → JPEG(q) → 解码回位图。 */
  private static BufferedImage jpegRoundTrip(BufferedImage source, float quality) {
    byte[] jpeg = writeJpeg(source, quality);
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

  /** 双线性重采样（先铺白底再合成，与 NearDuplicateCorpus 同纪律）。 */
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

  /** 显式质量参数的 JPEG 编码（不依赖 JDK 默认值）。 */
  private static byte[] writeJpeg(BufferedImage source, float quality) {
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
      param.setCompressionQuality(quality);
      writer.write(null, new IIOImage(rgb(source), null, null), param);
    } catch (IOException exception) {
      throw new IllegalStateException("JPEG 变体编码失败", exception);
    } finally {
      writer.dispose();
    }
    return out.toByteArray();
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

  /** 语料规则单行摘要（锚 corpusRule 用；与类内常量同源，不抄数字）。 */
  static Map<String, Object> ruleSummary() {
    Map<String, Object> rule = new LinkedHashMap<>();
    rule.put("docKey", DOC_KEY);
    rule.put("minEdgePx", MIN_EDGE_PX);
    rule.put("variantKinds", KINDS);
    rule.put("scaleLadder", SCALE_LADDER);
    rule.put("forms", FORMS);
    return rule;
  }
}
