package com.slz.crm.knowledge.retrieval;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 确定性规则上下文压缩器（方案10默认实现，add-context-compression-and-enrichment 任务 2.1）。
 *
 * <p>无模型调用、行为可复现，三步压缩：
 *
 * <ol>
 *   <li>连续重复句去重（切片正文常见的重复段落/表头行）；
 *   <li>按各编号段 token 占比分配预算，句级筛选——首末句无条件保留， 承重句（含数字、序号条目、定义冒号、"第X条"类条款引用）优先装填；
 *   <li>仍超预算时从最长段逐句丢弃非首句，直到达标；各段只剩首句仍超预算时 完整性优先（保留全部编号段）、接受超预算并告警。
 * </ol>
 *
 * <p>编号解析防破坏：仅当行首 {@code [n]} 的 n 恰好等于前段号+1（首段必须为 1）才视为段头， 正文里恰好出现在行首的 {@code [7]} 类文本不会被打断成新段，编号与
 * sources 映射不因压缩改变。
 */
@Service
public class RuleContextCompressor implements Compressor {
  private static final Logger LOG = LoggerFactory.getLogger(RuleContextCompressor.class);

  private static final Pattern SECTION_HEADER = Pattern.compile("^\\[(\\d{1,4})\\]\\s?");

  /** 编号段：header 为行首 {@code [n]} 头（含紧随空白），sentences 为段内句子列表（含定界符）。 */
  private record Section(String header, List<String> sentences) {}

  /** 解析产物：prefix 为首段前的内容（本管线格式下通常为空），sections 为连续编号段。 */
  private record Parsed(String prefix, List<Section> sections) {}

  @Override
  public String compress(String context, int tokenBudget) {
    String result;
    if (context == null
        || context.isEmpty()
        || tokenBudget <= 0
        || TokenEstimator.estimate(context) <= tokenBudget) {
      result = context;
    } else {
      Parsed parsed = parse(context);
      String joined;
      if (parsed.sections().isEmpty()) {
        result = context;
      } else {
        dedupConsecutiveSentences(parsed.sections());
        joined = join(parsed);
        if (TokenEstimator.estimate(joined) <= tokenBudget) {
          result = joined;
        } else {
          selectSentencesByBudget(parsed, tokenBudget);
          joined = join(parsed);
          if (TokenEstimator.estimate(joined) <= tokenBudget) {
            result = joined;
          } else {
            trimToBudget(parsed, tokenBudget);
            result = join(parsed);
          }
        }
      }
    }
    return result;
  }

  // ---------------------------------------------------------------- 解析与拼装

  private Parsed parse(String context) {
    StringBuilder prefix = new StringBuilder();
    List<String> headers = new ArrayList<>();
    List<StringBuilder> bodies = new ArrayList<>();
    StringBuilder current = null;
    for (String line : splitLines(context)) {
      Matcher matcher = SECTION_HEADER.matcher(line);
      int expected = headers.size() + 1;
      if (matcher.find() && Integer.parseInt(matcher.group(1)) == expected) {
        headers.add(line.substring(0, matcher.end()));
        current = new StringBuilder(line.substring(matcher.end()));
        bodies.add(current);
      } else if (current == null) {
        prefix.append(line);
      } else {
        current.append(line);
      }
    }
    List<Section> sections = new ArrayList<>(headers.size());
    for (int index = 0; index < headers.size(); index++) {
      sections.add(new Section(headers.get(index), splitSentences(bodies.get(index).toString())));
    }
    return new Parsed(prefix.toString(), sections);
  }

