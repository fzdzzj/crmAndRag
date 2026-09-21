package com.slz.crm.knowledge.retrieval;

import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.ModelCallOptions;
import com.slz.crm.platform.contract.ModelCallResult;
import com.slz.crm.platform.contract.ModelProvider;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
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
import org.springframework.stereotype.Service;

/**
 * LLM 重排器（方案08升级可选档，complete-hybrid-retrieval-and-rerank 任务 4.2）。
 *
 * <p>启用：DynamicConfig {@code rag.retrieval.rerank.mode = llm}（默认 default，即 {@link
 * DefaultWeightedReranker}）。对融合后候选做 listwise 排序——经 {@code ModelProvider} + 中立 {@code
 * ModelCallOptions} 调用（默认关闭，启用才产生 LLM 开销）。
 *
 * <p>回退语义（任务 4.2 三条路径，全部回退默认重排链、不向调用方抛错）： 调用失败（异常）/ 输出为空或无法解析 / 等待超时 （{@code
 * rag.retrieval.rerank.llm.timeout-ms}，默认 3000）。
 *
 * <p>分数语义：按 LLM 名次折算单调递减分 {@code (N - 名次) / N}，仅用于排序与引用相关度展示。
 */
@Service
public class LlmReranker implements Reranker {
  private static final Logger log = LoggerFactory.getLogger(LlmReranker.class);

  private static final String SYSTEM_PROMPT =
      """
            你是 CRM 知识库检索重排器。根据查询与候选片段的相关性，把全部候选按相关性从高到低排序。
            只输出一个 JSON 数组，按排序后的候选编号（从 1 开始）给出，如 [3,1,2]。不要输出解释。
            """;
  private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
  private static final int DEFAULT_MAX_CANDIDATES = 20;
  private static final int DEFAULT_TIMEOUT_MS = 3000;

  private final ModelProvider modelProvider;
  private final DefaultWeightedReranker fallbackReranker;
  private final ObjectProvider<DynamicConfigService> dynamicConfigProvider;

  public LlmReranker(
      ModelProvider modelProvider,
      DefaultWeightedReranker fallbackReranker,
      ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
    this.modelProvider = modelProvider;
    this.fallbackReranker = fallbackReranker;
    this.dynamicConfigProvider = dynamicConfigProvider;
  }

  @Override
  public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
    List<RetrievalCandidate> result;
    if (candidates == null || candidates.isEmpty()) {
      result = List.of();
    } else {
      int maxCandidates = resolveMaxCandidates();
      List<RetrievalCandidate> head =
          candidates.size() <= maxCandidates ? candidates : candidates.subList(0, maxCandidates);
      List<RetrievalCandidate> tail =
          candidates.size() <= maxCandidates
              ? List.of()
              : candidates.subList(maxCandidates, candidates.size());
      try {
        int[] order = parseOrder(callModel(query, head), head.size());
        if (order == null) {
          result = fallbackReranker.rerank(query, candidates);
        } else {
          List<RetrievalCandidate> reordered = new ArrayList<>(candidates.size());
          for (int index : order) {
            reordered.add(head.get(index));
          }
          reordered.addAll(tail);
          result = scoreByPosition(reordered);
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        log.warn("LLM 重排被中断，回退默认重排链: {}", exception.getMessage());
        result = fallbackReranker.rerank(query, candidates);
      } catch (Exception exception) {
        log.warn("LLM 重排失败，回退默认重排链: {}", exception.getMessage());
        result = fallbackReranker.rerank(query, candidates);
      }
    }
    return result;
  }

  /** listwise 排序调用：带超时护栏（超时走 catch 回退），候选项过多时只精排头部。 */
  private String callModel(String query, List<RetrievalCandidate> head) throws Exception {
    StringBuilder userContent = new StringBuilder("查询：").append(query.strip()).append("\n候选片段：\n");
    for (int index = 0; index < head.size(); index++) {
      String text = head.get(index).hit().text();
      userContent
          .append(index + 1)
          .append(". ")
          .append(text == null ? "" : text.strip())
          .append('\n');
    }
    List<Message> messages =
        List.of(new SystemMessage(SYSTEM_PROMPT), new UserMessage(userContent.toString()));
    ModelCallOptions options = new ModelCallOptions(null, false, 0.0d, 512, null, null, Map.of());
    CompletableFuture<String> future =
        CompletableFuture.supplyAsync(
            () -> {
              ModelCallResult<String> result = modelProvider.chat(new Prompt(messages), options);
              return result == null ? null : result.content();
            });
    return future.get(resolveTimeoutMs(), TimeUnit.MILLISECONDS);
  }

  /** 解析 "[3,1,2]" 型输出为 0 基下标顺序：非法/越界编号忽略；解析不出任何编号 → null（回退）； 部分缺失的候选按原顺序补在尾部，保证候选全集不丢。 */
  private int[] parseOrder(String output, int size) {
    int[] result = null;
    if (output != null && !output.isBlank()) {
      Set<Integer> indices = new LinkedHashSet<>();
      Matcher matcher = NUMBER_PATTERN.matcher(output);
      while (matcher.find() && indices.size() < size) {
        int index = Integer.parseInt(matcher.group()) - 1;
        if (index >= 0 && index < size) {
          indices.add(index);
        }
      }
      if (!indices.isEmpty()) {
        for (int index = 0; index < size; index++) {
          indices.add(index);
        }
        result = indices.stream().mapToInt(Integer::intValue).toArray();
      }
    }
    return result;
  }

  /** 名次折算单调递减分：(N - 名次) / N，名次 1 起。 */
  private List<RetrievalCandidate> scoreByPosition(List<RetrievalCandidate> reordered) {
    int size = reordered.size();
    List<RetrievalCandidate> scored = new ArrayList<>(size);
    for (int position = 0; position < size; position++) {
      scored.add(
          new RetrievalCandidate(reordered.get(position).hit(), (double) (size - position) / size));
    }
    return scored;
  }

  private int resolveMaxCandidates() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Integer configured =
        config == null
            ? null
            : config.get(
                "rag.retrieval.rerank.llm.max-candidates", Integer.class, DEFAULT_MAX_CANDIDATES);
    return configured == null || configured < 1 ? DEFAULT_MAX_CANDIDATES : configured;
  }

  /** 超时上限（protected 便于测试覆写短超时）。 */
  protected long resolveTimeoutMs() {
    DynamicConfigService config = dynamicConfigProvider.getIfAvailable();
    Long configured =
        config == null
            ? null
            : config.get(
                "rag.retrieval.rerank.llm.timeout-ms", Long.class, (long) DEFAULT_TIMEOUT_MS);
    return configured == null || configured < 1 ? DEFAULT_TIMEOUT_MS : configured;
  }
}
