package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.KnowledgeAdminController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** KnowledgeAdminController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("KnowledgeAdminController 写端点非法请求体统一 50002 接管")
class KnowledgeAdminWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({"POST, /knowledge/retrieval/test, false"})
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(
        standalone(new KnowledgeAdminController(null, null, null, null, null, null)),
        method,
        url,
        malformedBody);
  }
}
