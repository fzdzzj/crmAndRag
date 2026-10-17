package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.ContractController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** ContractController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("ContractController 写端点非法请求体统一 50002 接管")
class ContractWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "POST, /contract, false",
    "POST, /contract/query?pageNum=1&pageSize=1, false",
    "PUT, /contract, false",
    "DELETE, /contract, false"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new ContractController()), method, url, malformedBody);
  }
}
