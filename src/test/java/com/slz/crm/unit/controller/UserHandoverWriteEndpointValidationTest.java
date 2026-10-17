package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.UserHandoverController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** UserHandoverController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("UserHandoverController 写端点非法请求体统一 50002 接管")
class UserHandoverWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({"POST, /user/handover/execute, false", "POST, /user/handover/query, false"})
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new UserHandoverController(null)), method, url, malformedBody);
  }
}
