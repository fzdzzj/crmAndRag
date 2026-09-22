package com.slz.crm.knowledge.retrieval;

import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 上下文组装器（add-context-compression-and-enrichment）：邻居上下文增强（方案04）+ 超预算压缩（方案10）。
 *
 * <p>邻居增强（任务 1.1）：对每个命中块按 {@code documentId + 相邻 chunkIndex} 从 {@code document_vector_chunk}
 * 快照表取前/后邻居（chunkIndex 为全文档切片序号、0 起，故 ±1 即紧邻；同一序号多行按「同页优先 + 主键小者」确定性取舍），拼装为「[n]（前文承接）邻居 / 命中块 /
 * （后文承接）邻居」。
 *
 * <p>超预算压缩（任务 2.1/2.2）：拼装结果超过 {@code rag.context.token-budget} 时交给压缩器收敛， 按 {@code
 * rag.context.compressor.mode = rule | llm} 选择（默认 rule，llm 未装配或值非法落规则链）。
 *
 * <p>引用完整性约束（rag-context 规范）：邻居只进上下文、绝不进 {@code SourceReference}——引用与跳页 锚点仍指命中块；[n] 编号与 sources
 * 下标的一一对应不受邻居拼装与压缩影响。
 *
 * <p>双粒度父块展开（提案4 任务 3.3，方案03 Small-to-Big）：{@code rag.context.parent-expand}（默认 on）
 * 开启后命中的子块（语义切分产物）带父块则父块全文进上下文（生成单元），引用仍指命中小块；未挂父块的 命中（fixed 全量、单片逻辑段、评测非数字
 * chunkId、快照行缺失）逐块回退邻居拼装，与提案3完成态一致。
 *
 * <p>开关：{@code rag.context.neighbors = 0 | 1}（默认 1；0 = 关闭，输出与升级前逐字一致）；失败边界： 快照表不可用（DB
 * 异常）时按无邻居/无父块降级并告警，不拖垮检索链。
 */
@Service
public class ContextBuilder {
  private static final Logger LOG = LoggerFactory.getLogger(ContextBuilder.class);

  static final String NEIGHBORS_KEY = "rag.context.neighbors";
  static final int DEFAULT_NEIGHBORS = 1;
  static final String TOKEN_BUDGET_KEY = "rag.context.token-budget";
  static final int DEFAULT_TOKEN_BUDGET = 4096;
  static final String COMPRESSOR_MODE_KEY = "rag.context.compressor.mode";
  static final String COMPRESSOR_MODE_LLM = "llm";

  /** 父块展开开关（提案4 任务 3.3）：on（默认）| off（回退邻居模式）。 */
  static final String PARENT_EXPAND_KEY = "rag.context.parent-expand";

  static final String PARENT_EXPAND_OFF = "off";

  private final DocumentVectorChunkMapper chunkMapper;
  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  /** 规则压缩器：默认档与 LLM 压缩的回退链。 */
  private final RuleContextCompressor ruleCompressor;

  /** LLM 压缩器（默认关闭，mode=llm 才启用）；null = 兼容装配下不可用。 */
  private final LlmContextCompressor llmCompressor;

  public ContextBuilder(
      DocumentVectorChunkMapper chunkMapper,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider,
      RuleContextCompressor ruleCompressor,
      LlmContextCompressor llmCompressor) {
    this.chunkMapper = chunkMapper;
    this.dynamicConfigProvider = dynamicConfigProvider;
    this.ruleCompressor = ruleCompressor;
    this.llmCompressor = llmCompressor;
  }

  /**
   * 组装带编号的检索上下文（父块展开 → 邻居增强 → 超预算压缩）。
   *
   * <p>输出契约：第 i 个候选对应且仅对应 {@code [i+1]} 段，与调用方 sources 列表下标一一对应。
   */
  public String build(List<RetrievalCandidate> candidates) {
    String context;
    if (candidates == null || candidates.isEmpty()) {
      context = "";
    } else {
      context =
          parentExpandEnabled()
              ? assembleWithParentExpand(candidates)
              : neighborsEnabled()
                  ? NeighborContextSupport.assembleWithNeighbors(candidates, chunkMapper)
                  : plainNumbered(candidates);
    }
    return enforceTokenBudget(context);
  }

