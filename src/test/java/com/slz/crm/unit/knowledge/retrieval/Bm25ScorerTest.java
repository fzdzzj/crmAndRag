package com.slz.crm.unit.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.knowledge.retrieval.Bm25Scorer;
import java.util.List;
import org.junit.jupiter.api.Test;

/** BM25 中文 bigram 与英文 token 评分测试。 */
class Bm25ScorerTest {
  private final Bm25Scorer scorer = new Bm25Scorer();

  @Test
  void relevantDocumentShouldScoreHigher() {
    List<Double> scores = scorer.score("客户合同", List.of("客户合同流程包含签约和回款。", "部门数据范围说明。"));

    assertEquals(2, scores.size());
    assertTrue(scores.getFirst() > scores.getLast());
  }

  @Test
  void tokenizerShouldSupportLatinAndCjk() {
    assertEquals(List.of("crm", "客户", "户合", "合同", "123"), scorer.tokenize("CRM客户合同123"));
  }
}
