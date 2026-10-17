package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.CustomerContactController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** CustomerContactController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("CustomerContactController 写端点非法请求体统一 50002 接管")
class CustomerContactWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "PUT, /contact, false",
    "POST, /contact, false",
    "DELETE, /contact, false",
    "PUT, /contact/recover, false",
    "DELETE, /contact/logical/batch, false"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new CustomerContactController()), method, url, malformedBody);
  }
}
