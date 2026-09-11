package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.server.ai.port.KnowledgeRetrievalPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 助手检索编排。
 *
 * <p>KB OFF 绝不触发检索，也绝不注入未命中提示；KB ON 零命中时注入诚实生成的标记，
 * 避免旧 RAG 的“强制兜底一句未检索到”。B 生产实现合入后只需替换 port 实现。</p>
 */
@Slf4j
@Component
public class AiChatKnowledgeRetrievalService {

    private static final String MISS_CONTEXT = """
            【知识库检索】本轮未命中知识库片段。请基于已掌握的信息诚实回答；\
            不要声称引用了知识库来源，也不要输出机械化的空结果提示。""";

    private final ObjectProvider<KnowledgeRetrievalPort> retrievalPortProvider;

    public AiChatKnowledgeRetrievalService(ObjectProvider<KnowledgeRetrievalPort> retrievalPortProvider) {
        this.retrievalPortProvider = retrievalPortProvider;
    }

    public RetrievalOutcome retrieve(String query,
                                     Long userId,
                                     float[] imageVector,
                                     boolean useKnowledgeBase) {
        if (!useKnowledgeBase) {
            return RetrievalOutcome.empty();
        }
        KnowledgeRetrievalPort port = retrievalPortProvider == null
                ? null : retrievalPortProvider.getIfAvailable();
        if (port == null) {
            log.warn("KnowledgeRetrievalPort 未就绪，按零命中继续生成");
            return RetrievalOutcome.miss();
        }
        try {
            KnowledgeRetrievalPort.RetrievalResult result = port.retrieve(new KnowledgeRetrievalPort.RetrievalQuery(
                    query, userId, List.of(), 4, imageVector, null));
            if (result == null || result.hitCount() <= 0
                    || result.context() == null || result.context().isBlank()) {
                return RetrievalOutcome.miss();
            }
            return new RetrievalOutcome(toPromptContext(result.context()), result.sources());
        } catch (Exception exception) {
            log.warn("知识库检索失败，按零命中继续生成", exception);
            return RetrievalOutcome.miss();
        }
    }

    private String toPromptContext(String context) {
        return """
                【知识库检索】请优先使用以下片段；引用承重结论时在句尾使用 [编号]，\
                片段未支撑的内容不要编造来源。

                %s""".formatted(context.trim());
    }

    public record RetrievalOutcome(String context, List<SourceReference> sources) {
        public RetrievalOutcome {
            sources = sources == null ? List.of() : List.copyOf(sources);
        }

        public static RetrievalOutcome empty() {
            return new RetrievalOutcome(null, List.of());
        }

        public static RetrievalOutcome miss() {
            return new RetrievalOutcome(MISS_CONTEXT, List.of());
        }

        public boolean hasSources() {
            return !sources.isEmpty();
        }
    }
}
