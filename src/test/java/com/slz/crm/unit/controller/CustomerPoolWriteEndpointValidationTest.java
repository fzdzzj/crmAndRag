package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.CustomerPoolController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CustomerPoolController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("CustomerPoolController 写端点非法请求体统一 50002 接管")
class CustomerPoolWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({"POST, /company/pool/assign, false"})
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new CustomerPoolController()), method, url, malformedBody);
  }
}
