package com.slz.crm.unit.controller;

import com.slz.crm.platform.config.controller.DynamicConfigAdminController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** DynamicConfigAdminController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("DynamicConfigAdminController 写端点非法请求体统一 50002 接管")
class DynamicConfigAdminWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "POST, /platform/config/items, false",
    "POST, /platform/config/items/testKey/rollback, false"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(
        standalone(new DynamicConfigAdminController(null)), method, url, malformedBody);
  }
}
