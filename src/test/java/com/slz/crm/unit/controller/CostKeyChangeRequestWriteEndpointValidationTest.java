package com.slz.crm.unit.controller;

import com.slz.crm.platform.config.controller.CostKeyChangeRequestController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CostKeyChangeRequestController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("CostKeyChangeRequestController 写端点非法请求体统一 50002 接管")
class CostKeyChangeRequestWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "POST, /platform/config/cost-requests, false",
    "POST, /platform/config/cost-requests/1/reject, true"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(
        standalone(new CostKeyChangeRequestController(null)), method, url, malformedBody);
  }
}
