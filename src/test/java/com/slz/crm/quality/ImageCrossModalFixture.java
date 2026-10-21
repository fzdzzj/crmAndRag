package com.slz.crm.quality;

import com.slz.crm.platform.contract.VectorRecord;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * 图文跨模态评测 fixture（image-cross-modal-eval 任务 1.2）：确定性图像语料 + 图片描述文本 + 向量记录装配。
 *
 * <p><b>为什么是确定性程序化图像而不是仓库内 PNG 资产</b>：评测要可复现（同一份 fixture 任意机器重跑得到同一张图 → 同一份基线可比），也要 零二进制资产入库；{@code
 * VisionEmbeddingRealIT} 已用同款 {@link BufferedImage} 程序化样张验证过真视觉 embedding 能区分图像内容， 本 fixture
 * 沿用该口径并扩成「6 图语料 + 5 条查询」的评测面。
 *
 * <p><b>语料设计（跨模态判别的关键）</b>：每张图带一句<b>描述文本</b>（生产里由 VLM 生成，本卡用确定性文本替身，见 proposal 对照口径）， 并让「查询图 / 同图 /
 * 异图」三者形成两类对照：
 *
 * <ul>
 *   <li><b>同图</b>：同一视觉内容的再次编码 —— 视觉余弦应高（&gt; 阈值）；
 *   <li><b>异图（本套件的核心探针）</b>：与查询图<b>描述文本骨架相同、视觉内容跨图型</b>（流程图 vs 柱状图 vs 表格 vs 卡片看板）。
 *       纯文本降维把两者编码成近乎同一向量（误召），真视觉向量则应把它们分开——这正是本卡要量化的增量价值。
 *   <li>另有一条<b>易负例</b>（纯白底图）作地板对照：两条路都该拒答，证明探针不是靠"随便一张图都判异"混过。
 * </ul>
 *
 * <p>payload 口径逐字段对齐生产视觉向量写入（{@code ImageVectorFlushSupport} → {@code
 * DocumentIngestionSupport#createVectorRecord}）：{@code knowledgeBaseId}/{@code category}/{@code
 * filename}/ {@code fileType}/{@code pageNo}/{@code rowIndex}/{@code chunkId} + 图片切片的 {@code
 * sourceType=IMAGE}/ {@code imageAssetId}/{@code imageStorageKey}。检索面只依赖 {@code knowledgeBaseId} +
 * {@code category} 两个等值过滤键 （与 {@code KnowledgeRetrievalServiceImpl#recallIn} 一致）。
 */
final class ImageCrossModalFixture {

  /** 评测知识库 id（字符串口径，与生产 payload 的 {@code knowledgeBaseId} 同型）。 */
  static final String EVAL_KB_ID = "9901";

  /** 图片切片类目（生产 image 集合的分类过滤键）；文本对照集合不用该类目。 */
  static final String IMAGE_CATEGORY = "IMAGE";

  /** 视觉 embedding 的 MIME。 */
  static final String IMAGE_MIME = "image/png";

  /** 切片 id 前缀（黄金 id 由此派生，见 {@link #chunkId(String)}）。 */
  private static final String CHUNK_ID_PREFIX = "imgchunk-";

  /** 文档 id 前缀。 */
  private static final String DOCUMENT_ID_PREFIX = "imgdoc-";

  private static final int WIDTH = 320;

  private static final int HEIGHT = 220;

  private ImageCrossModalFixture() {}

  /**
   * 一张评测图：稳定键（黄金 id 与向量记录都由它派生）+ 一句描述文本（VLM 描述文本的确定性替身）。
   *
   * @param key 图像键（语料内唯一）
   * @param caption 该图的描述文本（文本降维路的语料与查询）
   */
  record FixtureImage(String key, String caption) {

    /** 该图 PNG 字节（程序化渲染，确定性；每次调用重渲染以模拟"再次编码"）。 */
    byte[] png() {
      return ImageCrossModalFixture.render(key);
    }

    /** 该图在 image 集合里的切片 id（黄金 id 口径）。 */
    String chunkId() {
      return ImageCrossModalFixture.chunkId(key);
    }

    /** 该图在 image 集合里的文档 id。 */
    String documentId() {
      return DOCUMENT_ID_PREFIX + key;
    }
  }

  /** 语料（6 图）：4 张内容图 + 1 张卡片看板 + 1 张纯白易负例。 */
  private static final List<FixtureImage> IMAGES =
      List.of(
          new FixtureImage("flow-blue", "蓝色主题的流程示意图：若干矩形区块与箭头连线，标注 SLA 台账与工单分层"),
          new FixtureImage("bars-blue", "蓝色主题的流程示意图：若干矩形区块与箭头连线，标注 SLA 台账与工单分层统计"),
          new FixtureImage("flow-orange", "橙色主题的流程示意图：若干矩形区块与箭头连线，标注 SLA 台账与工单分层"),
          new FixtureImage("table-scan", "灰度扫描的表格示意图：网格线与行列表头，收口工单台账"),
          new FixtureImage("cards-green", "绿色主题的看板示意图：圆角卡片与图标，展示工单分层"),
          new FixtureImage("blank-white", "纯白底图：无任何图形与文字"));

  /** 全量语料（只读）。 */
  static List<FixtureImage> images() {
    return IMAGES;
  }

  /**
   * 按图像键取语料图。
   *
   * @param key 图像键
   * @return 语料图
   * @throws IllegalArgumentException 键不在语料内（拼错即炸，避免静默漏图）
   */
  static FixtureImage image(String key) {
    return IMAGES.stream()
        .filter(image -> image.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("未知评测图像键: " + key));
  }

  /** 图像键 → image 集合切片 id（image collection 里的 point chunkId）。 */
  static String chunkId(String key) {
    return CHUNK_ID_PREFIX + key;
  }

  /**
   * 图像键 → 生产检索面回传的引用 id（{@code SourceReference#chunkId}）。
   *
   * <p>图片切片的引用 id 口径是 {@code IMAGE:{资产 id}}（{@code
   * SourceReferenceFactory.IMAGE_CHUNK_PREFIX}，与业务实体来源 {@code X:{id}} 同形）——评测的黄金 id
   * 必须用这个口径，否则"检索确实命中了、但 id 对不上"会假判零命中。
   *
   * @param key 图像键
   * @return {@code IMAGE:} 前缀的引用 id
   */
  static String sourceChunkId(String key) {
    return IMAGE_CATEGORY + ":" + chunkId(key);
  }

  /**
   * image 集合向量记录（payload 口径对齐生产 {@code ImageVectorFlushSupport} 写入的那条记录）。
   *
   * @param image 语料图
   * @param vector 真视觉向量
   * @return 可 upsert 的向量记录
   */
  static VectorRecord imageRecord(FixtureImage image, float[] vector) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", EVAL_KB_ID);
    metadata.put("category", IMAGE_CATEGORY);
    metadata.put("filename", "image-baseline-" + image.key() + ".png");
    metadata.put("fileType", "png");
    metadata.put("pageNo", 0L);
    metadata.put("rowIndex", 0L);
    metadata.put("chunkId", image.chunkId());
    // 图片切片专属 payload（生产 ImageSliceMetadata 的同名字段，检索期用于回填 SourceReference）
    metadata.put("sourceType", IMAGE_CATEGORY);
    metadata.put("imageAssetId", image.chunkId());
    metadata.put("imageStorageKey", "image/" + image.key() + ".png");
    // point id 由图像键派生（非随机）：同一 fixture 重复 upsert 覆盖而非叠加 → 幂等入库
    return new VectorRecord(
        "imgvec-" + image.key(),
        image.documentId(),
        image.chunkId(),
        0,
        image.caption(),
        vector,
        metadata);
  }

  /**
   * 文本对照集合向量记录：同一批图经"描述文本 → 文本向量"降维后的切片（方案 B 文本降维路）。
   *
   * <p>payload 的图片资产键与 image 集合同名同值：生产里图片描述切片本身就是一条<b>文本切片</b>（带 {@code extraMetadata} 的资产键），
   * 因此文本路命中的引用 id 同样是 {@code IMAGE:{资产 id}}——两条路的黄金 id 才可比（否则文本路"命中了但 id 口径不同"会假判零命中）。
   *
   * @param image 语料图
   * @param vector 真文本向量
   * @return 可 upsert 的文本向量记录
   */
  static VectorRecord textRecord(FixtureImage image, float[] vector) {
    Map<String, Object> metadata = new LinkedHashMap<>();
    metadata.put("knowledgeBaseId", EVAL_KB_ID);
    metadata.put("category", "");
    metadata.put("filename", "image-baseline-" + image.key() + ".png");
    metadata.put("fileType", "png");
    metadata.put("pageNo", 0L);
    metadata.put("rowIndex", 0L);
    metadata.put("chunkId", image.chunkId());
    metadata.put("sourceType", IMAGE_CATEGORY);
    metadata.put("imageAssetId", image.chunkId());
    metadata.put("imageStorageKey", "image/" + image.key() + ".png");
    // 文本对照集合的 point id 同样由图像键派生（与 image 集合分属两个 store，前缀区分以便日志辨识）
    return new VectorRecord(
        "txtvec-" + image.key(),
        image.documentId(),
        image.chunkId(),
        0,
        image.caption(),
        vector,
        metadata);
  }

  /** 确定性 PNG 渲染：同一键恒得同一图（无随机源、无时间戳）。 */
  private static byte[] render(String key) {
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, WIDTH, HEIGHT);
      graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
      switch (key) {
        case "flow-blue" -> flow(graphics, new Color(0x2C, 0x6D, 0xB4), "SLA 台账 / 工单分层");
        case "flow-orange" -> flow(graphics, new Color(0xD9, 0x6B, 0x1E), "SLA 台账 / 工单分层");
        case "bars-blue" -> bars(graphics, new Color(0x2C, 0x6D, 0xB4));
        case "table-scan" -> table(graphics);
        case "cards-green" -> cards(graphics, new Color(0x2E, 0x8B, 0x57));
        case "blank-white" -> {
          // 纯白易负例：只有底色，无任何图形/文字
        }
        default -> throw new IllegalArgumentException("未知评测图像键: " + key);
      }
    } finally {
      graphics.dispose();
    }
    return toPng(image);
  }

  /** 流程图样张：两个圆角区块 + 箭头连线 + 边框 + 标签（换色即得另一张"文字同骨架、视觉不同色"的图）。 */
  private static void flow(Graphics2D graphics, Color accent, String label) {
    graphics.setColor(accent);
    graphics.fillRoundRect(24, 26, 130, 62, 16, 16);
    graphics.fillRoundRect(178, 96, 118, 56, 16, 16);
    graphics.setColor(Color.DARK_GRAY);
    graphics.setStroke(new BasicStroke(2F));
    graphics.drawRect(24, 130, 272, 62);
    graphics.drawLine(154, 57, 178, 118);
    graphics.setColor(Color.BLACK);
    graphics.drawString(label, 40, 168);
    graphics.drawString("DAG-01", 210, 62);
  }

  /** 柱状图样张：与流程图共享"蓝色流程示意"描述骨架，但视觉完全不同的图型。 */
  private static void bars(Graphics2D graphics, Color accent) {
    graphics.setColor(accent);
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

  /** 表格样张：网格线 + 表头（面向"扫描表格图"这一常见知识库图类）。 */
  private static void table(Graphics2D graphics) {
    graphics.setColor(new Color(0x44, 0x44, 0x44));
    graphics.setStroke(new BasicStroke(1.4F));
    for (int row = 0; row < 7; row++) {
      graphics.drawLine(30, 40 + row * 24, 292, 40 + row * 24);
    }
    for (int column = 0; column < 5; column++) {
      graphics.drawLine(30 + column * 65, 40, 30 + column * 65, 184);
    }
    graphics.setColor(Color.BLACK);
    graphics.drawString("工单台账", 38, 56);
  }

  /** 卡片看板样张：三张圆角卡片 + 圆形图标 + 标题。 */
  private static void cards(Graphics2D graphics, Color accent) {
    for (int index = 0; index < 3; index++) {
      graphics.setColor(new Color(0xF2, 0xF6, 0xF3));
      graphics.fillRoundRect(20 + index * 96, 44, 84, 128, 14, 14);
      graphics.setColor(accent);
      graphics.fillOval(38 + index * 96, 58, 24, 24);
      graphics.fillRoundRect(30 + index * 96, 100, 64, 40, 8, 8);
    }
    graphics.setColor(Color.BLACK);
    graphics.drawString("工单分层看板", 96, 200);
  }

  private static byte[] toPng(BufferedImage image) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (Exception exception) {
      throw new IllegalStateException("评测 fixture PNG 生成失败", exception);
    }
  }
}
