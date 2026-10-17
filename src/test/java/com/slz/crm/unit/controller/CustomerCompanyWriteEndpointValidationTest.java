package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.CustomerCompanyController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CustomerCompanyController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("CustomerCompanyController 写端点非法请求体统一 50002 接管")
class CustomerCompanyWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "POST, /company, false",
    "PUT, /company, false",
    "DELETE, /company, false",
    "PUT, /company/recover, false",
    "PUT, /company/merge, false",
    "DELETE, /company/logical/batch, false"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new CustomerCompanyController()), method, url, malformedBody);
  }
}
