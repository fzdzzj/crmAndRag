package com.slz.crm.unit.knowledge.retrieval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slz.crm.knowledge.retrieval.KbRetrievalStrategyWhitelist;
import com.slz.crm.platform.config.ConfigValueType;
import com.slz.crm.platform.config.DynamicConfigKeyRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * per-KB 覆盖键白名单门禁（add-per-kb-retrieval-strategy-override 任务 2.2，红锚转绿）。
 *
 * <p>覆盖白名单封闭集 + 类型/范围/枚举校验（复用 {@link DynamicConfigKeyRegistry} 语义）： 白名单外键拒绝、类型不匹配拒绝、越界拒绝；12 键必须与
 * proposal §1 清单逐字一致。
 */
class KbRetrievalStrategyWhitelistTest {

  private final DynamicConfigKeyRegistry registry =
      new DynamicConfigKeyRegistry(new ObjectMapper());
  private final KbRetrievalStrategyWhitelist whitelist = new KbRetrievalStrategyWhitelist(registry);

  /** proposal §1 白名单全集（v1 恰 12 键，封闭集）。 */
  private static final List<String> EXPECTED =
      List.of(
          "rag.retrieval.topK",
          "rag.retrieval.minScore",
          "rag.retrieval.fusion.mode",
          "rag.retrieval.fusion.rrf-k",
          "rag.retrieval.rerank.vector-weight",
          "rag.retrieval.rerank.bm25-weight",
          "rag.retrieval.rerank.candidate-multiplier",
          "rag.retrieval.image-text-route-weight",
          "rag.retrieval.image-vector-route-weight",
          "rag.context.neighbors",
          "rag.context.parent-expand",
          "rag.retrieval.query-rewrite.enabled");

  @Test
  @DisplayName("白名单必须恰 12 键且与 proposal §1 清单逐字一致（封闭集）")
  void whitelistIsExactlyTwelveCrosskeyClosedSet() {
    assertEquals(12, KbRetrievalStrategyWhitelist.keys().size(), "白名单必须恰 12 键（封闭集）");
    assertEquals(EXPECTED, KbRetrievalStrategyWhitelist.keys(), "白名单与 proposal §1 清单逐字一致");
    for (String key : EXPECTED) {
      assertTrue(KbRetrievalStrategyWhitelist.isWhitelisted(key), key + " 应在白名单内");
    }
  }

  @Test
  @DisplayName("白名单外键拒绝写入（成本类/结构类永不进入 per-KB 覆盖）")
  void outOfWhitelistRejected() {
    // 成本类键（proposal §1 明列永不进入 per-KB 覆盖）
    for (String key :
        List.of(
            "rag.retrieval.rerank.mode",
            "rag.retrieval.compressor.mode",
            "rag.retrieval.multi-query.enabled",
            "rag.retrieval.hyde.enabled",
            "rag.retrieval.derived-questions.enabled",
            "rag.retrieval.vision-pdf.enabled")) {
      assertFalse(KbRetrievalStrategyWhitelist.isWhitelisted(key), key + " 不得进入白名单");
      ConfigValueType.Parsed parsed = whitelist.validate(key, "x");
      assertFalse(parsed.valid(), key + " 写入必须被拒");
      assertTrue(parsed.errorMessage().contains("不在 per-KB 覆盖白名单"), key + " 拒绝信息应可读");
    }
    // 结构类键（chunking.* / chunkSize / chunkOverlap）
    for (String key :
        List.of("rag.chunking.strategy", "rag.retrieval.chunkSize", "rag.retrieval.chunkOverlap")) {
      assertFalse(KbRetrievalStrategyWhitelist.isWhitelisted(key), key + " 不得进入白名单");
    }
  }

  @Test
  @DisplayName("类型不匹配拒绝（topK 传非整数 / query-rewrite.enabled 传非布尔）")
  void typeMismatchRejected() {
    assertFalse(whitelist.validate("rag.retrieval.topK", "abc").valid(), "topK 非整数必须拒");
    assertFalse(
        whitelist.validate("rag.retrieval.query-rewrite.enabled", "not-a-bool").valid(),
        "布尔传非布尔必须拒");
    assertFalse(
        whitelist.validate("rag.context.parent-expand", "anything-else").valid(), "枚举外值必须拒");
  }

  @Test
  @DisplayName("越界拒绝（topK 0/minScore 1.5/fusion.mode 未知值）")
  void outOfRangeRejected() {
    assertFalse(whitelist.validate("rag.retrieval.topK", "0").valid(), "topK=0 必须拒（合法 1~100）");
    assertFalse(whitelist.validate("rag.retrieval.topK", "101").valid(), "topK=101 必须拒");
    assertFalse(
        whitelist.validate("rag.retrieval.minScore", "1.5").valid(), "minScore=1.5 必须拒（0~1）");
    assertFalse(
        whitelist.validate("rag.retrieval.fusion.mode", "unknown").valid(), "fusion.mode 未知枚举必须拒");
  }

  @Test
  @DisplayName("合法覆盖通过并给出规范化值（topK=8 / fusion.mode=weighted / query-rewrite.enabled=false）")
  void validOverridesAccepted() {
    ConfigValueType.Parsed topK = whitelist.validate("rag.retrieval.topK", "8");
    assertTrue(topK.valid(), "topK=8 应合法");
    assertEquals("8", topK.canonical());

    ConfigValueType.Parsed mode = whitelist.validate("rag.retrieval.fusion.mode", "weighted");
    assertTrue(mode.valid(), "fusion.mode=weighted 应合法");
    assertEquals(8, topK.typed());

    ConfigValueType.Parsed enabled =
        whitelist.validate("rag.retrieval.query-rewrite.enabled", "false");
    assertTrue(enabled.valid(), "query-rewrite.enabled=false 应合法");
    assertEquals(Boolean.FALSE, enabled.typed());
  }
}
