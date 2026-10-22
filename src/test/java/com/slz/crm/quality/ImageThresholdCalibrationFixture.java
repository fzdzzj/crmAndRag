package com.slz.crm.quality;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * 异图阈值定标语料（multimodal-gray-and-eval-threshold 任务 2.1）：<b>多图同类/跨类</b>的确定性程序化图像。
 *
 * <p><b>为什么不复用 {@link ImageCrossModalFixture}</b>：那份语料是 {@code image-baseline-v1.json} 的锚点（6 图 / 5
 * 查询）， 任何增删都会让既有基线失去可比性。本卡只<b>新增</b>定标语料，不改、不并、不重排既有 fixture。
 *
 * <p><b>三层关系（定标的核心）</b>：
 *
 * <ul>
 *   <li><b>同图</b>（{@code same-image}）：同一张图编码两次（同字节再编码）——这是「同图下限」的观测口径；
 *   <li><b>同类</b>（{@code same-class}）：同一图型的两个不同实例（如两张不同配色的流程图）——「看起来像但不是同一张」， 真实语料里这类负例最接近同图；
 *   <li><b>跨类</b>（{@code cross-class}）：不同图型（流程图 vs 柱状图 vs 表格）——最容易判异的一档。
 * </ul>
 *
 * <p>真实语料的同类档由 {@link RealPdfAssets}（真实公有领域 PDF）经生产提取器提供，本类只负责程序化档的稳定底座，
 * 让「跨类必然分得开、同类可能分不开」这件事有一个可复现的最小对照。
 */
final class ImageThresholdCalibrationFixture {

  /** 同一图型的实例个数（3 个实例 → 同类档每类 3 对）。 */
  static final int VARIANTS_PER_CLASS = 3;

  /** 图像宽高（与既有 fixture 同量级，真视觉 embedding 端点的成本不随画布线性增长）。 */
  private static final int WIDTH = 320;

  private static final int HEIGHT = 220;

  private ImageThresholdCalibrationFixture() {}

  /**
   * 一张定标图。
   *
   * @param key 稳定键（同键恒得同字节）
   * @param klass 图型族（同类判定的唯一依据）
   * @param variant 族内变体序号（0 起）
   */
  record Sample(String key, String klass, int variant) {

    /** 该图 PNG 字节（每次调用重渲染，确定性；用于「同图再编码」观测）。 */
    byte[] png() {
      return render(this);
    }
  }

  /**
   * 全量程序化语料：3 个图型族 × 3 个实例 = 9 图。
   *
   * @return 语料（不可变，顺序即跑序）
   */
  static List<Sample> samples() {
    List<Sample> samples = new ArrayList<>();
    for (String klass : List.of("flow", "bars", "table")) {
      for (int variant = 0; variant < VARIANTS_PER_CLASS; variant++) {
        samples.add(new Sample(klass + "-" + variant, klass, variant));
      }
    }
    return List.copyOf(samples);
  }

  /** 确定性渲染：同一 key 恒得同一字节（无随机源、无时间戳）。 */
  private static byte[] render(Sample sample) {
    BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setRenderingHint(
          RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, WIDTH, HEIGHT);
      graphics.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
      switch (sample.klass()) {
        case "flow" -> flow(graphics, sample.variant());
        case "bars" -> bars(graphics, sample.variant());
        case "table" -> table(graphics, sample.variant());
        default -> throw new IllegalArgumentException("未知图型族: " + sample.klass());
      }
    } finally {
      graphics.dispose();
    }
    return toPng(image);
  }

  /** 流程图族：配色 / 区块尺寸 / 标签随变体变化——同族不同实例，视觉高度相似但不相同。 */
  private static void flow(Graphics2D graphics, int variant) {
    Color accent =
        List.of(
                new Color(0x2C, 0x6D, 0xB4),
                new Color(0xD9, 0x6B, 0x1E),
                new Color(0x2E, 0x8B, 0x57))
            .get(variant);
    int offset = 6 * variant;
    graphics.setColor(accent);
    graphics.fillRoundRect(24 + offset, 26, 130 - offset, 62, 16, 16);
    graphics.fillRoundRect(178, 96 + offset, 118, 56, 16, 16);
    graphics.setColor(Color.DARK_GRAY);
    graphics.setStroke(new BasicStroke(2F));
    graphics.drawRect(24, 130 + offset, 272 - offset, 62 - offset);
    graphics.drawLine(154 + offset, 57, 178, 118);
    graphics.setColor(Color.BLACK);
    graphics.drawString("SLA 台账 / 工单分层 · 变体 " + (variant + 1), 40, 168);
    graphics.drawString("DAG-0" + (variant + 1), 210, 62);
  }

  /** 柱状图族：柱高序列与配色随变体变化。 */
  private static void bars(Graphics2D graphics, int variant) {
    Color accent =
        List.of(
                new Color(0x2C, 0x6D, 0xB4),
                new Color(0x8B, 0x57, 0xD9),
                new Color(0xEB, 0xA0, 0x2E))
            .get(variant);
    graphics.setColor(accent);
    int[][] heights = {{48, 96, 72, 120, 60}, {64, 80, 108, 52, 96}, {88, 56, 76, 132, 44}};
    for (int index = 0; index < heights[variant].length; index++) {
      graphics.fillRect(
          40 + index * 48, 170 - heights[variant][index], 30, heights[variant][index]);
    }
    graphics.setColor(Color.DARK_GRAY);
    graphics.setStroke(new BasicStroke(2F));
    graphics.drawLine(32, 170, 292, 170);
    graphics.drawLine(32, 40, 32, 170);
    graphics.setColor(Color.BLACK);
    graphics.drawString("月度工单量 · 变体 " + (variant + 1), 44, 40);
  }

  /** 表格族：行列数与网格间距随变体变化。 */
  private static void table(Graphics2D graphics, int variant) {
    int rows = 7 + variant * 2;
    int columns = 5 - variant;
    graphics.setColor(new Color(0x44, 0x44, 0x44));
    graphics.setStroke(new BasicStroke(1.4F));
    for (int row = 0; row < rows; row++) {
      graphics.drawLine(30, 40 + row * (144 / rows), 292, 40 + row * (144 / rows));
    }
    for (int column = 0; column < columns; column++) {
      graphics.drawLine(30 + column * (262 / columns), 40, 30 + column * (262 / columns), 184);
    }
    graphics.setColor(Color.BLACK);
    graphics.drawString("工单台账 · 变体 " + (variant + 1), 38, 56);
  }

  private static byte[] toPng(BufferedImage image) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (Exception exception) {
      throw new IllegalStateException("定标语料 PNG 生成失败", exception);
    }
  }
}
