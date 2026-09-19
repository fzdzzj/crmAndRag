package com.slz.crm.platform.trace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** RequestTraceFilter 的入口写入与清理行为测试。 */
class RequestTraceFilterTest {

  @Test
  void shouldReuseValidRequestId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestTraceFilter.TRACE_ID_HEADER, "trace-123456");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();

    new RequestTraceFilter().doFilterInternal(request, response, chain);

    assertThat(response.getHeader(RequestTraceFilter.TRACE_ID_HEADER)).isEqualTo("trace-123456");
    assertThat(MDC.get(RequestTraceKey.TRACE_ID)).isNull();
  }

  @Test
  void shouldGenerateTraceIdForInvalidRequestId() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader(RequestTraceFilter.TRACE_ID_HEADER, "bad id!");
    MockHttpServletResponse response = new MockHttpServletResponse();

    new RequestTraceFilter().doFilterInternal(request, response, new MockFilterChain());

    String traceId = response.getHeader(RequestTraceFilter.TRACE_ID_HEADER);
    assertThat(traceId).hasSize(32).doesNotContain(" ").doesNotContain("!");
    assertThat(MDC.get(RequestTraceKey.TRACE_ID)).isNull();
  }
}
