package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.InvoiceInfoController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** InvoiceInfoController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("InvoiceInfoController 写端点非法请求体统一 50002 接管")
class InvoiceInfoWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "POST, /invoice/info, false",
    "PUT, /invoice/info, false",
    "DELETE, /invoice/info, false",
    "POST, /invoice/info/query?pageNum=1&pageSize=1, false"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new InvoiceInfoController()), method, url, malformedBody);
  }
}
