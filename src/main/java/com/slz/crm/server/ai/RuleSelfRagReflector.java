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
      String safeAnswer = answer == null ? "" : answer;
      Map<Integer, String> citationClauses = extractClauses(safeAnswer);

      List<Integer> targetCitations;
      if (initialCitations != null) {
        targetCitations = initialCitations;
      } else {
        targetCitations = List.copyOf(citationClauses.keySet());
      }

      LinkedHashSet<Integer> validCitations = new LinkedHashSet<>();
      boolean stripped = false;

      for (Integer citation : targetCitations) {
        if (citation != null) {
          if (isValid(citation, citationClauses.get(citation), sources)) {
            validCitations.add(citation);
          } else {
            stripped = true;
          }
        }
      }

      if (!stripped && initialCitations != null) {
        for (Integer key : citationClauses.keySet()) {
          if (!validCitations.contains(key)) {
            stripped = true;
            break;
          }
        }
      }

      String finalAnswer = safeAnswer;
      if (stripped && !safeAnswer.isBlank() && !safeAnswer.contains("未获知识库直接支持")) {
        finalAnswer = safeAnswer + UNSUPPORTED_CLAIM_NOTE;
      }

      result = new SelfRagResult(finalAnswer, List.copyOf(validCitations), false);
    }
    return result;
  }

  private boolean isValid(int citation, String clause, List<SourceReference> sources) {
    boolean valid = false;
    if (citation >= 1 && citation <= sources.size()) {
      SourceReference source = sources.get(citation - 1);
      if (source != null && source.excerpt() != null && !source.excerpt().isBlank()) {
        if (clause == null || clause.isBlank()) {
          valid = true;
        } else {
          double score = CitationSupport.supportScore(clause, source.excerpt());
          valid = score >= CitationAligner.KEEP_MIN;
        }
      }
    }
    return valid;
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
