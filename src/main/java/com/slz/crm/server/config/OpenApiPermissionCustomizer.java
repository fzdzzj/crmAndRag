package com.slz.crm.server.config;

import com.slz.crm.common.annotation.RequirePermission;
import com.slz.crm.common.enumeration.PermissionOperates;
import io.swagger.v3.oas.models.Operation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.web.method.HandlerMethod;

/**
 * 端点权限要求「生成化」进 OpenAPI 契约（retrieval-and-contract-hardening G3）：把方法级 {@link RequirePermission} 投影成
 * {@code x-} 扩展。
 *
 * <p>两个键互斥且必须有一个：有注解 → {@code x-required-permission {code,name}}（code 取枚举 {@code Long} 主键、name
 * 为常量名）； 无注解 → {@code x-permission-status: OPEN}（等价测试侧 {@code OpenEndpointRegistry} 的
 * INTENTIONAL_OPEN）。
 *
 * <p>为什么不塞 {@code security}：{@code securitySchemes} 表达认证（JWT），本系统权限是方法级业务鉴权，混用会误导 SDK
 * 生成器。不表达：哪个角色有权限、COST 档位、灰度白名单。本类无字段、无 I/O，只读方法级注解（不扫 classpath、不 import 测试源码）。
 *
 * <p>注册点：{@code OpenApiConfig#openApiPermissionCustomizer()}（与既有 multipart 自定义器并列）。
 */
public class OpenApiPermissionCustomizer implements OperationCustomizer {

  /** 有方法级 {@code @RequirePermission} 时的扩展键。 */
  public static final String EXTENSION_REQUIRED_PERMISSION = "x-required-permission";

  /** 无方法级注解（= 有意开放）时的扩展键。 */
  public static final String EXTENSION_PERMISSION_STATUS = "x-permission-status";

  /** {@link #EXTENSION_PERMISSION_STATUS} 的取值（唯一合法值）。 */
  public static final String STATUS_OPEN = "OPEN";

  /** {@link #EXTENSION_REQUIRED_PERMISSION} 内的权限码字段名（值为数字）。 */
  public static final String FIELD_CODE = "code";

  /** {@link #EXTENSION_REQUIRED_PERMISSION} 内的权限枚举名（全大写）。 */
  public static final String FIELD_NAME = "name";

  @Override
  public Operation customize(Operation operation, HandlerMethod handlerMethod) {
    RequirePermission required = handlerMethod.getMethodAnnotation(RequirePermission.class);
    if (required == null) {
      operation.addExtension(EXTENSION_PERMISSION_STATUS, STATUS_OPEN);
    } else {
      PermissionOperates operate = required.value();
      Map<String, Object> permission = new LinkedHashMap<>();
      permission.put(FIELD_CODE, operate.getId());
      permission.put(FIELD_NAME, operate.name());
      operation.addExtension(EXTENSION_REQUIRED_PERMISSION, permission);
    }
    return operation;
  }
}
