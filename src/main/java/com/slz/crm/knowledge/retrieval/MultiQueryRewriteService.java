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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 多查询变体生成器（方案 07 增强，enhance-query-transformation 任务 1.1）。
 *
 * <p>启用：DynamicConfig {@code rag.query.multi-query.enabled}（默认 false——成本敏感型增强）。
 * 启用后对主查询做一次 LLM 调用，产出 {@code rag.query.multi-query.variants}（默认 3，上限 5）个
 * 检索变体（补全指代/拆解子问题/换视角重述），与原始查询共同构成 N+1 路并行召回的输入。</p>
 *
 * <p>降级语义（任务 1.3）：关闭、调用失败、空输出、变体去重后为空——一律返回
 * {@code List.of(原始查询)}，即回退现行为（单查询管线），不向调用方抛错。</p>
 */
@Service
public class MultiQueryRewriteService {
    private static final Logger log = LoggerFactory.getLogger(MultiQueryRewriteService.class);

    private static final String ENABLED_KEY = "rag.query.multi-query.enabled";
    private static final String VARIANTS_KEY = "rag.query.multi-query.variants";
    private static final int DEFAULT_VARIANTS = 3;
    private static final int MAX_VARIANTS = 5;

    private static final String SYSTEM_PROMPT = """
            你是 CRM 知识库检索查询扩展器。基于给定查询生成指定数量的检索变体，用于提高召回：
            1) 补全省略的实体、指代或时间范围；2) 把复合问题拆成更聚焦的子问题；3) 换一个问法或视角重述。
            每个变体独占一行；不要编号；不要解释；不要回答业务问题；变体之间不要重复。
            """;

    private final ModelProvider modelProvider;
    private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;

    public MultiQueryRewriteService(ModelProvider modelProvider,
                                    ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
        this.modelProvider = modelProvider;
        this.dynamicConfigProvider = dynamicConfigProvider;
    }

    /**
     * 产出查询路列表：首元素恒为原始查询，其后为去重后的 LLM 变体。
     * 永不返回空列表、永不抛出——所有失败路径退化为单查询。
     */
    public List<String> expand(String primaryQuery) {
        if (primaryQuery == null || primaryQuery.isBlank()) {
            return List.of("");
        }
        String normalized = primaryQuery.strip();
        if (!enabled()) {
            return List.of(normalized);
        }
        int variants = resolveVariants();
        try {
            List<Message> messages = List.of(
                    new SystemMessage(SYSTEM_PROMPT.formatted(variants)),
                    new UserMessage(normalized));
            ModelCallOptions options = new ModelCallOptions(
                    null, false, 0.2d, 512, null, null, Map.of());
            String output = modelProvider.chat(new Prompt(messages), options).content();
            List<String> parsed = parseVariants(output, variants, normalized);
            List<String> routes = new ArrayList<>(parsed.size() + 1);
            routes.add(normalized);
            routes.addAll(parsed);
            return List.copyOf(routes);
        } catch (Exception exception) {
            log.warn("多查询变体生成失败，回退单查询: {}", exception.getMessage());
            return List.of(normalized);
        }
    }

    /** 变体解析：逐行 → 净化 → 去重（含与原始查询重复）→ 截断到变体数上限。 */
    private List<String> parseVariants(String output, int variants, String primary) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        Set<String> seen = new LinkedHashSet<>();
        seen.add(primary.toLowerCase(Locale.ROOT));
        List<String> result = new ArrayList<>(variants);
        for (String line : output.split("\\r?\\n")) {
            String variant = sanitize(line);
            if (variant.isBlank() || !seen.add(variant.toLowerCase(Locale.ROOT))) {
                continue;
            }
            result.add(variant);
            if (result.size() >= variants) {
                break;
            }
        }
        return result;
    }

    private String sanitize(String content) {
        if (content == null) {
            return "";
        }
        String value = content.strip().replaceAll("^\\s*\\d+\\s*[.、)．]\\s*", "");
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

    private boolean enabled() {
        DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
        return config != null
                && Boolean.TRUE.equals(config.get(ENABLED_KEY, Boolean.class, false));
    }

    private int resolveVariants() {
        DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
        Integer configured = config == null ? null
                : config.get(VARIANTS_KEY, Integer.class, DEFAULT_VARIANTS);
        if (configured == null || configured < 1) {
            return DEFAULT_VARIANTS;
        }
        return Math.min(configured, MAX_VARIANTS);
    }
}
