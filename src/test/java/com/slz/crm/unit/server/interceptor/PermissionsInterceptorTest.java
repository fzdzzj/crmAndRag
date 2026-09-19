package com.slz.crm.unit.server.interceptor;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.vo.UserVO;
import com.slz.crm.server.interceptor.JWTInterceptor;
import com.slz.crm.server.interceptor.PermissionsInterceptor;
import com.slz.crm.server.properties.JwtProperties;
import com.slz.crm.server.service.PermissionService;
import com.slz.crm.server.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/**
 * 权限拦截器上下文边界测试：登录等无 token 请求未建立 BaseUnit 上下文时必须直接放行 （历史回归：登录请求在 preHandle 读取空 ThreadLocal 直接 NPE）；
 * 已建立上下文的请求仍走完整状态闸。
 */
class PermissionsInterceptorTest {

  private final PermissionsInterceptor interceptor = new PermissionsInterceptor();

  @AfterEach
  void cleanUpContext() {
    BaseUnit.removeCurrentId();
  }

  @Test
  void preHandleShouldPassThroughWhenContextAbsent() throws Exception {
    // 模拟 JWTInterceptor 对 /user/login 提前放行且未设置上下文
    assertTrue(
        interceptor.preHandle(
            new MockHttpServletRequest("POST", "/user/login"),
            new MockHttpServletResponse(),
            handlerMethod()));
  }

  @Test
  void preHandleShouldEnforceUserStatusGateWhenContextPresent() {
    UserService userService = mock(UserService.class);
    UserVO frozen = new UserVO();
    frozen.setRoleId(0L);
    frozen.setStatus(0);
    when(userService.getById(anyLong())).thenReturn(frozen);
    setField("userService", userService);
    setField("permissionService", mock(PermissionService.class));

    RoleAO roleAO = new RoleAO();
    roleAO.setId(9L);
    BaseUnit.setCurrentRole(roleAO);

    assertThrows(
        BaseException.class,
        () ->
            interceptor.preHandle(
                new MockHttpServletRequest("GET", "/knowledge/bases"),
                new MockHttpServletResponse(),
                handlerMethod()));

    Mockito.verify(userService).getById(9L);
  }

  /**
   * D3 放行分支的可达边界：null 上下文只可能由 JWTInterceptor 的白名单产生。 这条跨文件依赖原先只写在 PermissionsInterceptor
   * 的注释里，这里用运行期断言钉住。
   */
  @Test
  void passThroughBranchIsOnlyReachableThroughJwtWhitelist() throws Exception {
    JWTInterceptor jwtInterceptor = jwtInterceptorRequiringToken();

    MockHttpServletRequest loginRequest = new MockHttpServletRequest("POST", "/user/login");
    assertTrue(
        jwtInterceptor.preHandle(loginRequest, new MockHttpServletResponse(), handlerMethod()));
    assertNull(BaseUnit.getCurrentRole(), "JWT 白名单分支不应建立用户上下文");
    assertTrue(interceptor.preHandle(loginRequest, new MockHttpServletResponse(), handlerMethod()));

    MockHttpServletRequest protectedRequest = new MockHttpServletRequest("GET", "/knowledge/bases");
    assertThrows(
        BaseException.class,
        () ->
            jwtInterceptor.preHandle(
                protectedRequest, new MockHttpServletResponse(), handlerMethod()));
  }

  /**
   * 现状刻画（不是认可）：JWT 白名单用的是 contains("/login") 子串匹配， 因此任何路径片段以 login 开头的端点都会以"无上下文"形态抵达放行分支； 而同前缀的
   * logout 仍需 token。收窄成精确/前缀匹配会让本用例变红，届时请与 PermissionsInterceptor 的注释一并更新。
   */
  @Test
  void jwtLoginWhitelistMatchesLexicallyNotSemantically() throws Exception {
    JWTInterceptor jwtInterceptor = jwtInterceptorRequiringToken();

    MockHttpServletRequest qrLogin = new MockHttpServletRequest("GET", "/auth/loginQrCode");
    assertTrue(jwtInterceptor.preHandle(qrLogin, new MockHttpServletResponse(), handlerMethod()));
    assertTrue(interceptor.preHandle(qrLogin, new MockHttpServletResponse(), handlerMethod()));

    MockHttpServletRequest logout = new MockHttpServletRequest("GET", "/user/logout");
    assertThrows(
        BaseException.class,
        () -> jwtInterceptor.preHandle(logout, new MockHttpServletResponse(), handlerMethod()));
  }

  private JWTInterceptor jwtInterceptorRequiringToken() {
    JWTInterceptor jwtInterceptor = new JWTInterceptor();
    JwtProperties properties = mock(JwtProperties.class);
    when(properties.getTokenName()).thenReturn("token");
    inject(jwtInterceptor, "jwtProperties", properties);
    return jwtInterceptor;
  }

  private static void inject(Object target, String name, Object value) {
    try {
      java.lang.reflect.Field field = target.getClass().getDeclaredField(name);
      field.setAccessible(true);
      field.set(target, value);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private void setField(String name, Object value) {
    try {
      java.lang.reflect.Field field = PermissionsInterceptor.class.getDeclaredField(name);
      field.setAccessible(true);
      field.set(interceptor, value);
    } catch (ReflectiveOperationException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private HandlerMethod handlerMethod() throws NoSuchMethodException {
    return new HandlerMethod(new Object(), Object.class.getDeclaredMethod("toString"));
  }
}
