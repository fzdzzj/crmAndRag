package com.slz.crm.server.ai;

import com.slz.crm.platform.contract.SourceReference;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * 确定性规则反思器（add-self-rag-reflection 任务 1.1）。
 *
 * <p>零模型调用、确定性校验三类引用并剥除/软降级：
 *
 * <ul>
 *   <li>引用编号越界：编号 &lt; 1 或 &gt; sources.size()；
 *   <li>指向空块：对应片段 excerpt 为 null 或空白；
 *   <li>引用与命中源不匹配：子句与对应片段无可靠支撑（支撑分 &lt; KEEP_MIN 0.12）。
 * </ul>
 *
 * <p>软降级处置（D2）：存在无据断言/剥除引用时，保留答案文本，并在末尾追加尾注 {@link #UNSUPPORTED_CLAIM_NOTE}。
 */
@Service
public class RuleSelfRagReflector implements SelfRagReflector {

  private static final Pattern CITATION_PATTERN = Pattern.compile("\\[(\\d{1,3})]");

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
    Map<Integer, String> citationClauses = extractClauses(safeAnswer);
    List<Integer> targetCitations =
        initialCitations != null ? initialCitations : List.copyOf(citationClauses.keySet());

    LinkedHashSet<Integer> validCitations = new LinkedHashSet<>();
    boolean stripped = filterCitations(targetCitations, citationClauses, sources, validCitations);

    if (!stripped && initialCitations != null) {
      stripped = hasUnverifiedKeys(citationClauses, validCitations);
    }

    String finalAnswer = safeAnswer;
    if (stripped && !safeAnswer.isBlank() && !safeAnswer.contains("未获知识库直接支持")) {
      finalAnswer = safeAnswer + UNSUPPORTED_CLAIM_NOTE;
    }
    return new SelfRagResult(finalAnswer, List.copyOf(validCitations), false);
  }

  private boolean filterCitations(
      List<Integer> targetCitations,
      Map<Integer, String> citationClauses,
      List<SourceReference> sources,
      LinkedHashSet<Integer> validCitations) {
    boolean stripped = false;
    for (Integer citation : targetCitations) {
      if (citation != null && isValid(citation, citationClauses.get(citation), sources)) {
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

  private boolean isValid(int citation, String clause, List<SourceReference> sources) {
    boolean valid = false;
    if (citation >= 1 && citation <= sources.size()) {
      SourceReference source = sources.get(citation - 1);
      if (source != null && source.excerpt() != null && !source.excerpt().isBlank()) {
        valid = isClauseSupported(clause, source.excerpt());
      }
    }
    return valid;
  }

  private boolean isClauseSupported(String clause, String excerpt) {
    boolean supported = true;
    if (clause != null && !clause.isBlank()) {
      double score = CitationSupport.supportScore(clause, excerpt);
      supported = score >= CitationAligner.KEEP_MIN;
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
