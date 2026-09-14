package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.BypassTaskExecutor;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.pojo.entity.AiConversationMemoryEntity;
import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.server.service.AiConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话记忆加工编排器。
 *
 * <p>意图/摘要是可失败的旁路任务：D 执行器缺失或饱和时直接跳过；单飞去重由本类按会话 CAS。</p>
 */
@Slf4j
@Component
// test-hygiene 任务 2.1：测试环境隔离，生产默认开（matchIfMissing=true 保证未配置时照常调度）
@ConditionalOnProperty(name = "crm.ai.scheduled-enabled", havingValue = "true", matchIfMissing = true)
public class AiMemoryOrchestrator {

    private static final int SUMMARY_TRIGGER_MESSAGE_COUNT = 12;
    private static final int SUMMARY_SOURCE_MESSAGE_COUNT = 12;
    private static final int SUMMARY_MAX_CHARS = 2000;
    private static final int INTENT_MAX_CHARS = 200;
    private static final int MAX_FACTS = 8;
    private static final int MAX_FACTS_PER_UPDATE = 3;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private AiConversationMemoryService memoryService;

    @Autowired(required = false)
    private ObjectProvider<BypassTaskExecutor> bypassExecutorProvider;

    @Autowired(required = false)
    private ObjectProvider<ModelProvider> modelProviderProvider;

    @Autowired(required = false)
    private ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider;

    @Value("${crm.ai.memory-ttl-seconds:1800}")
    private long memoryTtlSeconds = 1800;

    @Value("${crm.ai.memory-fact-score-threshold:0.75}")
    private double memoryFactScoreThreshold = 0.75;

    /** 会话级摘要 CAS；value 语义便于任务未入队/结束时精准清理。 */
    private final Map<Long, AtomicBoolean> summaryInFlight = new ConcurrentHashMap<>();

    /** 会话级意图 CAS。 */
    private final Map<Long, AtomicBoolean> intentInFlight = new ConcurrentHashMap<>();

    /**
     * 一轮回答完成后触发加工；主答线程只做 CAS/提交，绝不等待模型调用。
     */
    public void onRoundCompleted(Long sessionId, Long userId, String userMessage, String assistantAnswer) {
        if (sessionId == null || userMessage == null || userMessage.isBlank()
                || assistantAnswer == null || assistantAnswer.isBlank()) {
            return;
        }
        memoryService.ensureMemory(sessionId, userId);
        triggerSummary(sessionId, userId);
        triggerIntent(sessionId, userId, userMessage);
    }

    /**
     * 从检索 top1 片段提取原文事实；低于阈值的片段不污染长期记忆。
     */
    public void updateFactsFromTopMatch(Long sessionId, Long userId, String excerpt, Double relevanceScore) {
        if (sessionId == null || relevanceScore == null
                || relevanceScore < memoryFactScoreThreshold || excerpt == null || excerpt.isBlank()) {
            return;
        }
        List<String> extracted = excerpt.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .limit(MAX_FACTS_PER_UPDATE)
                .toList();
        if (extracted.isEmpty()) {
            return;
        }
        memoryService.ensureMemory(sessionId, userId);
        AiConversationMemoryEntity memory = memoryService.findBySessionId(sessionId);
        if (memory == null) {
            return;
        }
        List<String> facts = parseFacts(memory.getFacts());
        for (String fact : extracted) {
            if (!facts.contains(fact)) {
                facts.add(fact);
            }
        }
        // 超上限按插入序淘汰，保证旧事实不会无限累积。
        while (facts.size() > MAX_FACTS) {
            facts.remove(0);
        }
        try {
            memory.setFacts(OBJECT_MAPPER.writeValueAsString(facts));
        } catch (Exception exception) {
            log.warn("AI 会话事实序列化失败，跳过更新: sessionId={}", sessionId, exception);
            return;
        }
        memoryService.updateMemory(memory);
    }

    /**
     * 清理长期未更新的记忆加工品；ai_message 原文保留，不影响历史查看。
     */
    @Scheduled(fixedDelayString = "${crm.ai.memory-cleanup-interval-ms:300000}")
    public void cleanupExpiredMemories() {
        memoryService.deleteExpiredBefore(LocalDateTime.now().minusSeconds(memoryTtlSeconds));
    }

    private List<String> parseFacts(String factsJson) {
        if (factsJson == null || factsJson.isBlank()) {
            return new java.util.ArrayList<>();
        }
        try {
            List<String> facts = OBJECT_MAPPER.readValue(factsJson, new TypeReference<List<String>>() {});
            return new java.util.ArrayList<>(facts == null ? List.of() : facts);
        } catch (Exception exception) {
            log.warn("AI 会话事实 JSON 解析失败，按空列表处理", exception);
            return new java.util.ArrayList<>();
        }
    }

    private void triggerSummary(Long sessionId, Long userId) {
        List<AiMessageEntity> messages = memoryService.restoreRecentProjection(
                sessionId, SUMMARY_TRIGGER_MESSAGE_COUNT + 1);
        if (messages.size() <= SUMMARY_TRIGGER_MESSAGE_COUNT) {
            return;
        }
        AtomicBoolean state = summaryInFlight.computeIfAbsent(sessionId, ignored -> new AtomicBoolean());
        if (!state.compareAndSet(false, true)) {
            return;
        }
        List<AiMessageEntity> source = memoryService.restoreRecentProjection(
                sessionId, SUMMARY_SOURCE_MESSAGE_COUNT);
        BypassTaskExecutor executor = getBypassExecutor();
        if (executor == null || !executor.tryExecute(() -> compressSummary(sessionId, userId, source, state))) {
            // 任务未入队时 finally 不会执行；这里必须立即复位，否则该会话摘要被永久跳过。
            release(summaryInFlight, sessionId, state);
        }
    }

