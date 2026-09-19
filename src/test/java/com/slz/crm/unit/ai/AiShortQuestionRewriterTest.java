package com.slz.crm.unit.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.slz.crm.pojo.entity.AiConversationMemoryEntity;
import com.slz.crm.pojo.entity.AiMessageEntity;
import com.slz.crm.server.ai.AiShortQuestionRewriter;
import org.junit.jupiter.api.Test;

/** 短问题纯规则改写与锚点降级验证。 */
class AiShortQuestionRewriterTest {

  private final AiShortQuestionRewriter rewriter = new AiShortQuestionRewriter();

  @Test
  void followup_usesIntentFirst() {
    AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
    memory.setIntent("跟进客户A合同进展");
    memory.setFacts("[\"客户A合同金额120万\"]");

    var result = rewriter.rewrite("它现在怎么样", memory, lastUser("客户A合同进展如何"));

    assertThat(result.query()).isEqualTo("跟进客户A合同进展：它现在怎么样");
    assertThat(result.clarifyRequired()).isFalse();
  }

  @Test
  void reference_usesFactWhenIntentAbsent() {
    AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
    memory.setIntent("");
    memory.setFacts("[\"客户A合同金额120万\"]");

    var result = rewriter.rewrite("该合同何时交付", memory, lastUser("客户A合同何时交付"));

    assertThat(result.query()).isEqualTo("客户A合同金额120万：该合同何时交付");
  }

  @Test
  void shortQuestion_usesRecentQuestionBeforeSummary() {
    AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
    memory.setFacts("[]");
    memory.setSummary("客户A正在评估CRM采购预算");

    var result = rewriter.rewrite("预算多少", memory, lastUser("客户A正在看哪款CRM"));

    assertThat(result.query()).isEqualTo("客户A正在看哪款CRM：预算多少");
  }

  @Test
  void shortQuestion_usesSummaryWhenNoBetterAnchor() {
    AiConversationMemoryEntity memory = new AiConversationMemoryEntity();
    memory.setSummary("客户A正在评估CRM采购预算");

    var result = rewriter.rewrite("继续", memory, null);

    assertThat(result.query()).isEqualTo("客户A正在评估CRM采购预算：继续");
  }

  @Test
  void longIndependentQuestion_remainsUnchanged() {
    AiConversationMemoryEntity memory = new AiConversationMemoryEntity();

    var result = rewriter.rewrite("请帮我统计本月新增客户的总数量和转化率", memory, lastUser("客户A合同进展如何"));

    assertThat(result.query()).isEqualTo("请帮我统计本月新增客户的总数量和转化率");
    assertThat(result.clarifyRequired()).isFalse();
  }

  @Test
  void vagueQuestionWithoutAnchor_requestsClarification() {
    var result = rewriter.rewrite("它呢", null, null);

    assertThat(result.query()).isEqualTo("它呢");
    assertThat(result.clarifyRequired()).isTrue();
  }

  private AiMessageEntity lastUser(String content) {
    AiMessageEntity message = new AiMessageEntity();
    message.setRole("user");
    message.setContent(content);
    return message;
  }
}
