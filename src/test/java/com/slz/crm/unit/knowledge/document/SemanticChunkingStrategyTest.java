package com.slz.crm.unit.knowledge.document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.knowledge.document.ChunkingStrategy.StrategyChunk;
import com.slz.crm.knowledge.document.SemanticChunkingStrategy;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 语义切分策略单测（提案4 任务 1.2/1.4，方案02）。
 *
 * <p>覆盖：切点落在语义边界、超长段按上限二次切分、转折词优先切点、 同一小节内容不分裂、父块（逻辑段）按 ≥2 切片聚组。
 */
class SemanticChunkingStrategyTest {

  /** 小上限便于构造超长场景。 */
  private final SemanticChunkingStrategy strategy = new SemanticChunkingStrategy(120);

  @Test
  void chunksRespectCapAndCutAtSemanticBoundaries() {
    String doc =
        "# 标题一\n"
            + "第一段内容句子完整收尾。".repeat(12)
            + "\n\n"
            + "第二段内容同样句子完整收尾。".repeat(12)
            + "\n\n"
            + "## 标题二\n"
            + "另一节内容句子完整收尾。".repeat(12);
    List<StrategyChunk> chunks = strategy.split(doc);

    assertFalse(chunks.isEmpty(), "文档必须产出切片");
    for (StrategyChunk chunk : chunks) {
      assertTrue(chunk.text().length() <= 120, "切片不得超过上限: " + chunk.text().length());
    }
    // 切点边界断言：每个切片要么以标题开头，要么前一切片以标题收口（标题独占封口）或以句界收尾
    for (int i = 1; i < chunks.size(); i++) {
      String prev = chunks.get(i - 1).text();
      String current = chunks.get(i).text();
      boolean boundary =
          current.startsWith("#")
              || prev.startsWith("#")
              || prev.endsWith("。")
              || prev.endsWith("！")
              || prev.endsWith("？")
              || prev.endsWith("；")
              || prev.endsWith("\n");
      assertTrue(boundary, "切点必须落在语义边界: prev 尾=[" + tail(prev) + "] cur 头=[" + head(current) + "]");
    }
  }

  @Test
  void oversizedSingleSentenceHardCutPrefersTransitionWord() {
    // 单句 300 字、无任何句末标点："但是" 位于第 200 字——恰在第二个硬切窗口 [120,240) 的后半段，
    // 硬切必须提前到转折词前，让"但是"归下一段开头
    String sentence = "甲".repeat(200) + "但是" + "乙".repeat(98);
    List<String> pieces = strategy.split(sentence).stream().map(StrategyChunk::text).toList();

    assertFalse(pieces.isEmpty());
    for (String piece : pieces) {
      assertTrue(piece.length() <= 120, "硬切片段不得超过上限: " + piece.length());
    }
    // 拼回必须等于原文（切分不丢字）
    assertEquals(sentence, String.join("", pieces), "硬切不得丢失或改写内容");
    // 转折词优先：存在一个切片以"但是"开头，且其前一片段在"但是"前收口
    int transitionLead = -1;
    for (int i = 1; i < pieces.size(); i++) {
      if (pieces.get(i).startsWith("但是")) {
        transitionLead = i;
        break;
      }
    }
    assertTrue(
        transitionLead > 0,
        "硬切应优先落在转折词前: " + pieces.stream().map(p -> "[" + p.length() + "]").toList());
    assertFalse(pieces.get(transitionLead - 1).contains("但是"), "转折词必须整体归下一段开头");
  }

  @Test
  void sectionContentUnderSameHeadingStaysInOneChunk() {
    // 任务 1.4：小节内容装得下时不得分裂到两个切片（对比 fixed 滑窗会切在段中间）
    String doc =
        "# 安装\n"
            + "安装步骤说明，安装前需要检查环境。".repeat(6)
            + "\n\n"
            + "# 卸载\n"
            + "卸载步骤说明，卸载后需要清理残留。".repeat(6);
    List<StrategyChunk> chunks = strategy.split(doc);

    assertEquals(2, chunks.size(), "两小节各自完整装进一个切片");
    assertTrue(chunks.get(0).text().startsWith("# 安装"));
    assertTrue(chunks.get(0).text().contains("安装前需要检查环境"));
    assertFalse(chunks.get(0).text().contains("卸载"), "小节内容不得与相邻小节混片");
    assertTrue(chunks.get(1).text().startsWith("# 卸载"));
    assertTrue(chunks.get(1).text().contains("卸载后需要清理残留"));
    assertFalse(chunks.get(1).text().contains("安装"), "小节内容不得与相邻小节混片");
  }

  @Test
  void multiChunkSectionSharesParentTextWhileSingleChunkSectionHasNone() {
    // 任务 3.2 前置：同一逻辑段切成 ≥2 片时共享父块全文；单片逻辑段自身即父块（parentText=null）
    String longSection = "# 长小节\n" + "长小节内容句子完整收尾。".repeat(30);
    String shortSection = "\n# 短小节\n短小节内容收尾。";
    List<StrategyChunk> chunks = strategy.split(longSection + shortSection);

    List<StrategyChunk> longSectionChunks =
        chunks.stream().filter(c -> c.text().contains("长小节内容") || c.parentText() != null).toList();
    assertTrue(longSectionChunks.size() >= 2, "超上限逻辑段必须切成多个切片: " + chunks.size());
    String parentText = longSectionChunks.get(0).parentText();
    for (StrategyChunk chunk : longSectionChunks) {
      assertEquals(parentText, chunk.parentText(), "同逻辑段切片必须共享同一父块全文");
    }
    assertTrue(parentText.contains("# 长小节"), "父块全文须含小节标题");
    assertTrue(parentText.contains("长小节内容句子完整收尾"), "父块全文须含小节正文");
    assertTrue(parentText.length() >= 2 * 120, "父块全文须覆盖被切碎的完整逻辑段");

    List<StrategyChunk> shortSectionChunks =
        chunks.stream().filter(c -> c.text().startsWith("# 短小节")).toList();
    assertEquals(1, shortSectionChunks.size(), "装得下的小节只产出一个切片");
    assertNull(shortSectionChunks.get(0).parentText(), "单片逻辑段无独立父块");
  }

  @Test
  void blankLineParagraphsPackIntoOneChunkUntilCap() {
    String doc = "段甲第一句。段甲第二句。\n\n段乙第一句。段乙第二句。\n\n段丙第一句。";
    List<StrategyChunk> chunks = strategy.split(doc);

    assertEquals(1, chunks.size(), "上限内的多段落应聚合为单个切片");
    assertTrue(chunks.get(0).text().contains("段甲"));
    assertTrue(chunks.get(0).text().contains("段丙"));
    assertNull(chunks.get(0).parentText(), "单切片整页无独立父块");
  }

  private static String tail(String text) {
    return text.substring(Math.max(0, text.length() - 12));
  }

  private static String head(String text) {
    return text.substring(0, Math.min(12, text.length()));
  }
}
