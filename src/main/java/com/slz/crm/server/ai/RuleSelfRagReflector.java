package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.DynamicConfigService;
import com.slz.crm.platform.contract.SourceReference;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 确定性规则反思器（add-self-rag-reflection 任务 1.1）。
 *
 * <p>零模型调用、确定性校验三类引用并剥除/软降级：
 *
 * <ul>
 *   <li>引用编号越界：编号 &lt; 1 或 &gt; sources.size()；
 *   <li>指向空块：对应片段 excerpt 为 null 或空白；
 *   <li>引用与命中源不匹配：子句与对应片段无可靠支撑（支撑分 &lt; 生效的 keepMin，默认 0.12）。
 * </ul>
 *
 * <p>软降级处置（D2）：存在无据断言/剥除引用时，保留答案文本，并在末尾追加尾注 {@link #UNSUPPORTED_CLAIM_NOTE}。
 *
 * <p>阈值来源（fix-backlog-batch2 ⑫）：不再直读编译期 {@link CitationAligner#KEEP_MIN}，改由 {@link
 * CitationThresholdsResolver} 经 {@code Supplier<CitationThresholds>} 注入——与 {@code
 * AiChatStreamFinalizer} 同一范式，每次反思实时解析，运营改配置即热生效； 未配置/无动态配置服务时逐键回落 {@link
 * CitationThresholds#defaults()}，行为与升级前逐字节等价。核心逻辑仍零 Spring 依赖：类里只有 一个 {@code
 * java.util.function.Supplier} 字段，测试与旁路场景用无参构造即可。
 */
@Service
public class RuleSelfRagReflector implements SelfRagReflector {

  private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d{1,3})]");

  /** 生效阈值的来源；默认 {@link CitationThresholds#defaults()}（= 原编译期常量）。 */
  private final Supplier<CitationThresholds> thresholdsSupplier;

  /** 零 Spring 构造（测试/旁路）：阈值恒为 {@link CitationThresholds#defaults()}。 */
  public RuleSelfRagReflector() {
    this(CitationThresholds::defaults);
  }

  /** 显式阈值来源构造（宿主/测试传入；{@code null} 等同默认）。 */
  RuleSelfRagReflector(Supplier<CitationThresholds> thresholdsSupplier) {
    this.thresholdsSupplier =
        thresholdsSupplier == null ? CitationThresholds::defaults : thresholdsSupplier;
  }

  /** Spring 装配构造：经同包解析器读取 {@code rag.retrieval.citation.*}，无配置时逐键回落默认。 */
  @Autowired
  public RuleSelfRagReflector(ObjectProvider<DynamicConfigService> dynamicConfigProvider) {
    this(
        () ->
            CitationThresholdsResolver.resolve(
                dynamicConfigProvider == null ? null : dynamicConfigProvider.getIfAvailable()));
  }

  @Override
  public SelfRagResult reflect(
      String answer, List<SourceReference> sources, List<Integer> initialCitations) {
    SelfRagResult result;
    if (sources == null || sources.isEmpty()) {
      result = handleNoSources(answer, initialCitations);
    } else {
      result = handleWithSources(answer, sources, initialCitations);
    }
    return result;
  }

  private SelfRagResult handleWithSources(
      String answer, List<SourceReference> sources, List<Integer> initialCitations) {
    String safeAnswer = answer == null ? "" : answer;
    CitationThresholds thresholds = resolveThresholds();
    Map<Integer, String> citationClauses = extractClauses(safeAnswer);
    List<Integer> targetCitations =
        initialCitations != null ? initialCitations : List.copyOf(citationClauses.keySet());

    LinkedHashSet<Integer> validCitations = new LinkedHashSet<>();
    boolean stripped =
        filterCitations(targetCitations, citationClauses, sources, validCitations, thresholds);

    if (!stripped && initialCitations != null) {
      stripped = hasUnverifiedKeys(citationClauses, validCitations);
    }

    String finalAnswer = safeAnswer;
    if (stripped && !safeAnswer.isBlank() && !safeAnswer.contains("未获知识库直接支持")) {
      finalAnswer = safeAnswer + UNSUPPORTED_CLAIM_NOTE;
    }
    return new SelfRagResult(finalAnswer, List.copyOf(validCitations), false);
  }

  /** 每次反思实时解析一次（同一答案内保持一致）；来源为 null 时回落默认阈值。 */
  private CitationThresholds resolveThresholds() {
    CitationThresholds resolved = thresholdsSupplier.get();
    return resolved == null ? CitationThresholds.defaults() : resolved;
  }

  private boolean filterCitations(
      List<Integer> targetCitations,
      Map<Integer, String> citationClauses,
      List<SourceReference> sources,
      LinkedHashSet<Integer> validCitations,
      CitationThresholds thresholds) {
    boolean stripped = false;
    for (Integer citation : targetCitations) {
      if (citation != null
          && isValid(citation, citationClauses.get(citation), sources, thresholds)) {
        validCitations.add(citation);
      } else {
        stripped = true;
      }
    }
    return stripped;
  }

  private boolean hasUnverifiedKeys(
      Map<Integer, String> citationClauses, LinkedHashSet<Integer> validCitations) {
    boolean missing = false;
    for (Integer key : citationClauses.keySet()) {
      if (!validCitations.contains(key)) {
        missing = true;
        break;
      }
    }
    return missing;
  }

  private boolean isValid(
      int citation, String clause, List<SourceReference> sources, CitationThresholds thresholds) {
    boolean valid = false;
    if (citation >= 1 && citation <= sources.size()) {
      SourceReference source = sources.get(citation - 1);
      if (source != null && source.excerpt() != null && !source.excerpt().isBlank()) {
        valid = isClauseSupported(clause, source.excerpt(), thresholds);
      }
    }
    return valid;
  }

  private boolean isClauseSupported(String clause, String excerpt, CitationThresholds thresholds) {
    boolean supported = true;
    if (clause != null && !clause.isBlank()) {
      double score = CitationSupport.supportScore(clause, excerpt);
      supported = score >= thresholds.keepMin();
    }
    return supported;
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

  private SelfRagResult handleNoSources(String answer, List<Integer> initialCitations) {
    String safeAnswer = answer == null ? "" : answer;
    boolean hasCitations =
        (initialCitations != null && !initialCitations.isEmpty())
            || CITATION_PATTERN.matcher(safeAnswer).find();
    String finalAnswer = safeAnswer;
    if (hasCitations && !safeAnswer.isBlank() && !safeAnswer.contains("未获知识库直接支持")) {
      finalAnswer = safeAnswer + UNSUPPORTED_CLAIM_NOTE;
    }
    return new SelfRagResult(finalAnswer, List.of(), false);
  }
}
