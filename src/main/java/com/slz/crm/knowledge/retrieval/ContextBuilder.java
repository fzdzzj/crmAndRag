package com.slz.crm.knowledge.retrieval;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.slz.crm.knowledge.entity.DocumentVectorChunkEntity;
import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.VectorSearchHit;
import com.slz.crm.server.mapper.DocumentVectorChunkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 上下文组装器（add-context-compression-and-enrichment）：邻居上下文增强（方案04）+
 * 超预算压缩（方案10）。
 *
 * <p>邻居增强（任务 1.1）：对每个命中块按 {@code documentId + 相邻 chunkIndex} 从
 * {@code document_vector_chunk} 快照表取前/后邻居（chunkIndex 为全文档切片序号、0 起，
 * 故 ±1 即紧邻；同一序号出现多行的数据异常场景按「同页优先 + 主键小者」确定性取舍），
 * 拼装为：</p>
 * <pre>[n] （前文承接）邻居
 * 命中块
 * （后文承接）邻居</pre>
 *
 * <p>超预算压缩（任务 2.1/2.2）：拼装结果超过 {@code rag.context.token-budget} 时交给
 * 压缩器收敛；压缩器按 {@code rag.context.compressor.mode = rule | llm} 选择（默认 rule），
 * llm 未装配或值非法时落规则链。</p>
 *
 * <p>引用完整性约束（rag-context 规范）：邻居只进上下文、绝不进 {@code SourceReference}——
 * 引用与跳页锚点仍指命中块；上下文 [n] 编号与 sources 下标的一一对应不受邻居拼装与压缩影响。</p>
 *
 * <p>双粒度父块展开（提案4 任务 3.3，方案03 Small-to-Big）：{@code rag.context.parent-expand
 * = on | off}（默认 on）开启后，命中的是挂了父块的子块（语义切分产物）则把父块全文放进上下文
 * （生成单元），引用与跳页锚点仍指命中小块；未挂父块的命中（fixed 策略全量、单片逻辑段、
 * 评测非数字 chunkId、快照行缺失）逐块回退邻居拼装——fixed 策略下父块恒空，输出与提案3完成态一致。</p>
 *
 * <p>开关：{@code rag.context.neighbors = 0 | 1}（默认 1；0 = 关闭，输出与升级前逐字一致）。
 * 失败边界：快照表不可用（DB 异常）时按无邻居/无父块降级并告警，不拖垮检索链。</p>
 */
@Service
public class ContextBuilder {
    private static final Logger log = LoggerFactory.getLogger(ContextBuilder.class);

    static final String NEIGHBORS_KEY = "rag.context.neighbors";
    static final int DEFAULT_NEIGHBORS = 1;
    static final String TOKEN_BUDGET_KEY = "rag.context.token-budget";
    static final int DEFAULT_TOKEN_BUDGET = 4096;
    static final String COMPRESSOR_MODE_KEY = "rag.context.compressor.mode";
    static final String COMPRESSOR_MODE_LLM = "llm";
    /** 父块展开开关（提案4 任务 3.3）：on（默认）| off（回退邻居模式）。 */
    static final String PARENT_EXPAND_KEY = "rag.context.parent-expand";
    static final String PARENT_EXPAND_OFF = "off";
    /** 快照表只把 CHILD 行当邻居候选（PARENT 父块行占独立 chunk_index 空间，不作邻居）。 */
    static final String CHUNK_ROLE_CHILD = "CHILD";

    private static final String PREV_LABEL = "（前文承接）";
    private static final String NEXT_LABEL = "（后文承接）";

    private final DocumentVectorChunkMapper chunkMapper;
    private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;
    /** 规则压缩器：默认档与 LLM 压缩的回退链。 */
    private final RuleContextCompressor ruleCompressor;
    /** LLM 压缩器（默认关闭，mode=llm 才启用）；null = 兼容装配下不可用。 */
    private final LlmContextCompressor llmCompressor;

    public ContextBuilder(DocumentVectorChunkMapper chunkMapper,
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
     * <p>输出契约：第 i 个候选对应且仅对应 {@code [i+1]} 段，与调用方 sources 列表下标一一对应。</p>
     */
    public String build(List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return "";
        }
        String context = parentExpandEnabled() ? assembleWithParentExpand(candidates)
                : neighborsEnabled() ? assembleWithNeighbors(candidates) : plainNumbered(candidates);
        return enforceTokenBudget(context);
    }

