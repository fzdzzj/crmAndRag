package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.SalesLeadController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** SalesLeadController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("SalesLeadController 写端点非法请求体统一 50002 接管")
class SalesLeadWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "POST, /sales/lead, false",
    "POST, /sales/lead/query, true",
    "PUT, /sales/lead, false",
    "POST, /sales/lead/convert, false"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new SalesLeadController()), method, url, malformedBody);
  }
}
