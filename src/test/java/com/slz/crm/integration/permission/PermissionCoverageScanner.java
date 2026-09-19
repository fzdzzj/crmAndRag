package com.slz.crm.integration.permission;

import com.slz.crm.common.annotation.RequirePermission;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

/**
 * 端点权限覆盖扫描器（audit-permission-matrix 任务 1.1/1.2）。
 *
 * <p><b>扫描方式</b>：ClassPath 扫描 {@code com.slz.crm} 下 {@code @RestController}/{@code @Controller}
 * 类（Spring {@link ClassPathScanningCandidateComponentProvider}，只读注解元数据不解析源码）， 拼接类级
 * {@code @RequestMapping} 前缀与方法级 mapping（{@code @GetMapping}/{@code @PostMapping}/
 * {@code @PutMapping}/{@code @DeleteMapping}/{@code @RequestMapping}）得到端点清单，并提取方法级 {@link
 * RequirePermission} 值——与 {@code PermissionsInterceptor#preHandle} 的鉴权口径一致 （注解缺失 =
 * 静默放行，扫描器的职责就是把"缺失"显式化）。
 *
 * <p><b>登记清单</b>：{@link #CONTROLLER_REGISTRY} 为 28 个 controller 权威清单；扫描集合与登记不一致 即抛错——新 controller
 * 必须同步登记，防止漏审（同 schema 审计的实体登记制）。
 *
 * <p><b>口径说明</b>：摸底（2026-09-13）曾记 26 controller / Assist 22 / AiChat 8，与实测不符： 当前全仓实际 28 个
 * {@code @RestController}（含 {@code DynamicConfigAdminController}）， Assist 23 / AiChat
 * 7——以扫描实测为准（proposal 明确"实测数字以首跑为准"）。
 */
public final class PermissionCoverageScanner {

  private static final String SCAN_BASE_PACKAGE = "com.slz.crm";

  /** controller 登记清单（简单类名 → 全限定类名，按类名字母序）：server/controller 27 + platform/config 1 = 28。 */
  public static final Map<String, Class<?>> CONTROLLER_REGISTRY = buildRegistry();

  private PermissionCoverageScanner() {}

  /**
   * ClassPath 扫描 {@code com.slz.crm} 下所有 {@code @RestController}/{@code @Controller} 并做登记校验。
   *
   * @return 扫描到的 controller 类（按类名字母序）
   * @throws IllegalStateException 扫描集合与 {@link #CONTROLLER_REGISTRY} 不一致（漏登记 / 多出 / 类漂移）
   */
  public static List<Class<?>> scanControllers() {
    ClassPathScanningCandidateComponentProvider scanner =
        new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    scanner.addIncludeFilter(
        new AnnotationTypeFilter(org.springframework.stereotype.Controller.class));

    Map<String, Class<?>> scanned = new LinkedHashMap<>();
    for (BeanDefinition bd : scanner.findCandidateComponents(SCAN_BASE_PACKAGE)) {
      Class<?> clazz = loadClass(bd.getBeanClassName());
      if (clazz.isMemberClass()
          || clazz.isLocalClass()
          || clazz.isAnonymousClass()
          || clazz.isSynthetic()) {
        continue;
      }
      scanned.put(clazz.getName(), clazz);
    }

    List<String> problems = new ArrayList<>();
    // 登记了但没扫到 → controller 缺失或注解漂移
    for (Map.Entry<String, Class<?>> entry : CONTROLLER_REGISTRY.entrySet()) {
      if (!scanned.containsKey(entry.getValue().getName())) {
        problems.add("登记 controller '" + entry.getKey() + "' 未在扫描结果中出现（类缺失或 @RestController 注解改动）");
      }
    }
    // 扫到但没登记 → 新 controller 未同步登记
    for (String fqcn : scanned.keySet()) {
      boolean registered =
          CONTROLLER_REGISTRY.values().stream().anyMatch(c -> c.getName().equals(fqcn));
      if (!registered) {
        problems.add("扫描到未登记 controller '" + fqcn + "'——新 controller 必须同步登记进 CONTROLLER_REGISTRY");
      }
    }
    if (!problems.isEmpty()) {
      throw new IllegalStateException(
          "controller 登记校验失败，共 " + problems.size() + " 处：\n  - " + String.join("\n  - ", problems));
    }

    List<Class<?>> result = new ArrayList<>(CONTROLLER_REGISTRY.values());
    result.sort((a, b) -> a.getSimpleName().compareTo(b.getSimpleName()));
    return Collections.unmodifiableList(result);
  }

