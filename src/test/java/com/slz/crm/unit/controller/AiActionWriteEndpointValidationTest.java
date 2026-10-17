package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.AiActionController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** AiActionController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("AiActionController 写端点非法请求体统一 50002 接管")
class AiActionWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({"PUT, /ai/actions/1/edit, false"})
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new AiActionController()), method, url, malformedBody);
  }
}