    /** 升级前行为：top-K 全文拼接，仅 [n] 编号（兼容构造与邻居关闭路径共用）。 */
    static String plainNumbered(List<RetrievalCandidate> candidates) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < candidates.size(); index++) {
            if (index > 0) {
                builder.append('\n');
            }
            builder.append('[').append(index + 1).append("] ")
                    .append(candidates.get(index).hit().text().strip());
        }
        return builder.toString();
    }

    // ---------------------------------------------------------------- 邻居增强（方案04）

    /**
     * 双粒度父块展开（提案4 任务 3.3）：命中子块挂了父块 → [n] 段放父块全文（生成单元）；
     * 未挂父块/快照行缺失/查询失败 → 该命中块回退邻居拼装；邻居开关关闭时回退升级前纯拼接
     * （{@code rag.context.neighbors=0} 的语义在父块展开路径下同样生效）。
     * [n] 编号仍对应候选下标，sources 引用不进入本方法（由调用方 hit 原样产出）。
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
                appendWithNeighbors(builder, candidates.get(index).hit());
            } else {
                builder.append(candidates.get(index).hit().text().strip());
            }
        }
        return builder.toString();
    }

    /**
     * 查命中块的父块全文：chunkId 须为快照表数字主键（评测占位 id 非数字直接视为不可展开）；
     * 子块未挂父块或父块行缺失/空白返回 null（调用方回退邻居模式）。DB 异常降级不拖垮检索链。
     */
    private String lookupParentText(VectorSearchHit hit) {
        String chunkId = hit.chunkId();
        if (chunkId == null || !chunkId.chars().allMatch(Character::isDigit)) {
            return null;
        }
        try {
            DocumentVectorChunkEntity child = chunkMapper.selectById(Long.parseLong(chunkId));
            if (child == null || child.getParentChunkId() == null) {
                return null;
            }
            DocumentVectorChunkEntity parent = chunkMapper.selectById(child.getParentChunkId());
            return parent == null || parent.getChunkText() == null || parent.getChunkText().isBlank()
                    ? null : parent.getChunkText();
        } catch (Exception exception) {
            log.warn("父块展开查询失败，按邻居模式降级: {}", exception.getMessage());
            return null;
        }
    }

    private String assembleWithNeighbors(List<RetrievalCandidate> candidates) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < candidates.size(); index++) {
            if (index > 0) {
                builder.append('\n');
            }
            VectorSearchHit hit = candidates.get(index).hit();
            builder.append('[').append(index + 1).append("] ");
            appendWithNeighbors(builder, hit);
        }
        return builder.toString();
    }

    /** 命中块 + 前/后邻居拼装；邻居缺失（首末块/跨文档/快照表不可用）时静默跳过对应一侧。 */
    private void appendWithNeighbors(StringBuilder builder, VectorSearchHit hit) {
        String hitText = hit.text() == null ? "" : hit.text().strip();
        List<DocumentVectorChunkEntity> neighbors = fetchNeighbors(hit);
        Integer hitChunkIndex = chunkIndex(hit);
        DocumentVectorChunkEntity prev = neighborAt(neighbors, hitChunkIndex - 1, hit);
        DocumentVectorChunkEntity next = neighborAt(neighbors, hitChunkIndex + 1, hit);
        if (prev != null && !prev.getChunkText().isBlank()) {
            builder.append(PREV_LABEL).append(prev.getChunkText().strip()).append('\n');
        }
        builder.append(hitText);
        if (next != null && !next.getChunkText().isBlank()) {
            builder.append('\n').append(NEXT_LABEL).append(next.getChunkText().strip());
        }
    }

    /**
     * 查询命中块的潜在邻居行（chunkIndex-1 与 chunkIndex+1，同文档）。
     * chunkIndex 缺失或查询失败时返回空列表（无邻居降级，不抛错）。
     */
    private List<DocumentVectorChunkEntity> fetchNeighbors(VectorSearchHit hit) {
        Integer chunkIndex = chunkIndex(hit);
        if (chunkIndex == null || chunkIndex < 0 || hit.documentId() == null || hit.documentId().isBlank()) {
            return List.of();
        }
        List<Integer> targets = new ArrayList<>();
        if (chunkIndex > 0) {
            targets.add(chunkIndex - 1);
        }
        targets.add(chunkIndex + 1);
        try {
            return chunkMapper.selectList(new QueryWrapper<DocumentVectorChunkEntity>()
                    .eq("document_id", hit.documentId())
                    .eq("chunk_role", CHUNK_ROLE_CHILD)
                    .in("chunk_index", targets));
        } catch (Exception exception) {
            log.warn("邻居切片查询失败，按无邻居降级: {}", exception.getMessage());
            return List.of();
        }
    }

    /**
     * 取目标序号的邻居行：SQL 已按 document_id 收敛，此处再过滤跨文档行兜底；
     * 同序号多行按「同页优先、主键小者」确定性取舍（同页优先，任务 1.1）。
     */
    private DocumentVectorChunkEntity neighborAt(List<DocumentVectorChunkEntity> rows,
                                                 int targetIndex, VectorSearchHit hit) {
        if (targetIndex < 0) {
            return null;
        }
        Integer hitPageNo = positivePageNo(hit);
        DocumentVectorChunkEntity best = null;
        for (DocumentVectorChunkEntity row : rows) {
            if (row.getChunkIndex() == null || row.getChunkIndex() != targetIndex
                    || row.getChunkText() == null
                    || row.getDocumentId() == null || !row.getDocumentId().equals(hit.documentId())) {
                continue;
            }
            if (best == null || betterNeighbor(row, best, hitPageNo)) {
                best = row;
            }
        }
        return best;
    }

    /** 同页优先；同页（或同跨页）时取主键小者，保证同输入同输出。 */
    private boolean betterNeighbor(DocumentVectorChunkEntity candidate,
                                   DocumentVectorChunkEntity current, Integer hitPageNo) {
        boolean candidateSamePage = candidate.getPageNo() != null && candidate.getPageNo().equals(hitPageNo);
        boolean currentSamePage = current.getPageNo() != null && current.getPageNo().equals(hitPageNo);
        if (candidateSamePage != currentSamePage) {
            return candidateSamePage;
        }
        return candidate.getId() != null && current.getId() != null && candidate.getId() < current.getId();
    }

    private Integer chunkIndex(VectorSearchHit hit) {
        Object value = hit.metadata().get("chunkIndex");
        return value instanceof Number number ? number.intValue() : null;
    }

    private Integer positivePageNo(VectorSearchHit hit) {
        Object value = hit.metadata().get("pageNo");
        if (value instanceof Number number) {
            long pageNo = number.longValue();
            return pageNo > 0 ? (int) pageNo : null;
        }
        return null;
    }

    // ---------------------------------------------------------------- 超预算压缩（方案10）

    /** 超预算触发压缩（任务 2.4：未超预算原文逐字保留，压缩器不会被调用）。 */
    private String enforceTokenBudget(String context) {
        int budget = resolveTokenBudget();
        if (TokenEstimator.estimate(context) <= budget) {
            return context;
        }
        return activeCompressor().compress(context, budget);
    }

    /** token 预算：{@code rag.context.token-budget}（默认 4096）；&lt;1 回落默认。 */
    private int resolveTokenBudget() {
        DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
        Integer configured = config == null
                ? null : config.get(TOKEN_BUDGET_KEY, Integer.class, DEFAULT_TOKEN_BUDGET);
        return configured == null || configured < 1 ? DEFAULT_TOKEN_BUDGET : configured;
    }

    /** 压缩器选择：{@code rag.context.compressor.mode = rule | llm}（默认 rule）；llm 未装配或值非法落规则链。 */
    private Compressor activeCompressor() {
        DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
        String mode = config == null ? null : config.get(COMPRESSOR_MODE_KEY, String.class, "rule");
        if (mode != null && COMPRESSOR_MODE_LLM.equalsIgnoreCase(mode.strip()) && llmCompressor != null) {
            return llmCompressor;
        }
        return ruleCompressor;
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
        Integer configured = config == null
                ? null : config.get(NEIGHBORS_KEY, Integer.class, DEFAULT_NEIGHBORS);
        return configured == null || configured >= 1;
    }
}