  /**
   * 全量端点扫描：对每个登记 controller 提取类级前缀 × 方法级 mapping 的所有端点。
   *
   * <p>HTTP 方法口径：{@code @GetMapping}→GET、{@code @PostMapping}→POST、{@code @PutMapping}→PUT、
   * {@code @DeleteMapping}→DELETE；方法级 {@code @RequestMapping} 未声明 method 时取类级 method， 仍无则记 ANY（如
   * {@code /permission/getMyPermission}）。
   *
   * @return 端点清单（按 controller 类名字母序、方法声名序稳定输出）
   */
  public static List<EndpointCoverage> scanEndpoints() {
    List<EndpointCoverage> endpoints = new ArrayList<>();
    for (Class<?> controller : scanControllers()) {
      String classPrefix = classPrefix(controller);
      Set<String> classMethods = classLevelMethods(controller);
      for (Method method : controller.getDeclaredMethods()) {
        List<MappingInfo> mappings = mappingInfos(method);
        if (mappings.isEmpty()) {
          continue;
        }
        RequirePermission requirePermission = method.getAnnotation(RequirePermission.class);
        String permissionName = requirePermission == null ? null : requirePermission.value().name();
        for (MappingInfo mapping : mappings) {
          List<String> methods;
          if (mapping.methods.isEmpty()) {
            methods = new ArrayList<>(classMethods);
          } else {
            methods = new ArrayList<>(mapping.methods);
          }
          if (methods.isEmpty()) {
            methods = List.of("ANY");
          }
          for (String httpMethod : methods) {
            for (String methodPath : mapping.paths) {
              endpoints.add(
                  new EndpointCoverage(
                      httpMethod,
                      joinPath(classPrefix, methodPath),
                      controller.getSimpleName() + "#" + method.getName(),
                      requirePermission != null,
                      permissionName,
                      controller.getName()));
            }
          }
        }
      }
    }
    return Collections.unmodifiableList(endpoints);
  }

  /**
   * 类级 {@code @RequirePermission} 使用检查（口径：{@code @Target(METHOD)}，类级不生效）。
   *
   * @return 带类级注解的 controller 简单类名集合（当前应为空；非空即 WARN，防今后误用）
   */
  public static Set<String> scanClassLevelAnnotations() {
    Set<String> offenders = new LinkedHashSet<>();
    for (Class<?> controller : scanControllers()) {
      if (controller.getAnnotation(RequirePermission.class) != null) {
        offenders.add(controller.getSimpleName());
      }
    }
    return Collections.unmodifiableSet(offenders);
  }

  // ---------- 私有工具 ----------

  /** 类级 {@code @RequestMapping} 前缀；无注解或无 value 时按 {@code /} 处理。 */
  private static String classPrefix(Class<?> controller) {
    RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
    if (mapping == null || mapping.value().length == 0) {
      return "";
    }
    return mapping.value()[0];
  }

  /** 类级 {@code @RequestMapping} 声明的 HTTP 方法集合（空 = 任意方法）。 */
  private static Set<String> classLevelMethods(Class<?> controller) {
    RequestMapping mapping = controller.getAnnotation(RequestMapping.class);
    Set<String> methods = new LinkedHashSet<>();
    if (mapping != null) {
      for (RequestMethod rm : mapping.method()) {
        methods.add(rm.name());
      }
    }
    return methods;
  }

  /** 方法级 mapping 注解 → (方法集合, 路径集合)；无 mapping 注解返回空。 */
  private static List<MappingInfo> mappingInfos(Method method) {
    List<MappingInfo> infos = new ArrayList<>();
    addMapping(infos, method.getAnnotation(RequestMapping.class), null);
    addMapping(infos, method.getAnnotation(GetMapping.class), "GET");
    addMapping(infos, method.getAnnotation(PostMapping.class), "POST");
    addMapping(infos, method.getAnnotation(PutMapping.class), "PUT");
    addMapping(infos, method.getAnnotation(DeleteMapping.class), "DELETE");
    return infos;
  }

  private static void addMapping(
      List<MappingInfo> infos, Annotation annotation, String httpMethod) {
    if (annotation == null) {
      return;
    }
    List<String> paths;
    Set<String> methods = new LinkedHashSet<>();
    if (annotation instanceof RequestMapping rm) {
      paths = List.of(rm.value());
      for (RequestMethod m : rm.method()) {
        methods.add(m.name());
      }
    } else {
      paths = pathsOf(annotation);
      if (httpMethod != null) {
        methods.add(httpMethod);
      }
    }
    infos.add(new MappingInfo(methods, paths));
  }