  /** 还原为编号上下文：prefix 原样 + 每段 header 前保证换行（压缩裁句可能吃掉段尾换行）+ 段内句子按原顺序拼接。 */
  private String join(Parsed parsed) {
    StringBuilder builder = new StringBuilder(parsed.prefix());
    for (Section section : parsed.sections()) {
      if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
        builder.append('\n');
      }
      builder.append(section.header());
      for (String sentence : section.sentences()) {
        builder.append(sentence);
      }
    }
    return builder.toString();
  }

  private List<String> splitLines(String text) {
    List<String> lines = new ArrayList<>();
    int start = 0;
    for (int index = 0; index < text.length(); index++) {
      if (text.charAt(index) == '\n') {
        lines.add(text.substring(start, index + 1));
        start = index + 1;
      }
    }
    if (start < text.length()) {
      lines.add(text.substring(start));
    }
    return lines;
  }

  private List<String> splitSentences(String body) {
    List<String> sentences = new ArrayList<>();
    for (String part : body.split("(?<=[。！？；!?;\\n])")) {
      if (!part.isBlank()) {
        sentences.add(part);
      }
    }
    return sentences;
  }

  // ---------------------------------------------------------------- 压缩步骤

  /** 步骤1：连续重复句去重（内容 strip 后相同视为重复，空句不去）。 */
  private void dedupConsecutiveSentences(List<Section> sections) {
    for (int index = 0; index < sections.size(); index++) {
      Section section = sections.get(index);
      List<String> kept = new ArrayList<>(section.sentences().size());
      String previous = null;
      for (String sentence : section.sentences()) {
        String normalized = sentence.strip();
        if (!normalized.isEmpty() && normalized.equals(previous)) {
          continue;
        }
        kept.add(sentence);
        previous = normalized;
      }
      sections.set(index, new Section(section.header(), kept));
    }
  }

  /** 步骤2：按段 token 占比分配预算做句级筛选。 */
  private void selectSentencesByBudget(Parsed parsed, int tokenBudget) {
    List<Section> sections = parsed.sections();
    int fixedTokens = TokenEstimator.estimate(parsed.prefix());
    long totalBody = 0;
    long[] bodyTokens = new long[sections.size()];
    for (int index = 0; index < sections.size(); index++) {
      bodyTokens[index] = tokensOf(sections.get(index).sentences());
      totalBody += bodyTokens[index];
      fixedTokens += TokenEstimator.estimate(sections.get(index).header());
    }
    long bodyBudget = Math.max(0, tokenBudget - fixedTokens);
    for (int index = 0; index < sections.size(); index++) {
      long target = totalBody == 0 ? 0 : bodyBudget * bodyTokens[index] / totalBody;
      sections.set(
          index,
          new Section(
              sections.get(index).header(),
              selectSentences(sections.get(index).sentences(), target)));
    }
  }

  /**
   * 单段句级筛选：首句、末句无条件保留；中间句装得下段内预算就保留（原文尽量少丢）， 装不下时仅承重句继续保留（允许超支，由步骤3统一兜底收敛）。
   * 保留句按原顺序拼回（首句…保留的中间句…末句）。
   */
  private List<String> selectSentences(List<String> sentences, long target) {
    int size = sentences.size();
    List<String> kept;
    if (size == 0) {
      kept = sentences;
    } else {
      String first = sentences.get(0);
      String last = size > 1 ? sentences.get(size - 1) : null;
      long used =
          TokenEstimator.estimate(first) + (last == null ? 0 : TokenEstimator.estimate(last));
      long middleBudget = Math.max(0, target - used);
      kept = new ArrayList<>(size);
      kept.add(first);
      for (int index = 1; index < size - 1; index++) {
        String sentence = sentences.get(index);
        long cost = TokenEstimator.estimate(sentence);
        if (cost <= middleBudget || LoadBearingSentences.isLoadBearing(sentence)) {
          kept.add(sentence);
          middleBudget -= cost;
        }
      }
      if (last != null) {
        kept.add(last);
      }
    }
    return kept;
  }

  /** 步骤3：从当前最长段丢弃末句（非首句），直到达标；全部只剩首句仍超预算则接受并告警。 */
  private void trimToBudget(Parsed parsed, int tokenBudget) {
    List<Section> sections = parsed.sections();
    while (TokenEstimator.estimate(join(parsed)) > tokenBudget) {
      int victim = -1;
      long largest = 0;
      for (int index = 0; index < sections.size(); index++) {
        List<String> sentences = sections.get(index).sentences();
        if (sentences.size() <= 1) {
          continue;
        }
        long tokens = tokensOf(sentences);
        if (tokens > largest) {
          largest = tokens;
          victim = index;
        }
      }
      if (victim < 0) {
        LOG.warn("上下文压缩达编号保底下限仍超预算 budget={}，完整性优先保留全部编号段", tokenBudget);
        return;
      }
      List<String> sentences = new ArrayList<>(sections.get(victim).sentences());
      sentences.remove(sentences.size() - 1);
      sections.set(victim, new Section(sections.get(victim).header(), sentences));
    }
  }

  // ---------------------------------------------------------------- 承重句判定

  private long tokensOf(List<String> sentences) {
    long total = 0;
    for (String sentence : sentences) {
      total += TokenEstimator.estimate(sentence);
    }
    return total;
  }
}
