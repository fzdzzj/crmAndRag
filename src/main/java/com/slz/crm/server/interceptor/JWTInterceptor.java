package com.slz.crm.server.interceptor;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.JwtUntil;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.properties.JwtProperties;
import com.slz.crm.server.service.support.TokenBlacklistService;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;

@Configuration
@Slf4j
public class JWTInterceptor implements HandlerInterceptor {
  @Autowired private JwtProperties jwtProperties;

  @Autowired private UserMapper userMapper;

  @Autowired private TokenBlacklistService tokenBlacklistService;

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    final boolean shouldContinue;
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      response.setStatus(HttpServletResponse.SC_OK);
      shouldContinue = false;
    } else if (isPublicPath(request.getRequestURI())) {
      shouldContinue = true;
    } else {
      authenticate(request);
      shouldContinue = true;
    }
    return shouldContinue;
  }

  private boolean isPublicPath(String uri) {
    return "/login".equals(uri)
        || "/user/login".equals(uri)
        || "/health".equals(uri)
        || (uri != null && uri.startsWith("/public/"));
  }

  @SuppressWarnings("PMD.AvoidCatchingGenericException") // 鉴权边界：JWT解析+ORM查用户多源，失败统一按TOKEN无效拦截
  private void authenticate(HttpServletRequest request) {
    String token = request.getHeader(jwtProperties.getTokenName());
    if (Objects.isNull(token)) {
      throw new BaseException(ErrorCode.TOKEN_ERROR);
    }
    if (tokenBlacklistService != null && tokenBlacklistService.isBlacklisted(token)) {
      throw new BaseException(ErrorCode.TOKEN_INVALID);
    }

    try {
      Claims claims = JwtUntil.parseJWT(jwtProperties.getSecretKey(), token);
      Long userID = claims.get("userID", Long.class);

      UserEntity user = userMapper.selectById(userID);
      if (user == null) {
        throw new BaseException(ErrorCode.TOKEN_INVALID, "用户不存在，请重新登录");
      }

      checkUserStatus(user);

      RoleAO roleAO = new RoleAO();
      roleAO.setRoleId(user.getRoleId());
      roleAO.setId(userID);
      BaseUnit.setCurrentRole(roleAO);
    } catch (BaseException e) {
      throw e;
    } catch (Exception e) {
      throw new BaseException(ErrorCode.TOKEN_INVALID);
    }
  }

  private void checkUserStatus(UserEntity user) {
    Integer status = user.getStatus();
    Long roleId = user.getRoleId();
    if (status == null || status != 1) {
      if (Long.valueOf(2L).equals(roleId) || Integer.valueOf(2).equals(status)) {
        throw new BaseException(ErrorCode.USER_IS_QUIT);
      }
      if (Long.valueOf(0L).equals(roleId) || Integer.valueOf(0).equals(status)) {
        throw new BaseException(ErrorCode.USER_IS_FROZEN);
      }
      throw new BaseException(ErrorCode.USER_STATUS_EXCEPTION);
    }
  }
}
