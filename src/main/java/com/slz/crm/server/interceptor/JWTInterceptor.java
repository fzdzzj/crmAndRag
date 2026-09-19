package com.slz.crm.server.interceptor;

import com.slz.crm.common.enumeration.ErrorCode;
import com.slz.crm.common.exiception.BaseException;
import com.slz.crm.common.untils.BaseUnit;
import com.slz.crm.common.untils.JwtUntil;
import com.slz.crm.pojo.ao.RoleAO;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.properties.JwtProperties;
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

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    RoleAO roleAO = new RoleAO();

    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      response.setStatus(HttpServletResponse.SC_OK);
      return false; // 不继续执行后续拦截器或 Controller
    }
    if (request.getRequestURI().contains("/login")) {
      return true;
    }
    // 放行公共接口（如附件下载）
    if (request.getRequestURI().startsWith("/public/")) {
      return true;
    }
    String token = request.getHeader(jwtProperties.getTokenName());

    if (Objects.isNull(token)) {
      throw new BaseException(ErrorCode.TOKEN_ERROR);
    }

    try {
      Claims claims = JwtUntil.parseJWT(jwtProperties.getSecretKey(), token);

      Long userID = claims.get("userID", Long.class);

      // 从数据库查询用户信息，获取角色ID
      UserEntity user = userMapper.selectById(userID);
      if (user == null) {
        throw new BaseException("用户不存在，请重新登录");
      }

      roleAO.setRoleId(user.getRoleId());
      roleAO.setId(userID);

      BaseUnit.setCurrentRole(roleAO);

    } catch (Exception e) {
      throw new BaseException(ErrorCode.TOKEN_INVALID);
    }

    return true;
  }
}
