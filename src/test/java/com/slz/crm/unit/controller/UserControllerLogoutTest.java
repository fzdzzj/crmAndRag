package com.slz.crm.unit.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.slz.crm.common.result.Result;
import com.slz.crm.server.controller.UserController;
import com.slz.crm.server.properties.JwtProperties;
import com.slz.crm.server.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/** UserController logout 登出端点测试（harden-login-security H-9）。 */
@DisplayName("UserController logout 端点单元测试")
class UserControllerLogoutTest {

  private UserService userService;
  private UserController userController;

  @BeforeEach
  void setUp() {
    userService = mock(UserService.class);
    userController = new UserController(userService);

    JwtProperties jwtProperties = new JwtProperties();
    jwtProperties.setTokenName("token");
    injectField(userController, "jwtProperties", jwtProperties);
  }

  @Test
  @DisplayName("用户登出时提取 Header 中的 Token 并委托 UserService 完成登出")
  void shouldDelegateLogoutWithTokenFromHeader() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/user/logout");
    request.addHeader("token", "active-jwt-token-to-logout");

    Result<String> result = userController.logout(request);

    assertNotNull(result);
    assertEquals(1, result.getCode());
    verify(userService).logout("active-jwt-token-to-logout");
  }

  @Test
  @DisplayName("未携带 Token 的登出请求也能安全处理并返回成功")
  void shouldHandleLogoutWhenTokenAbsentGracefully() {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/user/logout");

    Result<String> result = userController.logout(request);

    assertNotNull(result);
    assertEquals(1, result.getCode());
    verify(userService).logout(null);
  }

  private static void injectField(Object target, String fieldName, Object value) {
    try {
      java.lang.reflect.Field field = target.getClass().getDeclaredField(fieldName);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
  }
}
