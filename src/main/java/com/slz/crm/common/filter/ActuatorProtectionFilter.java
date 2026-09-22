package com.slz.crm.common.filter;

import com.slz.crm.common.properties.ActuatorProtectionProperties;
import com.slz.crm.common.untils.JwtUntil;
import com.slz.crm.platform.contract.PlatformErrorCode;
import com.slz.crm.pojo.entity.UserEntity;
import com.slz.crm.server.mapper.UserMapper;
import com.slz.crm.server.properties.JwtProperties;
import io.jsonwebtoken.Claims;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;

/**
 * /actuator/** 的鉴权过滤器（任务 3 的“Actuator 保护”）。
 *
 * <p>职责：移除 Spring Security 后，Actuator 不能裸奔。本过滤器统一做两件事：
 *
 * <ol>
 *   <li>探针放行：仅放行 {@code platform.actuator.public-paths}（默认 liveness/readiness）；
 *   <li>鉴权：其余端点必须携带有效 JWT；{@code admin-only-paths} 还要求超级管理员（roleId=1）。
 * </ol>
 *
 * <p>为什么用 Filter 而不是 HandlerInterceptor：Actuator 由独立的 {@code EndpointHandlerMapping} 承载，不走 {@code
 * WebMvcConfiguration#addInterceptors}；只挂拦截器会形成“看似有保护、实则裸奔”。
 *
 * <p>线程安全：本类自身无状态（只读配置），可以单例复用。
 */
public class ActuatorProtectionFilter implements Filter {

  private static final Logger LOG = LoggerFactory.getLogger(ActuatorProtectionFilter.class);

  /** 标记“已通过鉴权”的请求属性名。预留给后续治理（Lane D）或测试断言使用。 */
  public static final String ATTR_AUTHORIZED = "platform.actuator.authorized";

  /** 标记当前访问主体是否超级管理员，供健康端点等后续逻辑复用 */
  public static final String ATTR_SUPER_ADMIN = "platform.actuator.superAdmin";

  private final ActuatorProtectionProperties properties;
  private final JwtProperties jwtProperties;
  private final UserMapper userMapper;

  /**
   * 构造过滤器（由 ActuatorProtectionConfig 注册，不直接加 @Component，避免二次注册）。
   *
   * @param properties 保护开关与白名单
   * @param jwtProperties JWT 解析所需密钥与 token 头名
   * @param userMapper 用于把 token 里的 userId 映射回角色（判定超管）
   */
  public ActuatorProtectionFilter(
      ActuatorProtectionProperties properties, JwtProperties jwtProperties, UserMapper userMapper) {
    this.properties = properties;
    this.jwtProperties = jwtProperties;
    this.userMapper = userMapper;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
      throws IOException, ServletException {
    if (!(request instanceof HttpServletRequest req)
        || !(response instanceof HttpServletResponse resp)) {
      chain.doFilter(request, response);
      return;
    }
    boolean forward = false;
    String path = resolvePath(req);
    if (properties.isProtectedEnabled() && path.startsWith("/actuator") && !isPublic(path)) {
      Long userId = authenticate(req);
      boolean blocked = userId == null;
      if (blocked) {
        writeJson(
            resp,
            HttpStatus.UNAUTHORIZED,
            PlatformErrorCode.UNAUTHORIZED.getCode(),
            PlatformErrorCode.UNAUTHORIZED.getMessage());
      } else {
        Long roleId = loadRoleId(userId);
        if (roleId == null) {
          // token 合法但用户已被删除：视为未登录，避免僵尸 token 访问运维端点
          writeJson(
              resp,
              HttpStatus.UNAUTHORIZED,
              PlatformErrorCode.UNAUTHORIZED.getCode(),
              PlatformErrorCode.UNAUTHORIZED.getMessage());
        } else {
          boolean superAdmin = roleId == 1L;
          if (requiresSuperAdmin(path) && !superAdmin) {
            LOG.warn("非超管访问敏感 Actuator 端点被拒绝: path={}, roleId={}", path, roleId);
            writeJson(resp, HttpStatus.FORBIDDEN, 12002, "仅超级管理员可访问该端点");
          } else {
            req.setAttribute(ATTR_AUTHORIZED, Boolean.TRUE);
            req.setAttribute(ATTR_SUPER_ADMIN, superAdmin);
            forward = true;
          }
        }
      }
    } else {
      // 未启用保护 / 非 Actuator 端点 / 探针白名单（组件明细由 show-details 控制，匿名只拿总体状态）
      forward = true;
    }
    if (forward) {
      chain.doFilter(request, response);
    }
  }

  /**
   * 统一去掉 contextPath 与尾部斜杠，保证配置里的路径与请求路径可比。
   *
   * @param request 当前请求
   * @return 归一化后的请求路径，例如 {@code /actuator/health/liveness}
   */
  private String resolvePath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    String path =
        (contextPath != null && uri.startsWith(contextPath))
            ? uri.substring(contextPath.length())
            : uri;
    if (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  /**
   * @param path 归一化路径
   * @return 是否在免鉴权白名单内（精确匹配，不做前缀放行，避免误放大权限）
   */
  private boolean isPublic(String path) {
    return properties.getPublicPaths().contains(path);
  }

  /**
   * @param path 归一化路径
   * @return 是否命中“仅超管可访问”的端点前缀
   */
  private boolean requiresSuperAdmin(String path) {
    return properties.getAdminOnlyPaths().stream().anyMatch(path::startsWith);
  }

  /**
   * 校验 JWT 并返回用户 id。
   *
   * @param request 当前请求
   * @return 用户 id；token 缺失/无效返回 {@code null}（统一按 401 处理，不区分具体原因，避免探测）
   */
  @SuppressWarnings(
      "PMD.AvoidCatchingGenericException") // 安全边界兜底：JWT 解析失败不向客户端透出细节，统一走 401，收窄会泄漏解析类型
  private Long authenticate(HttpServletRequest request) {
    Long result = null;
    String token = request.getHeader(jwtProperties.getTokenName());
    if (StringUtils.hasText(token)) {
      try {
        Claims claims = JwtUntil.parseJWT(jwtProperties.getSecretKey(), token);
        Long userId = claims.get("userID", Long.class);
        result = Objects.isNull(userId) ? null : userId;
      } catch (Exception e) {
        // 不把异常细节透给客户端，只记服务端日志便于排查
        LOG.debug("Actuator 访问 JWT 校验失败: {}", e.getMessage());
      }
    }
    return result;
  }

  /**
   * 查询用户角色 id。
   *
   * @param userId 用户 id
   * @return 角色 id；用户不存在返回 {@code null}
   */
  private Long loadRoleId(Long userId) {
    UserEntity user = userMapper.selectById(userId);
    return user == null ? null : user.getRoleId();
  }

  /**
   * 输出统一响应结构 JSON。
   *
   * @param response 响应对象
   * @param status HTTP 状态码
   * @param code 业务错误码
   * @param message 用户可读提示
   * @throws IOException 写响应失败时抛出
   */
  private void writeJson(
      HttpServletResponse response, HttpStatus status, Integer code, String message)
      throws IOException {
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write("{\"code\":" + code + ",\"msg\":" + quote(message) + ",\"data\":null}");
  }

  /**
   * JSON 字符串转义，避免用户可控内容注入导致响应体非法。
   *
   * @param value 原始字符串
   * @return 带双引号且已转义的 JSON 字符串
   */
  private String quote(String value) {
    String escaped =
        value == null
            ? ""
            : value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    return "\"" + escaped + "\"";
  }
}
