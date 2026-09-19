package com.slz.crm.server.ai.eval;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** 黄金用例集结构与覆盖范围验证。 */
class AiEvalCaseSetTest {

  @Test
  void cases_shouldBeUniqueAndCoverCoreToolFamilies() {
    List<AiEvalCase> cases = AiEvalCaseLoader.load();

    assertThat(cases).hasSizeGreaterThanOrEqualTo(16);
    assertThat(cases).extracting(AiEvalCase::id).doesNotHaveDuplicates();
    assertThat(cases)
        .extracting(AiEvalCase::expectedTool)
        .contains("getChartData", "queryCustomerCompany", "queryContract", "queryCompanyGroup");
    assertThat(cases)
        .extracting(AiEvalCase::expectedTool)
        .contains(
            "createCustomerDraft",
            "createContactDraft",
            "createContractDraft",
            "createPaymentDraft");
  }
}
