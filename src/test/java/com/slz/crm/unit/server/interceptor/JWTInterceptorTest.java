package com.slz.crm.unit.server.interceptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.JwtUntil;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.interceptor.JWTInterceptor;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.properties.JwtProperties;
import com.slz.crm.server.service.support.TokenBlacklistService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

/** JWT 拦截器路径匹配、黑名单与用户状态阻断单元测试（harden-login-security H-8, H-9）。 */
@DisplayName("JWTInterceptor 路径精准放行、登出黑名单与状态即时感知测试")
class JWTInterceptorTest {

  private static final String TEST_SECRET = "unit-test-secret-key-0123456789abcdef-0123456789";

  private JWTInterceptor jwtInterceptor;
  private UserMapper userMapper;
  private TokenBlacklistService tokenBlacklistService;

  @BeforeEach
  void setUp() {
    jwtInterceptor = new JWTInterceptor();
    JwtProperties properties = mock(JwtProperties.class);
    when(properties.getTokenName()).thenReturn("token");
    when(properties.getSecretKey()).thenReturn(TEST_SECRET);

    userMapper = mock(UserMapper.class);
    tokenBlacklistService = new TokenBlacklistService();

    injectField(jwtInterceptor, "jwtProperties", properties);
    injectField(jwtInterceptor, "userMapper", userMapper);
    injectField(jwtInterceptor, "tokenBlacklistService", tokenBlacklistService);
  }

  @AfterEach
  void tearDown() {
    BaseUnit.removeCurrentId();
  }

  @Test
  @DisplayName("OPTIONS 预检请求应直接放行并置响应状态为 200，终止后续拦截链")
  void optionsRequestShouldReturnOkAndStopInterceptorChain() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/customer/company");
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = jwtInterceptor.preHandle(request, response, dummyHandler());

    assertFalse(result, "OPTIONS 请求应返回 false 阻断后续拦截");
    assertEquals(HttpServletResponse.SC_OK, response.getStatus());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/login",
        "/user/login",
        "/health",
        "/public/attachment/download",
        "/public/files/test.png"
      })
  @DisplayName("合法白名单路径无需 Token 直接放行")
  void validWhitelistPathsShouldPassWithoutToken(String path) throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = jwtInterceptor.preHandle(request, response, dummyHandler());

    assertTrue(result, "合法白名单路径应放行: " + path);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "/customer/login-bypass",
        "/auth/loginQrCode",
        "/user/login/bypass",
        "/api/v1/login/extra",
        "/login-test",
        "/fake/login"
      })
  @DisplayName("包含 login 子串的伪造路径未携带 Token 时应被严格拦截，抛出 TOKEN_ERROR")
  void pseudoLoginPathsMustBeBlockedWhenTokenAbsent(String path) {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
    MockHttpServletResponse response = new MockHttpServletResponse();

    BaseException ex =
        assertThrows(
            BaseException.class,
            () -> jwtInterceptor.preHandle(request, response, dummyHandler()),
            "伪造路径应被拦截: " + path);

    assertEquals(ErrorCode.TOKEN_ERROR.getCode(), ex.getCode());
    assertEquals(ErrorCode.TOKEN_ERROR.getMessage(), ex.getMessage());
  }

  @Test
  @DisplayName("有效 Token 且用户状态正常(status=1)时成功放行并建立上下文")
  void validTokenWithActiveUserShouldPassAndSetContext() throws Exception {
    String token = generateToken(101L);
    UserEntity user = new UserEntity();
    user.setId(101L);
    user.setRoleId(3L);
    user.setStatus(1);
    when(userMapper.selectById(101L)).thenReturn(user);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customer/company");
    request.addHeader("token", token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    boolean result = jwtInterceptor.preHandle(request, response, dummyHandler());

    assertTrue(result);
    assertNotNull(BaseUnit.getCurrentRole());
    assertEquals(101L, BaseUnit.getCurrentRole().getId());
    assertEquals(3L, BaseUnit.getCurrentRole().getRoleId());
  }

  @Test
  @DisplayName("已加入登出黑名单的 Token 必须直接抛出 TOKEN_INVALID")
  void blacklistedTokenShouldThrowTokenInvalid() {
    String token = generateToken(102L);
    tokenBlacklistService.addToBlacklist(token);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customer/company");
    request.addHeader("token", token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    BaseException ex =
        assertThrows(
            BaseException.class, () -> jwtInterceptor.preHandle(request, response, dummyHandler()));

    assertEquals(ErrorCode.TOKEN_INVALID.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("冻结用户(status=0)请求应被前置阻断抛出 USER_IS_FROZEN (12006)")
  void frozenUserShouldBeBlockedWithUserIsFrozen() {
    String token = generateToken(103L);
    UserEntity user = new UserEntity();
    user.setId(103L);
    user.setRoleId(0L);
    user.setStatus(0);
    when(userMapper.selectById(103L)).thenReturn(user);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customer/company");
    request.addHeader("token", token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    BaseException ex =
        assertThrows(
            BaseException.class, () -> jwtInterceptor.preHandle(request, response, dummyHandler()));

    assertEquals(ErrorCode.USER_IS_FROZEN.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("离职用户(status=2)请求应被前置阻断抛出 USER_IS_QUIT (12007)")
  void quitUserShouldBeBlockedWithUserIsQuit() {
    String token = generateToken(104L);
    UserEntity user = new UserEntity();
    user.setId(104L);
    user.setRoleId(2L);
    user.setStatus(2);
    when(userMapper.selectById(104L)).thenReturn(user);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customer/company");
    request.addHeader("token", token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    BaseException ex =
        assertThrows(
            BaseException.class, () -> jwtInterceptor.preHandle(request, response, dummyHandler()));

    assertEquals(ErrorCode.USER_IS_QUIT.getCode(), ex.getCode());
  }

  @Test
  @DisplayName("其他异常状态(status=3)请求应被前置阻断抛出 USER_STATUS_EXCEPTION (12003)")
  void abnormalStatusUserShouldBeBlockedWithUserStatusException() {
    String token = generateToken(105L);
    UserEntity user = new UserEntity();
    user.setId(105L);
    user.setRoleId(4L);
    user.setStatus(3);
    when(userMapper.selectById(105L)).thenReturn(user);

    MockHttpServletRequest request = new MockHttpServletRequest("GET", "/customer/company");
    request.addHeader("token", token);
    MockHttpServletResponse response = new MockHttpServletResponse();

    BaseException ex =
        assertThrows(
            BaseException.class, () -> jwtInterceptor.preHandle(request, response, dummyHandler()));

    assertEquals(ErrorCode.USER_STATUS_EXCEPTION.getCode(), ex.getCode());
  }

  private String generateToken(Long userId) {
    return JwtUntil.createJWT(TEST_SECRET, 3_600_000L, Map.of("userID", userId));
  }

  private HandlerMethod dummyHandler() throws NoSuchMethodException {
    return new HandlerMethod(this, getClass().getDeclaredMethod("dummyHandler"));
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
