package com.slz.crm.quality;

import java.util.List;
import java.util.Set;

/**
 * RAG 质量基准单条用例（task17）。
 *
 * <p>作为"优化不产生质量回退"的固定对照（governance spec：以固定基准集验证）。 {@code expectedChunkIds} 是应被召回的黄金片段，作
 * recall/precision/MRR 的判定依据； 边界用例（闲聊/零命中）expected 为空——此时"不召回、不伪造引用"才是正确行为（D16 诚实生成）。
 *
 * @param id 用例编号
 * @param category 类别：文本/表格/图片/词法精确/边界
 * @param question 检索问题
 * @param expectedChunkIds 应召回的黄金片段 id（可空=边界用例）；基线运行前由数据准备 runner 把占位 id 替换为真实入库 chunkId（见
 *     add-rag-quality-baseline）
 * @param expectedAnswerPoints 答案应覆盖的要点（答案一致性评估用）
 * @param useKnowledgeBase 是否走知识库（边界闲聊用例为 false）
 */
public record RagBenchmarkCase(
    String id,
    Category category,
    String question,
    Set<String> expectedChunkIds,
    List<String> expectedAnswerPoints,
    boolean useKnowledgeBase) {

  /** 基准问题类别：覆盖 governance spec 要求的文本/表格/图片/词法/边界五类。 */
  public enum Category {
    /** 纯文本文档问答。 */
    TEXT,
    /** 表格/结构化行问答（rowIndex 锚点）。 */
    TABLE,
    /** 图片理解问答（图文双路检索）。 */
    IMAGE,
    /** 词法精确型：型号/编号/专名命中。考察纯向量检索的已知短板——目标片段与问题 向量相似度低、但文本精确包含目标词；混合检索补全（提案 2）前后的对照刚需。 */
    LEXICAL,
    /** 边界：闲聊、零命中、越权等——考察诚实生成而非强行召回。 */
    EDGE
  }
}
