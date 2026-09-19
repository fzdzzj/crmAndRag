package com.slz.crm.knowledge.retrieval;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/** 知识库候选片段的 BM25 评分器；中文按 bigram，英文和数字按连续 token。 */
@Service
public class Bm25Scorer {
  private static final double K1 = 1.2;
  private static final double B = 0.75;

  /** 对一组候选文本计算 BM25 分数，返回顺序与入参一致。 */
  public List<Double> score(String query, List<String> documents) {
    List<String> queryTokens = tokenize(query);
    if (queryTokens.isEmpty() || documents == null || documents.isEmpty()) {
      return java.util.Collections.nCopies(documents == null ? 0 : documents.size(), 0.0);
    }

    List<Map<String, Integer>> termFrequencies = new ArrayList<>(documents.size());
    List<Integer> documentLengths = new ArrayList<>(documents.size());
    Map<String, Integer> documentFrequencies = new HashMap<>();
    for (String document : documents) {
      List<String> tokens = tokenize(document);
      Map<String, Integer> frequencies = new HashMap<>();
      tokens.forEach(token -> frequencies.merge(token, 1, Integer::sum));
      termFrequencies.add(frequencies);
      documentLengths.add(tokens.size());
      queryTokens.stream()
          .distinct()
          .filter(frequencies::containsKey)
          .forEach(token -> documentFrequencies.merge(token, 1, Integer::sum));
    }

    double averageLength = documentLengths.stream().mapToInt(Integer::intValue).average().orElse(0);
    if (averageLength <= 0) {
      return java.util.Collections.nCopies(documents.size(), 0.0);
    }

    List<Double> scores = new ArrayList<>(documents.size());
    for (int index = 0; index < documents.size(); index++) {
      scores.add(
          scoreDocument(
              queryTokens,
              termFrequencies.get(index),
              documentLengths.get(index),
              averageLength,
              documentFrequencies,
              documents.size()));
    }
    return scores;
  }

  public List<String> tokenize(String text) {
    if (text == null || text.isBlank()) {
      return List.of();
    }
    List<String> tokens = new ArrayList<>();
    StringBuilder latinToken = new StringBuilder();
    StringBuilder cjkToken = new StringBuilder();
    text.toLowerCase(Locale.ROOT)
        .codePoints()
        .forEach(
            codePoint -> {
              if (isLatinOrDigit(codePoint)) {
                flushCjkToken(cjkToken, tokens);
                latinToken.appendCodePoint(codePoint);
              } else if (isCjk(codePoint)) {
                flushLatinToken(latinToken, tokens);
                cjkToken.appendCodePoint(codePoint);
              } else {
                flushLatinToken(latinToken, tokens);
                flushCjkToken(cjkToken, tokens);
              }
            });
    flushLatinToken(latinToken, tokens);
    flushCjkToken(cjkToken, tokens);
    return tokens;
  }

  private double scoreDocument(
      List<String> queryTokens,
      Map<String, Integer> termFrequencies,
      int documentLength,
      double averageLength,
      Map<String, Integer> documentFrequencies,
      int documentCount) {
    if (documentLength <= 0) {
      return 0.0;
    }
    double normalization = K1 * (1.0 - B + B * documentLength / averageLength);
    double score = 0.0;
    for (String token : queryTokens) {
      int frequency = termFrequencies.getOrDefault(token, 0);
      if (frequency == 0) {
        continue;
      }
      int documentFrequency = documentFrequencies.getOrDefault(token, 0);
      if (documentFrequency == 0) {
        continue;
      }
      double idf =
          Math.log1p((documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5));
      score += idf * (frequency * (K1 + 1.0)) / (frequency + normalization);
    }
    return score;
  }

  private void flushLatinToken(StringBuilder latinToken, List<String> tokens) {
    if (latinToken.isEmpty()) {
      return;
    }
    tokens.add(latinToken.toString());
    latinToken.setLength(0);
  }

  private void flushCjkToken(StringBuilder cjkToken, List<String> tokens) {
    if (cjkToken.isEmpty()) {
      return;
    }
    int[] codePoints = cjkToken.toString().codePoints().toArray();
    if (codePoints.length == 1) {
      tokens.add(new String(codePoints, 0, 1));
    } else {
      for (int index = 0; index < codePoints.length - 1; index++) {
        tokens.add(new String(codePoints, index, 2));
      }
    }
    cjkToken.setLength(0);
  }

  private boolean isLatinOrDigit(int codePoint) {
    return (codePoint >= 'a' && codePoint <= 'z') || (codePoint >= '0' && codePoint <= '9');
  }

  private boolean isCjk(int codePoint) {
    Character.UnicodeScript script = Character.UnicodeScript.of(codePoint);
    return script == Character.UnicodeScript.HAN
        || script == Character.UnicodeScript.HIRAGANA
        || script == Character.UnicodeScript.KATAKANA
        || script == Character.UnicodeScript.HANGUL;
  }
}
