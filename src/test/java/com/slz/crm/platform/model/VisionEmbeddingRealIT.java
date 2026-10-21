package com.slz.crm.platform.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.slz.crm.platform.contract.ModelCallResult;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * multimodal-shared-space 任务 5.2：视觉 embedding **真跑** IT（A 档真跑判据，RAG_VISION_EMBED_REAL=1 显式 opt-in）。
 *
 * <p>验的是"真视觉"是否真的成立，而不只是接口能通：同一内容的图片向量互相高度相似，不同内容（内容图 vs 纯白图）的向量相似度显著更低—— 若视觉 embedding
 * 退化成"文本降维"或返回常量向量，第二项断言必红。
 *
 * <p>门禁：未设 {@code RAG_VISION_EMBED_REAL=1} 或未配 {@code DASHSCOPE_API_KEY} 时整类跳过（默认零外呼，CI 与本地免密钥）。
 */
@DisplayName("视觉 embedding 真跑（RAG_VISION_EMBED_REAL 显式 opt-in）")
class VisionEmbeddingRealIT {

  @Test
  @DisplayName("真视觉：同图相似度远高于异图（视觉向量真实区分图像内容）")
  void realVisualEmbedding_distinguishesImageContent() {
    assumeTrue(
        "1".equals(System.getenv("RAG_VISION_EMBED_REAL")),
        "未显式 opt-in（RAG_VISION_EMBED_REAL=1）：跳过视觉 embedding 真跑");
    String apiKey = resolveApiKey();
    assumeTrue(!apiKey.isBlank(), "DASHSCOPE_API_KEY / SLZ_AI_API_KEY 未配置：跳过视觉 embedding 真跑");

    ModelProviderImpl provider = provider(apiKey);
    byte[] diagram = png(diagram(0));
    byte[] sameDiagramReencoded = png(diagram(0));
    byte[] blank = png(solid(Color.WHITE));

    ModelCallResult<float[]> diagramVector = provider.embedImage(diagram, "image/png");
    ModelCallResult<float[]> sameVector = provider.embedImage(sameDiagramReencoded, "image/png");
    ModelCallResult<float[]> blankVector = provider.embedImage(blank, "image/png");

    assertThat(diagramVector.vector()).isNotEmpty();
    assertThat(sameVector.vector()).hasSameSizeAs(diagramVector.vector());
    assertThat(blankVector.vector()).hasSameSizeAs(diagramVector.vector());
    assertThat(diagramVector.model()).isEqualTo("multimodal-embedding-v1");

    double sameCosine = cosine(diagramVector.vector(), sameVector.vector());
    double differentCosine = cosine(diagramVector.vector(), blankVector.vector());
    assertThat(sameCosine).isGreaterThan(0.9D);
    assertThat(sameCosine).isGreaterThan(differentCosine);
    assertThat(differentCosine).isLessThan(0.9D);
  }

  private static ModelProviderImpl provider(String apiKey) {
    ModelProviderProperties properties = new ModelProviderProperties();
    properties.setApiKey(apiKey);
    properties.setBaseUrl("https://dashscope.aliyuncs.com/compatible-mode/v1");
    properties.setTimeoutSeconds(60);
    properties.setConnectTimeoutSeconds(10);
    return new ModelProviderImpl(
        emptyProvider(),
        emptyProvider(),
        properties,
        new MockEnvironment(),
        ModelCallGuard.standalone());
  }

  /** 真跑用例不碰 chat/embed：两个模型提供者恒缺省（返回 null），构造期不触发外呼。 */
  private static <T> org.springframework.beans.factory.ObjectProvider<T> emptyProvider() {
    return new org.springframework.beans.factory.ObjectProvider<>() {
      @Override
      public T getObject() {
        return null;
      }
    };
  }

  private static String resolveApiKey() {
    List<String> candidates = new ArrayList<>();
    candidates.add(System.getenv("DASHSCOPE_API_KEY"));
    candidates.add(System.getenv("SLZ_AI_API_KEY"));
    return candidates.stream()
        .filter(value -> value != null && !value.isBlank())
        .findFirst()
        .orElse("")
        .strip();
  }

  /** 余弦相似度（视觉向量比较口径；零范数按 0 处理，避免 NaN 假红/假绿）。 */
  private static double cosine(float[] left, float[] right) {
    double dot = 0D;
    double leftNorm = 0D;
    double rightNorm = 0D;
    int size = Math.min(left.length, right.length);
    for (int index = 0; index < size; index++) {
      dot += (double) left[index] * right[index];
      leftNorm += (double) left[index] * left[index];
      rightNorm += (double) right[index] * right[index];
    }
    return leftNorm <= 0D || rightNorm <= 0D
        ? 0D
        : dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
  }

  private static byte[] png(BufferedImage image) {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (Exception exception) {
      throw new IllegalStateException("夹具 PNG 生成失败", exception);
    }
  }

  /** 内容图：有形状/线条的架构图样张（与纯白图形成明显视觉差异）。 */
  private static BufferedImage diagram(int seed) {
    BufferedImage image = new BufferedImage(320, 220, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, 320, 220);
      graphics.setColor(new Color(0x2C, 0x6D, 0xB4));
      graphics.fillRoundRect(20 + seed * 5, 24, 140, 70, 16, 16);
      graphics.setColor(Color.DARK_GRAY);
      graphics.drawRect(40, 120, 240, 70);
      graphics.setColor(Color.BLACK);
      graphics.drawLine(30, 160, 300, 160 + seed);
      graphics.drawString("SLA 台账 / 工单分层", 60, 150);
    } finally {
      graphics.dispose();
    }
    return image;
  }

  private static BufferedImage solid(Color color) {
    BufferedImage image = new BufferedImage(320, 220, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(color);
      graphics.fillRect(0, 0, 320, 220);
    } finally {
      graphics.dispose();
    }
    return image;
  }
}