  /** 升级前行为：top-K 全文拼接，仅 [n] 编号（兼容构造与邻居关闭路径共用）。 */
  static String plainNumbered(List<RetrievalCandidate> candidates) {
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < candidates.size(); index++) {
      if (index > 0) {
        builder.append('\n');
      }
      builder
          .append('[')
          .append(index + 1)
          .append("] ")
          .append(candidates.get(index).hit().text().strip());
    }
    return builder.toString();
  }

  // ---------------------------------------------------------------- 邻居增强（方案04）

  /**
   * 双粒度父块展开（提案4 任务 3.3）：命中子块挂了父块 → [n] 段放父块全文（生成单元）； 未挂父块/快照行缺失/查询失败 → 该命中块回退邻居拼装；邻居开关关闭时回退升级前纯拼接
   * （{@code rag.context.neighbors=0} 的语义在父块展开路径下同样生效）。 [n] 编号仍对应候选下标，sources 引用不进入本方法（由调用方 hit
   * 原样产出）。
   */
  private String assembleWithParentExpand(List<RetrievalCandidate> candidates) {
    boolean neighbors = neighborsEnabled();
    StringBuilder builder = new StringBuilder();
    for (int index = 0; index < candidates.size(); index++) {
      if (index > 0) {
        builder.append('\n');
      }
      builder.append('[').append(index + 1).append("] ");
      String parentText = lookupParentText(candidates.get(index).hit());
      if (parentText != null) {
        builder.append(parentText.strip());
      } else if (neighbors) {
        NeighborContextSupport.appendWithNeighbors(
            builder, candidates.get(index).hit(), chunkMapper);
      } else {
        builder.append(candidates.get(index).hit().text().strip());
      }
    }
    return builder.toString();
  }

  /**
   * 查命中块的父块全文：chunkId 须为快照表数字主键（评测占位 id 非数字直接视为不可展开）； 子块未挂父块或父块行缺失/空白返回 null（调用方回退邻居模式）。DB
   * 异常降级不拖垮检索链。
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException") // ORM查询边界：chunkMapper查询多源，失败按无父块降级
  private String lookupParentText(VectorSearchHit hit) {
    String chunkId = hit.chunkId();
    String result = null;
    if (chunkId != null && chunkId.chars().allMatch(Character::isDigit)) {
      try {
        DocumentVectorChunkEntity child = chunkMapper.selectById(Long.parseLong(chunkId));
        if (child != null && child.getParentChunkId() != null) {
          DocumentVectorChunkEntity parent = chunkMapper.selectById(child.getParentChunkId());
          if (parent != null && parent.getChunkText() != null && !parent.getChunkText().isBlank()) {
            result = parent.getChunkText();
          }
        }
      } catch (Exception exception) {
        LOG.warn("父块展开查询失败，按邻居模式降级: {}", exception.getMessage());
      }
    }
    return result;
  }

  // ---------------------------------------------------------------- 超预算压缩（方案10）

  /** 超预算触发压缩（任务 2.4：未超预算原文逐字保留，压缩器不会被调用）。 */
  private String enforceTokenBudget(String context) {
    int budget = resolveTokenBudget();
    String result;
    if (TokenEstimator.estimate(context) <= budget) {
      result = context;
    } else {
      result = activeCompressor().compress(context, budget);
    }
    return result;
  }

  /** token 预算：{@code rag.context.token-budget}（默认 4096）；&lt;1 回落默认。 */
  private int resolveTokenBudget() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Integer configured =
        config == null ? null : config.get(TOKEN_BUDGET_KEY, Integer.class, DEFAULT_TOKEN_BUDGET);
    return configured == null || configured < 1 ? DEFAULT_TOKEN_BUDGET : configured;
  }

  /** 压缩器选择：{@code rag.context.compressor.mode = rule | llm}（默认 rule）；llm 未装配或值非法落规则链。 */
  private Compressor activeCompressor() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    String mode = config == null ? null : config.get(COMPRESSOR_MODE_KEY, String.class, "rule");
    Compressor result = ruleCompressor;
    if (mode != null
        && COMPRESSOR_MODE_LLM.equalsIgnoreCase(mode.strip())
        && llmCompressor != null) {
      result = llmCompressor;
    }
    return result;
  }

  // ---------------------------------------------------------------- 开关与邻居解析

  /** 父块展开开关：{@code rag.context.parent-expand = on | off}（默认 on；显式 off 回退邻居模式）。 */
  private boolean parentExpandEnabled() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    String configured = config == null ? null : config.get(PARENT_EXPAND_KEY, String.class, "on");
    return configured == null || !PARENT_EXPAND_OFF.equalsIgnoreCase(configured.strip());
  }

  /** 邻居开关：{@code rag.context.neighbors}（默认 1）；显式 0 或负值 = 关闭回退升级前行为。 */
  private boolean neighborsEnabled() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Integer configured =
        config == null ? null : config.get(NEIGHBORS_KEY, Integer.class, DEFAULT_NEIGHBORS);
    return configured == null || configured >= 1;
  }
}
