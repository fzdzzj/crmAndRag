package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.SalesOpportunityController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SalesOpportunityController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("SalesOpportunityController 写端点非法请求体统一 50002 接管")
class SalesOpportunityWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({"PUT, /sales, false", "POST, /sales, false"})
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new SalesOpportunityController()), method, url, malformedBody);
  }
}
