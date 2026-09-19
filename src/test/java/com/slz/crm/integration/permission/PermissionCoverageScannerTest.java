package com.slz.crm.integration.permission;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.slz.crm.server.controller.AssistController;
import com.slz.crm.server.controller.HealthController;
import com.slz.crm.server.controller.PermissionController;
import com.slz.crm.server.controller.UserController;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * 端点权限覆盖扫描器单测（audit-permission-matrix 任务 1.3，≥4 条）。
 *
 * <p>对摸底已知事实断言：PermissionController 恰 3 处注解（list / addORDeletePermissionsToRole /
 * getByRole，606）；AssistController 全方法端点已按方案A挂 800 段注解（apply-permission-matrix 任务 1.2）；
 * UserController 注解端点被正确提取枚举值（含新增 /user/find 与 DELETE /user）； HealthController（JWT
 * 排除路径内）被扫出且零注解。端点计数以扫描实测为准（proposal：实测数字以首跑为准）。
 */
class PermissionCoverageScannerTest {

  private static List<EndpointCoverage> endpointsOf(Class<?> controller) {
    return PermissionCoverageScanner.scanEndpoints().stream()
        .filter(e -> e.controllerClass().equals(controller.getName()))
        .toList();
  }

  @Test
  void scanMatchesRegistryExactly() {
    List<Class<?>> controllers = PermissionCoverageScanner.scanControllers();

    assertEquals(
        PermissionCoverageScanner.CONTROLLER_REGISTRY.size(),
        controllers.size(),
        "扫描 controller 数必须等于登记数（当前登记 27：server/controller 26 + platform/config 1）");
    Set<String> scanned =
        new TreeSet<>(controllers.stream().map(Class::getSimpleName).collect(Collectors.toSet()));
    assertEquals(
        new TreeSet<>(PermissionCoverageScanner.CONTROLLER_REGISTRY.keySet()),
        scanned,
        "扫描类名集合必须与登记清单完全一致——新 controller 必须同步登记");
    // 类级 @RequirePermission 不生效口径：当前无任何 controller 误用类级注解
    assertTrue(
        PermissionCoverageScanner.scanClassLevelAnnotations().isEmpty(),
        "类级 @RequirePermission 不生效（@Target(METHOD)），出现即应记录 WARN 并人工处置");
  }

  @Test
  void permissionControllerHasExactlyThreeAnnotations() {
    List<EndpointCoverage> secured =
        endpointsOf(PermissionController.class).stream().filter(EndpointCoverage::secured).toList();

    assertEquals(
        3, secured.size(), "PermissionController 恰 3 处注解（close-permission-read-gap 成果不回退）");
    Map<String, String> byPath =
        secured.stream()
            .collect(
                Collectors.toMap(
                    EndpointCoverage::path, EndpointCoverage::permissionName, (a, b) -> a));
    assertEquals("SYSTEM_ASSIGN_PERMISSION", byPath.get("/permission/list"));
    assertEquals(
        "SYSTEM_ASSIGN_PERMISSION", byPath.get("/permission/addORDeletePermissionsToRole"));
    assertEquals("SYSTEM_ASSIGN_PERMISSION", byPath.get("/permission/getByRole"));
    // getMyPermission / auditor 必须保持开放（业务必需，登记 INTENTIONAL_OPEN 依据）
    assertTrue(
        endpointsOf(PermissionController.class).stream()
            .anyMatch(e -> "/permission/getMyPermission".equals(e.path()) && !e.secured()));
    assertTrue(
        endpointsOf(PermissionController.class).stream()
            .anyMatch(e -> "/permission/auditor".equals(e.path()) && !e.secured()));
  }

  @Test
  void assistControllerAllEndpointsSecuredBySchemeA() {
    List<EndpointCoverage> endpoints = endpointsOf(AssistController.class);

    assertEquals(
        23,
        endpoints.size(),
        "AssistController 实测 23 个方法级端点（apply-permission-matrix 任务 1.2 已全部挂注解）");
    assertTrue(
        endpoints.stream().allMatch(EndpointCoverage::secured),
        "AssistController 全部端点已按方案A挂 800 段注解——PENDING_DECISION 消解");
    // 写语义端点占比自检：23 中应有 9 个写语义（PUT 1 / POST 6 / DELETE 2）
    long writes = endpoints.stream().filter(e -> !"GET".equals(e.httpMethod())).count();
    assertEquals(9, writes, "AssistController 写语义端点数应 = 9（PUT 1 / POST 6 / DELETE 2）");
  }

  @Test
  void userControllerExtractsPermissionEnumValues() {
    List<EndpointCoverage> secured =
        endpointsOf(UserController.class).stream().filter(EndpointCoverage::secured).toList();

    assertEquals(
        6,
        secured.size(),
        "UserController 已挂注解端点应 = 6（add / find / GET 列表 / password/reset / update / delete）");
    Map<String, String> byKey =
        secured.stream()
            .collect(
                Collectors.toMap(
                    e -> e.httpMethod() + " " + e.path(),
                    EndpointCoverage::permissionName,
                    (a, b) -> a));
    assertEquals("SYSTEM_CREATE_USER", byKey.get("POST /user/add"));
    assertEquals("SYSTEM_VIEW_USER", byKey.get("POST /user/find"));
    assertEquals("SYSTEM_VIEW_USER", byKey.get("GET /user"));
    assertEquals("SYSTEM_UPDATE_USER", byKey.get("POST /user/password/reset"));
    assertEquals("SYSTEM_UPDATE_USER", byKey.get("POST /user/update"));
    assertEquals("SYSTEM_UPDATE_USER", byKey.get("DELETE /user"));
    // 自服务端点 /user/my 与 /user/options 保持开放（登录即可用，登记 INTENTIONAL_OPEN）
    assertTrue(
        endpointsOf(UserController.class).stream()
            .anyMatch(e -> "/user/my".equals(e.path()) && !e.secured()));
    assertTrue(
        endpointsOf(UserController.class).stream()
            .anyMatch(e -> "/user/options".equals(e.path()) && !e.secured()));
  }

  @Test
  void healthControllerScannedUnderJwtExcludedPath() {
    List<EndpointCoverage> endpoints = endpointsOf(HealthController.class);

    assertEquals(1, endpoints.size());
    EndpointCoverage health = endpoints.get(0);
    assertEquals("GET", health.httpMethod());
    assertEquals("/health", health.path());
    assertFalse(health.secured(), "HealthController 零注解——JWT 层 publicPaths 排除（/health）");
  }

  @Test
  void everyControllerHasAtLeastOneEndpoint() {
    List<Class<?>> controllers = PermissionCoverageScanner.scanControllers();

    for (Class<?> controller : controllers) {
      assertFalse(
          endpointsOf(controller).isEmpty(),
          "登记 controller 必须至少暴露一个端点：" + controller.getSimpleName());
    }
  }

  @Test
  void noDuplicateEndpointKeyAcrossControllers() {
    // 同一端点 key（METHOD path）不应重复出现（跨 controller 路径冲突会双写路由，属隐患）
    Map<String, Long> counts =
        PermissionCoverageScanner.scanEndpoints().stream()
            .collect(Collectors.groupingBy(EndpointCoverage::key, Collectors.counting()));
    List<String> dupes =
        counts.entrySet().stream().filter(e -> e.getValue() > 1).map(Map.Entry::getKey).toList();
    assertTrue(dupes.isEmpty(), "存在重复端点 key（METHOD path）可能导致路由歧义：" + dupes);
  }
}
