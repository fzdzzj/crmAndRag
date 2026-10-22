package com.slz.crm.quality;

import com.slz.crm.knowledge.storage.FileStorageService;
import com.slz.crm.quality.ImageCrossModalSuite.ImageCase;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * PDF 摄取链图文评测 fixture（pdf-ingest-image-eval 任务 1.1）：确定性三型内嵌图 PDF + 用例表 + 摄取侧测试替身。
 *
 * <p><b>为什么是程序化 PDF 而不是入库二进制资产</b>：与 {@link ImageCrossModalFixture} 同纪律——评测要可复现（任意机器重跑得到同一份图 →
 * 同一份基线可比），且零二进制资产入库。上一卡把"确定性图像语料"做成 PNG 直喂 {@code embedImage}；本卡把同一批图像<b>先塞进真
 * PDF</b>，于是评测真正穿过了生产摄取链的前半段（{@code PdfImageExtractor} 从页资源字典取 {@code PDImageXObject} → 解码 → PNG）。
 *
 * <p><b>三型语料（任务 1.1 的判据面）</b>：
 *
 * <ul>
 *   <li><b>结构图</b>{@link #STRUCTURE_KEY}：三层节点区块 + 箭头连线的蓝色结构示意图（query-by-image 的黄金目标）；
 *   <li><b>异图</b>{@link #CROSS_TYPE_KEY}：柱状图。与结构图<b>同为"图表类"但跨图型</b>——真视觉向量应把它推开，这正是"同图召回 +
 *       异图拒答"判别力的位点；
 *   <li><b>纯白易负例</b>{@link #BLANK_KEY}：整页纯白、零图形零文字。它给判据装地板：证明"异图拒答"不是靠"任何图都判异"混过。
 * </ul>
 *
 * <p><b>摄取顺序即黄金映射</b>：三张图各占一页（pageNo 1/2/3）且每页只有一个图对象，{@code PdfImageExtractor} 按页升序、页内按 XObject
 * 名升序提取，因此"提取顺序 = {@link #images()} 顺序"是由 PDF 结构保证的不变式（单测对 PDF 结构直接断言），切片行主键由 {@link
 * #chunkIdOf(String)} 确定给出，黄金引用 id 走生产口径 {@code IMAGE:{行主键}}（{@code
 * SourceReferenceFactory.IMAGE_CHUNK_PREFIX}）。
 *
 * <p><b>为什么不按结果挑图</b>：三型与用例在跑之前固定；判据阈值直接复用 {@code ImageCrossModalSuite} 的 0.90（对齐 {@code
 * VisionEmbeddingRealIT}），本类不新增阈值、不改任何既有套件。
 */
final class PdfIngestImageFixture {

  /** 独立套件版本号（不并入 {@code RagBenchmarkSuite.SUITE_VERSION}，也不复用 {@code IMAGE-SUITE-1.0}）。 */
  static final String SUITE_VERSION = "PDF-IMAGE-SUITE-1.0";

  /** 评测知识库 id（字符串口径，生产 payload 的 {@code knowledgeBaseId} 同型）。 */
  static final String EVAL_KB_ID = "9902";

  /** 图片切片类目（既有 image 集合的分类过滤键，本卡不自造类目）。 */
  static final String IMAGE_CATEGORY = ImageCrossModalFixture.IMAGE_CATEGORY;

  /** 视觉 embedding 的 MIME（生产 {@code PdfImageSliceIngestService.IMAGE_MIME_TYPE} 同值）。 */
  static final String IMAGE_MIME = ImageCrossModalFixture.IMAGE_MIME;

  /** 评测 PDF 文件名（摄取开关按扩展名判定，必须 .pdf）。 */
  static final String FILENAME = "pdf-ingest-image-baseline.pdf";

  /** 切片行主键起点（测试侧合成的 DB 主键，与生产"自增主键作 chunkId"同构）。 */
  static final long FIRST_CHUNK_ID = 9001L;

  /** 结构图键（同图目标的黄金图）。 */
  static final String STRUCTURE_KEY = "pdf-structure";

  /** 异图键（跨图型：柱状图）。 */
  static final String CROSS_TYPE_KEY = "pdf-bars";

  /** 纯白易负例键。 */
  static final String BLANK_KEY = "pdf-blank";

  private static final int WIDTH = 320;

  private static final int HEIGHT = 220;

  private static final float PAGE_WIDTH = 360F;

  private static final float PAGE_HEIGHT = 260F;

  private PdfIngestImageFixture() {}

  /**
   * 一张评测图：稳定键 + 该图"预期视觉内容"的描述文本。
   *
   * <p>{@code caption} 是本 fixture 对图内容的确定性自述（落盘 corpus 段留痕、单测里作 fake VLM 回函），<b>不是</b>真跑时喂给文本降维路的描述
   * 文本——真跑用生产链路里 VLM 真产出的描述（见 {@code PdfIngestImageBenchmarkIT}），否则文本对照路就变成了"拿标准答案查标准答案"。
   *
   * @param key 图像键（语料内唯一，决定页序与黄金 id）
   * @param caption 预期视觉内容（确定性文本）
   */
  record PdfImage(String key, String caption) {

    /**
     * 该图 PNG 字节（确定性渲染；每次调用重渲染，模拟"同一视觉内容再次编码"）。
     *
     * @return PNG 字节
     */
    byte[] png() {
      return PdfIngestImageFixture.toPng(PdfIngestImageFixture.raster(key));
    }

    /**
     * 该图光栅（确定性；PDF 内嵌与查询侧重渲染共用同一份像素源）。
     *
     * @return 光栅图
     */
    BufferedImage raster() {
      return PdfIngestImageFixture.raster(key);
    }

    /**
     * 该图作为切片行时的黄金引用 id（{@code IMAGE:{行主键}}，生产 {@code SourceReferenceFactory} 口径）。
     *
     * @return 黄金引用 id
     */
    String goldenId() {
      return PdfIngestImageFixture.goldenId(key);
    }
  }

  /** 语料（3 图 / 3 页，顺序即提取顺序）。 */
  private static final List<PdfImage> IMAGES =
      List.of(
          new PdfImage(STRUCTURE_KEY, "蓝色结构示意图：三层圆角节点区块与箭头连线，标注 SLA 台账与工单分层"),
          new PdfImage(CROSS_TYPE_KEY, "蓝色柱状图：五根竖向柱体与两条坐标轴，标注月度工单量"),
          new PdfImage(BLANK_KEY, "纯白底图：无任何图形、文字、网格或水印，整页接近纯白"));

  /**
   * 全量语料（顺序 = PDF 页序 = 提取顺序）。
   *
   * @return 只读语料
   */
  static List<PdfImage> images() {
    return IMAGES;
  }

  /**
   * 语料键表（顺序 = 提取顺序；调用方据此把切片 index 映射回图键）。
   *
   * @return 键表
   */
  static List<String> keys() {
    return IMAGES.stream().map(PdfImage::key).toList();
  }

  /**
   * 按图像键取语料图。
   *
   * @param key 图像键
   * @return 语料图
   * @throws IllegalArgumentException 键不在语料内（拼错即炸，避免静默漏图）
   */
  static PdfImage image(String key) {
    return IMAGES.stream()
        .filter(image -> image.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知 PDF 评测图像键: " + key));
  }

  /**
   * 图像键 → 合成切片行主键（顺序由语料顺序决定）。
   *
   * @param key 图像键
   * @return 行主键（字符串，与生产 {@code String.valueOf(entity.getId())} 同型）
   */
  static String chunkIdOf(String key) {
    return String.valueOf(FIRST_CHUNK_ID + keys().indexOf(image(key).key()));
  }

  /**
   * 图像键 → 黄金引用 id（{@code IMAGE:{行主键}}）。
   *
   * @param key 图像键
   * @return 黄金引用 id
   */
  static String goldenId(String key) {
    return IMAGE_CATEGORY + ":" + chunkIdOf(key);
  }

  /**
   * 确定性 PDF：三页各内嵌一张图（lossless 编码，解码回来像素不变），页序 = 语料顺序。
   *
   * @return PDF 字节
   */
  static byte[] pdfBytes() {
    try (PDDocument document = new PDDocument()) {
      for (PdfImage image : IMAGES) {
        PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, PAGE_HEIGHT));
        document.addPage(page);
        PDImageXObject xobject = LosslessFactory.createFromImage(document, image.raster());
        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
          content.drawImage(xobject, 20F, 20F, WIDTH, HEIGHT);
        }
      }
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      document.save(out);
      return out.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("评测 PDF 生成失败", exception);
    }
  }

  /**
   * 用例表（3 条）：同图 = 查询图自身的再次编码，异图跨图型；PG-03 的异图是纯白易负例（判据地板）。
   *
   * @param descriptionByKey 图键 → 描述文本（真跑传生产链路 VLM 真产出；单测传 {@link PdfImage#caption()}）
   * @return 用例表（顺序即判分顺序）
   */
  static List<ImageCase> cases(Map<String, String> descriptionByKey) {
    Function<String, String> textOf =
        key -> {
          String provided = descriptionByKey == null ? null : descriptionByKey.get(key);
          return provided == null || provided.isBlank() ? image(key).caption() : provided;
        };
    return List.of(
        caseOf("PG-01", STRUCTURE_KEY, CROSS_TYPE_KEY, textOf),
        caseOf("PG-02", CROSS_TYPE_KEY, STRUCTURE_KEY, textOf),
        caseOf("PG-03", STRUCTURE_KEY, BLANK_KEY, textOf));
  }

  /**
   * 描述文本表：语料自述（单测作 fake VLM 回函，避免任何真外呼）。
   *
   * @return 不可变描述表
   */
  static Map<String, String> fixtureDescriptions() {
    Map<String, String> descriptions = new LinkedHashMap<>();
    for (PdfImage image : IMAGES) {
      descriptions.put(image.key(), image.caption());
    }
    return Collections.unmodifiableMap(descriptions);
  }

  /** 单条用例：query 与 same 同图（再次编码），expectedChunkIds = 该图黄金引用 id。 */
  private static ImageCase caseOf(
      String id, String queryKey, String crossKey, Function<String, String> textOf) {
    return new ImageCase(
        id, queryKey, queryKey, crossKey, textOf.apply(queryKey), Set.of(goldenId(queryKey)));
  }

  /**
   * 语料指纹：三张图 PNG 的 SHA-256 拼接摘要（不依赖 PDF 字节里可能存在的容器元数据，跨机可复现）。
   *
   * @return 十六进制摘要
   */
  static String corpusFingerprint() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (PdfImage image : IMAGES) {
        digest.update(image.png());
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("计算语料指纹失败", exception);
    }
  }

  /**
   * 内存文件存储替身（图片对象落盘侧的生产接口，零磁盘零外呼）。
   *
   * <p>生产里图片对象进 MinIO/本地存储；评测只需要 storageKey 可复现且对象可寻回，故用内存表实现同一 {@link FileStorageService} 契约—— 不
   * mock（Mockito 会掩盖"键生成重合"这类真问题），也不用临时目录（收尾即弃）。
   *
   * @return 内存实现在用的存储
   */
  static InMemoryImageStorage inMemoryStorage() {
    return new InMemoryImageStorage();
  }

  /** 确定性渲染：同一键恒得同一像素（无随机源、无时间戳）。 */
  private static BufferedImage raster(String key) {
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, WIDTH, HEIGHT);
      graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
      switch (key) {
        case STRUCTURE_KEY -> structure(graphics);
        case CROSS_TYPE_KEY -> bars(graphics);
        case BLANK_KEY -> {
          // 纯白易负例：只有底色，无任何图形/文字（判据地板）
        }
        default -> throw new IllegalArgumentException("未知 PDF 评测图像键: " + key);
      }
    } finally {
      graphics.dispose();
    }
    return image;
  }

  /** 结构图样张：三层节点区块 + 层间箭头 + 边框（"结构"特征密集，与柱状图跨图型）。 */
  private static void structure(Graphics2D graphics) {
    Color accent = new Color(0x2C, 0x6D, 0xB4);
    graphics.setColor(accent);
    graphics.fillRoundRect(24, 22, 128, 46, 14, 14);
    graphics.fillRoundRect(24, 88, 128, 46, 14, 14);
    graphics.fillRoundRect(24, 154, 128, 46, 14, 14);
    graphics.setColor(Color.DARK_GRAY);
    graphics.setStroke(new BasicStroke(2F));
    graphics.drawLine(88, 68, 88, 88);
    graphics.drawLine(88, 134, 88, 154);
    graphics.drawRect(180, 22, 116, 178);
    graphics.setColor(Color.BLACK);
    graphics.drawString("接入层", 34, 50);
    graphics.drawString("处理层", 34, 116);
    graphics.drawString("台账层", 34, 182);
    graphics.drawString("SLA / 工单分层", 188, 48);
    graphics.drawString("DAG-01", 188, 72);
    graphics.drawString("节点 3 / 连线 2", 188, 96);
  }

  /** 异图样张：柱状图（同为图表语域，视觉跨图型）。 */
  private static void bars(Graphics2D graphics) {
    graphics.setColor(new Color(0x2C, 0x6D, 0xB4));
    int[] heights = {48, 96, 72, 120, 60};
    for (int index = 0; index < heights.length; index++) {
      graphics.fillRect(40 + index * 48, 170 - heights[index], 30, heights[index]);
    }
    graphics.setColor(Color.DARK_GRAY);
    graphics.setStroke(new BasicStroke(2F));
    graphics.drawLine(32, 170, 292, 170);
    graphics.drawLine(32, 40, 32, 170);
    graphics.setColor(Color.BLACK);
    graphics.drawString("月度工单量", 44, 40);
  }

  private static byte[] toPng(BufferedImage image) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (IOException exception) {
      throw new IllegalStateException("评测 fixture PNG 生成失败", exception);
    }
  }

  /** 内存图片对象存储（{@link FileStorageService} 的评测替身：键可复现、对象可寻回、零磁盘）。 */
  static final class InMemoryImageStorage implements FileStorageService {

    private final Map<String, byte[]> objects = new LinkedHashMap<>();

    @Override
    public String store(InputStream content, String filename, String contentType) {
      try {
        String storageKey = "pdf-image-object/" + filename;
        objects.put(storageKey, content.readAllBytes());
        return storageKey;
      } catch (IOException exception) {
        throw new IllegalStateException("评测图片对象读流失败", exception);
      }
    }

    @Override
    public InputStream open(String storageKey) {
      byte[] data = objects.get(storageKey);
      if (data == null) {
        throw new IllegalArgumentException("评测图片对象不存在: " + storageKey);
      }
      return new ByteArrayInputStream(data);
    }

    @Override
    public void delete(String storageKey) {
      objects.remove(storageKey);
    }

    @Override
    public boolean exists(String storageKey) {
      return objects.containsKey(storageKey);
    }

    /**
     * 已落盘对象数（摄取侧断言用：一图一对象，不多不少）。
     *
     * @return 对象数
     */
    int size() {
      return objects.size();
    }
  }
}
