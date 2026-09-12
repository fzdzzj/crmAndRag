package com.slz.crm.knowledge.retrieval;

import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LLM 上下文压缩器（方案10可选档，add-context-compression-and-enrichment 任务 2.2/2.3）。
 *
 * <p>启用：DynamicConfig {@code rag.context.compressor.mode = rule | llm}（默认 rule）。
 * 对超预算上下文做一次要点化压缩——经 {@code ModelProvider} + 中立 {@code ModelCallOptions}
 * 调用（未超预算不触发 LLM，零额外开销）。</p>
 *
 * <p>回退语义（任务 2.2 三条路径，全部回退规则压缩链、不向调用方抛错）：
 * 调用失败（异常）/ 输出为空或编号不完整或仍超预算 / 等待超时
 * （{@code rag.context.compressor.llm.timeout-ms}，默认 3000）。</p>
 *
 * <p>计量（任务 2.3，补盲点）：压缩调用的 token 消耗挂 {@link TokenUsageRecorder}——
 * 成功按 usage 记 success=true；失败/超时记 success=false（契约约定失败调用也要计量）。
 * 计量类型用 {@link TokenUsageType#SUMMARY}：压缩属要点化摘要类旁路调用，
 * {@code TokenUsageType} 为冻结契约无压缩枚举值，不为其解冻，语义最近者为 SUMMARY。</p>
 */
@Service
public class LlmContextCompressor implements Compressor {
    private static final Logger log = LoggerFactory.getLogger(LlmContextCompressor.class);

    private static final String SYSTEM_PROMPT = """
            你是 CRM 知识库上下文压缩器。把给定的带编号检索上下文压缩为更短的要点版本：
            1. 必须保留全部 [n] 编号标记，编号的数量、数值与顺序不得增删改；
            2. 每个编号段保留承重信息（数字、金额、期限、定义、责任主体），删除冗余修饰与重复表述；
            3. 不得新增上下文中不存在的信息；只输出压缩后的上下文全文，不要任何解释或额外标记。
            """;
    private static final Pattern SECTION_NUMBER = Pattern.compile("^\\s*\\[(\\d{1,4})\\]");
    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final int MIN_OUTPUT_TOKENS = 512;

    private final ModelProvider modelProvider;
    private final RuleContextCompressor fallbackCompressor;
    private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;
    private final ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider;

    public LlmContextCompressor(ModelProvider modelProvider,
                                RuleContextCompressor fallbackCompressor,
                                ObjectProvider<DynamicConfigService> dynamicConfigProvider,
                                ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider) {
        this.modelProvider = modelProvider;
        this.fallbackCompressor = fallbackCompressor;
        this.dynamicConfigProvider = dynamicConfigProvider;
        this.tokenUsageRecorderProvider = tokenUsageRecorderProvider;
    }

    @Override
    public String compress(String context, int tokenBudget) {
        if (context == null || context.isEmpty()
                || tokenBudget <= 0 || TokenEstimator.estimate(context) <= tokenBudget) {
            return context;
        }
        try {
            ModelCallResult<String> result = callModel(context, tokenBudget);
            recordUsage(result, true);
            String output = result == null ? null : result.content();
            if (!isValid(output, context, tokenBudget)) {
                log.info("LLM 压缩输出不可用（空/编号不完整/仍超预算），回退规则压缩链");
                return fallbackCompressor.compress(context, tokenBudget);
            }
            return output.strip();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("LLM 压缩被中断，回退规则压缩链: {}", exception.getMessage());
            recordUsage(null, false);
            return fallbackCompressor.compress(context, tokenBudget);
        } catch (Exception exception) {
            log.warn("LLM 压缩失败，回退规则压缩链: {}", exception.getMessage());
            recordUsage(null, false);
            return fallbackCompressor.compress(context, tokenBudget);
        }
    }

    /** 要点化压缩调用：带超时护栏（超时走 catch 回退）。 */
    private ModelCallResult<String> callModel(String context, int tokenBudget) throws Exception {
        List<Message> messages = List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage("token 预算约 " + tokenBudget + "，压缩以下上下文：\n" + context.strip()));
        ModelCallOptions options = new ModelCallOptions(null, false, 0.0d,
                Math.max(MIN_OUTPUT_TOKENS, tokenBudget), null, null, Map.of());
        CompletableFuture<ModelCallResult<String>> future = CompletableFuture.supplyAsync(
                () -> modelProvider.chat(new Prompt(messages), options));
        return future.get(resolveTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    /**
     * 输出校验：非空、编号序列与输入完全一致（引用编号完整性，任务 3.1 依赖）、
     * 且压缩后不超过预算——任一不满足即回退规则链。
     */
    private boolean isValid(String output, String input, int tokenBudget) {
        if (output == null || output.isBlank()) {
            return false;
        }
        return sectionNumbers(output).equals(sectionNumbers(input))
                && TokenEstimator.estimate(output) <= tokenBudget;
    }

    /** 提取输出中行首 {@code [n]} 编号序列（只认递增编号，正文内编号不误伤）。 */
    private List<Integer> sectionNumbers(String text) {
        List<Integer> numbers = new java.util.ArrayList<>();
        int expected = 1;
        for (String line : text.split("\n", -1)) {
            Matcher matcher = SECTION_NUMBER.matcher(line);
            if (matcher.find() && Integer.parseInt(matcher.group(1)) == expected) {
                numbers.add(expected);
                expected++;
            }
        }
        return numbers;
    }

    /**
     * 压缩调用计量（任务 2.3）：成功记 usage；失败/超时记 success=false、token 留空。
     * recorder 未装配（测试/极端降级）时跳过，不影响业务流。
     */
    private void recordUsage(ModelCallResult<String> result, boolean success) {
        TokenUsageRecorder recorder = tokenUsageRecorderProvider == null
                ? null : tokenUsageRecorderProvider.getIfAvailable();
        if (recorder == null) {
            return;
        }
        String model = result == null ? "unknown" : result.model();
        recorder.record(new TokenUsageRecord(model, currentUserRef(), null, null,
                TokenUsageType.SUMMARY,
                result == null ? null : result.promptTokens(),
                result == null ? null : result.completionTokens(),
                result == null ? null : result.totalTokens(),
                success));
    }

    private String currentUserRef() {
        UserContext user = UserContextHolder.current();
        return user == null ? "user:system" : user.userIdRef();
    }

    /** 超时上限（protected 便于测试覆写短超时）。 */
    protected long resolveTimeoutMs() {
        DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
        Long configured = config == null ? null
                : config.get("rag.context.compressor.llm.timeout-ms", Long.class, (long) DEFAULT_TIMEOUT_MS);
        return configured == null || configured < 1 ? DEFAULT_TIMEOUT_MS : configured;
    }
}
