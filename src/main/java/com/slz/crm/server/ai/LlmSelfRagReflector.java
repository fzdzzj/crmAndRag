package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import com.slz.crm.platform.contract.SourceReference;
import com.slz.crm.platform.contract.TokenUsageRecord;
import com.slz.crm.platform.contract.TokenUsageRecorder;
import com.slz.crm.platform.contract.TokenUsageType;
import com.slz.crm.platform.contract.UserContext;
import com.slz.crm.platform.contract.UserContextHolder;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * LLM 支持度自评反思器（add-self-rag-reflection 任务 2.1/2.2）。
 *
 * <p>启用条件：动态配置 {@code rag.generation.selfrag.mode = llm}（默认 rule，本档走 COST 档申请-审批流）。 对答案中的引用断言调用
 * {@link ModelProvider} 判定逐条引用是否得到检索片段支持。
 *
 * <p>失败回退链（任务 2.2）：超时/模型不可用/空输出/解析失败一律回退 {@link RuleSelfRagReflector} 规则链。
 *
 * <p>计量口径（任务 2.1）：调用挂账 {@link TokenUsageRecorder}，类型用 {@link TokenUsageType#SUMMARY} （同 LLM
 * 压缩口径，TokenUsageType 为冻结契约不新增枚举值）。
 */
@Service
public class LlmSelfRagReflector implements SelfRagReflector {

  private static final Logger LOG = LoggerFactory.getLogger(LlmSelfRagReflector.class);

  private static final String SYSTEM_PROMPT =
      """
      你是 CRM 知识库生成质量评估专家。根据给定的检索片段，判定答案中的各项引用断言是否得到对应检索片段的直接支持：
      1. 逐项核对断言内容是否真被对应编号的检索片段所支持；
      2. 只输出一个标准 JSON 对象，格式严格为：
      {"supported": [1, 2], "unsupported": [3]}
      其中 supported 为得到支持的引用编号列表，unsupported 为未得到支持的引用编号列表；
      3. 严禁输出 Markdown 标记或任何解释性文字。
      """;

  private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d{1,3})]");
  private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
  private static final int DEFAULT_TIMEOUT_MS = 3000;
  private static final int DEFAULT_MAX_CLAIMS = 20;

  private final ModelProvider modelProvider;
  private final RuleSelfRagReflector fallbackReflector;
  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;
  private final ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider;
  private final Executor llmAuxExecutor;

  public LlmSelfRagReflector(
      ModelProvider modelProvider,
      RuleSelfRagReflector fallbackReflector,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider,
      ObjectProvider<TokenUsageRecorder> tokenUsageRecorderProvider,
      @Qualifier("llmAuxTaskExecutor") Executor llmAuxExecutor) {
    this.modelProvider = modelProvider;
    this.fallbackReflector = fallbackReflector;
    this.dynamicConfigProvider = dynamicConfigProvider;
    this.tokenUsageRecorderProvider = tokenUsageRecorderProvider;
    this.llmAuxExecutor = llmAuxExecutor;
  }

  @Override
  public SelfRagResult reflect(
      String answer, List<SourceReference> sources, List<Integer> initialCitations) {
    SelfRagResult result;
    if (sources == null
        || sources.isEmpty()
        || modelProvider == null
        || answer == null
        || answer.isBlank()) {
      result = fallbackReflector.reflect(answer, sources, initialCitations);
    } else {
      result = evaluateWithLlm(answer, sources, initialCitations);
    }
    return result;
  }

  private SelfRagResult evaluateWithLlm(
      String safeAnswer, List<SourceReference> sources, List<Integer> initialCitations) {
    SelfRagResult result;
    Map<Integer, String> citationClauses = extractClauses(safeAnswer);
    List<Integer> targetCitations =
        initialCitations != null ? initialCitations : List.copyOf(citationClauses.keySet());

    if (targetCitations.isEmpty()) {
      result = fallbackReflector.reflect(safeAnswer, sources, initialCitations);
    } else {
      result =
          executeLlmEvaluation(
              safeAnswer, sources, initialCitations, targetCitations, citationClauses);
    }
    return result;
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // LLM 自评外呼多源，失败回退规则反思链
  private SelfRagResult executeLlmEvaluation(
      String safeAnswer,
      List<SourceReference> sources,
      List<Integer> initialCitations,
      List<Integer> targetCitations,
      Map<Integer, String> citationClauses) {
    SelfRagResult result;
    int maxClaims = resolveMaxClaims();
    List<Integer> claimsToEvaluate =
        targetCitations.size() <= maxClaims
            ? targetCitations
            : targetCitations.subList(0, maxClaims);

    try {
      ModelCallResult<String> callResult = callModel(claimsToEvaluate, citationClauses, sources);
      String output = callResult == null ? null : callResult.content();
      Set<Integer> supportedSet = parseSupported(output);

      if (supportedSet == null) {
        LOG.info("LLM 自评输出不可用或解析失败，回退规则反思链");
        recordUsage(callResult, false);
        result = fallbackReflector.reflect(safeAnswer, sources, initialCitations);
      } else {
        recordUsage(callResult, true);
        result = assembleResult(safeAnswer, sources, targetCitations, supportedSet);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      LOG.warn("LLM 自评被中断，回退规则反思链: {}", exception.getMessage());
      recordUsage(null, false);
      result = fallbackReflector.reflect(safeAnswer, sources, initialCitations);
    } catch (Exception exception) {
      LOG.warn("LLM 自评失败或超时，回退规则反思链: {}", exception.getMessage());
      recordUsage(null, false);
      result = fallbackReflector.reflect(safeAnswer, sources, initialCitations);
    }
    return result;
  }

  private SelfRagResult assembleResult(
      String safeAnswer,
      List<SourceReference> sources,
      List<Integer> targetCitations,
      Set<Integer> supportedSet) {
    LinkedHashSet<Integer> validCitations = new LinkedHashSet<>();
    boolean stripped = false;

    for (Integer citation : targetCitations) {
      if (citation != null && isSupportedSource(citation, sources, supportedSet)) {
        validCitations.add(citation);
      } else {
        stripped = true;
      }
    }

    String finalAnswer = safeAnswer;
    if (stripped && !finalAnswer.isBlank() && !finalAnswer.contains("未获知识库直接支持")) {
      finalAnswer = finalAnswer + UNSUPPORTED_CLAIM_NOTE;
    }

    return new SelfRagResult(finalAnswer, List.copyOf(validCitations), false);
  }

  private boolean isSupportedSource(
      int citation, List<SourceReference> sources, Set<Integer> supportedSet) {
    boolean supported = false;
    if (supportedSet.contains(citation) && citation >= 1 && citation <= sources.size()) {
      SourceReference source = sources.get(citation - 1);
      supported = source != null && source.excerpt() != null && !source.excerpt().isBlank();
    }
    return supported;
  }

  private ModelCallResult<String> callModel(
      List<Integer> claimsToEvaluate,
      Map<Integer, String> citationClauses,
      List<SourceReference> sources)
      throws Exception {
    StringBuilder userContent = new StringBuilder(1024);
    userContent.append("答案断言：\n");
    for (Integer citation : claimsToEvaluate) {
      String clause = citationClauses.get(citation);
      userContent
          .append("[")
          .append(citation)
          .append("] ")
          .append(clause == null || clause.isBlank() ? "无特定子句" : clause.strip())
          .append('\n');
    }
    userContent.append("\n检索片段：\n");
    for (int i = 0; i < sources.size(); i++) {
      SourceReference s = sources.get(i);
      String excerpt = s == null || s.excerpt() == null ? "" : s.excerpt().strip();
      userContent.append("[").append(i + 1).append("] ").append(excerpt).append('\n');
    }

    List<Message> messages =
        List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userContent.toString()));
    ModelCallOptions options = new ModelCallOptions(null, false, 0.0d, 512, null, null, Map.of());

    CompletableFuture<ModelCallResult<String>> future =
        CompletableFuture.supplyAsync(
            () -> modelProvider.chat(new Prompt(messages), options), llmAuxExecutor);
    try {
      return future.get(resolveTimeoutMs(), TimeUnit.MILLISECONDS);
    } finally {
      future.cancel(true);
    }
  }

  /**
   * 解析输出中的 supported 列表。
   *
   * @return supported 编号集合；解析失败或输出为空返回 null 触发回退
   */
  private Set<Integer> parseSupported(String output) {
    Set<Integer> result = null;
    if (output != null && !output.isBlank()) {
      int supportedIdx = output.indexOf("\"supported\"");
      if (supportedIdx >= 0) {
        int arrayStart = output.indexOf('[', supportedIdx);
        int arrayEnd = arrayStart >= 0 ? output.indexOf(']', arrayStart) : -1;
        if (arrayStart >= 0 && arrayEnd > arrayStart) {
          String arrayContent = output.substring(arrayStart + 1, arrayEnd);
          Set<Integer> supported = new LinkedHashSet<>();
          Matcher matcher = NUMBER_PATTERN.matcher(arrayContent);
          while (matcher.find()) {
            supported.add(Integer.parseInt(matcher.group()));
          }
          result = supported;
        }
      }
    }
    return result;
  }

  private Map<Integer, String> extractClauses(String answer) {
    Map<Integer, String> map = new LinkedHashMap<>();
    if (answer != null && !answer.isBlank()) {
      Matcher matcher = CITATION_PATTERN.matcher(answer);
      while (matcher.find()) {
        int citation = Integer.parseInt(matcher.group(1));
        String clause = CitationAligner.clauseAround(answer, matcher.start(), matcher.end());
        map.merge(
            citation,
            clause,
            (oldVal, newVal) -> newVal.length() > oldVal.length() ? newVal : oldVal);
      }
    }
    return map;
  }

  private void recordUsage(ModelCallResult<String> result, boolean success) {
    TokenUsageRecorder recorder =
        tokenUsageRecorderProvider == null ? null : tokenUsageRecorderProvider.getIfAvailable();
    if (recorder == null) {
      return;
    }
    String model = result == null ? "unknown" : result.model();
    recorder.record(
        new TokenUsageRecord(
            model,
            currentUserRef(),
            null,
            null,
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

  protected long resolveTimeoutMs() {
    DynamicConfigService config =
        dynamicConfigProvider == null ? null : dynamicConfigProvider.getIfAvailable();
    Long configured =
        config == null
            ? null
            : config.get(
                "rag.generation.selfrag.llm.timeout-ms", Long.class, (long) DEFAULT_TIMEOUT_MS);
    return configured == null || configured < 1 ? DEFAULT_TIMEOUT_MS : configured;
  }

  private int resolveMaxClaims() {
    DynamicConfigService config =
        dynamicConfigProvider == null ? null : dynamicConfigProvider.getIfAvailable();
    Integer configured =
        config == null
            ? null
            : config.get(
                "rag.generation.selfrag.llm.max-claims", Integer.class, DEFAULT_MAX_CLAIMS);
    return configured == null || configured < 1 ? DEFAULT_MAX_CLAIMS : configured;
  }
}
