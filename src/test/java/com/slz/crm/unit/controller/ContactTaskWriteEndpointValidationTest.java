package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.ContactTaskController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ContactTaskController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("ContactTaskController 写端点非法请求体统一 50002 接管")
class ContactTaskWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "POST, /contactTask/create, false",
    "DELETE, /contactTask/batch, false",
    "PUT, /contactTask/batch, false",
    "PUT, /contactTask/update, false",
    "POST, /contactTask/query, false"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new ContactTaskController()), method, url, malformedBody);
  }
}
