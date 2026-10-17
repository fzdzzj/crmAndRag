package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.TaskCommentController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** TaskCommentController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("TaskCommentController 写端点非法请求体统一 50002 接管")
class TaskCommentWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "POST, /taskComment/create, false",
    "DELETE, /taskComment/batch, false",
    "PUT, /taskComment/update, false"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new TaskCommentController()), method, url, malformedBody);
  }
}