    private void triggerIntent(Long sessionId, Long userId, String userMessage) {
        AtomicBoolean state = intentInFlight.computeIfAbsent(sessionId, ignored -> new AtomicBoolean());
        if (!state.compareAndSet(false, true)) {
            return;
        }
        BypassTaskExecutor executor = getBypassExecutor();
        if (executor == null || !executor.tryExecute(() -> extractIntent(sessionId, userId, userMessage, state))) {
            release(intentInFlight, sessionId, state);
        }
    }

    private BypassTaskExecutor getBypassExecutor() {
        return bypassExecutorProvider == null ? null : bypassExecutorProvider.getIfAvailable();
    }

    private void compressSummary(Long sessionId, Long userId, List<AiMessageEntity> messages, AtomicBoolean state) {
        try {
            String source = buildSource(messages);
            if (source.isBlank()) {
                return;
            }
            String prompt = """
                    请把以下 CRM 助手对话压缩成一份事实性历史摘要，保留客户、合同、商机、金额、时间和待办；\
                    不写客套话，不超过 2000 字。
                    """;
            ModelCallResult<String> result = callModel(userId, sessionId, TokenUsageType.SUMMARY, prompt, source);
            String generatedSummary = AiThinkTagStripper.strip(result.content()).trim();
            String summary = generatedSummary.length() > SUMMARY_MAX_CHARS
                    ? generatedSummary.substring(0, SUMMARY_MAX_CHARS) : generatedSummary;
            updateMemoryField(sessionId, memory -> memory.setSummary(summary));
        } catch (Exception exception) {
            log.warn("AI 会话摘要加工失败，跳过本轮: sessionId={}", sessionId, exception);
        } finally {
            release(summaryInFlight, sessionId, state);
        }
    }

    private void extractIntent(Long sessionId, Long userId, String userMessage, AtomicBoolean state) {
        try {
            String prompt = """
                    请从用户输入中抽取当前单值业务意图，输出一行不超过 200 字的短语；\
                    不要解释，不要输出 JSON。
                    """;
            ModelCallResult<String> result = callModel(userId, sessionId, TokenUsageType.INTENT, prompt, userMessage);
            String generatedIntent = AiThinkTagStripper.strip(result.content()).trim();
            String intent = generatedIntent.length() > INTENT_MAX_CHARS
                    ? generatedIntent.substring(0, INTENT_MAX_CHARS) : generatedIntent;
            if (!intent.isBlank()) {
                updateMemoryField(sessionId, memory -> memory.setIntent(intent));
            }
        } catch (Exception exception) {
            log.warn("AI 会话意图抽取失败，跳过本轮: sessionId={}", sessionId, exception);
        } finally {
            release(intentInFlight, sessionId, state);
        }
    }

    private ModelCallResult<String> callModel(Long userId, Long sessionId, TokenUsageType type,
                                              String instruction, String input) {
        ModelProvider provider = modelProviderProvider == null ? null : modelProviderProvider.getIfAvailable();
        if (provider == null) {
            throw new IllegalStateException("ModelProvider 未就绪");
        }
        List<Message> messages = List.of(new SystemMessage(instruction), new UserMessage(input));
        ModelCallResult<String> result = provider.chat(new Prompt(messages));
        recordUsage(userId, sessionId, type, result);
        return result;
    }

    private void updateMemoryField(Long sessionId, java.util.function.Consumer<AiConversationMemoryEntity> updater) {
        AiConversationMemoryEntity memory = memoryService.findBySessionId(sessionId);
        if (memory == null) {
            return;
        }
        updater.accept(memory);
        boolean updated = memoryService.updateMemory(memory);
        if (!updated) {
            // 乐观锁冲突说明有更新的加工结果；旧任务按设计拒绝降级，不重试覆盖。
            log.info("AI 会话记忆更新乐观锁冲突，跳过: sessionId={}", sessionId);
        }
    }

    private String buildSource(List<AiMessageEntity> messages) {
        StringBuilder source = new StringBuilder();
        for (AiMessageEntity message : messages) {
            String content = AiThinkTagStripper.strip(message.getContent());
            if (content.isBlank()) {
                continue;
            }
            source.append("user".equals(message.getRole()) ? "用户：" : "助手：")
                    .append(content)
                    .append('\n');
        }
        return source.toString();
    }

    private void release(Map<Long, AtomicBoolean> states, Long sessionId, AtomicBoolean state) {
        state.set(false);
        states.remove(sessionId, state);
    }

    private void recordUsage(Long userId, Long sessionId, TokenUsageType type, ModelCallResult<String> result) {
        TokenUsageRecorder recorder = tokenUsageRecorderProvider == null
                ? null : tokenUsageRecorderProvider.getIfAvailable();
        if (recorder == null) {
            return;
        }
        recorder.record(new TokenUsageRecord(result.model(), userId == null ? "user:system" : "user:" + userId,
                String.valueOf(sessionId), null, type, result.promptTokens(), result.completionTokens(),
                result.totalTokens(), true));
    }
}
