package com.slz.crm.quality;

import java.util.List;
import java.util.Set;

/**
 * RAG 质量基准单条用例（task17）。
 *
 * <p>作为"优化不产生质量回退"的固定对照（governance spec：以固定基准集验证）。 {@code expectedChunkIds} 是应被召回的黄金片段，作
 * recall/precision/MRR 的判定依据； 边界用例（闲聊/零命中）expected 为空——此时"不召回、不伪造引用"才是正确行为（D16 诚实生成）。
 *
 * <p>多轮维度（expand-rag-eval-blind-spots 任务 1.1）：{@code history} 携带本轮问句之前的对话轮次（user/assistant 成对
 * 文本），单轮用例为空表。同一问句在有无 history 时召回的黄金片段不同（指代消解/上下文追问/澄清触发），该维度即多轮改写 的评测面；history 与
 * category（内容类型）正交，故多轮用例沿用内容类别而不新增类目。
 *
 * @param id 用例编号
 * @param category 类别：文本/表格/图片/词法精确/边界/失配/时间限定
 * @param question 检索问题
 * @param history 本轮之前的对话历史（按时间顺序，user/assistant 成对；单轮用例为空表）
 * @param expectedChunkIds 应召回的黄金片段 id（可空=边界用例）；基线运行前由数据准备 runner 把占位 id 替换为真实入库 chunkId（见
 *     add-rag-quality-baseline）
 * @param expectedAnswerPoints 答案应覆盖的要点（答案一致性评估用）
 * @param useKnowledgeBase 是否走知识库（边界闲聊用例为 false）
 */
public record RagBenchmarkCase(
    String id,
    Category category,
    String question,
    List<HistoryTurn> history,
    Set<String> expectedChunkIds,
    List<String> expectedAnswerPoints,
    boolean useKnowledgeBase) {

  /**
   * 单轮用例兼容构造：history 视为空表。
   *
   * <p>既有用例（v1.0~v5.0 共 63 条）全部按此形态构造——升版新增 history 组件不要求既有用例补参（expand-rag-eval-blind-spots 任务
   * 1.1「兼容重载」）。
   */
  public RagBenchmarkCase(
      String id,
      Category category,
      String question,
      Set<String> expectedChunkIds,
      List<String> expectedAnswerPoints,
      boolean useKnowledgeBase) {
    this(
        id,
        category,
        question,
        List.of(),
        expectedChunkIds,
        expectedAnswerPoints,
        useKnowledgeBase);
  }

  /**
   * 一条对话轮次（多轮用例的上下文单元）。
   *
   * @param userText 该轮用户问句
   * @param assistantText 该轮助手回答
   */
  public record HistoryTurn(String userText, String assistantText) {}

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
    EDGE,
    /**
     * 查询-文档词汇失配（expand-rag-benchmark-mismatch 任务 2.1）：question 与 gold chunk 词面重叠≈空
     * （行话/编号体系/口语-术语同义类失配），词法命中救不动，考察检索改写类增强（multi-query/HyDE/衍生问题）能否 桥接用语差异补漏召；答案一致性由黄金答案点覆盖。
     */
    MISMATCH,
    /**
     * 时间限定类（expand-rag-eval-blind-spots 任务 2.1）：问句含「本月/本季度/上季度/最近」等相对时间限定词，黄金锚是静态语料中
     * 已写明时间口径的段落——考察检索改写能否把相对时间表述落到语料内的绝对/周期口径上。语料纯静态、判分全 fake 可验证，不依赖真库 的时效数据。
     */
    TEMPORAL
  }
}
