package com.slz.crm.unit.controller;

import com.slz.crm.server.controller.DataStatisticsController;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** DataStatisticsController 写端点对非法请求体的全局接管断言（add-write-endpoint-validation）。 */
@DisplayName("DataStatisticsController 写端点非法请求体统一 50002 接管")
class DataStatisticsWriteEndpointValidationTest extends WriteEndpointValidationSupport {
  @ParameterizedTest
  @CsvSource({
    "POST, /dataStatistics/chartData, false",
    "POST, /dataStatistics/chart, false",
    "POST, /dataStatistics/summary, false"
  })
  void rejectsIllegalBody(String method, String url, boolean malformedBody) throws Exception {
    rejectsIllegalBody(standalone(new DataStatisticsController()), method, url, malformedBody);
  }
}