  /** 通过反射读 mapping 注解的 value()（各 @*Mapping 注解签名一致：String[] value()）。 */
  private static List<String> pathsOf(Annotation annotation) {
    try {
      Object value = annotation.annotationType().getMethod("value").invoke(annotation);
      if (value instanceof String[] strings && strings.length > 0) {
        return List.of(strings);
      }
    } catch (ReflectiveOperationException ignored) {
      // 注解契约固定，此处不会触发
    }
    return List.of("");
  }

  /** 拼接类级前缀与方法级路径：空方法路径 = 前缀本身；统一去尾斜杠。 */
  private static String joinPath(String classPrefix, String methodPath) {
    String prefix = classPrefix == null ? "" : classPrefix;
    String path = methodPath == null || methodPath.isEmpty() ? "" : methodPath;
    String joined = prefix + path;
    if (joined.isEmpty()) {
      return "/";
    }
    if (!joined.startsWith("/")) {
      joined = "/" + joined;
    }
    while (joined.length() > 1 && joined.endsWith("/")) {
      joined = joined.substring(0, joined.length() - 1);
    }
    return joined;
  }

  private static Class<?> loadClass(String className) {
    try {
      return Class.forName(className, false, PermissionCoverageScanner.class.getClassLoader());
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("扫描候选类加载失败：" + className, e);
    }
  }

  private static Map<String, Class<?>> buildRegistry() {
    Map<String, Class<?>> registry = new LinkedHashMap<>();
    registry.put("AiActionController", clazz("com.slz.crm.server.controller.AiActionController"));
    registry.put("AiChatController", clazz("com.slz.crm.server.controller.AiChatController"));
    registry.put("AssistController", clazz("com.slz.crm.server.controller.AssistController"));
    registry.put(
        "BusinessActivityController",
        clazz("com.slz.crm.server.controller.BusinessActivityController"));
    registry.put(
        "CompanyDeptController", clazz("com.slz.crm.server.controller.CompanyDeptController"));
    registry.put(
        "CompanyGroupController", clazz("com.slz.crm.server.controller.CompanyGroupController"));
    registry.put(
        "ContactTaskController", clazz("com.slz.crm.server.controller.ContactTaskController"));
    registry.put("ContractController", clazz("com.slz.crm.server.controller.ContractController"));
    registry.put(
        "ContractOrderItemController",
        clazz("com.slz.crm.server.controller.ContractOrderItemController"));
    registry.put(
        "CustomerCompanyController",
        clazz("com.slz.crm.server.controller.CustomerCompanyController"));
    registry.put(
        "CustomerContactController",
        clazz("com.slz.crm.server.controller.CustomerContactController"));
    registry.put(
        "DataStatisticsController",
        clazz("com.slz.crm.server.controller.DataStatisticsController"));
    registry.put(
        "DynamicConfigAdminController",
        clazz("com.slz.crm.platform.config.controller.DynamicConfigAdminController"));
    registry.put("HealthController", clazz("com.slz.crm.server.controller.HealthController"));
    registry.put(
        "InvoiceInfoController", clazz("com.slz.crm.server.controller.InvoiceInfoController"));
    registry.put(
        "PaymentRecordController", clazz("com.slz.crm.server.controller.PaymentRecordController"));
    registry.put(
        "PermissionController", clazz("com.slz.crm.server.controller.PermissionController"));
    registry.put(
        "ProjectFileController", clazz("com.slz.crm.server.controller.ProjectFileController"));
    registry.put(
        "PublicAttachmentController",
        clazz("com.slz.crm.server.controller.PublicAttachmentController"));
    registry.put("ReportController", clazz("com.slz.crm.server.controller.ReportController"));
    registry.put("RoleController", clazz("com.slz.crm.server.controller.RoleController"));
    registry.put(
        "SalesOpportunityController",
        clazz("com.slz.crm.server.controller.SalesOpportunityController"));
    registry.put(
        "SalesStageApprovalController",
        clazz("com.slz.crm.server.controller.SalesStageApprovalController"));
    registry.put("SysDeptController", clazz("com.slz.crm.server.controller.SysDeptController"));
    registry.put(
        "TaskCommentController", clazz("com.slz.crm.server.controller.TaskCommentController"));
    registry.put("UserController", clazz("com.slz.crm.server.controller.UserController"));
    registry.put(
        "UserHandoverController", clazz("com.slz.crm.server.controller.UserHandoverController"));
    registry.put(
        "KnowledgeAdminController",
        clazz("com.slz.crm.server.controller.KnowledgeAdminController"));
    return Collections.unmodifiableMap(registry);
  }

  private static Class<?> clazz(String name) {
    try {
      return Class.forName(name);
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException("登记 controller 类加载失败：" + name, e);
    }
  }

  /** 方法级 mapping 元数据：HTTP 方法集合 + 路径集合。 */
  private record MappingInfo(Set<String> methods, List<String> paths) {}
}
