package com.slz.crm.knowledge.retrieval;

import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 知识库检索查询改写；模型失败时回退原查询。
 */
@Service
public class RetrievalQueryRewriteService {
    private static final Logger log = LoggerFactory.getLogger(RetrievalQueryRewriteService.class);
    private static final String SYSTEM_PROMPT = """
            你是 CRM 知识库检索查询改写器。只改写查询，不回答业务问题。
            规则：保留业务实体、数字、时间、类目和用户意图；补齐明显指代；不扩写长句。
            只输出一行改写后的查询；无法改写时原样输出用户查询。
            """;

    private final ModelProvider modelProvider;
    private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;

    public RetrievalQueryRewriteService(ModelProvider modelProvider,
                                        ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
        this.modelProvider = modelProvider;
        this.dynamicConfigProvider = dynamicConfigProvider;
    }

    /** 使用中立 ModelCallOptions 改写；失败或空输出回退原查询。 */
    public String rewrite(String query) {
        if (query == null || query.isBlank()) {
            return "";
        }
        if (!rewriteEnabled()) {
            return query.strip();
        }
        try {
            List<Message> messages = List.of(
                    new SystemMessage(SYSTEM_PROMPT),
                    new UserMessage(query.strip()));
            ModelCallOptions options = new ModelCallOptions(
                    null, false, 0.1d, 128, null, null, Map.of());
            String rewritten = sanitize(modelProvider.chat(new Prompt(messages), options).content());
            return rewritten.isBlank() ? query.strip() : rewritten;
        } catch (Exception exception) {
            log.warn("查询改写失败，使用原查询继续检索: {}", exception.getMessage());
            return query.strip();
        }
    }

    private boolean rewriteEnabled() {
        DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
        return config == null || config.get("rag.retrieval.query-rewrite.enabled", Boolean.class, true);
    }

    private String sanitize(String content) {
        if (content == null) {
            return "";
        }
        String value = content.strip().replaceAll("(?i)^(改写后查询|查询|rewritten query)\\s*[:：]\\s*", "");
        value = value.replaceAll("[\\r\\n]+", " ").strip();
        if (value.length() > 256) {
            value = value.substring(0, 256).strip();
        }
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("“") && value.endsWith("”")))) {
            value = value.substring(1, value.length() - 1).strip();
        }
        return value;
    }
}
