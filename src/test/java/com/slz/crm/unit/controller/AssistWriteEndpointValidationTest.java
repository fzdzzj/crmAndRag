package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.AssistController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** AssistController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("AssistController 写端点非法请求体统一 50002 接管")
class AssistWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "PUT, /assist, false",
    "POST, /assist/reapply, false",
    "POST, /assist/append, false",
    "POST, /assist/apply?modelName=s&recordId=1, false",
    "POST, /assist/1/messages, false"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new AssistController()), method, url, malformedBody);
  }
}
