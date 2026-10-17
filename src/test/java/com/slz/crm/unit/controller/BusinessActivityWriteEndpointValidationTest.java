package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.BusinessActivityController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** BusinessActivityController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("BusinessActivityController 写端点非法请求体统一 50002 接管")
class BusinessActivityWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "POST, /business/activity, false",
    "DELETE, /business/activity, false",
    "PUT, /business/activity, false",
    "POST, /business/activity/query?pageNum=1&pageSize=1, false",
    "POST, /business/activity/1/contacts, false",
    "POST, /business/activity/1/users, false",
    "DELETE, /business/activity/1/associations, false"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new BusinessActivityController()), method, url, malformedBody);
  }
}
