package com.slz.crm.unit.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slz.crm.server.handler.GlobalExceptionHandler;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * 写端点校验反向断言基类：畸形 JSON 请求体必须在反序列化层被拒， 由全局 handler 统一接管返回 PARAM_FORMAT_ERROR(50002)。
 *
 * <p>反序列化失败发生在 controller 方法体执行之前，因此子类无需注入任何依赖。
 */
abstract class WriteEndpointValidationSupport {

  private static final String PARAM_FORMAT_ERROR_CODE = "50002";
  private static final String MALFORMED_JSON = "{\"broken\": ";

  /** 构建 standalone MockMvc：只挂全局异常处理器，不注册拦截器与真实依赖。 */
  protected MockMvc standalone(Object controller) {
    return MockMvcBuilders.standaloneSetup(controller)
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  /**
   * 断言写端点对非法请求体返回统一 50002 通道：malformedBody=false 用缺 body（required=true 必炸），true 用畸形 JSON（覆盖
   * required=false 端点）。
   */
  protected void rejectsIllegalBody(MockMvc mvc, String method, String url, boolean malformedBody)
      throws Exception {
    MockHttpServletRequestBuilder request =
        request(method, url).contentType(MediaType.APPLICATION_JSON);
    if (malformedBody) {
      request = request.content(MALFORMED_JSON);
    }
    mvc.perform(request)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(PARAM_FORMAT_ERROR_CODE));
  }

  private static MockHttpServletRequestBuilder request(String method, String url) {
    return switch (method) {
      case "PUT" -> put(url);
      case "DELETE" -> delete(url);
      default -> post(url);
    };
  }
}
