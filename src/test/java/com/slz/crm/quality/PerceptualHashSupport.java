package com.slz.crm.quality;

import java.awt.image.BufferedImage;

/**
 * 感知哈希（dHash-64）纯函数（retrieval-and-contract-hardening G1 任务 1.2.1/1.2.2）：{@link BufferedImage} →
 * {@code long}。
 *
 * <p><b>为什么零依赖手写</b>：G1 判据 C4 要求外呼次数 = 0，且本卡零新依赖（{@code pom.xml} 零 diff）；JDK 自带 ImageIO 足以 承担「9×8
 * 灰度 + 相邻差分」这一纯算术，不引第三方图片库。
 *
 * <p><b>为什么输入是位图而不是字节</b>：入参只收已解码的 {@link BufferedImage}，因此本类不出现任何 I/O、网络或解码入口—— 位图由 {@link
 * NearDuplicateCorpus}（生产提取器 + 内存变体）统一产出，禁止二次栅格化（proposal §1.3 M2）。
 *
 * <p><b>算法</b>：把图按面积平均缩到 9×8 灰度格（BT.601 定点整数灰度 {@code (299R+587G+114B)/1000}），逐行比较相邻两格， 左 &gt; 右 记
 * 1，共 8 行 × 8 次比较 = 64 bit。裁剪/缩放后这些差分符号基本不变，故对"同源变体"鲁棒。
 *
 * <p><b>确定性</b>：无随机、无时钟、无并发；同一图（同尺寸同像素）两次调用必得同值（{@link PerceptualHashSupportTest} 守）。
 */
final class PerceptualHashSupport {

  /** dHash 目标列数（相邻差分次数 = {@code COLUMNS - 1}）。 */
  static final int COLUMNS = 9;

  /** dHash 目标行数。 */
  static final int ROWS = 8;

  /** 哈希位数 = 8 行 × 8 次差分。 */
  static final int BITS = 64;

  private PerceptualHashSupport() {}

  /**
   * 计算 64 bit 差分哈希。
   *
   * @param image 已解码位图（非空、宽高 &gt; 0）
   * @return 64 bit 哈希（bit 0 = 第 1 行第 1 对相邻格）
   */
  static long dHash(BufferedImage image) {
    if (image == null) {
      throw new IllegalArgumentException("位图为空");
    }
    int[] cells = cellLuma(image);
    long hash = 0L;
    for (int row = 0; row < ROWS; row++) {
      for (int column = 0; column < COLUMNS - 1; column++) {
        int left = cells[row * COLUMNS + column];
        int right = cells[row * COLUMNS + column + 1];
        if (left > right) {
          hash |= 1L << (row * (COLUMNS - 1) + column);
        }
      }
    }
    return hash;
  }

  /**
   * 汉明距离（不同 bit 数，0..64）。
   *
   * @param left 哈希
   * @param right 哈希
   * @return 不同 bit 数
   */
  static int hamming(long left, long right) {
    return Long.bitCount(left ^ right);
  }

  /**
   * 距离占比（{@code hamming / 64}），供跨尺寸对照。
   *
   * @param left 哈希
   * @param right 哈希
   * @return 0.0..1.0
   */
  static double distanceRatio(long left, long right) {
    return (double) hamming(left, right) / BITS;
  }

  /**
   * 面积平均缩到 {@code 9 × 8} 灰度格：逐像素一次归属到唯一格（列 = {@code x*9/width}），故总计 {@code width*height} 次累加。
   *
   * @param image 位图
   * @return 长度 72 的灰度格数组（行优先）
   */
  private static int[] cellLuma(BufferedImage image) {
    int width = image.getWidth();
    int height = image.getHeight();
    if (width <= 0 || height <= 0) {
      throw new IllegalArgumentException("位图尺寸非法: " + width + "x" + height);
    }
    // 一次批量取像素（逐像素 getRGB 在大图上会慢两个数量级）
    int[] pixels = image.getRGB(0, 0, width, height, null, 0, width);
    long[] sums = new long[ROWS * COLUMNS];
    long[] counts = new long[ROWS * COLUMNS];
    for (int y = 0; y < height; y++) {
      int row = (int) ((long) y * ROWS / height);
      int lineOffset = y * width;
      for (int x = 0; x < width; x++) {
        int cell = row * COLUMNS + (int) ((long) x * COLUMNS / width);
        sums[cell] += luma(pixels[lineOffset + x]);
        counts[cell]++;
      }
    }
    int[] cells = new int[ROWS * COLUMNS];
    for (int index = 0; index < cells.length; index++) {
      cells[index] = counts[index] == 0L ? 0 : (int) (sums[index] / counts[index]);
    }
    return cells;
  }

  /**
   * BT.601 亮度（定点整数，不用浮点以避免平台差异）。
   *
   * @param rgb 像素（ARGB）
   * @return 0..255 亮度
   */
  static int luma(int rgb) {
    int red = (rgb >> 16) & 0xFF;
    int green = (rgb >> 8) & 0xFF;
    int blue = rgb & 0xFF;
    return (299 * red + 587 * green + 114 * blue) / 1000;
  }
}
