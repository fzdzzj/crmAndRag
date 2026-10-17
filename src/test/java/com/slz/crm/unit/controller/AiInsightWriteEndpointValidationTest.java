package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.AiInsightController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** AiInsightController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("AiInsightController 写端点非法请求体统一 50002 接管")
class AiInsightWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({"POST, /ai/insight/customer/1/to-action, false"})
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new AiInsightController()), method, url, malformedBody);
  }
}
