package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.PaymentRecordController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** PaymentRecordController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("PaymentRecordController 写端点非法请求体统一 50002 接管")
class PaymentRecordWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "POST, /payment/record, false",
    "PUT, /payment/record, false",
    "DELETE, /payment/record, false",
    "POST, /payment/record/query?pageNum=1&pageSize=1, false"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new PaymentRecordController()), method, url, malformedBody);
  }
}
